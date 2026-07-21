package com.idata.profile.service;

import com.idata.profile.agentproxy.dto.t3.T3ResolveBatchRequest;
import com.idata.profile.infra.elasticsearch.EntityEsService;
import com.idata.profile.infra.embedding.EmbeddingService;
import com.idata.profile.infra.milvus.MilvusVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class EntityCandidateRetrievalService {

    private static final double MIN_NAME_INDEX_SCORE = 0.35D;
    private static final double MIN_VECTOR_ONLY_SCORE = 0.995D;
    private static final double MIN_VECTOR_SCORE_WITH_STRONG_NAME = 0.75D;
    private static final double MIN_VECTOR_SCORE_WITH_RELATED_NAME = 0.90D;
    private static final double STRONG_NAME_SCORE = 0.90D;
    private static final double RELATED_NAME_SCORE = 0.50D;
    private static final int MAX_VECTOR_ONLY_CANDIDATES = 2;
    private static final Set<String> DIRECTIONAL_FRAGMENT_NAMES = Set.of(
            "north", "south", "east", "west", "northern", "southern", "eastern", "western");

    private final EntityEsService entityEsService;
    private final MilvusVectorService milvusVectorService;
    private final EmbeddingService embeddingService;

    public List<T3ResolveBatchRequest.Candidate> retrieveCandidates(String canonicalName,
                                                                    String entityType,
                                                                    int topK) {
        Map<String, T3ResolveBatchRequest.Candidate> candidates = new LinkedHashMap<>();

        try {
            List<Map<String, Object>> esResults =
                    entityEsService.searchEntities(canonicalName, entityType, topK);
            for (Map<String, Object> r : esResults) {
                String entityId = firstString(r, "entity_id", "entityId");
                if (!hasText(entityId)) {
                    continue;
                }
                T3ResolveBatchRequest.Candidate candidate = toCandidate(r, "NAME_INDEX", canonicalName);
                if (scoreOf(candidate.getScore()) < MIN_NAME_INDEX_SCORE
                        || isFragmentCandidate(canonicalName, candidate.getCanonicalName())) {
                    continue;
                }
                candidates.put(entityId, candidate);
            }
        } catch (Exception e) {
            log.warn("[EntityCandidateRetrieval] ES candidate retrieval failed, name={}", canonicalName, e);
        }

        try {
            float[] embedding = embeddingService.generateTextEmbedding(canonicalName);
            if (embedding != null) {
                List<MilvusVectorService.ScoredEntityId> milvusHits = milvusVectorService.searchEntityEmbeddings(
                        embedding, topK, entityType);

                List<String> needsBackfill = milvusHits.stream()
                        .map(MilvusVectorService.ScoredEntityId::entityId)
                        .filter(id -> !candidates.containsKey(id))
                        .toList();
                Map<String, Map<String, Object>> backfilled = needsBackfill.isEmpty()
                        ? Map.of()
                        : entityEsService.getEntitiesByIds(needsBackfill);

                for (MilvusVectorService.ScoredEntityId hit : milvusHits) {
                    String entityId = hit.entityId();
                    if (candidates.containsKey(entityId)) {
                        addChannel(candidates.get(entityId), "VECTOR_INDEX");
                        continue;
                    }
                    Map<String, Object> doc = backfilled.get(entityId);
                    if (doc == null) {
                        continue;
                    }
                    String candidateName = firstString(doc, "canonical_name", "canonicalName");
                    List<String> aliases = stringList(doc.get("aliases"));
                    double nameScore = candidateNameScore(canonicalName, candidateName, aliases);
                    if (!shouldKeepVectorOnlyCandidate(hit.score(), nameScore)) {
                        continue;
                    }
                    T3ResolveBatchRequest.Candidate candidate = new T3ResolveBatchRequest.Candidate();
                    candidate.setEntityId(entityId);
                    candidate.setType(normalizeCandidateType(firstString(doc, "entity_type", "entityType", entityType)));
                    candidate.setScore(Math.max(boundedScore((double) hit.score()), nameScore));
                    candidate.setRetrievalChannels(new String[]{"VECTOR_INDEX"});
                    candidate.setCanonicalName(candidateName);
                    candidate.setAliases(aliases);
                    candidate.setImportanceScore(numberValue(doc.get("importance_score")));
                    candidate.setAttributes(Map.of());
                    candidates.put(entityId, candidate);
                    if (countVectorOnly(candidates) >= MAX_VECTOR_ONLY_CANDIDATES) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[EntityCandidateRetrieval] Milvus candidate retrieval failed, name={}", canonicalName, e);
        }

        return candidates.values().stream()
                .sorted((a, b) -> Double.compare(scoreOf(b.getScore()), scoreOf(a.getScore())))
                .limit(topK)
                .toList();
    }

    private T3ResolveBatchRequest.Candidate toCandidate(Map<String, Object> esResult,
                                                       String channel,
                                                       String queryName) {
        T3ResolveBatchRequest.Candidate candidate = new T3ResolveBatchRequest.Candidate();
        candidate.setEntityId(firstString(esResult, "entity_id", "entityId"));
        String candidateName = firstString(esResult, "canonical_name", "canonicalName");
        List<String> aliases = stringList(esResult.get("aliases"));
        candidate.setCanonicalName(candidateName);
        candidate.setType(normalizeCandidateType(firstString(esResult, "entity_type", "entityType")));
        candidate.setAliases(aliases);
        candidate.setImportanceScore(numberValue(esResult.get("importance_score")));
        candidate.setAttributes(Map.of());
        candidate.setScore(candidateNameScore(queryName, candidateName, aliases));
        candidate.setRetrievalChannels(new String[]{channel});
        return candidate;
    }

    private void addChannel(T3ResolveBatchRequest.Candidate candidate, String channel) {
        String[] current = candidate.getRetrievalChannels();
        if (current == null || current.length == 0) {
            candidate.setRetrievalChannels(new String[]{channel});
            return;
        }
        for (String existing : current) {
            if (channel.equals(existing)) {
                return;
            }
        }
        String[] next = new String[current.length + 1];
        System.arraycopy(current, 0, next, 0, current.length);
        next[current.length] = channel;
        candidate.setRetrievalChannels(next);
    }

    private String firstString(Map<String, Object> map, String firstKey, String secondKey) {
        return firstString(map, firstKey, secondKey, null);
    }

    private String firstString(Map<String, Object> map, String firstKey, String secondKey, String fallback) {
        Object value = map.get(firstKey);
        if (value == null) {
            value = map.get(secondKey);
        }
        return value == null ? fallback : value.toString();
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }
        return List.of();
    }

    private Double numberValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean shouldKeepVectorOnlyCandidate(double vectorScore, double nameScore) {
        if (vectorScore >= MIN_VECTOR_ONLY_SCORE) {
            return true;
        }
        if (nameScore >= STRONG_NAME_SCORE && vectorScore >= MIN_VECTOR_SCORE_WITH_STRONG_NAME) {
            return true;
        }
        return nameScore >= RELATED_NAME_SCORE && vectorScore >= MIN_VECTOR_SCORE_WITH_RELATED_NAME;
    }

    private int countVectorOnly(Map<String, T3ResolveBatchRequest.Candidate> candidates) {
        int count = 0;
        for (T3ResolveBatchRequest.Candidate candidate : candidates.values()) {
            String[] channels = candidate.getRetrievalChannels();
            if (channels != null && channels.length == 1 && "VECTOR_INDEX".equals(channels[0])) {
                count++;
            }
        }
        return count;
    }

    private double candidateNameScore(String queryName, String candidateName, List<String> aliases) {
        double best = lexicalSimilarity(queryName, candidateName);
        if (aliases != null) {
            for (String alias : aliases) {
                best = Math.max(best, lexicalSimilarity(queryName, alias));
            }
        }
        return boundedScore(best);
    }

    private double lexicalSimilarity(String left, String right) {
        String a = normalizeName(left);
        String b = normalizeName(right);
        if (!hasText(a) || !hasText(b)) {
            return 0D;
        }
        String compactA = a.replace(" ", "");
        String compactB = b.replace(" ", "");
        if (a.equals(b) || compactA.equals(compactB)) {
            return 1D;
        }

        double best = 0D;
        if (compactA.length() <= 4) {
            String acronym = acronym(b);
            String hybridAcronym = hybridAcronym(b);
            if (compactA.equals(acronym) || compactA.equals(hybridAcronym)) {
                best = Math.max(best, 0.95D);
            } else if (acronym.endsWith(compactA) || hybridAcronym.endsWith(compactA)) {
                best = Math.max(best, 0.85D);
            } else if (isOrderedSubsequence(compactA, compactB) && !b.contains(" ")) {
                best = Math.max(best, 0.70D);
            }
        }

        if (compactB.startsWith(compactA) || compactA.startsWith(compactB)
                || b.contains(a) || a.contains(b)) {
            int shorter = Math.min(compactA.length(), compactB.length());
            int longer = Math.max(compactA.length(), compactB.length());
            best = Math.max(best, longer == 0 ? 0D : (double) shorter / longer);
        }

        best = Math.max(best, tokenJaccard(a, b));
        return boundedScore(best);
    }

    private double tokenJaccard(String left, String right) {
        Set<String> leftTokens = tokenSet(left);
        Set<String> rightTokens = tokenSet(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0D;
        }
        Set<String> intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new HashSet<>(leftTokens);
        union.addAll(rightTokens);
        return union.isEmpty() ? 0D : (double) intersection.size() / union.size();
    }

    private Set<String> tokenSet(String value) {
        Set<String> result = new HashSet<>();
        if (!hasText(value)) {
            return result;
        }
        for (String token : value.split("\\s+")) {
            if (hasText(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private String acronym(String value) {
        StringBuilder result = new StringBuilder();
        for (String token : value.split("\\s+")) {
            if (!token.isBlank()) {
                result.append(token.charAt(0));
            }
        }
        return result.toString();
    }

    private String hybridAcronym(String value) {
        String[] tokens = value.split("\\s+");
        if (tokens.length == 0) {
            return "";
        }
        StringBuilder result = new StringBuilder(tokens[0]);
        for (int i = 1; i < tokens.length; i++) {
            if (!tokens[i].isBlank()) {
                result.append(tokens[i].charAt(0));
            }
        }
        return result.toString();
    }

    private boolean isOrderedSubsequence(String query, String candidate) {
        int index = 0;
        for (int i = 0; i < candidate.length() && index < query.length(); i++) {
            if (candidate.charAt(i) == query.charAt(index)) {
                index++;
            }
        }
        return index == query.length();
    }

    private boolean isFragmentCandidate(String queryName, String candidateName) {
        String query = normalizeName(queryName);
        String candidate = normalizeName(candidateName);
        if (!hasText(query) || !hasText(candidate)) {
            return false;
        }
        return query.contains(" ") && DIRECTIONAL_FRAGMENT_NAMES.contains(candidate);
    }

    private String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        String noDiacritics = decomposed.replaceAll("\\p{M}", "");
        String normalized = noDiacritics.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return normalized;
    }

    private String normalizeCandidateType(String entityType) {
        if (!hasText(entityType)) {
            return entityType;
        }
        return switch (entityType.trim().toLowerCase(Locale.ROOT)) {
            case "person" -> "person";
            case "organization", "org" -> "organization";
            case "event" -> "event";
            case "location" -> "location";
            case "socialaccount", "social_account", "account" -> "social_account";
            case "mediacontent", "media_content", "content" -> "media_content";
            default -> entityType.trim().toLowerCase(Locale.ROOT);
        };
    }

    private double boundedScore(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return 0D;
        }
        if (value < 0D) {
            return 0D;
        }
        if (value > 1D) {
            return 1D;
        }
        return value;
    }

    private double scoreOf(Double value) {
        return value == null ? 0D : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

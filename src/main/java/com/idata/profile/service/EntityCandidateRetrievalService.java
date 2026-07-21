package com.idata.profile.service;

import com.idata.profile.agentproxy.dto.t3.T3ResolveBatchRequest;
import com.idata.profile.infra.elasticsearch.EntityEsService;
import com.idata.profile.infra.embedding.EmbeddingService;
import com.idata.profile.infra.milvus.MilvusVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EntityCandidateRetrievalService {

    private static final double MIN_VECTOR_ONLY_SCORE = 0.85D;

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
                candidates.put(entityId, toCandidate(r, "NAME_INDEX"));
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
                    if (hit.score() < MIN_VECTOR_ONLY_SCORE) {
                        continue;
                    }
                    Map<String, Object> doc = backfilled.get(entityId);
                    T3ResolveBatchRequest.Candidate candidate = new T3ResolveBatchRequest.Candidate();
                    candidate.setEntityId(entityId);
                    candidate.setType(entityType);
                    candidate.setScore((double) hit.score());
                    candidate.setRetrievalChannels(new String[]{"VECTOR_INDEX"});
                    if (doc != null) {
                        candidate.setCanonicalName(firstString(doc, "canonical_name", "canonicalName"));
                        candidate.setAliases(stringList(doc.get("aliases")));
                        candidate.setImportanceScore(numberValue(doc.get("importance_score")));
                        candidate.setAttributes(Map.of());
                    } else {
                        candidate.setAliases(List.of());
                        candidate.setAttributes(Map.of());
                    }
                    candidates.put(entityId, candidate);
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

    private T3ResolveBatchRequest.Candidate toCandidate(Map<String, Object> esResult, String channel) {
        T3ResolveBatchRequest.Candidate candidate = new T3ResolveBatchRequest.Candidate();
        candidate.setEntityId(firstString(esResult, "entity_id", "entityId"));
        candidate.setCanonicalName(firstString(esResult, "canonical_name", "canonicalName"));
        candidate.setType(firstString(esResult, "entity_type", "entityType"));
        candidate.setAliases(stringList(esResult.get("aliases")));
        candidate.setImportanceScore(numberValue(esResult.get("importance_score")));
        candidate.setAttributes(Map.of());
        candidate.setScore(numberValue(esResult.getOrDefault("score", 0.5D)));
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
        Object value = map.get(firstKey);
        if (value == null) {
            value = map.get(secondKey);
        }
        return value == null ? null : value.toString();
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

    private double scoreOf(Double value) {
        return value == null ? 0D : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

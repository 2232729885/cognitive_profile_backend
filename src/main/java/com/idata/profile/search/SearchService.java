package com.idata.profile.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingRequest;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingResponse;
import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.infra.elasticsearch.MediaContentEsService;
import com.idata.profile.infra.elasticsearch.SocialAccountEsService;
import com.idata.profile.infra.milvus.MilvusVectorService;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.account.SocialAccountMapper;
import com.idata.profile.mapper.content.MediaAssetMapper;
import com.idata.profile.mapper.content.MediaContentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final int DEFAULT_TOP_K = 20;
    private static final int RRF_K = 60;
    private static final long HYBRID_ROUTE_TIMEOUT_SECONDS = 8L;

    private final AgentProxyClient agentProxyClient;
    private final MilvusVectorService milvusService;
    private final MediaContentEsService esService;
    private final SocialAccountEsService socialAccountEsService;
    private final Neo4jGraphService neo4jGraphService;
    private final MediaContentMapper mediaContentMapper;
    private final MediaAssetMapper mediaAssetMapper;
    private final SocialAccountMapper socialAccountMapper;

    public SearchResult searchByText(String keyword, String platform,
                                     String language, int page, int size) {
        long startedAt = System.currentTimeMillis();
        int safePage = Math.max(page, 0);
        int safeSize = normalizeSize(size);
        int fetchSize = Math.max((safePage + 1) * safeSize, safeSize);

        List<MediaContentEsService.EsSearchResult> esResults = esService.searchByKeywordWithHighlight(
                keyword, platform, language, fetchSize);
        List<String> rankedIds = esResults.stream()
                .map(MediaContentEsService.EsSearchResult::getContentId)
                .toList();
        Map<String, Map<String, List<String>>> highlights = esResults.stream()
                .collect(Collectors.toMap(
                        MediaContentEsService.EsSearchResult::getContentId,
                        MediaContentEsService.EsSearchResult::getHighlights,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, Double> scores = esResults.stream()
                .filter(r -> r.getScore() != null)
                .collect(Collectors.toMap(
                        MediaContentEsService.EsSearchResult::getContentId,
                        MediaContentEsService.EsSearchResult::getScore,
                        (left, right) -> left,
                        LinkedHashMap::new));
        int fromIndex = Math.min(safePage * safeSize, rankedIds.size());
        int toIndex = Math.min(fromIndex + safeSize, rankedIds.size());
        List<MediaContent> items = fetchContentsInOrder(rankedIds.subList(fromIndex, toIndex));

        SearchResult result = buildResult(items, rankedIds.size(), "text", startedAt);
        result.setHighlights(highlights);
        result.setScores(scores);
        return result;
    }

    public SearchResult searchBySemantic(String queryText, String platform,
                                         String language, int topK) {
        long startedAt = System.currentTimeMillis();
        int safeTopK = normalizeSize(topK);
        List<MilvusVectorService.ScoredEntityId> scored =
                searchSemanticIds(queryText, platform, language, safeTopK);
        List<String> ids = scored.stream()
                .map(MilvusVectorService.ScoredEntityId::entityId)
                .toList();
        List<MediaContent> items = fetchContentsInOrder(ids);
        Map<String, Double> similarityScores = scored.stream()
                .collect(Collectors.toMap(
                        MilvusVectorService.ScoredEntityId::entityId,
                        s -> (double) s.score(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        SearchResult result = buildResult(items, ids.size(), "semantic", startedAt);
        result.setSimilarityScores(similarityScores);
        return result;
    }

    public SearchResult searchHybrid(HybridSearchRequest request) {
        long startedAt = System.currentTimeMillis();
        int topK = request != null && request.getTopK() > 0 ? request.getTopK() : DEFAULT_TOP_K;
        String queryText = request == null ? null : request.getQueryText();
        String platform = request == null ? null : request.getPlatform();
        String language = request == null ? null : request.getLanguage();
        boolean hasImage = request != null && hasText(request.getImageUrl()) && request.isEnableMilvus();

        CompletableFuture<List<MediaContentEsService.EsSearchResult>> esFuture = isEnabled(request, "es")
                ? safeRoute("es", () -> esService.searchByKeywordWithHighlight(queryText, platform, language, topK))
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<MilvusVectorService.ScoredEntityId>> milvusFuture = isEnabled(request, "milvus")
                ? safeRoute("milvus", () -> searchSemanticIds(queryText, platform, language, topK))
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<String>> neo4jFuture = request != null && request.isEnableNeo4j()
                ? safeRoute("neo4j", () -> searchNeo4jContentIds(queryText, platform, language, topK))
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<String>> imageFuture = hasImage
                ? safeRoute("image", () -> vectorizeAndSearchByImage(
                request.getImageUrl(), request.getTargetModalities(), topK))
                : CompletableFuture.completedFuture(List.of());

        CompletableFuture.allOf(esFuture, milvusFuture, neo4jFuture, imageFuture).join();

        List<MediaContentEsService.EsSearchResult> esResults = esFuture.join();
        List<String> esIds = esResults.stream()
                .map(MediaContentEsService.EsSearchResult::getContentId)
                .toList();
        List<MilvusVectorService.ScoredEntityId> milvusResults = milvusFuture.join();
        List<String> milvusIds = milvusResults.stream()
                .map(MilvusVectorService.ScoredEntityId::entityId)
                .toList();

        Map<String, Double> fusedScores = fuseByRrf(List.of(
                esIds, milvusIds, neo4jFuture.join(), imageFuture.join()));
        List<String> fusedIds = new ArrayList<>(fusedScores.keySet());
        List<String> topIds = fusedIds.size() > topK ? fusedIds.subList(0, topK) : fusedIds;
        List<MediaContent> items = fetchContentsInOrder(topIds);

        Map<String, Map<String, List<String>>> highlights = esResults.stream()
                .collect(Collectors.toMap(
                        MediaContentEsService.EsSearchResult::getContentId,
                        MediaContentEsService.EsSearchResult::getHighlights,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, Double> scores = esResults.stream()
                .filter(r -> r.getScore() != null)
                .collect(Collectors.toMap(
                        MediaContentEsService.EsSearchResult::getContentId,
                        MediaContentEsService.EsSearchResult::getScore,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, Double> similarityScores = milvusResults.stream()
                .collect(Collectors.toMap(
                        MilvusVectorService.ScoredEntityId::entityId,
                        s -> (double) s.score(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        SearchResult result = buildResult(items, fusedIds.size(), "hybrid", startedAt);
        result.setHighlights(highlights);
        result.setScores(scores);
        result.setSimilarityScores(similarityScores);
        result.setFusionScores(fusedScores);
        return result;
    }

    public List<SocialAccount> searchAccounts(String queryText, String platform, String accountType, int topK) {
        int safeTopK = normalizeSize(topK);

        CompletableFuture<List<String>> esFuture = safeRoute("account-es",
                () -> socialAccountEsService.searchByKeyword(queryText, platform, accountType, safeTopK));
        CompletableFuture<List<String>> milvusFuture = safeRoute("account-milvus",
                () -> searchAccountSemanticIds(queryText, safeTopK));

        CompletableFuture.allOf(esFuture, milvusFuture).join();

        List<String> fusedIds = new ArrayList<>(fuseByRrf(List.of(esFuture.join(), milvusFuture.join())).keySet());
        List<String> topIds = fusedIds.size() > safeTopK ? fusedIds.subList(0, safeTopK) : fusedIds;
        if (topIds.isEmpty()) {
            return List.of();
        }

        List<UUID> uuids = topIds.stream()
                .map(this::parseUuid)
                .filter(Objects::nonNull)
                .toList();
        if (uuids.isEmpty()) {
            return List.of();
        }

        List<SocialAccount> accounts = socialAccountMapper.selectBatchIds(uuids);
        Map<String, SocialAccount> byId = accounts.stream()
                .collect(Collectors.toMap(a -> a.getId().toString(), a -> a, (a, b) -> a));
        return topIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(account -> !hasText(platform) || platform.equals(account.getPlatform()))
                .filter(account -> !hasText(accountType) || accountType.equals(account.getAccountType()))
                .toList();
    }

    public SearchResult searchByImage(String imageUrl, String targetModalities, int topK) {
        long startedAt = System.currentTimeMillis();
        int safeTopK = normalizeSize(topK);
        List<String> ids = vectorizeAndSearchByImage(
                imageUrl, targetModalities != null ? targetModalities : "all", safeTopK);
        List<MediaContent> items = fetchContentsInOrder(ids);
        return buildResult(items, ids.size(), "image", startedAt);
    }

    private <T> CompletableFuture<List<T>> safeRoute(String routeName, SearchRoute<T> route) {
        return CompletableFuture.supplyAsync(() -> {
                    try {
                        return route.search();
                    } catch (Exception e) {
                        log.warn("Hybrid search route failed, route={}", routeName, e);
                        return List.<T>of();
                    }
                })
                .orTimeout(HYBRID_ROUTE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    log.warn("Hybrid search route completed exceptionally, route={}", routeName, e);
                    return List.of();
                });
    }

    private List<MilvusVectorService.ScoredEntityId> searchSemanticIds(
            String queryText, String platform, String language, int topK) {
        if (!hasText(queryText) || topK <= 0) {
            return List.of();
        }

        T4EmbeddingRequest request = new T4EmbeddingRequest();
        request.setText(queryText);
        T4EmbeddingResponse response = agentProxyClient.call(
                "T4", "generate_text_embedding", request, T4EmbeddingResponse.class);
        float[] embedding = response == null ? null : response.getEmbedding();
        if (embedding == null) {
            return List.of();
        }
        return milvusService.searchTextEmbeddingsWithScore(embedding, topK, platform, language);
    }

    private List<String> searchAccountSemanticIds(String queryText, int topK) {
        if (!hasText(queryText)) {
            return List.of();
        }
        T4EmbeddingRequest request = new T4EmbeddingRequest();
        request.setText(queryText);
        T4EmbeddingResponse response = agentProxyClient.call(
                "T4", "generate_text_embedding", request, T4EmbeddingResponse.class);
        float[] embedding = response == null ? null : response.getEmbedding();
        if (embedding == null) {
            return List.of();
        }
        return milvusService.searchAccountEmbeddings(embedding, topK).stream()
                .map(MilvusVectorService.ScoredEntityId::entityId)
                .toList();
    }

    /**
     * 把图片向量化后查 Milvus，返回 content_id 列表。
     * 同时支持 text_embeddings（以图搜文）和 image_embeddings（以图搜图）。
     */
    private List<String> vectorizeAndSearchByImage(String imageUrl, String targetModalities, int topK) {
        if (!hasText(imageUrl) || topK <= 0) {
            return List.of();
        }

        float[] embedding;
        try {
            T4EmbeddingRequest request = new T4EmbeddingRequest();
            request.setImageUrl(imageUrl);
            T4EmbeddingResponse response = agentProxyClient.call(
                    "T4", "generate_image_embedding", request, T4EmbeddingResponse.class);
            embedding = response == null ? null : response.getEmbedding();
        } catch (Exception e) {
            log.warn("Image vectorization failed, imageUrl={}", imageUrl, e);
            return List.of();
        }
        if (embedding == null) {
            return List.of();
        }

        try {
            String modality = hasText(targetModalities) ? targetModalities.trim().toLowerCase() : "all";
            if ("text".equals(modality)) {
                return milvusService.searchTextEmbeddings(embedding, topK, null, null);
            }
            if ("image".equals(modality)) {
                return imageAssetIdsToContentIds(milvusService.searchImageEmbeddings(embedding, topK));
            }

            CompletableFuture<List<String>> textFuture = safeRoute("image-text",
                    () -> milvusService.searchTextEmbeddings(embedding, topK, null, null));
            CompletableFuture<List<String>> imageFuture = safeRoute("image-image",
                    () -> imageAssetIdsToContentIds(milvusService.searchImageEmbeddings(embedding, topK)));
            CompletableFuture.allOf(textFuture, imageFuture).join();
            List<String> fused = new ArrayList<>(
                    fuseByRrf(List.of(textFuture.join(), imageFuture.join())).keySet());
            return fused.size() > topK ? fused.subList(0, topK) : fused;
        } catch (Exception e) {
            log.warn("Image vector search failed, imageUrl={}, targetModalities={}", imageUrl, targetModalities, e);
            return List.of();
        }
    }

    private List<String> imageAssetIdsToContentIds(List<String> assetIds) {
        List<UUID> uuidIds = assetIds.stream()
                .map(id -> {
                    String raw = id != null && id.startsWith("image_")
                            ? id.substring("image_".length())
                            : id;
                    return parseUuid(raw);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        if (uuidIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, MediaAsset> assetsById = new LinkedHashMap<>();
        for (MediaAsset asset : mediaAssetMapper.selectByIds(uuidIds)) {
            assetsById.put(asset.getId(), asset);
        }

        List<String> contentIds = new ArrayList<>();
        for (UUID assetId : uuidIds) {
            MediaAsset asset = assetsById.get(assetId);
            if (asset != null && asset.getContentId() != null) {
                contentIds.add(asset.getContentId().toString());
            }
        }
        return contentIds;
    }

    private List<String> searchNeo4jContentIds(String queryText, String platform, String language, int topK) {
        if (!hasText(queryText) || topK <= 0) {
            return List.of();
        }

        List<Map<String, Object>> nodes = neo4jGraphService.searchNodesByName(queryText, null, topK);
        LinkedHashSet<UUID> accountIds = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            String label = stringValue(node.get("label"));
            String id = stringValue(node.get("id"));
            if (!hasText(label) || !hasText(id)) {
                continue;
            }
            UUID uuid = parseUuid(id);
            if (uuid != null && "Narrative".equals(label)) {
                accountIds.addAll(neo4jGraphService.findSocialAccountIdsByNarrative(uuid));
            }
            collectSocialAccountsFromGraph(neo4jGraphService.findTwoHopGraph(id, label), accountIds);
        }

        if (accountIds.isEmpty()) {
            return List.of();
        }

        LambdaQueryWrapper<MediaContent> query = new LambdaQueryWrapper<>();
        query.in(MediaContent::getAuthorAccountId, accountIds);
        if (hasText(platform)) {
            query.eq(MediaContent::getPlatform, platform);
        }
        if (hasText(language)) {
            query.eq(MediaContent::getLanguage, language);
        }
        query.orderByDesc(MediaContent::getPublishedAt);
        query.last("LIMIT " + topK);
        return mediaContentMapper.selectList(query).stream()
                .map(MediaContent::getId)
                .map(UUID::toString)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private void collectSocialAccountsFromGraph(Map<String, Object> graph, Collection<UUID> accountIds) {
        if (graph == null || graph.isEmpty()) {
            return;
        }
        Object nodesValue = graph.get("nodes");
        if (!(nodesValue instanceof List<?> nodes)) {
            return;
        }
        for (Object value : nodes) {
            if (!(value instanceof Map<?, ?> rawNode)) {
                continue;
            }
            Map<String, Object> node = (Map<String, Object>) rawNode;
            if (!"SocialAccount".equals(node.get("label"))) {
                continue;
            }
            UUID accountId = parseUuid(stringValue(node.get("id")));
            if (accountId != null) {
                accountIds.add(accountId);
            }
        }
    }

    private Map<String, Double> fuseByRrf(List<List<String>> rankedLists) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> rankedIds : rankedLists) {
            LinkedHashSet<String> deduped = new LinkedHashSet<>(rankedIds);
            int rank = 1;
            for (String id : deduped) {
                if (hasText(id)) {
                    scores.merge(id, 1D / (RRF_K + rank), Double::sum);
                }
                rank++;
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private List<MediaContent> fetchContentsInOrder(List<String> ids) {
        List<UUID> uuidIds = ids.stream()
                .map(this::parseUuid)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (uuidIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, MediaContent> contentsById = new LinkedHashMap<>();
        for (MediaContent content : mediaContentMapper.selectBatchIds(uuidIds)) {
            contentsById.put(content.getId(), content);
        }

        List<MediaContent> ordered = new ArrayList<>();
        for (UUID id : uuidIds) {
            MediaContent content = contentsById.get(id);
            if (content != null) {
                ordered.add(content);
            }
        }
        return ordered;
    }

    private SearchResult buildResult(List<MediaContent> items, int total, String searchType, long startedAt) {
        SearchResult result = new SearchResult();
        result.setItems(items);
        result.setTotal(total);
        result.setSearchType(searchType);
        result.setDurationMs(System.currentTimeMillis() - startedAt);
        return result;
    }

    private boolean isEnabled(HybridSearchRequest request, String routeName) {
        if (request == null) {
            return false;
        }
        return switch (routeName) {
            case "es" -> request.isEnableEs();
            case "milvus" -> request.isEnableMilvus();
            default -> false;
        };
    }

    private int normalizeSize(int size) {
        return size > 0 ? size : DEFAULT_TOP_K;
    }

    private UUID parseUuid(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @FunctionalInterface
    private interface SearchRoute<T> {
        List<T> search();
    }
}

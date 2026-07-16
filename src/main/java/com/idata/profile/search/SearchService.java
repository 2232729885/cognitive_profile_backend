package com.idata.profile.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.infra.elasticsearch.EntityEsService;
import com.idata.profile.infra.elasticsearch.MediaContentEsService;
import com.idata.profile.infra.elasticsearch.SocialAccountEsService;
import com.idata.profile.infra.embedding.EmbeddingService;
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
    private static final float DEFAULT_CONTENT_SEMANTIC_MIN_SCORE = 0.45F;

    private final EmbeddingService embeddingService;
    private final MilvusVectorService milvusService;
    private final MediaContentEsService esService;
    private final EntityEsService entityEsService;
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
                                         String language, int topK, Double semanticMinScore) {
        long startedAt = System.currentTimeMillis();
        int safeTopK = normalizeSize(topK);
        List<MilvusVectorService.ScoredEntityId> scored =
                searchSemanticIds(queryText, platform, language, safeTopK, semanticMinScore);
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
        String targetModality = normalizeTargetModality(request != null ? request.getTargetModalities() : null);
        Double semanticMinScore = request == null ? null : request.getSemanticMinScore();
        if ("image".equals(targetModality)) {
            if (!isEnabled(request, "milvus")) {
                SearchResult result = buildResult(List.of(), 0, "hybrid-image", startedAt);
                result.setImageItems(List.of());
                return result;
            }
            if (hasImage) {
                return searchHybridImagesByImage(request.getImageUrl(), topK, startedAt);
            }
            if (hasText(queryText)) {
                return searchHybridImagesByText(queryText, topK, startedAt);
            }
            SearchResult result = buildResult(List.of(), 0, "hybrid-image", startedAt);
            result.setImageItems(List.of());
            return result;
        }

        CompletableFuture<List<MediaContentEsService.EsSearchResult>> esFuture = isEnabled(request, "es")
                ? safeRoute("es", () -> esService.searchByKeywordWithHighlight(queryText, platform, language, topK))
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<MilvusVectorService.ScoredEntityId>> milvusFuture = isEnabled(request, "milvus")
                ? safeRoute("milvus", () -> searchSemanticIds(queryText, platform, language, topK, semanticMinScore))
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

    private SearchResult searchHybridImagesByText(String queryText, int topK, long startedAt) {
        float[] embedding = textEmbedding(queryText);
        List<SearchResult.ImageItem> imageItems = embedding == null
                ? List.of()
                : searchImageSemanticAssets(embedding, topK);
        SearchResult result = buildResult(List.of(), imageItems.size(), "hybrid-image", startedAt);
        result.setImageItems(imageItems);
        return result;
    }

    private SearchResult searchHybridImagesByImage(String imageUrl, int topK, long startedAt) {
        float[] embedding = imageEmbedding(imageUrl);
        List<SearchResult.ImageItem> imageItems = embedding == null
                ? List.of()
                : searchImageSemanticAssets(embedding, topK);
        SearchResult result = buildResult(List.of(), imageItems.size(), "hybrid-image", startedAt);
        result.setImageItems(imageItems);
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

    public List<Map<String, Object>> searchEntitiesFused(String keyword, String entityType, int topK) {
        return searchEntitiesFused(keyword, hasText(entityType) ? List.of(entityType) : List.of(), topK);
    }

    public EntityCandidateSearchResponse searchEntityCandidates(EntityCandidateSearchRequest request) {
        if (request == null || !hasText(request.getQuery())) {
            return EntityCandidateSearchResponse.fail("400", "query不能为空");
        }

        int safeTopK = request.getTopK() > 0 ? request.getTopK() : 10;
        List<String> requestedTypes = normalizeEntityTypes(request.getEntityTypes());
        String retrievalMode = normalizeRetrievalMode(request.getRetrievalMode());
        if (retrievalMode == null) {
            return EntityCandidateSearchResponse.fail("400", "retrieval_mode只支持keyword、semantic、both");
        }

        CompletableFuture<List<EntityCandidateSearchResponse.Candidate>> keywordFuture =
                CompletableFuture.completedFuture(List.of());
        if (useKeywordCandidates(retrievalMode)) {
            keywordFuture = safeRoute("entity-candidate-keyword",
                    () -> searchEntityCandidatesFromEs(request.getQuery(), requestedTypes, safeTopK));
        }
        CompletableFuture<List<EntityCandidateSearchResponse.Candidate>> semanticFuture =
                CompletableFuture.completedFuture(List.of());
        if (useSemanticCandidates(retrievalMode)) {
            semanticFuture = safeRoute("entity-candidate-semantic",
                    () -> searchEntityCandidatesFromMilvus(request.getQuery(), requestedTypes, safeTopK));
        }
        CompletableFuture.allOf(keywordFuture, semanticFuture).join();
        return EntityCandidateSearchResponse.ok(request.getTraceId(), keywordFuture.join(), semanticFuture.join());
    }

    public List<Map<String, Object>> searchEntitiesFused(String keyword, Collection<String> entityTypes, int topK) {
        if (!hasText(keyword)) {
            return List.of();
        }
        int safeTopK = normalizeSize(topK);
        List<String> requestedTypes = normalizeEntityTypes(entityTypes);
        boolean allDomains = requestedTypes.isEmpty();
        List<String> standardEntityTypes = requestedTypes.stream()
                .map(this::canonicalStandardEntityType)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        boolean includeEntities = allDomains || !standardEntityTypes.isEmpty();
        boolean includeAccounts = allDomains || requestedTypes.stream().anyMatch("SocialAccount"::equalsIgnoreCase);
        boolean includeContents = allDomains || requestedTypes.stream().anyMatch("MediaContent"::equalsIgnoreCase);
        if (!includeEntities && !includeAccounts && !includeContents) {
            return List.of();
        }

        float[] embedding = textEmbedding(keyword);

        List<String> entityTypeFilters = new ArrayList<>();
        if (includeEntities) {
            if (allDomains) {
                entityTypeFilters.add(null);
            } else {
                entityTypeFilters.addAll(standardEntityTypes);
            }
        }
        List<CompletableFuture<List<Map<String, Object>>>> entityEsFutures = entityTypeFilters.stream()
                .map(type -> safeRoute("entity-es", () -> entityEsService.searchEntities(keyword, type, safeTopK)))
                .toList();
        List<CompletableFuture<List<MilvusVectorService.ScoredEntityId>>> entityMilvusFutures =
                embedding == null ? List.of() : entityTypeFilters.stream()
                        .map(type -> safeRoute("entity-milvus",
                                () -> milvusService.searchEntityEmbeddings(embedding, safeTopK, type)))
                        .toList();
        CompletableFuture<List<SocialAccountEsService.EsAccountSearchResult>> accountEsFuture = includeAccounts
                ? safeRoute("account-es", () -> socialAccountEsService.searchByKeywordWithScore(
                keyword, null, null, safeTopK))
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<MilvusVectorService.ScoredEntityId>> accountMilvusFuture =
                includeAccounts && embedding != null
                        ? safeRoute("account-milvus", () -> milvusService.searchAccountEmbeddings(embedding, safeTopK))
                        : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<MediaContentEsService.EsSearchResult>> contentEsFuture = includeContents
                ? safeRoute("content-es", () -> esService.searchByKeywordWithHighlight(
                keyword, null, null, safeTopK))
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<MilvusVectorService.ScoredEntityId>> textMilvusFuture =
                includeContents && embedding != null
                        ? safeRoute("content-text-milvus",
                        () -> milvusService.searchTextEmbeddingsWithScore(embedding, safeTopK, null, null))
                        : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<MilvusVectorService.ScoredEntityId>> imageMilvusFuture =
                includeContents && embedding != null
                        ? safeRoute("content-image-milvus",
                        () -> searchImageSemanticContentIds(embedding, safeTopK))
                        : CompletableFuture.completedFuture(List.of());

        List<CompletableFuture<?>> futures = new ArrayList<>();
        futures.addAll(entityEsFutures);
        futures.addAll(entityMilvusFutures);
        futures.add(accountEsFuture);
        futures.add(accountMilvusFuture);
        futures.add(contentEsFuture);
        futures.add(textMilvusFuture);
        futures.add(imageMilvusFuture);
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        List<Map<String, Object>> entityEsResults = entityEsFutures.stream()
                .flatMap(future -> future.join().stream())
                .toList();
        List<MilvusVectorService.ScoredEntityId> entityMilvusResults = entityMilvusFutures.stream()
                .flatMap(future -> future.join().stream())
                .toList();
        List<SocialAccountEsService.EsAccountSearchResult> accountEsResults = accountEsFuture.join();
        List<MilvusVectorService.ScoredEntityId> accountMilvusResults = accountMilvusFuture.join();
        List<MediaContentEsService.EsSearchResult> contentEsResults = contentEsFuture.join();
        List<MilvusVectorService.ScoredEntityId> textMilvusResults = textMilvusFuture.join();
        List<MilvusVectorService.ScoredEntityId> imageMilvusResults = imageMilvusFuture.join();

        List<String> entityEsIds = entityEsResults.stream()
                .map(r -> scopedId("entity", (String) r.get("entityId")))
                .toList();
        List<String> entityMilvusIds = entityMilvusResults.stream()
                .map(r -> scopedId("entity", r.entityId()))
                .toList();
        List<String> accountEsIds = accountEsResults.stream()
                .map(r -> scopedId("account", r.accountId()))
                .toList();
        List<String> accountMilvusIds = accountMilvusResults.stream()
                .map(r -> scopedId("account", r.entityId()))
                .toList();
        List<String> contentEsIds = contentEsResults.stream()
                .map(r -> scopedId("content", r.getContentId()))
                .toList();
        List<String> textMilvusIds = textMilvusResults.stream()
                .map(r -> scopedId("content", r.entityId()))
                .toList();
        List<String> imageMilvusIds = imageMilvusResults.stream()
                .map(r -> scopedId("content", r.entityId()))
                .toList();

        Map<String, Double> esScores = new LinkedHashMap<>();
        entityEsResults.forEach(r -> putScore(esScores, scopedId("entity", (String) r.get("entityId")),
                r.get("score")));
        accountEsResults.forEach(r -> putScore(esScores, scopedId("account", r.accountId()), r.score()));
        contentEsResults.forEach(r -> putScore(esScores, scopedId("content", r.getContentId()), r.getScore()));

        Map<String, Double> similarityScores = new LinkedHashMap<>();
        entityMilvusResults.forEach(r -> putScore(similarityScores, scopedId("entity", r.entityId()), r.score()));
        accountMilvusResults.forEach(r -> putScore(similarityScores, scopedId("account", r.entityId()), r.score()));
        textMilvusResults.forEach(r -> putScore(similarityScores, scopedId("content", r.entityId()), r.score()));
        imageMilvusResults.forEach(r -> {
            String id = scopedId("content", r.entityId());
            if (hasText(id)) {
                similarityScores.merge(id, (double) r.score(), Math::max);
            }
        });

        Map<String, Double> fusedScores = fuseByRrf(List.of(
                entityEsIds, entityMilvusIds,
                accountEsIds, accountMilvusIds,
                contentEsIds, textMilvusIds, imageMilvusIds));
        List<String> topScopedIds = new ArrayList<>(fusedScores.keySet());
        if (topScopedIds.size() > safeTopK) {
            topScopedIds = topScopedIds.subList(0, safeTopK);
        }
        if (topScopedIds.isEmpty()) {
            return List.of();
        }

        List<String> entityIds = topScopedIds.stream()
                .filter(id -> id.startsWith("entity:"))
                .map(id -> id.substring("entity:".length()))
                .toList();
        List<UUID> accountIds = topScopedIds.stream()
                .filter(id -> id.startsWith("account:"))
                .map(id -> parseUuid(id.substring("account:".length())))
                .filter(Objects::nonNull)
                .toList();
        List<UUID> contentIds = topScopedIds.stream()
                .filter(id -> id.startsWith("content:"))
                .map(id -> parseUuid(id.substring("content:".length())))
                .filter(Objects::nonNull)
                .toList();

        Map<String, Map<String, Object>> entityDetails = entityEsService.getEntitiesByIds(entityIds);
        Map<String, SocialAccount> accountsById = accountIds.isEmpty()
                ? Map.of()
                : socialAccountMapper.selectBatchIds(accountIds).stream()
                .collect(Collectors.toMap(a -> a.getId().toString(), a -> a, (a, b) -> a));
        Map<String, MediaContent> contentsById = contentIds.isEmpty()
                ? Map.of()
                : mediaContentMapper.selectBatchIds(contentIds).stream()
                .collect(Collectors.toMap(c -> c.getId().toString(), c -> c, (a, b) -> a));

        List<Map<String, Object>> result = new ArrayList<>();
        for (String scopedId : topScopedIds) {
            Map<String, Object> item = null;
            String id = scopedId.substring(scopedId.indexOf(':') + 1);
            if (scopedId.startsWith("entity:")) {
                Map<String, Object> detail = entityDetails.get(id);
                if (detail != null) {
                    item = new LinkedHashMap<>(detail);
                    item.put("id", id);
                    item.put("resultType", "Entity");
                    item.put("entityType", detail.get("entity_type"));
                }
            } else if (scopedId.startsWith("account:")) {
                SocialAccount account = accountsById.get(id);
                if (account != null) {
                    item = socialAccountResult(account);
                }
            } else if (scopedId.startsWith("content:")) {
                MediaContent content = contentsById.get(id);
                if (content != null) {
                    item = mediaContentResult(content);
                }
            }
            if (item != null) {
                item.put("fusionScore", fusedScores.get(scopedId));
                if (esScores.containsKey(scopedId)) {
                    item.put("esScore", esScores.get(scopedId));
                }
                if (similarityScores.containsKey(scopedId)) {
                    item.put("similarityScore", similarityScores.get(scopedId));
                }
                result.add(item);
            }
        }
        return result;
    }

    public SearchResult searchByImage(String imageUrl, String targetModalities, int topK) {
        long startedAt = System.currentTimeMillis();
        int safeTopK = normalizeSize(topK);
        String targetModality = normalizeTargetModality(targetModalities);
        if ("image".equals(targetModality)) {
            return searchHybridImagesByImage(imageUrl, safeTopK, startedAt);
        }
        List<String> ids = vectorizeAndSearchByImage(
                imageUrl, targetModality, safeTopK);
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

    private float[] textEmbedding(String text) {
        if (!hasText(text)) {
            return null;
        }
        return embeddingService.generateTextEmbedding(text);
    }

    private float[] imageEmbedding(String imageUrl) {
        if (!hasText(imageUrl)) {
            return null;
        }
        try {
            return embeddingService.generateImageEmbedding(imageUrl);
        } catch (Exception e) {
            log.warn("Image vectorization failed, imageUrl={}", imageUrl, e);
            return null;
        }
    }

    private String canonicalStandardEntityType(String entityType) {
        if (!hasText(entityType)) {
            return null;
        }
        return switch (entityType.trim().toLowerCase()) {
            case "person" -> "Person";
            case "organization" -> "Organization";
            case "event" -> "Event";
            case "location" -> "Location";
            default -> null;
        };
    }

    private List<String> normalizeEntityTypes(Collection<String> entityTypes) {
        if (entityTypes == null || entityTypes.isEmpty()) {
            return List.of();
        }
        return entityTypes.stream()
                .filter(this::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String normalizeRetrievalMode(String retrievalMode) {
        String mode = hasText(retrievalMode) ? retrievalMode.trim().toLowerCase() : "both";
        return switch (mode) {
            case "keyword", "semantic", "both" -> mode;
            default -> null;
        };
    }

    private boolean useKeywordCandidates(String retrievalMode) {
        return "keyword".equals(retrievalMode) || "both".equals(retrievalMode);
    }

    private boolean useSemanticCandidates(String retrievalMode) {
        return "semantic".equals(retrievalMode) || "both".equals(retrievalMode);
    }

    private String scopedId(String domain, String id) {
        if (!hasText(id)) {
            return null;
        }
        return domain + ":" + id;
    }

    private void putScore(Map<String, Double> scores, String id, Object score) {
        if (hasText(id) && score instanceof Number number) {
            scores.put(id, number.doubleValue());
        }
    }

    private List<MilvusVectorService.ScoredEntityId> searchImageSemanticContentIds(float[] embedding, int topK) {
        List<MilvusVectorService.ScoredEntityId> assetScores =
                milvusService.searchImageEmbeddingsWithScore(embedding, topK);
        List<UUID> assetIds = assetScores.stream()
                .map(item -> item.entityId() != null && item.entityId().startsWith("image_")
                        ? item.entityId().substring("image_".length())
                        : item.entityId())
                .map(this::parseUuid)
                .filter(Objects::nonNull)
                .toList();
        if (assetIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, MediaAsset> assetsById = new LinkedHashMap<>();
        for (MediaAsset asset : mediaAssetMapper.selectByIds(assetIds)) {
            assetsById.put(asset.getId(), asset);
        }

        Map<String, Float> contentScores = new LinkedHashMap<>();
        for (MilvusVectorService.ScoredEntityId scoredAsset : assetScores) {
            String rawAssetId = scoredAsset.entityId() != null && scoredAsset.entityId().startsWith("image_")
                    ? scoredAsset.entityId().substring("image_".length())
                    : scoredAsset.entityId();
            UUID assetId = parseUuid(rawAssetId);
            MediaAsset asset = assetId == null ? null : assetsById.get(assetId);
            if (asset != null && asset.getContentId() != null) {
                contentScores.merge(asset.getContentId().toString(), scoredAsset.score(), Math::max);
            }
        }
        return contentScores.entrySet().stream()
                .map(entry -> new MilvusVectorService.ScoredEntityId(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<SearchResult.ImageItem> searchImageSemanticAssets(float[] embedding, int topK) {
        List<MilvusVectorService.ScoredEntityId> assetScores =
                milvusService.searchImageEmbeddingsWithScore(embedding, topK);
        List<UUID> assetIds = assetScores.stream()
                .map(item -> normalizeImageAssetId(item.entityId()))
                .map(this::parseUuid)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (assetIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, MediaAsset> assetsById = new LinkedHashMap<>();
        for (MediaAsset asset : mediaAssetMapper.selectByIds(assetIds)) {
            if (asset != null && "image".equalsIgnoreCase(asset.getAssetType())
                    && asset.getContentId() != null) {
                assetsById.put(asset.getId(), asset);
            }
        }
        if (assetsById.isEmpty()) {
            return List.of();
        }

        List<UUID> contentIds = assetsById.values().stream()
                .map(MediaAsset::getContentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, MediaContent> contentsById = contentIds.isEmpty()
                ? Map.of()
                : mediaContentMapper.selectBatchIds(contentIds).stream()
                .collect(Collectors.toMap(c -> c.getId().toString(), c -> c, (left, right) -> left));

        List<SearchResult.ImageItem> items = new ArrayList<>();
        for (MilvusVectorService.ScoredEntityId scoredAsset : assetScores) {
            UUID assetId = parseUuid(normalizeImageAssetId(scoredAsset.entityId()));
            MediaAsset asset = assetId == null ? null : assetsById.get(assetId);
            MediaContent content = asset != null && asset.getContentId() != null
                    ? contentsById.get(asset.getContentId().toString())
                    : null;
            if (asset == null || content == null) {
                continue;
            }
            SearchResult.ImageItem item = new SearchResult.ImageItem();
            item.setAssetId(asset.getId().toString());
            item.setContentId(content.getId().toString());
            item.setSourceUrl(asset.getSourceUrl());
            item.setStorageUri(asset.getStorageUri());
            item.setMinioBucket(asset.getMinioBucket());
            item.setMinioKey(asset.getMinioKey());
            item.setMimeType(asset.getMimeType());
            item.setWidth(asset.getWidth());
            item.setHeight(asset.getHeight());
            item.setSimilarityScore((double) scoredAsset.score());
            item.setPlatform(content.getPlatform());
            item.setLanguage(content.getLanguage());
            item.setContentType(content.getContentType());
            item.setContentTitle(content.getTitle());
            item.setContentBodyText(content.getBodyText());
            item.setPublishedAt(content.getPublishedAt() != null ? content.getPublishedAt().toString() : null);
            items.add(item);
        }
        return items;
    }

    private String normalizeImageAssetId(String id) {
        return id != null && id.startsWith("image_") ? id.substring("image_".length()) : id;
    }

    private Map<String, Object> socialAccountResult(SocialAccount account) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", account.getId().toString());
        item.put("resultType", "SocialAccount");
        item.put("entityType", "SocialAccount");
        item.put("platform", account.getPlatform());
        item.put("handle", account.getHandle());
        item.put("displayName", account.getDisplayName());
        item.put("canonicalName", hasText(account.getDisplayName()) ? account.getDisplayName() : account.getHandle());
        item.put("bio", account.getBio());
        item.put("accountType", account.getAccountType());
        item.put("followersCount", account.getFollowersCount());
        item.put("verified", account.getVerified());
        return item;
    }

    private Map<String, Object> mediaContentResult(MediaContent content) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", content.getId().toString());
        item.put("resultType", "MediaContent");
        item.put("entityType", "MediaContent");
        item.put("platform", content.getPlatform());
        item.put("contentType", content.getContentType());
        item.put("title", content.getTitle());
        item.put("canonicalName", hasText(content.getTitle()) ? content.getTitle() : content.getPlatformContentId());
        item.put("bodyText", content.getBodyText());
        item.put("language", content.getLanguage());
        item.put("publishedAt", content.getPublishedAt());
        item.put("url", content.getUrl());
        return item;
    }

    private List<EntityCandidateSearchResponse.Candidate> searchEntityCandidatesFromEs(
            String keyword, List<String> requestedTypes, int topK) {
        if (!hasText(keyword) || topK <= 0) {
            return List.of();
        }
        boolean allDomains = requestedTypes == null || requestedTypes.isEmpty();
        List<String> standardTypes = standardEntityTypes(requestedTypes);
        boolean includeEntities = allDomains || !standardTypes.isEmpty();
        boolean includeAccounts = allDomains || containsType(requestedTypes, "SocialAccount");
        boolean includeContents = allDomains || containsType(requestedTypes, "MediaContent");

        List<ScoredScopedId> scored = new ArrayList<>();
        if (includeEntities) {
            List<String> filters = allDomains ? java.util.Collections.singletonList(null) : standardTypes;
            for (String type : filters) {
                entityEsService.searchEntities(keyword, type, topK).forEach(result ->
                        addScored(scored, "entity", stringValue(result.get("entityId")), result.get("score")));
            }
        }
        if (includeAccounts) {
            socialAccountEsService.searchByKeywordWithScore(keyword, null, null, topK).forEach(result ->
                    addScored(scored, "account", result.accountId(), result.score()));
        }
        if (includeContents) {
            esService.searchByKeywordWithHighlight(keyword, null, null, topK).forEach(result ->
                    addScored(scored, "content", result.getContentId(), result.getScore()));
        }
        return hydrateEntityCandidates(scored, topK);
    }

    private List<EntityCandidateSearchResponse.Candidate> searchEntityCandidatesFromMilvus(
            String keyword, List<String> requestedTypes, int topK) {
        if (!hasText(keyword) || topK <= 0) {
            return List.of();
        }
        float[] embedding = textEmbedding(keyword);
        if (embedding == null) {
            return List.of();
        }

        boolean allDomains = requestedTypes == null || requestedTypes.isEmpty();
        List<String> standardTypes = standardEntityTypes(requestedTypes);
        boolean includeEntities = allDomains || !standardTypes.isEmpty();
        boolean includeAccounts = allDomains || containsType(requestedTypes, "SocialAccount");
        boolean includeContents = allDomains || containsType(requestedTypes, "MediaContent");

        List<ScoredScopedId> scored = new ArrayList<>();
        if (includeEntities) {
            List<String> filters = allDomains ? java.util.Collections.singletonList(null) : standardTypes;
            for (String type : filters) {
                milvusService.searchEntityEmbeddings(embedding, topK, type).forEach(result ->
                        addScored(scored, "entity", result.entityId(), result.score()));
            }
        }
        if (includeAccounts) {
            milvusService.searchAccountEmbeddings(embedding, topK).forEach(result ->
                    addScored(scored, "account", result.entityId(), result.score()));
        }
        if (includeContents) {
            milvusService.searchTextEmbeddingsWithScore(embedding, topK, null, null).forEach(result ->
                    addScored(scored, "content", result.entityId(), result.score()));
            searchImageSemanticContentIds(embedding, topK).forEach(result ->
                    addScored(scored, "content", result.entityId(), result.score()));
        }
        return hydrateEntityCandidates(scored, topK);
    }

    private List<EntityCandidateSearchResponse.Candidate> hydrateEntityCandidates(
            List<ScoredScopedId> scored, int topK) {
        if (scored == null || scored.isEmpty()) {
            return List.of();
        }
        List<ScoredScopedId> ranked = dedupeAndRank(scored, topK);

        List<String> entityIds = ranked.stream()
                .map(ScoredScopedId::scopedId)
                .filter(id -> id.startsWith("entity:"))
                .map(id -> id.substring("entity:".length()))
                .toList();
        List<UUID> accountIds = ranked.stream()
                .map(ScoredScopedId::scopedId)
                .filter(id -> id.startsWith("account:"))
                .map(id -> parseUuid(id.substring("account:".length())))
                .filter(Objects::nonNull)
                .toList();
        List<UUID> contentIds = ranked.stream()
                .map(ScoredScopedId::scopedId)
                .filter(id -> id.startsWith("content:"))
                .map(id -> parseUuid(id.substring("content:".length())))
                .filter(Objects::nonNull)
                .toList();

        Map<String, Map<String, Object>> entityDetails = entityEsService.getEntitiesByIds(entityIds);
        Map<String, SocialAccount> accountsById = accountIds.isEmpty()
                ? Map.of()
                : socialAccountMapper.selectBatchIds(accountIds).stream()
                .collect(Collectors.toMap(a -> a.getId().toString(), a -> a, (a, b) -> a));
        Map<String, MediaContent> contentsById = contentIds.isEmpty()
                ? Map.of()
                : mediaContentMapper.selectBatchIds(contentIds).stream()
                .collect(Collectors.toMap(c -> c.getId().toString(), c -> c, (a, b) -> a));

        List<EntityCandidateSearchResponse.Candidate> candidates = new ArrayList<>();
        for (ScoredScopedId scoredId : ranked) {
            String scopedId = scoredId.scopedId();
            String id = scopedId.substring(scopedId.indexOf(':') + 1);
            Map<String, Object> item = null;
            if (scopedId.startsWith("entity:")) {
                Map<String, Object> detail = entityDetails.get(id);
                if (detail != null) {
                    item = new LinkedHashMap<>(detail);
                    item.put("id", id);
                    item.put("entityType", detail.get("entity_type"));
                }
            } else if (scopedId.startsWith("account:")) {
                SocialAccount account = accountsById.get(id);
                if (account != null) {
                    item = socialAccountResult(account);
                }
            } else if (scopedId.startsWith("content:")) {
                MediaContent content = contentsById.get(id);
                if (content != null) {
                    item = mediaContentResult(content);
                }
            }
            EntityCandidateSearchResponse.Candidate candidate = toEntityCandidate(item, scoredId.score());
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private EntityCandidateSearchResponse.Candidate toEntityCandidate(Map<String, Object> item, double score) {
        if (item == null) {
            return null;
        }
        String nodeId = stringValue(item.get("id"));
        String entityType = stringValue(item.get("entityType"));
        String name = firstText(
                item.get("canonicalName"),
                item.get("canonical_name"),
                item.get("displayName"),
                item.get("handle"),
                item.get("title"),
                item.get("platformContentId"),
                item.get("platform_content_id"),
                item.get("name"),
                nodeId);
        if (!hasText(nodeId) || !hasText(entityType) || !hasText(name)) {
            return null;
        }
        return new EntityCandidateSearchResponse.Candidate(nodeId, name, entityType, score);
    }

    private List<ScoredScopedId> dedupeAndRank(List<ScoredScopedId> scored, int topK) {
        Map<String, Double> bestScores = new LinkedHashMap<>();
        for (ScoredScopedId item : scored) {
            if (item != null && hasText(item.scopedId())) {
                bestScores.merge(item.scopedId(), item.score(), Math::max);
            }
        }
        return bestScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(topK)
                .map(entry -> new ScoredScopedId(entry.getKey(), entry.getValue()))
                .toList();
    }

    private void addScored(List<ScoredScopedId> scored, String domain, String id, Object score) {
        String scopedId = scopedId(domain, id);
        if (hasText(scopedId)) {
            scored.add(new ScoredScopedId(scopedId, scoreValue(score)));
        }
    }

    private double scoreValue(Object score) {
        return score instanceof Number number ? number.doubleValue() : 0D;
    }

    private boolean containsType(List<String> types, String type) {
        return types != null && types.stream().anyMatch(type::equalsIgnoreCase);
    }

    private List<String> standardEntityTypes(List<String> requestedTypes) {
        if (requestedTypes == null || requestedTypes.isEmpty()) {
            return List.of();
        }
        return requestedTypes.stream()
                .map(this::canonicalStandardEntityType)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private record ScoredScopedId(String scopedId, double score) {
    }

    private List<MilvusVectorService.ScoredEntityId> searchSemanticIds(
            String queryText, String platform, String language, int topK, Double semanticMinScore) {
        if (!hasText(queryText) || topK <= 0) {
            return List.of();
        }

        float[] embedding = embeddingService.generateTextEmbedding(queryText);
        if (embedding == null) {
            return List.of();
        }
        float minScore = normalizeSemanticMinScore(semanticMinScore);
        return milvusService.searchTextEmbeddingsWithScore(embedding, topK, platform, language).stream()
                .filter(result -> result.score() >= minScore)
                .toList();
    }

    private List<String> searchAccountSemanticIds(String queryText, int topK) {
        if (!hasText(queryText)) {
            return List.of();
        }
        float[] embedding = embeddingService.generateTextEmbedding(queryText);
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
            embedding = embeddingService.generateImageEmbedding(imageUrl);
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

    private String normalizeTargetModality(String targetModalities) {
        if (!hasText(targetModalities)) {
            return "all";
        }
        String value = targetModalities.trim().toLowerCase();
        return switch (value) {
            case "text", "image" -> value;
            default -> "all";
        };
    }

    private float normalizeSemanticMinScore(Double semanticMinScore) {
        if (semanticMinScore == null || !Double.isFinite(semanticMinScore)) {
            return DEFAULT_CONTENT_SEMANTIC_MIN_SCORE;
        }
        return (float) Math.max(0D, Math.min(1D, semanticMinScore));
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

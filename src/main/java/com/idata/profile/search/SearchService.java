package com.idata.profile.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.infra.elasticsearch.EntityEsService;
import com.idata.profile.infra.elasticsearch.MediaAssetEsService;
import com.idata.profile.infra.elasticsearch.MediaContentEsService;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final int DEFAULT_TOP_K = 20;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int DEFAULT_RECALL_SIZE = 50;
    private static final int MAX_RECALL_SIZE = 500;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int RRF_K = 60;
    private static final long HYBRID_ROUTE_TIMEOUT_SECONDS = 8L;
    private static final float DEFAULT_CONTENT_SEMANTIC_MIN_SCORE = 0.45F;
    private static final float DEFAULT_MEDIA_TEXT_SEMANTIC_MIN_SCORE = 0.58F;
    private static final float DEFAULT_MEDIA_VISUAL_MIN_SCORE = 0.65F;
    private static final float IMAGE_QUERY_VISUAL_MIN_SCORE = 0.90F;
    private static final float IDENTIFIER_CONTENT_SEMANTIC_MIN_SCORE = 0.72F;
    private static final float IDENTIFIER_MEDIA_TEXT_SEMANTIC_MIN_SCORE = 0.72F;
    private static final float MEDIA_ONLY_TEXT_SEMANTIC_MIN_SCORE = 0.72F;
    private static final float MEDIA_TARGET_TEXT_SEMANTIC_MIN_SCORE = 0.80F;
    private static final float MEDIA_TEXT_SEMANTIC_FALLBACK_MIN_SCORE = 0.86F;
    private static final double MEDIA_FIRST_RATIO = 0.60D;
    private static final double TEXT_FIRST_RATIO = 0.60D;
    private static final double STRONG_MEDIA_KEYWORD_SCORE = 8D;
    private static final int CONTENT_RESULT_NAME_MAX_LENGTH = 160;
    private static final Set<String> ENTITY_QUERY_STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in",
            "is", "of", "on", "or", "that", "the", "this", "to", "with");
    private static final Set<String> CONTENT_QUERY_STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in",
            "is", "it", "its", "of", "on", "or", "that", "the", "than", "this", "to",
            "was", "were", "with", "another", "somehow");
    private static final List<String> STANDARD_ENTITY_TYPES = List.of("Person", "Organization", "Event", "Location");

    private final EmbeddingService embeddingService;
    private final MilvusVectorService milvusService;
    private final MediaContentEsService esService;
    private final MediaAssetEsService mediaAssetEsService;
    private final EntityEsService entityEsService;
    private final Neo4jGraphService neo4jGraphService;
    private final MediaContentMapper mediaContentMapper;
    private final MediaAssetMapper mediaAssetMapper;
    private final SocialAccountMapper socialAccountMapper;

    public SearchResult searchByText(String keyword, String platform,
                                     String language, int page, int size) {
        long startedAt = System.currentTimeMillis();
        int safePage = normalizePage(page);
        int safeSize = normalizePageSize(size);
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
        List<MediaContent> items = fetchContentsInOrder(pageSlice(rankedIds, safePage, safeSize));

        SearchResult result = buildResult(items, rankedIds.size(), "text", startedAt);
        result.setHighlights(highlights);
        result.setScores(scores);
        return result;
    }

    public SearchResult searchBySemantic(String queryText, String platform,
                                         String language, Integer topK, int page, int size, Double semanticMinScore) {
        long startedAt = System.currentTimeMillis();
        int safePage = normalizePage(page);
        int safeSize = normalizePageSize(size);
        int safeTopK = normalizeRecallSize(topK, safePage, safeSize);
        List<MilvusVectorService.ScoredEntityId> scored =
                searchSemanticIds(queryText, platform, language, safeTopK, semanticMinScore);
        List<String> ids = scored.stream()
                .map(MilvusVectorService.ScoredEntityId::entityId)
                .toList();
        List<String> pageIds = pageSlice(ids, safePage, safeSize);
        List<MediaContent> items = fetchContentsInOrder(pageIds);
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
        int safePage = normalizePage(request == null ? 0 : request.getPage());
        int safeSize = normalizePageSize(request == null ? DEFAULT_PAGE_SIZE : request.getSize());
        int topK = normalizeRecallSize(request == null ? null : request.getTopK(), safePage, safeSize);
        String queryText = request == null ? null : request.getQueryText();
        String platform = request == null ? null : request.getPlatform();
        String language = request == null ? null : request.getLanguage();
        boolean hasImage = request != null && hasText(request.getImageUrl()) && request.isEnableMilvus();
        String targetModality = normalizeTargetModality(request != null ? request.getTargetModalities() : null);
        int mediaTopK = mediaRecallSize(topK, targetModality);
        Double semanticMinScore = request == null ? null : request.getSemanticMinScore();
        Double visualMinScore = request == null ? null : request.getVisualMinScore();

        return searchHybridContents(request, queryText, platform, language, targetModality,
                hasImage, topK, mediaTopK, safePage, safeSize, semanticMinScore, visualMinScore, startedAt);
    }

    private SearchResult searchHybridContents(HybridSearchRequest request, String queryText,
                                              String platform, String language, String targetModality,
                                              boolean hasImage, int topK, int mediaTopK, int page, int size,
                                              Double semanticMinScore, Double visualMinScore, long startedAt) {
        boolean includeText = !isMediaOnlyTargetModality(targetModality);
        boolean includeMedia = !"text".equals(targetModality);
        CompletableFuture<List<MediaContentEsService.EsSearchResult>> esFuture = includeText && isEnabled(request, "es")
                ? safeRoute("es", () -> esService.searchByKeywordWithHighlight(queryText, platform, language, topK))
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<MilvusVectorService.ScoredEntityId>> milvusFuture = includeText && isEnabled(request, "milvus")
                ? safeRoute("milvus", () -> searchSemanticIds(queryText, platform, language, topK, semanticMinScore))
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<String>> neo4jFuture = includeText && request != null && request.isEnableNeo4j()
                ? safeRoute("neo4j", () -> searchNeo4jContentIds(queryText, platform, language, topK))
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<MediaAssetEsService.EsImageAssetSearchResult>> mediaKeywordFuture =
                includeMedia && isEnabled(request, "es") && hasText(queryText)
                        ? safeRoute("media-es", () -> mediaAssetEsService.searchMediaByKeyword(
                                queryText.trim(), mediaTopK, targetMediaType(targetModality)))
                        : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<MilvusVectorService.MediaAssetVectorSearchResult>> mediaSemanticFuture =
                includeMedia && isEnabled(request, "milvus") && hasText(queryText)
                        ? safeRoute("media-milvus", () -> searchMediaAssetTextVectors(
                                queryText, mediaTopK, semanticMinScore, targetModality))
                        : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<MilvusVectorService.MediaAssetVectorSearchResult>> imageFuture = includeMedia && hasImage
                ? safeRoute("image", () -> searchMediaAssetVisualVectors(
                        request.getImageUrl(), mediaTopK, targetModality, visualMinScore))
                : CompletableFuture.completedFuture(List.of());

        CompletableFuture.allOf(esFuture, milvusFuture, neo4jFuture,
                mediaKeywordFuture, mediaSemanticFuture, imageFuture).join();

        List<MediaContentEsService.EsSearchResult> esResults = esFuture.join();
        List<String> esIds = esResults.stream()
                .map(MediaContentEsService.EsSearchResult::getContentId)
                .toList();
        List<MilvusVectorService.ScoredEntityId> milvusResults = milvusFuture.join();
        List<String> milvusIds = milvusResults.stream()
                .map(MilvusVectorService.ScoredEntityId::entityId)
                .toList();
        List<MediaAssetEsService.EsImageAssetSearchResult> mediaKeywordResults = mediaKeywordFuture.join();
        List<MilvusVectorService.MediaAssetVectorSearchResult> mediaSemanticResults =
                filterMediaSemanticResultsForTarget(
                        filterMediaTextSemanticBySourceText(mediaSemanticFuture.join(), queryText),
                        targetModality,
                        !mediaKeywordResults.isEmpty());

        List<SearchResult.ContentHit> contentHits = buildContentHits(
                esResults,
                milvusResults,
                neo4jFuture.join(),
                mediaKeywordResults,
                mergeMediaVectorResults(mediaSemanticResults, imageFuture.join(), topK),
                queryText,
                includeText,
                topK);
        List<String> fusedIds = contentHits.stream()
                .map(SearchResult.ContentHit::getContentId)
                .toList();
        List<String> pageIds = pageSlice(fusedIds, page, size);
        List<MediaContent> items = fetchContentsInOrder(pageIds);
        Map<String, MediaContent> contentsById = items.stream()
                .collect(Collectors.toMap(c -> c.getId().toString(), c -> c, (left, right) -> left));
        List<SearchResult.ContentHit> pageHits = pageSlice(contentHits, page, size);
        pageHits.forEach(hit -> hit.setPost(contentsById.get(hit.getContentId())));

        Map<String, Map<String, List<String>>> highlights = esResults.stream()
                .collect(Collectors.toMap(
                        MediaContentEsService.EsSearchResult::getContentId,
                        MediaContentEsService.EsSearchResult::getHighlights,
                        (left, right) -> left,
                        LinkedHashMap::new));

        SearchResult result = buildResult(null, fusedIds.size(), "hybrid", startedAt);
        result.setHighlights(highlights);
        result.setContentHits(pageHits);
        return result;
    }

    private List<SearchResult.ContentHit> buildContentHits(
            List<MediaContentEsService.EsSearchResult> textKeywordResults,
            List<MilvusVectorService.ScoredEntityId> textSemanticResults,
            List<String> graphContentIds,
            List<MediaAssetEsService.EsImageAssetSearchResult> mediaKeywordResults,
            List<MilvusVectorService.MediaAssetVectorSearchResult> mediaSemanticResults,
            String queryText,
            boolean useTextMatchLevel,
            int topK) {
        Map<String, ContentAccumulator> accumulators = new LinkedHashMap<>();

        addTextChannel(accumulators, "ES_POST_KEYWORD", textKeywordResults.stream()
                .map(result -> new TextHitSeed(result.getContentId(), result.getScore(), null))
                .toList());
        addTextChannel(accumulators, "MILVUS_POST_SEMANTIC", textSemanticResults.stream()
                .map(result -> new TextHitSeed(result.entityId(), (double) result.score(), null))
                .toList());
        addTextChannel(accumulators, "NEO4J_GRAPH", graphContentIds.stream()
                .map(id -> new TextHitSeed(id, null, null))
                .toList());

        List<MediaHitSeed> mediaKeywordSeeds = mediaKeywordResults.stream()
                .map(result -> new MediaHitSeed(
                        result.assetId(),
                        result.segmentId(),
                        result.contentId(),
                        result.mediaType(),
                        null,
                        result.segmentStart(),
                        result.segmentEnd(),
                        result.score(),
                        result.hitField()))
                .toList();
        List<MediaHitSeed> mediaSemanticSeeds = mediaSemanticResults.stream()
                .map(result -> new MediaHitSeed(
                        result.assetId(),
                        result.segmentId(),
                        result.contentId(),
                        result.mediaType(),
                        result.mimeType(),
                        result.segmentStart(),
                        result.segmentEnd(),
                        (double) result.score(),
                        result.vectorField()))
                .toList();

        Map<UUID, MediaAsset> assetsById = fetchAssetsById(List.of(mediaKeywordSeeds, mediaSemanticSeeds));
        addMediaChannel(accumulators, "ES_MEDIA_KEYWORD", mediaKeywordSeeds, assetsById);
        addMediaChannel(accumulators, "MILVUS_MEDIA_SEMANTIC", mediaSemanticSeeds, assetsById);
        applyTextMatchLevels(accumulators, queryText, useTextMatchLevel);

        return accumulators.values().stream()
                .filter(ContentAccumulator::isQualified)
                .map(ContentAccumulator::toContentHit)
                .sorted(Comparator
                        .comparingInt(SearchResult.ContentHit::getMatchLevelRank).reversed()
                        .thenComparing(SearchResult.ContentHit::getRankScore, Comparator.reverseOrder()))
                .limit(topK)
                .toList();
    }

    private void addTextChannel(Map<String, ContentAccumulator> accumulators, String channel,
                                List<TextHitSeed> hits) {
        LinkedHashSet<String> seenContentIds = new LinkedHashSet<>();
        int uniqueRank = 0;
        int rawRank = 0;
        for (TextHitSeed hit : hits) {
            rawRank++;
            if (hit == null || !hasText(hit.contentId())) {
                continue;
            }
            boolean contributes = seenContentIds.add(hit.contentId());
            double rrfContribution = 0D;
            if (contributes) {
                uniqueRank++;
                rrfContribution = rrfContribution(uniqueRank);
                accumulator(accumulators, hit.contentId()).textContribution += rrfContribution;
            }
            SearchResult.Evidence evidence = evidence(channel, "TEXT", rawRank,
                    rrfContribution, hit.rawScore(), hit.hitField(), hit.contentId(), null, hit.contentId());
            ContentAccumulator accumulator = accumulator(accumulators, hit.contentId());
            accumulator.textKeywordHit = accumulator.textKeywordHit || "ES_POST_KEYWORD".equals(channel);
            accumulator.textSemanticHit = accumulator.textSemanticHit || "MILVUS_POST_SEMANTIC".equals(channel);
            accumulator.evidences.add(evidence);
        }
    }

    private void addMediaChannel(Map<String, ContentAccumulator> accumulators, String channel,
                                 List<MediaHitSeed> hits, Map<UUID, MediaAsset> assetsById) {
        LinkedHashSet<String> seenContentIds = new LinkedHashSet<>();
        int uniqueRank = 0;
        int rawRank = 0;
        for (MediaHitSeed hit : hits) {
            rawRank++;
            if (hit == null || !hasText(hit.assetId())) {
                continue;
            }
            UUID assetUuid = parseUuid(normalizeImageAssetId(hit.assetId()));
            MediaAsset asset = assetUuid == null ? null : assetsById.get(assetUuid);
            String contentId = firstText(hit.contentId(),
                    asset != null && asset.getContentId() != null ? asset.getContentId().toString() : null);
            if (!hasText(contentId)) {
                continue;
            }

            boolean contributes = seenContentIds.add(contentId);
            double rrfContribution = 0D;
            if (contributes) {
                uniqueRank++;
                rrfContribution = rrfContribution(uniqueRank);
                accumulator(accumulators, contentId).mediaContribution += rrfContribution;
            }
            String entityId = mediaEntityId(hit, asset);
            SearchResult.Evidence evidence = evidence(channel, "MEDIA", rawRank,
                    rrfContribution, hit.rawScore(), hit.hitField(), contentId, hit.assetId(), entityId);
            ContentAccumulator accumulator = accumulator(accumulators, contentId);
            accumulator.evidences.add(evidence);
            SearchResult.AssetHit assetHit = toAssetHit(hit, asset, contentId, entityId, rrfContribution);
            if (assetHit != null) {
                accumulator.hasStrongMediaMatch = accumulator.hasStrongMediaMatch || isStrongMediaHit(channel, hit);
                accumulator.assets.merge(assetHit.getEntityId(), assetHit, (left, right) -> {
                    double leftScore = scoreValue(left.getRrfContribution());
                    double rightScore = scoreValue(right.getRrfContribution());
                    left.setRrfContribution(leftScore + rightScore);
                    return left;
                });
            }
        }
    }

    private void applyTextMatchLevels(Map<String, ContentAccumulator> accumulators, String queryText,
                                      boolean useTextMatchLevel) {
        if (accumulators.isEmpty() || !hasText(queryText)) {
            return;
        }
        List<UUID> contentIds = accumulators.keySet().stream()
                .map(this::parseUuid)
                .filter(Objects::nonNull)
                .toList();
        if (contentIds.isEmpty()) {
            return;
        }
        Map<String, MediaContent> contentsById = mediaContentMapper.selectBatchIds(contentIds).stream()
                .collect(Collectors.toMap(content -> content.getId().toString(), content -> content,
                        (left, right) -> left));
        for (ContentAccumulator accumulator : accumulators.values()) {
            MediaContent content = contentsById.get(accumulator.contentId);
            accumulator.hasSearchableText = hasText(contentText(content));
            if (useTextMatchLevel && accumulator.textContribution > 0D) {
                accumulator.matchLevel = classifyTextMatch(queryText, content);
            }
        }
    }

    private MatchLevel classifyTextMatch(String queryText, MediaContent content) {
        if (content == null || !hasText(queryText)) {
            return MatchLevel.HYBRID_RELEVANT;
        }
        String query = normalizeComparableText(queryText);
        String title = normalizeComparableText(content.getTitle());
        String body = normalizeComparableText(content.getBodyText());
        String haystack = normalizeComparableText(firstText(content.getTitle(), "") + " " + firstText(content.getBodyText(), ""));
        if (hasText(query) && (title.contains(query) || body.contains(query) || haystack.contains(query))) {
            return MatchLevel.EXACT_PHRASE;
        }

        List<String> tokens = significantContentTokens(queryText);
        if (tokens.size() >= 2 && hasText(haystack)) {
            long matched = tokens.stream().filter(haystack::contains).count();
            double coverage = matched / (double) tokens.size();
            if (coverage >= 0.70D) {
                return MatchLevel.HIGH_TEXT_MATCH;
            }
            if (coverage >= 0.40D) {
                return MatchLevel.TEXT_MATCH;
            }
        }
        return MatchLevel.HYBRID_RELEVANT;
    }

    private String contentText(MediaContent content) {
        if (content == null) {
            return null;
        }
        return firstText(content.getTitle(), content.getBodyText());
    }

    private List<String> significantContentTokens(String text) {
        if (!hasText(text)) {
            return List.of();
        }
        String normalized = normalizeAsciiText(text);
        if (!hasText(normalized)) {
            return List.of();
        }
        return java.util.Arrays.stream(normalized.split(" "))
                .filter(this::hasText)
                .filter(token -> token.length() > 1)
                .filter(token -> !CONTENT_QUERY_STOP_WORDS.contains(token))
                .distinct()
                .toList();
    }

    private String normalizeComparableText(String text) {
        if (!hasText(text)) {
            return "";
        }
        return text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isStrongMediaHit(String channel, MediaHitSeed hit) {
        if ("ES_MEDIA_KEYWORD".equals(channel)) {
            return scoreValue(hit.rawScore()) >= STRONG_MEDIA_KEYWORD_SCORE;
        }
        if ("MILVUS_MEDIA_SEMANTIC".equals(channel)) {
            double minScore = "visual_embedding".equals(hit.hitField())
                    ? DEFAULT_MEDIA_VISUAL_MIN_SCORE
                    : DEFAULT_MEDIA_TEXT_SEMANTIC_MIN_SCORE;
            return scoreValue(hit.rawScore()) >= minScore;
        }
        return false;
    }

    private List<MilvusVectorService.MediaAssetVectorSearchResult> searchMediaAssetTextVectors(
            String queryText, int topK, Double semanticMinScore, String targetModality) {
        Map<String, MilvusVectorService.MediaAssetVectorSearchResult> best = new LinkedHashMap<>();
        for (String variant : semanticQueryVariants(queryText)) {
            float[] embedding = textEmbedding(variant);
            if (embedding == null) {
                continue;
            }
            milvusService.searchMediaAssetTextEmbeddingsDetailed(embedding, topK).stream()
                    .filter(result -> matchesTargetMediaType(result.mediaType(), targetModality))
                    .filter(result -> result.score() >= mediaSemanticMinScore(
                            queryText, semanticMinScore, targetModality, result.vectorField()))
                    .forEach(result -> mergeMediaVectorResult(best, result));
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(MilvusVectorService.MediaAssetVectorSearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    private List<MilvusVectorService.MediaAssetVectorSearchResult> filterMediaSemanticResultsForTarget(
            List<MilvusVectorService.MediaAssetVectorSearchResult> results,
            String targetModality,
            boolean hasMediaKeywordHits) {
        if (!isMediaOnlyTargetModality(targetModality) || !hasMediaKeywordHits || results == null || results.isEmpty()) {
            return results == null ? List.of() : results;
        }
        return results.stream()
                .filter(result -> result != null
                        && (isVisualVectorField(result.vectorField())
                        || result.score() >= MEDIA_TARGET_TEXT_SEMANTIC_MIN_SCORE))
                .toList();
    }

    private List<MilvusVectorService.MediaAssetVectorSearchResult> filterMediaTextSemanticBySourceText(
            List<MilvusVectorService.MediaAssetVectorSearchResult> results,
            String queryText) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<UUID> assetIds = results.stream()
                .filter(result -> result != null && isMediaTextVectorField(result.vectorField()))
                .map(MilvusVectorService.MediaAssetVectorSearchResult::assetId)
                .map(this::normalizeImageAssetId)
                .map(this::parseUuid)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, MediaAsset> assetsById = new LinkedHashMap<>();
        if (!assetIds.isEmpty()) {
            for (MediaAsset asset : mediaAssetMapper.selectByIds(assetIds)) {
                if (asset != null && asset.getId() != null) {
                    assetsById.put(asset.getId(), asset);
                }
            }
        }
        return results.stream()
                .filter(result -> isQualifiedMediaTextSemanticResult(result, queryText, assetsById))
                .toList();
    }

    private boolean isQualifiedMediaTextSemanticResult(
            MilvusVectorService.MediaAssetVectorSearchResult result,
            String queryText,
            Map<UUID, MediaAsset> assetsById) {
        if (result == null) {
            return false;
        }
        String vectorField = result.vectorField();
        if (!isMediaTextVectorField(vectorField)) {
            return true;
        }
        UUID assetId = parseUuid(normalizeImageAssetId(result.assetId()));
        MediaAsset asset = assetId == null ? null : assetsById.get(assetId);
        String sourceText = mediaSemanticSourceText(vectorField, asset);
        if (!hasText(sourceText)) {
            return result.score() >= MEDIA_TEXT_SEMANTIC_FALLBACK_MIN_SCORE;
        }
        if (!isUsefulSemanticSourceText(sourceText)) {
            return false;
        }
        if (isNaturalLanguageQuery(queryText) || isLikelyCrossLanguage(queryText, sourceText)) {
            return true;
        }
        return hasCompatibleSemanticToken(queryText, sourceText)
                || result.score() >= MEDIA_TEXT_SEMANTIC_FALLBACK_MIN_SCORE;
    }

    private String mediaSemanticSourceText(String vectorField, MediaAsset asset) {
        if (asset == null) {
            return null;
        }
        if ("ocr_embedding".equals(vectorField)) {
            return asset.getOcrText();
        }
        if ("asr_embedding".equals(vectorField)) {
            return asset.getAsrText();
        }
        if ("caption_embedding".equals(vectorField)) {
            return asset.getCaptionText();
        }
        return null;
    }

    private List<MilvusVectorService.MediaAssetVectorSearchResult> searchMediaAssetVisualVectors(
            String imageUrl, int topK, String targetModality, Double visualMinScore) {
        float[] embedding = imageEmbedding(imageUrl);
        if (embedding == null) {
            return List.of();
        }
        float minScore = imageQueryVisualMinScore(visualMinScore);
        return milvusService.searchMediaAssetVisualEmbeddingsDetailed(embedding, topK).stream()
                .filter(result -> matchesTargetMediaType(result.mediaType(), targetModality))
                .filter(result -> result.score() >= minScore)
                .toList();
    }

    private List<MilvusVectorService.MediaAssetVectorSearchResult> mergeMediaVectorResults(
            List<MilvusVectorService.MediaAssetVectorSearchResult> textResults,
            List<MilvusVectorService.MediaAssetVectorSearchResult> imageResults,
            int topK) {
        Map<String, MilvusVectorService.MediaAssetVectorSearchResult> best = new LinkedHashMap<>();
        for (MilvusVectorService.MediaAssetVectorSearchResult result : concat(textResults, imageResults)) {
            mergeMediaVectorResult(best, result);
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(MilvusVectorService.MediaAssetVectorSearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    private void mergeMediaVectorResult(Map<String, MilvusVectorService.MediaAssetVectorSearchResult> best,
                                        MilvusVectorService.MediaAssetVectorSearchResult result) {
        if (best == null || result == null || !hasText(result.assetId())) {
            return;
        }
        String key = result.assetId() + ":" + firstText(result.segmentId(), "asset");
        best.merge(key, result, (left, right) -> right.score() > left.score() ? right : left);
    }

    private Map<UUID, MediaAsset> fetchAssetsById(List<List<MediaHitSeed>> seedLists) {
        List<UUID> assetIds = seedLists.stream()
                .flatMap(Collection::stream)
                .map(MediaHitSeed::assetId)
                .map(this::normalizeImageAssetId)
                .map(this::parseUuid)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, MediaAsset> assetsById = new LinkedHashMap<>();
        for (MediaAsset asset : mediaAssetMapper.selectByIds(assetIds)) {
            if (asset != null && asset.getId() != null) {
                assetsById.put(asset.getId(), asset);
            }
        }
        return assetsById;
    }

    private <T> List<T> concat(List<T> left, List<T> right) {
        List<T> merged = new ArrayList<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return merged;
    }

    private ContentAccumulator accumulator(Map<String, ContentAccumulator> accumulators, String contentId) {
        return accumulators.computeIfAbsent(contentId, ContentAccumulator::new);
    }

    private double rrfContribution(int rank) {
        return 1D / (RRF_K + rank);
    }

    private SearchResult.Evidence evidence(String channel, String category, int rank,
                                           double rrfContribution, Double rawScore, String hitField,
                                           String contentId, String assetId, String entityId) {
        SearchResult.Evidence evidence = new SearchResult.Evidence();
        evidence.setChannel(channel);
        evidence.setCategory(category);
        evidence.setRank(rank);
        evidence.setRrfContribution(rrfContribution);
        evidence.setRawScore(rawScore);
        evidence.setHitField(hitField);
        evidence.setContentId(contentId);
        evidence.setAssetId(assetId);
        evidence.setEntityId(entityId);
        return evidence;
    }

    private SearchResult.AssetHit toAssetHit(MediaHitSeed hit, MediaAsset asset,
                                             String contentId, String entityId, double rrfContribution) {
        SearchResult.AssetHit item = new SearchResult.AssetHit();
        item.setEntityId(entityId);
        item.setAssetId(normalizeImageAssetId(hit.assetId()));
        item.setContentId(contentId);
        item.setMediaType(firstText(hit.mediaType(), asset != null ? asset.getAssetType() : null));
        item.setSourceUrl(asset != null ? asset.getSourceUrl() : null);
        item.setStorageUri(asset != null ? asset.getStorageUri() : null);
        item.setMinioBucket(asset != null ? asset.getMinioBucket() : null);
        item.setMinioKey(asset != null ? asset.getMinioKey() : null);
        item.setMimeType(firstText(hit.mimeType(), asset != null ? asset.getMimeType() : null));
        item.setWidth(asset != null ? asset.getWidth() : null);
        item.setHeight(asset != null ? asset.getHeight() : null);
        item.setSegmentStartMs(secondsToMillis(hit.segmentStart()));
        item.setSegmentEndMs(secondsToMillis(hit.segmentEnd()));
        item.setPreviewTimeMs(previewTimeMs(item.getSegmentStartMs(), item.getSegmentEndMs()));
        item.setPreviewUrl(firstText(asset != null ? asset.getThumbnailUri() : null,
                asset != null ? asset.getSourceUrl() : null,
                asset != null ? asset.getStorageUri() : null));
        item.setRrfContribution(rrfContribution);
        return item;
    }

    private String mediaEntityId(MediaHitSeed hit, MediaAsset asset) {
        String assetId = normalizeImageAssetId(hit.assetId());
        String mediaType = firstText(hit.mediaType(), asset != null ? asset.getAssetType() : null, "media");
        String segmentId = firstText(hit.segmentId(), "asset");
        return mediaType + ":" + assetId + ":" + segmentId;
    }

    private Long secondsToMillis(Float seconds) {
        return seconds == null ? null : Math.round(seconds * 1000D);
    }

    private Long previewTimeMs(Long startMs, Long endMs) {
        if (startMs == null && endMs == null) {
            return null;
        }
        if (startMs == null) {
            return endMs;
        }
        if (endMs == null || endMs <= startMs) {
            return startMs;
        }
        return startMs + ((endMs - startMs) / 2);
    }

    private record TextHitSeed(String contentId, Double rawScore, String hitField) {
    }

    private record MediaHitSeed(String assetId, String segmentId, String contentId,
                                String mediaType, String mimeType,
                                Float segmentStart, Float segmentEnd,
                                Double rawScore, String hitField) {
    }

    private enum MatchLevel {
        HYBRID_RELEVANT(0),
        TEXT_MATCH(1),
        HIGH_TEXT_MATCH(2),
        EXACT_PHRASE(3);

        private final int rank;

        MatchLevel(int rank) {
            this.rank = rank;
        }
    }

    private static class ContentAccumulator {
        private final String contentId;
        private double textContribution;
        private double mediaContribution;
        private boolean hasStrongMediaMatch;
        private boolean hasSearchableText;
        private boolean textKeywordHit;
        private boolean textSemanticHit;
        private MatchLevel matchLevel = MatchLevel.HYBRID_RELEVANT;
        private final List<SearchResult.Evidence> evidences = new ArrayList<>();
        private final Map<String, SearchResult.AssetHit> assets = new LinkedHashMap<>();

        private ContentAccumulator(String contentId) {
            this.contentId = contentId;
        }

        private SearchResult.ContentHit toContentHit() {
            double total = textContribution + mediaContribution;
            double textRatio = total <= 0D ? 0D : textContribution / total;
            double mediaRatio = total <= 0D ? 0D : mediaContribution / total;

            SearchResult.ContentHit hit = new SearchResult.ContentHit();
            hit.setContentId(contentId);
            hit.setRrfScore(total);
            hit.setRankScore(total);
            hit.setMatchLevel(matchLevel.name());
            hit.setMatchLevelRank(matchLevel.rank);
            SearchResult.Contribution contribution = new SearchResult.Contribution();
            contribution.setText(contributionSide(textContribution, textRatio));
            contribution.setMedia(contributionSide(mediaContribution, mediaRatio));
            hit.setContribution(contribution);
            hit.setMatchedAssets(assets.values().stream()
                    .sorted(Comparator.comparingDouble(
                            asset -> -scoreValue(asset.getRrfContribution())))
                    .toList());
            hit.setPrimaryAsset(hit.getMatchedAssets().isEmpty() ? null : hit.getMatchedAssets().getFirst());
            hit.setDominantHitType(dominantHitType(hit.getPrimaryAsset(), textRatio, mediaRatio, hasStrongMediaMatch));
            hit.setDisplaySuggestion(displaySuggestion(hit.getPrimaryAsset(), textRatio, mediaRatio, hasStrongMediaMatch));
            hit.setEvidences(evidences);
            return hit;
        }

        private static SearchResult.ContributionSide contributionSide(double score, double ratio) {
            SearchResult.ContributionSide side = new SearchResult.ContributionSide();
            side.setRrfScore(score);
            side.setRatio(ratio);
            return side;
        }

        private static String dominantHitType(SearchResult.AssetHit primaryAsset,
                                              double textRatio, double mediaRatio,
                                              boolean hasStrongMediaMatch) {
            if (primaryAsset == null) {
                return "TEXT";
            }
            if (mediaRatio >= MEDIA_FIRST_RATIO && hasStrongMediaMatch) {
                return "MEDIA_ASSET";
            }
            if (textRatio >= TEXT_FIRST_RATIO) {
                return "TEXT";
            }
            return "MIXED";
        }

        private static String displaySuggestion(SearchResult.AssetHit primaryAsset,
                                                double textRatio, double mediaRatio,
                                                boolean hasStrongMediaMatch) {
            if (primaryAsset == null) {
                return "TEXT_FIRST";
            }
            if (mediaRatio >= MEDIA_FIRST_RATIO && hasStrongMediaMatch) {
                return "MEDIA_FIRST";
            }
            if (textRatio >= TEXT_FIRST_RATIO) {
                return "TEXT_FIRST";
            }
            return "MIXED";
        }

        private static double scoreValue(Double value) {
            return value == null ? 0D : value;
        }

        private boolean isQualified() {
            return mediaContribution > 0D
                    || textKeywordHit
                    || !textSemanticHit
                    || hasSearchableText;
        }
    }

    public List<SocialAccount> searchAccounts(String queryText, String platform, String accountType, int topK) {
        int safeTopK = normalizeSize(topK);

        CompletableFuture<List<String>> esFuture = safeRoute("account-es",
                () -> searchAccountKeywordIds(queryText, platform, accountType, safeTopK));
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

    private List<String> searchAccountKeywordIds(String queryText, String platform, String accountType, int topK) {
        if (!hasText(queryText) || topK <= 0) {
            return List.of();
        }
        Map<String, String> filters = new LinkedHashMap<>();
        if (hasText(platform)) {
            filters.put("platform", platform);
        }
        if (hasText(accountType)) {
            filters.put("account_type", accountType);
        }
        return entityEsService.searchEntities(queryText, "SocialAccount", topK, filters).stream()
                .map(result -> stringValue(result.get("entityId")))
                .filter(this::hasText)
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
            return EntityCandidateSearchResponse.fail("400", "retrieval_mode只支持keyword、semantic、both、hybrid");
        }

        if ("hybrid".equals(retrievalMode)) {
            List<EntityCandidateSearchResponse.Candidate> hybridCandidates =
                    searchEntityCandidatesFromMilvusHybridPoc(
                            request.getQuery(), requestedTypes, safeTopK, request.getSemanticMinScore());
            return EntityCandidateSearchResponse.ok(request.getTraceId(), List.of(), List.of(), hybridCandidates);
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
        List<String> entityMilvusTypeFilters = new ArrayList<>();
        if (includeEntities) {
            if (allDomains) {
                entityMilvusTypeFilters.addAll(List.of("Person", "Organization", "Event", "Location"));
            } else {
                entityMilvusTypeFilters.addAll(standardEntityTypes);
            }
        }
        List<CompletableFuture<List<Map<String, Object>>>> entityEsFutures = entityTypeFilters.stream()
                .map(type -> safeRoute("entity-es", () -> entityEsService.searchEntities(keyword, type, safeTopK)))
                .toList();
        List<CompletableFuture<List<MilvusVectorService.ScoredEntityId>>> entityMilvusFutures =
                embedding == null ? List.of() : entityMilvusTypeFilters.stream()
                        .map(type -> safeRoute("entity-milvus",
                                () -> milvusService.searchEntityEmbeddings(embedding, safeTopK, type)))
                        .toList();
        CompletableFuture<List<Map<String, Object>>> accountEsFuture = includeAccounts
                ? safeRoute("account-es", () -> entityEsService.searchEntities(
                keyword, "SocialAccount", safeTopK))
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<MilvusVectorService.ScoredEntityId>> accountMilvusFuture =
                includeAccounts && embedding != null
                        ? safeRoute("account-milvus",
                        () -> milvusService.searchEntityEmbeddings(embedding, safeTopK, "SocialAccount"))
                        : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<Map<String, Object>>> contentEsFuture = includeContents
                ? safeRoute("content-es", () -> entityEsService.searchEntities(
                keyword, "MediaContent", safeTopK))
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<MilvusVectorService.ScoredEntityId>> contentMilvusFuture =
                includeContents && embedding != null
                        ? safeRoute("content-entity-milvus",
                        () -> milvusService.searchEntityEmbeddings(embedding, safeTopK, "MediaContent"))
                        : CompletableFuture.completedFuture(List.of());

        List<CompletableFuture<?>> futures = new ArrayList<>();
        futures.addAll(entityEsFutures);
        futures.addAll(entityMilvusFutures);
        futures.add(accountEsFuture);
        futures.add(accountMilvusFuture);
        futures.add(contentEsFuture);
        futures.add(contentMilvusFuture);
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        List<Map<String, Object>> entityEsResults = entityEsFutures.stream()
                .flatMap(future -> future.join().stream())
                .toList();
        List<MilvusVectorService.ScoredEntityId> entityMilvusResults = entityMilvusFutures.stream()
                .flatMap(future -> future.join().stream())
                .toList();
        List<Map<String, Object>> accountEsResults = accountEsFuture.join();
        List<MilvusVectorService.ScoredEntityId> accountMilvusResults = accountMilvusFuture.join();
        List<Map<String, Object>> contentEsResults = contentEsFuture.join();
        List<MilvusVectorService.ScoredEntityId> contentMilvusResults = contentMilvusFuture.join();

        List<String> entityEsIds = entityEsResults.stream()
                .map(r -> scopedId("entity", (String) r.get("entityId")))
                .toList();
        List<String> entityMilvusIds = entityMilvusResults.stream()
                .map(r -> scopedId("entity", r.entityId()))
                .toList();
        List<String> accountEsIds = accountEsResults.stream()
                .map(r -> scopedId("account", stringValue(r.get("entityId"))))
                .toList();
        List<String> accountMilvusIds = accountMilvusResults.stream()
                .map(r -> scopedId("account", r.entityId()))
                .toList();
        List<String> contentEsIds = contentEsResults.stream()
                .map(r -> scopedId("content", stringValue(r.get("entityId"))))
                .toList();
        List<String> contentMilvusIds = contentMilvusResults.stream()
                .map(r -> scopedId("content", r.entityId()))
                .toList();

        Map<String, Double> esScores = new LinkedHashMap<>();
        entityEsResults.forEach(r -> putScore(esScores, scopedId("entity", (String) r.get("entityId")),
                r.get("score")));
        accountEsResults.forEach(r -> putScore(esScores, scopedId("account", stringValue(r.get("entityId"))),
                r.get("score")));
        contentEsResults.forEach(r -> putScore(esScores, scopedId("content", stringValue(r.get("entityId"))),
                r.get("score")));

        Map<String, Double> similarityScores = new LinkedHashMap<>();
        entityMilvusResults.forEach(r -> putScore(similarityScores, scopedId("entity", r.entityId()), r.score()));
        accountMilvusResults.forEach(r -> putScore(similarityScores, scopedId("account", r.entityId()), r.score()));
        contentMilvusResults.forEach(r -> putScore(similarityScores, scopedId("content", r.entityId()), r.score()));

        Map<String, Double> fusedScores = fuseByRrf(List.of(
                entityEsIds, entityMilvusIds,
                accountEsIds, accountMilvusIds,
                contentEsIds, contentMilvusIds));
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

    public SearchResult searchByImage(String imageUrl, String targetModalities, Integer topK,
                                      int page, int size, Double visualMinScore) {
        long startedAt = System.currentTimeMillis();
        int safePage = normalizePage(page);
        int safeSize = normalizePageSize(size);
        int safeTopK = normalizeRecallSize(topK, safePage, safeSize);
        String targetModality = normalizeTargetModality(targetModalities);
        int mediaTopK = mediaRecallSize(safeTopK, targetModality);
        HybridSearchRequest request = new HybridSearchRequest();
        request.setImageUrl(imageUrl);
        request.setTargetModalities(targetModality);
        request.setVisualMinScore(visualMinScore);
        request.setEnableEs(false);
        request.setEnableMilvus(true);
        request.setEnableNeo4j(false);
        SearchResult result = searchHybridContents(request, null, null, null, targetModality,
                hasText(imageUrl), safeTopK, mediaTopK, safePage, safeSize, null, visualMinScore, startedAt);
        result.setSearchType("image");
        return result;
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

    private List<String> semanticQueryVariants(String queryText) {
        if (!hasText(queryText)) {
            return List.of();
        }
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        String normalized = normalizeWhitespace(queryText);
        if (hasText(normalized)) {
            variants.add(normalized);
        }
        Arrays.stream(queryText.split("[\\r\\n]+"))
                .map(this::normalizeWhitespace)
                .filter(this::hasText)
                .filter(value -> value.length() >= 8)
                .forEach(variants::add);
        Arrays.stream(queryText.split("[。！？!?；;。،؛؟।]+"))
                .map(this::normalizeWhitespace)
                .filter(this::hasText)
                .filter(value -> value.length() >= 8)
                .forEach(variants::add);
        return variants.stream().limit(4).toList();
    }

    private String normalizeWhitespace(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("\\s+", " ").trim();
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
            case "keyword", "semantic", "both", "hybrid" -> mode;
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
                milvusService.searchMediaAssetTextEmbeddingsWithScore(embedding, topK);
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
        item.put("canonicalName", contentDisplayName(content));
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
            entityEsService.searchEntities(keyword, "SocialAccount", topK).forEach(result ->
                    addScored(scored, "account", stringValue(result.get("entityId")), result.get("score")));
        }
        if (includeContents) {
            entityEsService.searchEntities(keyword, "MediaContent", topK).forEach(result ->
                    addScored(scored, "content", stringValue(result.get("entityId")), result.get("score")));
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
            List<String> filters = allDomains ? List.of("Person", "Organization", "Event", "Location") : standardTypes;
            for (String type : filters) {
                milvusService.searchEntityEmbeddings(embedding, topK, type).forEach(result ->
                        addScored(scored, "entity", result.entityId(), result.score()));
            }
        }
        if (includeAccounts) {
            milvusService.searchEntityEmbeddings(embedding, topK, "SocialAccount").forEach(result ->
                    addScored(scored, "account", result.entityId(), result.score()));
        }
        if (includeContents) {
            milvusService.searchEntityEmbeddings(embedding, topK, "MediaContent").forEach(result ->
                    addScored(scored, "content", result.entityId(), result.score()));
        }
        return hydrateEntityCandidates(scored, topK);
    }

    private List<EntityCandidateSearchResponse.Candidate> searchEntityCandidatesFromMilvusHybridPoc(
            String keyword, List<String> requestedTypes, int topK, Double semanticMinScore) {
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

        List<String> typeFilters = new ArrayList<>();
        if (includeEntities) {
            typeFilters.addAll(allDomains ? STANDARD_ENTITY_TYPES : standardTypes);
        }
        if (includeAccounts) {
            typeFilters.add("SocialAccount");
        }
        if (includeContents) {
            typeFilters.add("MediaContent");
        }
        if (typeFilters.isEmpty()) {
            return List.of();
        }

        List<ScoredScopedId> scored = new ArrayList<>();
        int recallK = Math.max(topK * 5, 50);
        milvusService.searchEntityHybridPocDetailed(keyword, embedding, recallK, typeFilters).forEach(result -> {
            String domain = domainForEntityType(result.entityType());
            if (domain != null) {
                addHybridScored(scored, domain, result, keyword);
            }
        });
        return hydrateEntityCandidates(scored, topK, normalizeEntityHybridSemanticMinScore(semanticMinScore), keyword);
    }

    private List<EntityCandidateSearchResponse.Candidate> hydrateEntityCandidates(
            List<ScoredScopedId> scored, int topK) {
        return hydrateEntityCandidates(scored, topK, null);
    }

    private List<EntityCandidateSearchResponse.Candidate> hydrateEntityCandidates(
            List<ScoredScopedId> scored, int topK, Double hybridSemanticMinScore) {
        return hydrateEntityCandidates(scored, topK, hybridSemanticMinScore, null);
    }

    private List<EntityCandidateSearchResponse.Candidate> hydrateEntityCandidates(
            List<ScoredScopedId> scored, int topK, Double hybridSemanticMinScore, String queryText) {
        if (scored == null || scored.isEmpty()) {
            return List.of();
        }
        int hydrateLimit = hybridSemanticMinScore != null || hasText(queryText)
                ? Math.max(topK * 5, topK)
                : topK;
        List<ScoredScopedId> ranked = dedupeAndRank(scored, hydrateLimit);

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
            EntityCandidateSearchResponse.Candidate candidate =
                    toEntityCandidate(item, scoredId, hybridSemanticMinScore, queryText);
            if (candidate != null) {
                candidates.add(candidate);
                if (candidates.size() >= topK) {
                    break;
                }
            }
        }
        return candidates;
    }

    private EntityCandidateSearchResponse.Candidate toEntityCandidate(Map<String, Object> item, ScoredScopedId scored) {
        return toEntityCandidate(item, scored, null);
    }

    private EntityCandidateSearchResponse.Candidate toEntityCandidate(
            Map<String, Object> item, ScoredScopedId scored, Double hybridSemanticMinScore) {
        return toEntityCandidate(item, scored, hybridSemanticMinScore, null);
    }

    private EntityCandidateSearchResponse.Candidate toEntityCandidate(
            Map<String, Object> item, ScoredScopedId scored, Double hybridSemanticMinScore, String queryText) {
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
                item.get("name"),
                nodeId);
        if (!hasText(nodeId) || !hasText(entityType) || !hasText(name)) {
            return null;
        }
        EntityCandidateSearchResponse.Candidate candidate =
                new EntityCandidateSearchResponse.Candidate(nodeId, name, entityType, scored.score());
        candidate.setKeywordScore(scored.keywordScore());
        candidate.setKeywordField(scored.keywordField());
        candidate.setSemanticScore(scored.semanticScore());
        candidate.setSemanticField(scored.semanticField());
        candidate.setFusionScore(scored.fusionScore());
        candidate.setMatchedChannels(scored.matchedChannels());
        if (!shouldKeepHybridCandidate(candidate, hybridSemanticMinScore, queryText)) {
            return null;
        }
        return candidate;
    }

    private boolean shouldKeepHybridCandidate(EntityCandidateSearchResponse.Candidate candidate,
                                              Double hybridSemanticMinScore) {
        return shouldKeepHybridCandidate(candidate, hybridSemanticMinScore, null);
    }

    private boolean shouldKeepHybridCandidate(EntityCandidateSearchResponse.Candidate candidate,
                                              Double hybridSemanticMinScore, String queryText) {
        if (candidate.getFusionScore() == null) {
            return true;
        }
        List<String> channels = candidate.getMatchedChannels();
        if (channels == null || channels.isEmpty()) {
            return false;
        }
        boolean semanticMatched = channels.contains("semantic");
        Double normalizedMinScore = normalizeEntityHybridSemanticMinScore(hybridSemanticMinScore);
        if (semanticMatched && normalizedMinScore != null
                && scoreValue(candidate.getSemanticScore()) < normalizedMinScore) {
            return false;
        }
        boolean keywordOnly = channels.contains("keyword") && !semanticMatched;
        if (keywordOnly && !matchesSignificantQueryToken(candidate.getName(), queryText)) {
            return false;
        }
        return !"MediaContent".equalsIgnoreCase(candidate.getEntityType())
                || !looksLikeOpaqueContentName(candidate.getName());
    }

    private boolean matchesSignificantQueryToken(String candidateName, String queryText) {
        List<String> tokens = significantAsciiTokens(queryText);
        if (tokens.isEmpty()) {
            return true;
        }
        String normalizedName = normalizeAsciiText(candidateName);
        return tokens.stream().anyMatch(token -> normalizedName.contains(token));
    }

    private List<String> significantAsciiTokens(String text) {
        if (!hasText(text)) {
            return List.of();
        }
        String normalized = normalizeAsciiText(text);
        if (!hasText(normalized)) {
            return List.of();
        }
        return java.util.Arrays.stream(normalized.split(" "))
                .filter(this::hasText)
                .filter(token -> token.length() > 1)
                .filter(token -> !ENTITY_QUERY_STOP_WORDS.contains(token))
                .distinct()
                .toList();
    }

    private String normalizeAsciiText(String text) {
        if (!hasText(text)) {
            return "";
        }
        return text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private boolean looksLikeOpaqueContentName(String name) {
        if (!hasText(name)) {
            return true;
        }
        String normalized = name.trim();
        return normalized.length() <= 12 && normalized.matches("[A-Za-z0-9_-]+");
    }

    private Double normalizeEntityHybridSemanticMinScore(Double semanticMinScore) {
        if (semanticMinScore == null || !Double.isFinite(semanticMinScore)) {
            return null;
        }
        return Math.max(0D, Math.min(1D, semanticMinScore));
    }

    private List<ScoredScopedId> dedupeAndRank(List<ScoredScopedId> scored, int topK) {
        Map<String, ScoredScopedId> bestScores = new LinkedHashMap<>();
        for (ScoredScopedId item : scored) {
            if (item != null && hasText(item.scopedId())) {
                bestScores.merge(item.scopedId(), item, (left, right) ->
                        right.score() > left.score() ? right : left);
            }
        }
        return bestScores.values().stream()
                .sorted(Comparator.comparingDouble(ScoredScopedId::score).reversed())
                .limit(topK)
                .toList();
    }

    private void addScored(List<ScoredScopedId> scored, String domain, String id, Object score) {
        String scopedId = scopedId(domain, id);
        if (hasText(scopedId)) {
            scored.add(new ScoredScopedId(scopedId, scoreValue(score)));
        }
    }

    private void addHybridScored(List<ScoredScopedId> scored, String domain,
                                 MilvusVectorService.EntityHybridPocResult result, String queryText) {
        String scopedId = scopedId(domain, result.entityId());
        if (!hasText(scopedId)) {
            return;
        }
        boolean meaningfulKeywordMatch = result.keywordScore() != null
                && significantKeywordMatch(result.canonicalName(), queryText);
        List<String> channels = new ArrayList<>();
        if (meaningfulKeywordMatch) {
            channels.add("keyword");
        }
        if (result.semanticScore() != null) {
            channels.add("semantic");
        }
        scored.add(new ScoredScopedId(
                scopedId,
                entityHybridRankScore(result, meaningfulKeywordMatch),
                meaningfulKeywordMatch ? result.keywordScore().doubleValue() : null,
                meaningfulKeywordMatch ? result.keywordField() : null,
                result.semanticScore() == null ? null : normalizeCosineScore(result.semanticScore()),
                result.semanticField(),
                (double) result.fusionScore(),
                channels));
    }

    private double entityHybridRankScore(MilvusVectorService.EntityHybridPocResult result,
                                         boolean meaningfulKeywordMatch) {
        double score = result.fusionScore();
        if (meaningfulKeywordMatch && result.keywordScore() != null) {
            score += Math.log1p(Math.max(0F, result.keywordScore())) * keywordFieldWeight(result.keywordField());
        }
        if (result.semanticScore() != null) {
            score += normalizeCosineScore(result.semanticScore()) * semanticFieldWeight(result.semanticField());
        }
        return score;
    }

    private boolean significantKeywordMatch(String candidateName, String queryText) {
        return !hasText(queryText) || matchesSignificantQueryToken(candidateName, queryText);
    }

    private double normalizeCosineScore(float score) {
        return Math.max(0D, Math.min(1D, score));
    }

    private double keywordFieldWeight(String field) {
        if (!hasText(field)) {
            return 0D;
        }
        return switch (field) {
            case "canonical_name_sparse" -> 10D;
            case "aliases_sparse" -> 8D;
            case "description_sparse" -> 1D;
            default -> 1D;
        };
    }

    private double semanticFieldWeight(String field) {
        if (!hasText(field)) {
            return 0D;
        }
        return switch (field) {
            case "canonical_name_embedding" -> 4D;
            case "aliases_embedding" -> 3D;
            case "description_embedding" -> 1D;
            default -> 1D;
        };
    }

    private double scoreValue(Object score) {
        return score instanceof Number number ? number.doubleValue() : 0D;
    }

    private boolean containsType(List<String> types, String type) {
        return types != null && types.stream().anyMatch(type::equalsIgnoreCase);
    }

    private String domainForEntityType(String entityType) {
        if (!hasText(entityType)) {
            return null;
        }
        return switch (entityType.trim().toLowerCase()) {
            case "socialaccount", "social_account", "account" -> "account";
            case "mediacontent", "media_content", "content" -> "content";
            case "person", "organization", "event", "location" -> "entity";
            default -> null;
        };
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

    private String contentDisplayName(MediaContent content) {
        if (content == null) {
            return null;
        }
        return firstText(
                readableContentSnippet(content.getTitle(), CONTENT_RESULT_NAME_MAX_LENGTH),
                readableContentSnippet(content.getBodyText(), CONTENT_RESULT_NAME_MAX_LENGTH));
    }

    private String readableContentSnippet(String text, int maxLength) {
        if (!hasText(text)) {
            return null;
        }
        String normalized = text
                .replaceAll("https?://\\S+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (!hasText(normalized)) {
            return null;
        }
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength).trim();
    }

    private record ScoredScopedId(String scopedId, double score, Double keywordScore, String keywordField,
                                  Double semanticScore,
                                  String semanticField,
                                  Double fusionScore, List<String> matchedChannels) {
        private ScoredScopedId(String scopedId, double score) {
            this(scopedId, score, null, null, null, null, null, List.of());
        }
    }

    private List<MilvusVectorService.ScoredEntityId> searchSemanticIds(
            String queryText, String platform, String language, int topK, Double semanticMinScore) {
        if (!hasText(queryText) || topK <= 0) {
            return List.of();
        }

        float minScore = contentSemanticMinScore(queryText, semanticMinScore);
        Map<String, MilvusVectorService.ScoredEntityId> best = new LinkedHashMap<>();
        for (String variant : semanticQueryVariants(queryText)) {
            float[] embedding = embeddingService.generateTextEmbedding(variant);
            if (embedding == null) {
                continue;
            }
            milvusService.searchTextEmbeddingsWithScore(embedding, topK, platform, language).stream()
                    .filter(result -> result.score() >= minScore)
                    .forEach(result -> mergeScoredEntityId(best, result));
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(MilvusVectorService.ScoredEntityId::score).reversed())
                .limit(topK)
                .toList();
    }

    private void mergeScoredEntityId(Map<String, MilvusVectorService.ScoredEntityId> best,
                                     MilvusVectorService.ScoredEntityId result) {
        if (best == null || result == null || !hasText(result.entityId())) {
            return;
        }
        best.merge(result.entityId(), result, (left, right) -> right.score() > left.score() ? right : left);
    }

    private List<String> searchAccountSemanticIds(String queryText, int topK) {
        if (!hasText(queryText)) {
            return List.of();
        }
        float[] embedding = embeddingService.generateTextEmbedding(queryText);
        if (embedding == null) {
            return List.of();
        }
        return milvusService.searchEntityEmbeddings(embedding, topK, "SocialAccount").stream()
                .map(MilvusVectorService.ScoredEntityId::entityId)
                .toList();
    }

    /**
     * 把图片向量化后查 Milvus，返回 content_id 列表。
     * 同时支持 media_content_embeddings（以图搜文）和 media_asset_embeddings（以图搜图）。
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
            case "text", "image", "video", "audio" -> value;
            default -> "all";
        };
    }

    private boolean isMediaOnlyTargetModality(String targetModality) {
        return "image".equals(targetModality) || "video".equals(targetModality) || "audio".equals(targetModality);
    }

    private String targetMediaType(String targetModality) {
        return isMediaOnlyTargetModality(targetModality) ? targetModality : null;
    }

    private boolean matchesTargetMediaType(String mediaType, String targetModality) {
        String targetMediaType = targetMediaType(targetModality);
        return !hasText(targetMediaType) || targetMediaType.equalsIgnoreCase(firstText(mediaType, ""));
    }

    private int mediaRecallSize(int topK, String targetModality) {
        if (!isMediaOnlyTargetModality(targetModality)) {
            return topK;
        }
        return Math.min(MAX_RECALL_SIZE, Math.max(topK, topK * 3));
    }

    private float imageQueryVisualMinScore(Double visualMinScore) {
        if (visualMinScore == null || !Double.isFinite(visualMinScore)) {
            return IMAGE_QUERY_VISUAL_MIN_SCORE;
        }
        return (float) Math.max(0D, Math.min(1D, visualMinScore));
    }

    private float normalizeSemanticMinScore(Double semanticMinScore) {
        if (semanticMinScore == null || !Double.isFinite(semanticMinScore)) {
            return DEFAULT_CONTENT_SEMANTIC_MIN_SCORE;
        }
        return (float) Math.max(0D, Math.min(1D, semanticMinScore));
    }

    private float contentSemanticMinScore(String queryText, Double semanticMinScore) {
        float minScore = normalizeSemanticMinScore(semanticMinScore);
        return isIdentifierLikeQuery(queryText)
                ? Math.max(minScore, IDENTIFIER_CONTENT_SEMANTIC_MIN_SCORE)
                : minScore;
    }

    private float mediaTextSemanticMinScore(String queryText, Double semanticMinScore) {
        float minScore = Math.max(DEFAULT_MEDIA_TEXT_SEMANTIC_MIN_SCORE, normalizeSemanticMinScore(semanticMinScore));
        return isIdentifierLikeQuery(queryText)
                ? Math.max(minScore, IDENTIFIER_MEDIA_TEXT_SEMANTIC_MIN_SCORE)
                : minScore;
    }

    private float mediaSemanticMinScore(String queryText, Double semanticMinScore,
                                        String targetModality, String vectorField) {
        if (isVisualVectorField(vectorField)) {
            return Math.max(DEFAULT_MEDIA_VISUAL_MIN_SCORE, normalizeSemanticMinScore(semanticMinScore));
        }
        float minScore = mediaTextSemanticMinScore(queryText, semanticMinScore);
        return isMediaOnlyTargetModality(targetModality)
                ? Math.max(minScore, MEDIA_ONLY_TEXT_SEMANTIC_MIN_SCORE)
                : minScore;
    }

    private boolean isVisualVectorField(String vectorField) {
        return "visual_embedding".equals(vectorField);
    }

    private boolean isMediaTextVectorField(String vectorField) {
        return "ocr_embedding".equals(vectorField)
                || "asr_embedding".equals(vectorField)
                || "caption_embedding".equals(vectorField);
    }

    private boolean isStoredTextVectorField(String vectorField) {
        return "ocr_embedding".equals(vectorField) || "asr_embedding".equals(vectorField);
    }

    private boolean isUsefulSemanticSourceText(String text) {
        if (!hasText(text)) {
            return false;
        }
        long letters = text.codePoints().filter(Character::isLetter).count();
        long digits = text.codePoints().filter(Character::isDigit).count();
        return letters >= 3 && letters >= digits;
    }

    private boolean hasCompatibleSemanticToken(String queryText, String sourceText) {
        List<String> queryTokens = semanticTextTokens(queryText);
        List<String> sourceTokens = semanticTextTokens(sourceText);
        if (queryTokens.isEmpty() || sourceTokens.isEmpty()) {
            return false;
        }
        for (String queryToken : queryTokens) {
            for (String sourceToken : sourceTokens) {
                if (isCompatibleSemanticToken(queryToken, sourceToken)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isLikelyCrossLanguage(String queryText, String sourceText) {
        String queryScript = dominantScriptGroup(queryText);
        String sourceScript = dominantScriptGroup(sourceText);
        return hasText(queryScript) && hasText(sourceScript) && !queryScript.equals(sourceScript);
    }

    private boolean isNaturalLanguageQuery(String queryText) {
        if (!hasText(queryText) || isIdentifierLikeQuery(queryText)) {
            return false;
        }
        String normalized = normalizeWhitespace(queryText);
        if (!hasText(normalized)) {
            return false;
        }
        int tokenCount = semanticTextTokens(normalized).size();
        long letterCount = normalized.codePoints().filter(Character::isLetter).count();
        boolean hasSentencePunctuation = normalized.matches(".*[。！？!?；;,.，、].*");
        return tokenCount >= 4 || letterCount >= 18 || hasSentencePunctuation;
    }

    private String dominantScriptGroup(String text) {
        if (!hasText(text)) {
            return null;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        text.codePoints()
                .filter(Character::isLetter)
                .mapToObj(this::scriptGroup)
                .filter(Objects::nonNull)
                .forEach(group -> counts.merge(group, 1, Integer::sum));
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private String scriptGroup(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return switch (script) {
            case HAN, HIRAGANA, KATAKANA, HANGUL -> "cjk";
            case LATIN -> "latin";
            case ARABIC -> "arabic";
            case CYRILLIC -> "cyrillic";
            default -> null;
        };
    }

    private List<String> semanticTextTokens(String text) {
        if (!hasText(text)) {
            return List.of();
        }
        return Arrays.stream(text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{Nd}]+"))
                .filter(token -> token.length() >= 3)
                .filter(token -> !CONTENT_QUERY_STOP_WORDS.contains(token))
                .distinct()
                .toList();
    }

    private boolean isCompatibleSemanticToken(String left, String right) {
        if (left.equals(right)) {
            return true;
        }
        if (left.length() >= 4 && right.length() >= 4
                && (left.contains(right) || right.contains(left))) {
            return true;
        }
        return left.length() >= 4 && right.length() >= 4 && editDistanceAtMostOne(left, right);
    }

    private boolean editDistanceAtMostOne(String left, String right) {
        int leftLength = left.length();
        int rightLength = right.length();
        if (Math.abs(leftLength - rightLength) > 1) {
            return false;
        }
        int i = 0;
        int j = 0;
        int edits = 0;
        while (i < leftLength && j < rightLength) {
            if (left.charAt(i) == right.charAt(j)) {
                i++;
                j++;
                continue;
            }
            edits++;
            if (edits > 1) {
                return false;
            }
            if (leftLength > rightLength) {
                i++;
            } else if (rightLength > leftLength) {
                j++;
            } else {
                i++;
                j++;
            }
        }
        return edits + (leftLength - i) + (rightLength - j) <= 1;
    }

    private boolean isIdentifierLikeQuery(String queryText) {
        if (!hasText(queryText)) {
            return false;
        }
        String value = queryText.trim();
        if (value.length() < 4 || value.length() > 32 || value.contains(" ")) {
            return false;
        }
        if (!value.matches("[A-Za-z0-9_.-]+")) {
            return false;
        }
        boolean hasLetter = value.matches(".*[A-Za-z].*");
        boolean hasDigit = value.matches(".*\\d.*");
        boolean hasIdentifierPunctuation = value.matches(".*[_.-].*");
        return hasLetter && (hasDigit || hasIdentifierPunctuation);
    }

    private int normalizeSize(int size) {
        return size > 0 ? size : DEFAULT_TOP_K;
    }

    private int normalizeRecallSize(Integer requestedTopK, int page, int size) {
        if (requestedTopK != null && requestedTopK > 0) {
            return Math.min(requestedTopK, MAX_RECALL_SIZE);
        }
        int requiredForPage = (normalizePage(page) + 1) * normalizePageSize(size) * 3;
        return Math.min(Math.max(requiredForPage, DEFAULT_RECALL_SIZE), MAX_RECALL_SIZE);
    }

    private int normalizePage(int page) {
        return Math.max(page, 0);
    }

    private int normalizePageSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private <T> List<T> pageSlice(List<T> items, int page, int size) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int safePage = normalizePage(page);
        int safeSize = normalizePageSize(size);
        int fromIndex = Math.min(safePage * safeSize, items.size());
        int toIndex = Math.min(fromIndex + safeSize, items.size());
        return items.subList(fromIndex, toIndex);
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

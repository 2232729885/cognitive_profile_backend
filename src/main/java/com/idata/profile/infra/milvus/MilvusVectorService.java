package com.idata.profile.infra.milvus;

import com.google.gson.Gson;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusVectorService {

    private static final int EMBEDDING_DIMENSION = 4096;
    private static final String MEDIA_CONTENT_COLLECTION = "media_content_embeddings";
    private static final String MEDIA_ASSET_COLLECTION = "media_asset_embeddings";
    private static final String ENTITY_COLLECTION = "entity_embeddings";
    private static final String ENTITY_HYBRID_POC_COLLECTION = "entity_hybrid_poc_embeddings";

    private static final String CONTENT_TITLE_VECTOR_FIELD = "title_embedding";
    private static final String CONTENT_TEXT_VECTOR_FIELD = "text_embedding";
    private static final String ASSET_IMAGE_VECTOR_FIELD = "image_embedding";
    private static final String ASSET_OCR_TEXT_VECTOR_FIELD = "ocr_text_embedding";
    private static final String ENTITY_NAME_VECTOR_FIELD = "name_embedding";
    private static final String ENTITY_ALIAS_VECTOR_FIELD = "alias_embedding";
    private static final String ENTITY_DESCRIPTION_VECTOR_FIELD = "description_embedding";
    private static final String ENTITY_KEYWORD_TEXT_FIELD = "keyword_text";
    private static final String ENTITY_KEYWORD_SPARSE_FIELD = "keyword_sparse";
    private static final Gson GSON = new Gson();
    private static final float[] ZERO_EMBEDDING = new float[EMBEDDING_DIMENSION];
    private static final float MIN_VECTOR_SCORE = 0.000001F;
    private static final int ID_MAX_LENGTH = 128;
    private static final int VECTOR_ID_MAX_LENGTH = 192;
    private static final int PLATFORM_MAX_LENGTH = 64;
    private static final int LANGUAGE_MAX_LENGTH = 32;
    private static final int TYPE_MAX_LENGTH = 64;
    private static final int SOURCE_ASSET_ID_MAX_LENGTH = 256;
    private static final int MIME_TYPE_MAX_LENGTH = 128;
    private static final int CANONICAL_NAME_MAX_LENGTH = 512;
    private static final int ALIASES_MAX_LENGTH = 2048;
    private static final int KEYWORD_TEXT_MAX_LENGTH = 4096;

    private final MilvusClientV2 milvusClient;

    public record ScoredEntityId(String entityId, float score) {
    }

    public record EntityHybridPocResult(String entityId, String entityType, float fusionScore,
                                        Float keywordScore, Float semanticScore) {
    }

    public String insertMediaContentEmbedding(String contentId, String platform, String language,
                                              String contentType, long publishedAt,
                                              float[] titleEmbedding, float[] textEmbedding) {
        validateOptionalEmbedding(titleEmbedding);
        validateOptionalEmbedding(textEmbedding);
        if (titleEmbedding == null && textEmbedding == null) {
            return null;
        }

        String vectorId = "media_content_" + contentId;
        JsonObject row = new JsonObject();
        addText(row, "id", vectorId, ID_MAX_LENGTH);
        addText(row, "content_id", contentId, ID_MAX_LENGTH);
        addText(row, "platform", platform, PLATFORM_MAX_LENGTH);
        addText(row, "language", language, LANGUAGE_MAX_LENGTH);
        addText(row, "content_type", contentType, TYPE_MAX_LENGTH);
        row.addProperty("published_at", publishedAt);
        addVector(row, CONTENT_TITLE_VECTOR_FIELD, titleEmbedding);
        addVector(row, CONTENT_TEXT_VECTOR_FIELD, textEmbedding);
        insert(MEDIA_CONTENT_COLLECTION, row);
        return vectorId;
    }

    public String insertTextEmbedding(String sourceId, String sourceType,
                                      String platform, String language,
                                      long publishedAt, float importance,
                                      float[] embedding) {
        return insertMediaContentEmbedding(sourceId, platform, language, sourceType, publishedAt, null, embedding);
    }

    public String upsertMediaAssetEmbedding(String assetId, String sourceAssetId, String contentId,
                                            String platform, String assetType, String mimeType,
                                            float[] imageEmbedding, float[] ocrTextEmbedding) {
        validateOptionalEmbedding(imageEmbedding);
        validateOptionalEmbedding(ocrTextEmbedding);
        if (imageEmbedding == null && ocrTextEmbedding == null) {
            return null;
        }

        String vectorId = "media_asset_" + assetId;
        JsonObject row = new JsonObject();
        addText(row, "id", vectorId, ID_MAX_LENGTH);
        addText(row, "asset_id", assetId, ID_MAX_LENGTH);
        addText(row, "source_asset_id", sourceAssetId, SOURCE_ASSET_ID_MAX_LENGTH);
        addText(row, "content_id", contentId, ID_MAX_LENGTH);
        addText(row, "platform", platform, PLATFORM_MAX_LENGTH);
        addText(row, "asset_type", assetType, TYPE_MAX_LENGTH);
        addText(row, "mime_type", mimeType, MIME_TYPE_MAX_LENGTH);
        addVector(row, ASSET_IMAGE_VECTOR_FIELD, imageEmbedding);
        addVector(row, ASSET_OCR_TEXT_VECTOR_FIELD, ocrTextEmbedding);
        insert(MEDIA_ASSET_COLLECTION, row);
        return vectorId;
    }

    public String insertImageEmbedding(String assetId, String contentId,
                                       String platform, float aigcScore,
                                       float[] embedding) {
        return upsertMediaAssetEmbedding(assetId, null, contentId, platform, "image", null, embedding, null);
    }

    public String insertImageEmbedding(String assetId, String sourceAssetId, String contentId,
                                       String platform, float aigcScore,
                                       float[] embedding) {
        return upsertMediaAssetEmbedding(assetId, sourceAssetId, contentId, platform, "image", null, embedding, null);
    }

    public String insertImageOcrEmbedding(String assetId, String contentId,
                                          String platform, float[] embedding) {
        return upsertMediaAssetEmbedding(assetId, null, contentId, platform, "image", null, null, embedding);
    }

    public String insertImageOcrEmbedding(String assetId, String sourceAssetId, String contentId,
                                          String platform, float[] embedding) {
        return upsertMediaAssetEmbedding(assetId, sourceAssetId, contentId, platform, "image", null, null, embedding);
    }

    public String insertEntityEmbedding(String entityId, String entityType,
                                        String normalizedName, float[] embedding) {
        return upsertEntityEmbedding(entityId, entityType, normalizedName, null, null, null, embedding);
    }

    public String upsertEntityEmbedding(String entityId, String entityType, String canonicalName,
                                        String aliases, String sourceId, String platform,
                                        float[] nameEmbedding, float[] aliasEmbedding,
                                        float[] descriptionEmbedding) {
        validateOptionalEmbedding(nameEmbedding);
        validateOptionalEmbedding(aliasEmbedding);
        validateOptionalEmbedding(descriptionEmbedding);
        if (nameEmbedding == null && aliasEmbedding == null && descriptionEmbedding == null) {
            return null;
        }
        if (!hasText(entityId)) {
            return null;
        }

        String vectorId = "entity_" + normalizeEntityType(entityType) + "_" + entityId;
        JsonObject row = new JsonObject();
        addText(row, "id", vectorId, VECTOR_ID_MAX_LENGTH);
        addText(row, "entity_id", entityId, ID_MAX_LENGTH);
        addText(row, "entity_type", normalizeEntityType(entityType), TYPE_MAX_LENGTH);
        addText(row, "canonical_name", canonicalName, CANONICAL_NAME_MAX_LENGTH);
        addText(row, "aliases", aliases, ALIASES_MAX_LENGTH);
        addText(row, "source_id", sourceId, ID_MAX_LENGTH);
        addText(row, "platform", platform, PLATFORM_MAX_LENGTH);
        addVector(row, ENTITY_NAME_VECTOR_FIELD, nameEmbedding);
        addVector(row, ENTITY_ALIAS_VECTOR_FIELD, aliasEmbedding);
        addVector(row, ENTITY_DESCRIPTION_VECTOR_FIELD, descriptionEmbedding);
        try {
            insert(ENTITY_COLLECTION, row);
            upsertEntityHybridPocEmbedding(row, canonicalName, aliases);
        } catch (RuntimeException e) {
            log.warn("Failed to upsert entity embeddings, entityId={}, entityType={}",
                    entityId, normalizeEntityType(entityType), e);
            return null;
        }
        return vectorId;
    }

    private void upsertEntityHybridPocEmbedding(JsonObject baseRow, String canonicalName, String aliases) {
        if (milvusClient == null) {
            return;
        }
        try {
            if (!collectionExists(ENTITY_HYBRID_POC_COLLECTION)) {
                return;
            }
            JsonObject row = baseRow.deepCopy();
            addText(row, ENTITY_KEYWORD_TEXT_FIELD, buildEntityKeywordText(row, canonicalName, aliases),
                    KEYWORD_TEXT_MAX_LENGTH);
            insert(ENTITY_HYBRID_POC_COLLECTION, row);
        } catch (RuntimeException e) {
            log.warn("Failed to upsert entity hybrid POC embedding, entityId={}",
                    baseRow.has("entity_id") ? baseRow.get("entity_id").getAsString() : null, e);
        }
    }

    private String upsertEntityEmbedding(String entityId, String entityType, String canonicalName,
                                         String aliases, String sourceId, String platform,
                                         float[] descriptionEmbedding) {
        return upsertEntityEmbedding(entityId, entityType, canonicalName, aliases, sourceId, platform,
                null, null, descriptionEmbedding);
    }

    public void deleteEntityEmbeddings(List<String> entityIds) {
        if (milvusClient == null || entityIds == null || entityIds.isEmpty()) {
            return;
        }
        try {
            Boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                    .collectionName(ENTITY_COLLECTION)
                    .build());
            if (!Boolean.TRUE.equals(exists)) {
                return;
            }
            String idList = entityIds.stream()
                    .map(id -> "\"" + escapeFilterValue(id) + "\"")
                    .collect(java.util.stream.Collectors.joining(", "));
            milvusClient.delete(io.milvus.v2.service.vector.request.DeleteReq.builder()
                    .collectionName(ENTITY_COLLECTION)
                    .filter("entity_id in [" + idList + "]")
                    .build());
        } catch (RuntimeException e) {
            log.warn("Failed to delete entity embeddings from Milvus, entityIds={}", entityIds, e);
        }
    }

    public List<String> searchTextEmbeddings(float[] queryEmbedding, int topK,
                                             String platform, String language) {
        return searchMediaContentEmbeddingsWithScore(queryEmbedding, topK, platform, language).stream()
                .map(ScoredEntityId::entityId)
                .toList();
    }

    public List<ScoredEntityId> searchTextEmbeddingsWithScore(float[] queryEmbedding, int topK,
                                                              String platform, String language) {
        return searchMediaContentEmbeddingsWithScore(queryEmbedding, topK, platform, language);
    }

    public List<ScoredEntityId> searchMediaContentEmbeddingsWithScore(float[] queryEmbedding, int topK,
                                                                      String platform, String language) {
        String filter = buildContentFilter(platform, language);
        List<ScoredEntityId> titleResults = searchEmbeddingsWithScore(
                MEDIA_CONTENT_COLLECTION, CONTENT_TITLE_VECTOR_FIELD, queryEmbedding, topK, filter, "content_id");
        List<ScoredEntityId> textResults = searchEmbeddingsWithScore(
                MEDIA_CONTENT_COLLECTION, CONTENT_TEXT_VECTOR_FIELD, queryEmbedding, topK, filter, "content_id");
        return mergeByMaxScore(List.of(titleResults, textResults), topK);
    }

    public List<String> searchImageEmbeddings(float[] queryEmbedding, int topK) {
        return searchMediaAssetImageEmbeddingsWithScore(queryEmbedding, topK).stream()
                .map(ScoredEntityId::entityId)
                .toList();
    }

    public List<ScoredEntityId> searchImageEmbeddingsWithScore(float[] queryEmbedding, int topK) {
        return searchMediaAssetImageEmbeddingsWithScore(queryEmbedding, topK);
    }

    public List<ScoredEntityId> searchMediaAssetImageEmbeddingsWithScore(float[] queryEmbedding, int topK) {
        return searchEmbeddingsWithScore(
                MEDIA_ASSET_COLLECTION, ASSET_IMAGE_VECTOR_FIELD, queryEmbedding, topK, null, "asset_id");
    }

    public List<ScoredEntityId> searchMediaAssetTextEmbeddingsWithScore(float[] queryEmbedding, int topK) {
        List<ScoredEntityId> imageResults = searchEmbeddingsWithScore(
                MEDIA_ASSET_COLLECTION, ASSET_IMAGE_VECTOR_FIELD, queryEmbedding, topK, null, "asset_id");
        List<ScoredEntityId> ocrResults = searchEmbeddingsWithScore(
                MEDIA_ASSET_COLLECTION, ASSET_OCR_TEXT_VECTOR_FIELD, queryEmbedding, topK, null, "asset_id");
        return mergeByMaxScore(List.of(imageResults, ocrResults), topK);
    }

    public List<ScoredEntityId> searchEntityEmbeddings(float[] queryEmbedding, int topK,
                                                       String entityType) {
        String filter = hasText(entityType)
                ? "entity_type == \"" + escapeFilterValue(normalizeEntityType(entityType)) + "\""
                : null;
        List<ScoredEntityId> nameResults = searchEmbeddingsWithScore(
                ENTITY_COLLECTION, ENTITY_NAME_VECTOR_FIELD, queryEmbedding, topK, filter, "entity_id");
        List<ScoredEntityId> aliasResults = searchEmbeddingsWithScore(
                ENTITY_COLLECTION, ENTITY_ALIAS_VECTOR_FIELD, queryEmbedding, topK, filter, "entity_id");
        List<ScoredEntityId> descriptionResults = searchEmbeddingsWithScore(
                ENTITY_COLLECTION, ENTITY_DESCRIPTION_VECTOR_FIELD, queryEmbedding, topK, filter, "entity_id");
        return mergeByMaxScore(List.of(nameResults, aliasResults, descriptionResults), topK);
    }

    public List<ScoredEntityId> searchEntityHybridPoc(String keyword, float[] queryEmbedding, int topK,
                                                      String entityType) {
        Collection<String> entityTypes = hasText(entityType) ? List.of(entityType) : List.of();
        return searchEntityHybridPocDetailed(keyword, queryEmbedding, topK, entityTypes).stream()
                .map(result -> new ScoredEntityId(result.entityId(), result.fusionScore()))
                .toList();
    }

    public List<EntityHybridPocResult> searchEntityHybridPocDetailed(String keyword, float[] queryEmbedding,
                                                                     int topK, Collection<String> entityTypes) {
        if (milvusClient == null || !hasText(keyword) || topK <= 0) {
            return List.of();
        }
        validateEmbedding(queryEmbedding);
        String filter = buildEntityTypeFilter(entityTypes);
        try {
            if (!collectionExists(ENTITY_HYBRID_POC_COLLECTION)) {
                return List.of();
            }
            List<ScoredEntityId> keywordResults = searchEntityHybridPocKeyword(keyword, topK, filter);
            List<ScoredEntityId> semanticResults = mergeByMaxScore(List.of(
                    searchEntityHybridPocDense(ENTITY_NAME_VECTOR_FIELD, queryEmbedding, topK, filter),
                    searchEntityHybridPocDense(ENTITY_ALIAS_VECTOR_FIELD, queryEmbedding, topK, filter),
                    searchEntityHybridPocDense(ENTITY_DESCRIPTION_VECTOR_FIELD, queryEmbedding, topK, filter)), topK);
            Map<String, Float> keywordScores = scoreMap(keywordResults);
            Map<String, Float> semanticScores = scoreMap(semanticResults);

            List<AnnSearchReq> requests = new ArrayList<>();
            requests.add(AnnSearchReq.builder()
                    .vectorFieldName(ENTITY_KEYWORD_SPARSE_FIELD)
                    .vectors(List.<BaseVector>of(new EmbeddedText(keyword)))
                    .filter(filter)
                    .limit(topK)
                    .build());
            requests.add(entityDenseSearchReq(ENTITY_NAME_VECTOR_FIELD, queryEmbedding, topK, filter));
            requests.add(entityDenseSearchReq(ENTITY_ALIAS_VECTOR_FIELD, queryEmbedding, topK, filter));
            requests.add(entityDenseSearchReq(ENTITY_DESCRIPTION_VECTOR_FIELD, queryEmbedding, topK, filter));

            SearchResp response = milvusClient.hybridSearch(HybridSearchReq.builder()
                    .collectionName(ENTITY_HYBRID_POC_COLLECTION)
                    .searchRequests(requests)
                    .ranker(RRFRanker.builder().k(60).build())
                    .limit(topK)
                    .outFields(List.of("entity_id", "entity_type"))
                    .build());
            return extractEntityHybridPocResults(response, keywordScores, semanticScores);
        } catch (RuntimeException e) {
            log.warn("Milvus entity hybrid POC search failed, entityTypes={}, topK={}", entityTypes, topK, e);
            return List.of();
        }
    }

    private List<ScoredEntityId> searchEntityHybridPocKeyword(String keyword, int topK, String filter) {
        try {
            SearchReq.SearchReqBuilder builder = SearchReq.builder()
                    .collectionName(ENTITY_HYBRID_POC_COLLECTION)
                    .annsField(ENTITY_KEYWORD_SPARSE_FIELD)
                    .topK(topK)
                    .data(List.<BaseVector>of(new EmbeddedText(keyword)))
                    .outputFields(List.of("entity_id"));
            if (hasText(filter)) {
                builder.filter(filter);
            }
            SearchResp response = milvusClient.search(builder.build());
            return extractSearchFieldWithScore(response, "entity_id");
        } catch (RuntimeException e) {
            log.warn("Milvus entity hybrid POC keyword score search failed, topK={}", topK, e);
            return List.of();
        }
    }

    private List<ScoredEntityId> searchEntityHybridPocDense(String vectorField, float[] queryEmbedding,
                                                           int topK, String filter) {
        return searchEmbeddingsWithScore(
                ENTITY_HYBRID_POC_COLLECTION, vectorField, queryEmbedding, topK, filter, "entity_id");
    }

    private Map<String, Float> scoreMap(List<ScoredEntityId> results) {
        Map<String, Float> scores = new LinkedHashMap<>();
        for (ScoredEntityId result : results) {
            if (result != null && hasText(result.entityId())) {
                scores.merge(result.entityId(), result.score(), Math::max);
            }
        }
        return scores;
    }

    private List<EntityHybridPocResult> extractEntityHybridPocResults(
            SearchResp response, Map<String, Float> keywordScores, Map<String, Float> semanticScores) {
        if (response == null || response.getSearchResults() == null || response.getSearchResults().isEmpty()) {
            return List.of();
        }

        List<EntityHybridPocResult> results = new ArrayList<>();
        for (List<SearchResp.SearchResult> group : response.getSearchResults()) {
            if (group == null) {
                continue;
            }
            for (SearchResp.SearchResult result : group) {
                if (result == null || result.getEntity() == null) {
                    continue;
                }
                Object entityId = result.getEntity().get("entity_id");
                if (entityId == null) {
                    continue;
                }
                String id = entityId.toString();
                Object entityType = result.getEntity().get("entity_type");
                results.add(new EntityHybridPocResult(
                        id,
                        entityType == null ? null : entityType.toString(),
                        result.getScore(),
                        keywordScores.get(id),
                        semanticScores.get(id)));
            }
        }
        return results;
    }

    private AnnSearchReq entityDenseSearchReq(String vectorField, float[] queryEmbedding, int topK, String filter) {
        return AnnSearchReq.builder()
                .vectorFieldName(vectorField)
                .vectors(List.<BaseVector>of(new FloatVec(queryEmbedding)))
                .metricType(IndexParam.MetricType.COSINE)
                .filter(filter)
                .limit(topK)
                .build();
    }

    public void ensureAllCollections() {
        if (milvusClient == null) {
            log.info("Milvus client not configured, skip collection initialization");
            return;
        }
        ensure("media content", this::ensureMediaContentCollection);
        ensure("media asset", this::ensureMediaAssetCollection);
        ensure("entity", this::ensureEntityCollection);
        ensure("entity hybrid POC", this::ensureEntityHybridPocCollection);
    }

    private void ensure(String label, Runnable action) {
        try {
            action.run();
            log.info("Milvus collection ensured: {}", label);
        } catch (Exception e) {
            log.error("Failed to ensure Milvus collection: {}", label, e);
        }
    }

    private void ensureMediaContentCollection() {
        if (collectionExists(MEDIA_CONTENT_COLLECTION)) {
            return;
        }
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(true)
                .build()
                .addField(varcharField("id", 128, false, true))
                .addField(varcharField("content_id", 64, false, false))
                .addField(varcharField("platform", 64, true, false))
                .addField(varcharField("language", 32, true, false))
                .addField(varcharField("content_type", 64, true, false))
                .addField(int64Field("published_at", true))
                .addField(vectorField(CONTENT_TITLE_VECTOR_FIELD))
                .addField(vectorField(CONTENT_TEXT_VECTOR_FIELD));
        createCollection(MEDIA_CONTENT_COLLECTION, schema, List.of(
                vectorIndex(CONTENT_TITLE_VECTOR_FIELD),
                vectorIndex(CONTENT_TEXT_VECTOR_FIELD)));
    }

    private void ensureMediaAssetCollection() {
        if (collectionExists(MEDIA_ASSET_COLLECTION)) {
            return;
        }
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(true)
                .build()
                .addField(varcharField("id", 128, false, true))
                .addField(varcharField("asset_id", 64, false, false))
                .addField(varcharField("source_asset_id", 256, true, false))
                .addField(varcharField("content_id", 64, true, false))
                .addField(varcharField("platform", 64, true, false))
                .addField(varcharField("asset_type", 32, true, false))
                .addField(varcharField("mime_type", 128, true, false))
                .addField(vectorField(ASSET_IMAGE_VECTOR_FIELD))
                .addField(vectorField(ASSET_OCR_TEXT_VECTOR_FIELD));
        createCollection(MEDIA_ASSET_COLLECTION, schema, List.of(
                vectorIndex(ASSET_IMAGE_VECTOR_FIELD),
                vectorIndex(ASSET_OCR_TEXT_VECTOR_FIELD)));
    }

    private void ensureEntityCollection() {
        if (collectionExists(ENTITY_COLLECTION)) {
            return;
        }
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(true)
                .build()
                .addField(varcharField("id", 192, false, true))
                .addField(varcharField("entity_id", 128, false, false))
                .addField(varcharField("entity_type", 64, false, false))
                .addField(varcharField("canonical_name", 512, true, false))
                .addField(varcharField("aliases", 2048, true, false))
                .addField(varcharField("source_id", 128, true, false))
                .addField(varcharField("platform", 64, true, false))
                .addField(vectorField(ENTITY_NAME_VECTOR_FIELD))
                .addField(vectorField(ENTITY_ALIAS_VECTOR_FIELD))
                .addField(vectorField(ENTITY_DESCRIPTION_VECTOR_FIELD));
        createCollection(ENTITY_COLLECTION, schema, List.of(
                vectorIndex(ENTITY_NAME_VECTOR_FIELD),
                vectorIndex(ENTITY_ALIAS_VECTOR_FIELD),
                vectorIndex(ENTITY_DESCRIPTION_VECTOR_FIELD)));
    }

    private void ensureEntityHybridPocCollection() {
        if (collectionExists(ENTITY_HYBRID_POC_COLLECTION)) {
            return;
        }
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(true)
                .build()
                .addField(varcharField("id", 192, false, true))
                .addField(varcharField("entity_id", 128, false, false))
                .addField(varcharField("entity_type", 64, false, false))
                .addField(varcharField("canonical_name", 512, true, false))
                .addField(varcharField("aliases", 2048, true, false))
                .addField(varcharField("source_id", 128, true, false))
                .addField(varcharField("platform", 64, true, false))
                .addField(analyzedTextField(ENTITY_KEYWORD_TEXT_FIELD, 4096))
                .addField(sparseVectorField(ENTITY_KEYWORD_SPARSE_FIELD))
                .addField(vectorField(ENTITY_NAME_VECTOR_FIELD))
                .addField(vectorField(ENTITY_ALIAS_VECTOR_FIELD))
                .addField(vectorField(ENTITY_DESCRIPTION_VECTOR_FIELD))
                .addFunction(CreateCollectionReq.Function.builder()
                        .name("entity_keyword_bm25")
                        .functionType(FunctionType.BM25)
                        .inputFieldNames(List.of(ENTITY_KEYWORD_TEXT_FIELD))
                        .outputFieldNames(List.of(ENTITY_KEYWORD_SPARSE_FIELD))
                        .build());
        createCollection(ENTITY_HYBRID_POC_COLLECTION, schema, List.of(
                sparseBm25Index(ENTITY_KEYWORD_SPARSE_FIELD),
                vectorIndex(ENTITY_NAME_VECTOR_FIELD),
                vectorIndex(ENTITY_ALIAS_VECTOR_FIELD),
                vectorIndex(ENTITY_DESCRIPTION_VECTOR_FIELD)));
    }

    private void createCollection(String collectionName, CreateCollectionReq.CollectionSchema schema,
                                  List<IndexParam> indexParams) {
        milvusClient.createCollection(CreateCollectionReq.builder()
                .collectionName(collectionName)
                .description(collectionName + " for cognitive profile backend")
                .collectionSchema(schema)
                .indexParams(indexParams)
                .autoID(false)
                .enableDynamicField(true)
                .build());
        loadCollection(collectionName);
    }

    private boolean collectionExists(String collectionName) {
        return Boolean.TRUE.equals(milvusClient.hasCollection(HasCollectionReq.builder()
                .collectionName(collectionName)
                .build()));
    }

    private void loadCollection(String collectionName) {
        milvusClient.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .build());
    }

    private AddFieldReq varcharField(String name, int maxLength, boolean nullable, boolean primaryKey) {
        return AddFieldReq.builder()
                .fieldName(name)
                .dataType(DataType.VarChar)
                .maxLength(maxLength)
                .isNullable(nullable)
                .isPrimaryKey(primaryKey)
                .autoID(false)
                .build();
    }

    private AddFieldReq int64Field(String name, boolean nullable) {
        return AddFieldReq.builder()
                .fieldName(name)
                .dataType(DataType.Int64)
                .isNullable(nullable)
                .build();
    }

    private AddFieldReq vectorField(String name) {
        return AddFieldReq.builder()
                .fieldName(name)
                .dataType(DataType.FloatVector)
                .dimension(EMBEDDING_DIMENSION)
                .build();
    }

    private AddFieldReq sparseVectorField(String name) {
        return AddFieldReq.builder()
                .fieldName(name)
                .dataType(DataType.SparseFloatVector)
                .build();
    }

    private AddFieldReq analyzedTextField(String name, int maxLength) {
        return AddFieldReq.builder()
                .fieldName(name)
                .dataType(DataType.VarChar)
                .maxLength(maxLength)
                .enableAnalyzer(true)
                .enableMatch(true)
                .analyzerParams(Map.of("type", "chinese"))
                .build();
    }

    private IndexParam vectorIndex(String fieldName) {
        return IndexParam.builder()
                .fieldName(fieldName)
                .indexName(fieldName + "_idx")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();
    }

    private IndexParam sparseBm25Index(String fieldName) {
        return IndexParam.builder()
                .fieldName(fieldName)
                .indexName(fieldName + "_idx")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build();
    }

    private void insert(String collectionName, JsonObject row) {
        io.milvus.v2.service.vector.response.UpsertResp response = milvusClient.upsert(UpsertReq.builder()
                .collectionName(collectionName)
                .data(List.of(row))
                .build());
        log.debug("Upserted Milvus vector, collection={}, count={}",
                collectionName, response.getUpsertCnt());
    }

    private void addVector(JsonObject row, String fieldName, float[] embedding) {
        if (embedding == null) {
            row.add(fieldName, GSON.toJsonTree(toFloatList(ZERO_EMBEDDING)));
            return;
        }
        row.add(fieldName, GSON.toJsonTree(toFloatList(embedding)));
    }

    private void addText(JsonObject row, String fieldName, String value, int maxLength) {
        if (value == null) {
            row.add(fieldName, JsonNull.INSTANCE);
            return;
        }
        row.addProperty(fieldName, truncate(value, maxLength));
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private List<String> searchEmbeddings(String collectionName, String vectorField, float[] queryEmbedding,
                                          int topK, String filter, String outputField) {
        return searchEmbeddingsWithScore(collectionName, vectorField, queryEmbedding, topK, filter, outputField)
                .stream()
                .map(ScoredEntityId::entityId)
                .toList();
    }

    private List<ScoredEntityId> searchEmbeddingsWithScore(String collectionName, String vectorField,
                                                           float[] queryEmbedding, int topK,
                                                           String filter, String outputField) {
        if (milvusClient == null || topK <= 0) {
            return List.of();
        }
        validateEmbedding(queryEmbedding);

        try {
            if (!collectionExists(collectionName)) {
                return List.of();
            }

            SearchReq.SearchReqBuilder builder = SearchReq.builder()
                    .collectionName(collectionName)
                    .annsField(vectorField)
                    .metricType(IndexParam.MetricType.COSINE)
                    .topK(topK)
                    .data(List.of(new FloatVec(queryEmbedding)))
                    .outputFields(List.of(outputField));
            if (hasText(filter)) {
                builder.filter(filter);
            }

            SearchResp response = milvusClient.search(builder.build());
            return extractSearchFieldWithScore(response, outputField);
        } catch (RuntimeException e) {
            log.warn("Milvus vector search failed, collection={}, field={}, topK={}",
                    collectionName, vectorField, topK, e);
            return List.of();
        }
    }

    private List<ScoredEntityId> extractSearchFieldWithScore(SearchResp response, String fieldName) {
        if (response == null || response.getSearchResults() == null || response.getSearchResults().isEmpty()) {
            return List.of();
        }

        List<ScoredEntityId> results = new ArrayList<>();
        for (List<SearchResp.SearchResult> group : response.getSearchResults()) {
            if (group == null) {
                continue;
            }
            for (SearchResp.SearchResult result : group) {
                if (result == null || result.getEntity() == null) {
                    continue;
                }
                Object value = result.getEntity().get(fieldName);
                if (value != null) {
                    results.add(new ScoredEntityId(value.toString(), result.getScore()));
                }
            }
        }
        return results;
    }

    private List<ScoredEntityId> mergeByMaxScore(List<List<ScoredEntityId>> resultLists, int topK) {
        Map<String, Float> scores = new LinkedHashMap<>();
        for (List<ScoredEntityId> results : resultLists) {
            for (ScoredEntityId result : results) {
                if (hasText(result.entityId())) {
                    scores.merge(result.entityId(), result.score(), Math::max);
                }
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue(Comparator.reverseOrder()))
                .filter(entry -> entry.getValue() > MIN_VECTOR_SCORE)
                .limit(topK)
                .map(entry -> new ScoredEntityId(entry.getKey(), entry.getValue()))
                .toList();
    }

    private String buildContentFilter(String platform, String language) {
        List<String> filters = new ArrayList<>();
        if (hasText(platform)) {
            filters.add("platform == \"" + escapeFilterValue(platform) + "\"");
        }
        if (hasText(language)) {
            filters.add("language == \"" + escapeFilterValue(language) + "\"");
        }
        return String.join(" && ", filters);
    }

    private String buildEntityTypeFilter(Collection<String> entityTypes) {
        if (entityTypes == null || entityTypes.isEmpty()) {
            return null;
        }
        List<String> normalized = entityTypes.stream()
                .filter(this::hasText)
                .map(this::normalizeEntityType)
                .filter(this::hasText)
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.size() == 1) {
            return "entity_type == \"" + escapeFilterValue(normalized.get(0)) + "\"";
        }
        return "entity_type in [" + normalized.stream()
                .filter(Objects::nonNull)
                .map(type -> "\"" + escapeFilterValue(type) + "\"")
                .collect(Collectors.joining(", ")) + "]";
    }

    private String escapeFilterValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String buildEntityKeywordText(JsonObject row, String canonicalName, String aliases) {
        StringBuilder text = new StringBuilder();
        appendKeywordText(text, canonicalName);
        appendKeywordText(text, aliases);
        appendKeywordText(text, jsonString(row, "source_id"));
        return text.toString().trim();
    }

    private void appendKeywordText(StringBuilder text, String value) {
        if (hasText(value)) {
            text.append(value.trim()).append('\n');
        }
    }

    private String jsonString(JsonObject row, String field) {
        return row.has(field) && !row.get(field).isJsonNull() ? row.get(field).getAsString() : null;
    }

    private String normalizeEntityType(String entityType) {
        if (!hasText(entityType)) {
            return "Entity";
        }
        return switch (entityType.trim().toLowerCase()) {
            case "person" -> "Person";
            case "organization", "org" -> "Organization";
            case "event" -> "Event";
            case "location" -> "Location";
            case "socialaccount", "social_account", "account" -> "SocialAccount";
            case "mediacontent", "media_content", "content" -> "MediaContent";
            default -> entityType.trim();
        };
    }

    private void validateOptionalEmbedding(float[] embedding) {
        if (embedding != null) {
            validateEmbedding(embedding);
        }
    }

    private void validateEmbedding(float[] embedding) {
        if (embedding == null) {
            throw new IllegalArgumentException("Embedding is null");
        }
        if (embedding.length != EMBEDDING_DIMENSION) {
            throw new IllegalArgumentException(
                    "Embedding dimension mismatch, expected=" + EMBEDDING_DIMENSION + ", actual=" + embedding.length);
        }
    }

    private List<Float> toFloatList(float[] values) {
        List<Float> result = new ArrayList<>(values.length);
        for (float value : values) {
            result.add(value);
        }
        return result;
    }

    public String newVectorId() {
        return UUID.randomUUID().toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

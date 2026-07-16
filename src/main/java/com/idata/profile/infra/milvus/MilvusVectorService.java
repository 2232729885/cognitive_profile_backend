package com.idata.profile.infra.milvus;

import com.google.gson.Gson;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusVectorService {

    private static final int EMBEDDING_DIMENSION = 4096;
    private static final String MEDIA_CONTENT_COLLECTION = "media_content_embeddings";
    private static final String MEDIA_ASSET_COLLECTION = "media_asset_embeddings";
    private static final String ENTITY_COLLECTION = "entity_embeddings";

    private static final String CONTENT_TITLE_VECTOR_FIELD = "title_embedding";
    private static final String CONTENT_TEXT_VECTOR_FIELD = "text_embedding";
    private static final String ASSET_IMAGE_VECTOR_FIELD = "image_embedding";
    private static final String ASSET_OCR_TEXT_VECTOR_FIELD = "ocr_text_embedding";
    private static final String ENTITY_NAME_VECTOR_FIELD = "name_embedding";
    private static final String ENTITY_ALIAS_VECTOR_FIELD = "alias_embedding";
    private static final String ENTITY_DESCRIPTION_VECTOR_FIELD = "description_embedding";
    private static final Gson GSON = new Gson();

    private final MilvusClientV2 milvusClient;

    public record ScoredEntityId(String entityId, float score) {
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
        row.addProperty("id", vectorId);
        row.addProperty("content_id", contentId);
        row.addProperty("platform", platform);
        row.addProperty("language", language);
        row.addProperty("content_type", contentType);
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
        row.addProperty("id", vectorId);
        row.addProperty("asset_id", assetId);
        row.addProperty("source_asset_id", sourceAssetId);
        row.addProperty("content_id", contentId);
        row.addProperty("platform", platform);
        row.addProperty("asset_type", assetType);
        row.addProperty("mime_type", mimeType);
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
        row.addProperty("id", vectorId);
        row.addProperty("entity_id", entityId);
        row.addProperty("entity_type", normalizeEntityType(entityType));
        row.addProperty("canonical_name", canonicalName);
        row.addProperty("aliases", aliases);
        row.addProperty("source_id", sourceId);
        row.addProperty("platform", platform);
        addVector(row, ENTITY_NAME_VECTOR_FIELD, nameEmbedding);
        addVector(row, ENTITY_ALIAS_VECTOR_FIELD, aliasEmbedding);
        addVector(row, ENTITY_DESCRIPTION_VECTOR_FIELD, descriptionEmbedding);
        insert(ENTITY_COLLECTION, row);
        return vectorId;
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

    public void ensureAllCollections() {
        if (milvusClient == null) {
            log.info("Milvus client not configured, skip collection initialization");
            return;
        }
        ensure("media content", this::ensureMediaContentCollection);
        ensure("media asset", this::ensureMediaAssetCollection);
        ensure("entity", this::ensureEntityCollection);
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
                .addField(vectorField(CONTENT_TITLE_VECTOR_FIELD, true))
                .addField(vectorField(CONTENT_TEXT_VECTOR_FIELD, true));
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
                .addField(vectorField(ASSET_IMAGE_VECTOR_FIELD, true))
                .addField(vectorField(ASSET_OCR_TEXT_VECTOR_FIELD, true));
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
                .addField(vectorField(ENTITY_NAME_VECTOR_FIELD, true))
                .addField(vectorField(ENTITY_ALIAS_VECTOR_FIELD, true))
                .addField(vectorField(ENTITY_DESCRIPTION_VECTOR_FIELD, true));
        createCollection(ENTITY_COLLECTION, schema, List.of(
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

    private AddFieldReq vectorField(String name, boolean nullable) {
        return AddFieldReq.builder()
                .fieldName(name)
                .dataType(DataType.FloatVector)
                .dimension(EMBEDDING_DIMENSION)
                .isNullable(nullable)
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
            row.add(fieldName, JsonNull.INSTANCE);
            return;
        }
        row.add(fieldName, GSON.toJsonTree(toFloatList(embedding)));
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

    private String escapeFilterValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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

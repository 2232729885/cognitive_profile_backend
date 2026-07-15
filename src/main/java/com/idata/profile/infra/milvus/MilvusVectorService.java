package com.idata.profile.infra.milvus;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
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
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusVectorService {

    private static final int EMBEDDING_DIMENSION = 4096;
    private static final String TEXT_COLLECTION = "text_embeddings";
    private static final String IMAGE_COLLECTION = "image_embeddings";
    private static final String ENTITY_COLLECTION = "entity_embeddings";
    private static final String ACCOUNT_COLLECTION = "account_embeddings";
    private static final String VECTOR_FIELD = "embedding";
    private static final Gson GSON = new Gson();

    private final MilvusClientV2 milvusClient;

    public record ScoredEntityId(String entityId, float score) {
    }

    public String insertTextEmbedding(String sourceId, String sourceType,
                                      String platform, String language,
                                      long publishedAt, float importance,
                                      float[] embedding) {
        String vectorId = "text_" + sourceId;
        JsonObject row = baseRow(vectorId, sourceId, sourceType, platform, embedding);
        row.addProperty("language", language);
        row.addProperty("published_at", publishedAt);
        row.addProperty("importance", importance);
        insert(TEXT_COLLECTION, row);
        return vectorId;
    }

    public String insertImageEmbedding(String assetId, String contentId,
                                       String platform, float aigcScore,
                                       float[] embedding) {
        String vectorId = "image_" + assetId;
        JsonObject row = baseRow(vectorId, assetId, "media_asset", platform, embedding);
        row.addProperty("content_id", contentId);
        row.addProperty("aigc_score", aigcScore);
        insert(IMAGE_COLLECTION, row);
        return vectorId;
    }

    /**
     * 写入实体向量。
     *
     * @param entityId       stableUuid（如 "3f8a2b1c-..."）
     * @param entityType     "person"/"organization"/"event"/"location"/"narrative"
     * @param normalizedName normalizedName 的向量
     * @param embedding      normalizedName 的向量
     */
    public String insertEntityEmbedding(String entityId, String entityType,
                                        String normalizedName, float[] embedding) {
        String vectorId = "entity_" + entityId;
        JsonObject row = baseRow(vectorId, entityId, entityType, null, embedding);
        row.addProperty("normalized_name", normalizedName);
        row.addProperty("entity_type", entityType);
        insert(ENTITY_COLLECTION, row);
        return vectorId;
    }

    /**
     * @param accountId 账号UUID（PG social_accounts.id）
     * @param platform  平台
     * @param embedding 账号bio文本的向量
     */
    public String insertAccountEmbedding(String accountId, String platform, float[] embedding) {
        String vectorId = "account_" + accountId;
        JsonObject row = baseRow(vectorId, accountId, "social_account", platform, embedding);
        insert(ACCOUNT_COLLECTION, row);
        return vectorId;
    }

    /**
     * 批量删除实体向量，EntityDeduplicationJob 合并实体后清理被合并掉的旧实体向量。
     */
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
                    .filter("source_id in [" + idList + "]")
                    .build());
        } catch (RuntimeException e) {
            log.warn("Failed to delete entity embeddings from Milvus, entityIds={}", entityIds, e);
        }
    }

    /**
     * 文本向量检索，返回最相似的 topK 个 source_id（对应 media_contents.id）。
     * 支持按 platform 和 language 过滤，传 null 表示不过滤。
     */
    public List<String> searchTextEmbeddings(float[] queryEmbedding, int topK,
                                             String platform, String language) {
        return searchEmbeddings(TEXT_COLLECTION, queryEmbedding, topK,
                buildFilter(platform, language), "source_id");
    }

    /**
     * 图像向量检索，返回最相似的 topK 个 asset_id（对应 media_assets.id）
     */
    public List<String> searchImageEmbeddings(float[] queryEmbedding, int topK) {
        return searchEmbeddings(IMAGE_COLLECTION, queryEmbedding, topK, null, "source_id");
    }

    /**
     * 实体向量检索，返回最相似的 topK 个 entity_id。
     */
    public List<ScoredEntityId> searchEntityEmbeddings(float[] queryEmbedding, int topK,
                                                       String entityType) {
        String filter = hasText(entityType)
                ? "entity_type == \"" + escapeFilterValue(entityType) + "\""
                : null;
        List<ScoredEntityId> raw = searchEmbeddingsWithScore(
                ENTITY_COLLECTION, queryEmbedding, topK, filter, "source_id");
        return raw.stream()
                .map(item -> new ScoredEntityId(
                        item.entityId().startsWith("entity_")
                                ? item.entityId().substring("entity_".length())
                                : item.entityId(),
                        item.score()))
                .toList();
    }

    public List<ScoredEntityId> searchAccountEmbeddings(float[] queryEmbedding, int topK) {
        List<ScoredEntityId> raw = searchEmbeddingsWithScore(
                ACCOUNT_COLLECTION, queryEmbedding, topK, null, "source_id");
        return raw.stream()
                .map(item -> new ScoredEntityId(
                        item.entityId().startsWith("account_")
                                ? item.entityId().substring("account_".length())
                                : item.entityId(),
                        item.score()))
                .toList();
    }

    private JsonObject baseRow(String id, String sourceId, String sourceType, String platform, float[] embedding) {
        validateEmbedding(embedding);
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("source_id", sourceId);
        row.addProperty("source_type", sourceType);
        row.addProperty("platform", platform);
        row.add(VECTOR_FIELD, GSON.toJsonTree(toFloatList(embedding)));
        return row;
    }

    private void insert(String collectionName, JsonObject row) {
        ensureCollection(collectionName);
        io.milvus.v2.service.vector.response.UpsertResp response = milvusClient.upsert(UpsertReq.builder()
                .collectionName(collectionName)
                .data(List.of(row))
                .build());
        log.debug("Upserted Milvus vector, collection={}, count={}",
                collectionName, response.getUpsertCnt());
    }

    /**
     * 应用启动时预建所有已知的 collection，不用等第一条真实数据写入才触发。
     * milvusClient 为 null（未配置Milvus）时直接跳过；每个collection独立try-catch，
     * 某一个建失败不影响其他几个继续尝试。
     */
    public void ensureAllCollections() {
        if (milvusClient == null) {
            log.info("Milvus client not configured, skip collection initialization");
            return;
        }
        for (String collectionName : List.of(TEXT_COLLECTION, IMAGE_COLLECTION, ENTITY_COLLECTION, ACCOUNT_COLLECTION)) {
            try {
                ensureCollection(collectionName);
                log.info("Milvus collection ensured: {}", collectionName);
            } catch (Exception e) {
                log.error("Failed to ensure Milvus collection: {}", collectionName, e);
            }
        }
    }

    private void ensureCollection(String collectionName) {
        Boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        milvusClient.createCollection(CreateCollectionReq.builder()
                .collectionName(collectionName)
                .description(collectionName + " for cognitive profile backend")
                .primaryFieldName("id")
                .idType(DataType.VarChar)
                .maxLength(128)
                .vectorFieldName(VECTOR_FIELD)
                .dimension(EMBEDDING_DIMENSION)
                .metricType(IndexParam.MetricType.COSINE.name())
                .autoID(false)
                .enableDynamicField(true)
                .build());
        milvusClient.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .build());
    }

    private List<String> searchEmbeddings(String collectionName, float[] queryEmbedding, int topK,
                                          String filter, String outputField) {
        if (milvusClient == null || topK <= 0) {
            return List.of();
        }
        validateEmbedding(queryEmbedding);

        try {
            Boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            if (!Boolean.TRUE.equals(exists)) {
                return List.of();
            }

            SearchReq.SearchReqBuilder builder = SearchReq.builder()
                    .collectionName(collectionName)
                    .annsField(VECTOR_FIELD)
                    .metricType(IndexParam.MetricType.COSINE)
                    .topK(topK)
                    .data(List.of(new FloatVec(queryEmbedding)))
                    .outputFields(List.of(outputField));
            if (hasText(filter)) {
                builder.filter(filter);
            }

            SearchResp response = milvusClient.search(builder.build());
            return extractSearchField(response, outputField);
        } catch (RuntimeException e) {
            log.warn("Milvus vector search failed, collection={}, topK={}", collectionName, topK, e);
            return List.of();
        }
    }

    private List<ScoredEntityId> searchEmbeddingsWithScore(String collectionName, float[] queryEmbedding, int topK,
                                                           String filter, String outputField) {
        if (milvusClient == null || topK <= 0) {
            return List.of();
        }
        validateEmbedding(queryEmbedding);

        try {
            Boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            if (!Boolean.TRUE.equals(exists)) {
                return List.of();
            }

            SearchReq.SearchReqBuilder builder = SearchReq.builder()
                    .collectionName(collectionName)
                    .annsField(VECTOR_FIELD)
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
            log.warn("Milvus vector search failed, collection={}, topK={}", collectionName, topK, e);
            return List.of();
        }
    }

    private List<String> extractSearchField(SearchResp response, String fieldName) {
        if (response == null || response.getSearchResults() == null || response.getSearchResults().isEmpty()) {
            return List.of();
        }

        List<String> ids = new ArrayList<>();
        for (List<SearchResp.SearchResult> results : response.getSearchResults()) {
            if (results == null) {
                continue;
            }
            for (SearchResp.SearchResult result : results) {
                if (result == null || result.getEntity() == null) {
                    continue;
                }
                Object value = result.getEntity().get(fieldName);
                if (value != null) {
                    ids.add(value.toString());
                }
            }
        }
        return ids;
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

    private String buildFilter(String platform, String language) {
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

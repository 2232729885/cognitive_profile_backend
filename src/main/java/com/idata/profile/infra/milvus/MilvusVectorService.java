package com.idata.profile.infra.milvus;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
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
    private static final String VECTOR_FIELD = "embedding";
    private static final Gson GSON = new Gson();

    private final MilvusClientV2 milvusClient;

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
        InsertResp response = milvusClient.insert(InsertReq.builder()
                .collectionName(collectionName)
                .data(List.of(row))
                .build());
        log.debug("Inserted Milvus vector, collection={}, count={}, primaryKeys={}",
                collectionName, response.getInsertCnt(), response.getPrimaryKeys());
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
}

package com.idata.profile.config;

import com.idata.profile.infra.elasticsearch.MediaContentEsService;
import com.idata.profile.infra.elasticsearch.MediaAssetEsService;
import com.idata.profile.infra.milvus.MilvusVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.UUID;

@Slf4j
@Configuration
@Profile("mock-infra")
public class MockPipelineInfraConfig {

    @Bean
    @Primary
    public MilvusVectorService mockMilvusVectorService() {
        return new MilvusVectorService(null) {
            @Override
            public String insertTextEmbedding(String sourceId, String sourceType,
                                              String platform, String language,
                                              long publishedAt, float importance,
                                              float[] embedding) {
                String vectorId = "mock_text_vector_" + UUID.randomUUID();
                log.info("Mock Milvus text embedding inserted, sourceId={}, vectorId={}", sourceId, vectorId);
                return vectorId;
            }

            @Override
            public String insertMediaContentEmbedding(String contentId, String platform, String language,
                                                      String contentType, long publishedAt,
                                                      float[] titleEmbedding, float[] textEmbedding) {
                return insertMediaContentEmbedding(contentId, platform, language, contentType, publishedAt,
                        titleEmbedding, null, textEmbedding);
            }

            @Override
            public String insertMediaContentEmbedding(String contentId, String platform, String language,
                                                      String contentType, long publishedAt,
                                                      float[] titleEmbedding, float[] summaryEmbedding,
                                                      float[] bodyEmbedding) {
                String vectorId = "mock_media_content_vector_" + UUID.randomUUID();
                log.info("Mock Milvus media content embeddings inserted, contentId={}, vectorId={}",
                        contentId, vectorId);
                return vectorId;
            }

            @Override
            public String insertMediaContentPivotEmbedding(String contentId, String platform, String language,
                                                           String contentType, long publishedAt,
                                                           float[] titleEmbedding, float[] summaryEmbedding,
                                                           float[] bodyEmbedding) {
                String vectorId = "mock_media_content_pivot_vector_" + UUID.randomUUID();
                log.info("Mock Milvus media content pivot embeddings inserted, contentId={}, vectorId={}",
                        contentId, vectorId);
                return vectorId;
            }

            @Override
            public String upsertMediaAssetEmbedding(String assetId, String sourceAssetId, String contentId,
                                                    String platform, String assetType, String mimeType,
                                                    float[] imageEmbedding, float[] ocrTextEmbedding) {
                return upsertMediaAssetEmbedding(assetId, sourceAssetId, contentId, platform, assetType, mimeType,
                        null, null, imageEmbedding, ocrTextEmbedding, null, null);
            }

            @Override
            public String upsertMediaAssetEmbedding(String assetId, String sourceAssetId, String contentId,
                                                    String platform, String mediaType, String mimeType,
                                                    Float segmentStart, Float segmentEnd,
                                                    float[] visualEmbedding, float[] ocrEmbedding,
                                                    float[] asrEmbedding, float[] captionEmbedding) {
                String vectorId = "mock_media_asset_vector_" + UUID.randomUUID();
                log.info("Mock Milvus media asset embeddings upserted, assetId={}, vectorId={}",
                        assetId, vectorId);
                return vectorId;
            }

            @Override
            public String upsertMediaAssetPivotEmbedding(String assetId, String segmentId,
                                                         String sourceAssetId, String contentId,
                                                         String platform, String mediaType, String mimeType,
                                                         Float segmentStart, Float segmentEnd,
                                                         float[] ocrEmbedding,
                                                         float[] asrEmbedding, float[] captionEmbedding) {
                String vectorId = "mock_media_asset_pivot_vector_" + UUID.randomUUID();
                log.info("Mock Milvus media asset pivot embeddings upserted, assetId={}, segmentId={}, vectorId={}",
                        assetId, segmentId, vectorId);
                return vectorId;
            }

            @Override
            public String upsertEntityEmbedding(String entityId, String entityType, String canonicalName,
                                                String aliases, String sourceId, String platform,
                                                float[] nameEmbedding, float[] aliasEmbedding,
                                                float[] descriptionEmbedding) {
                String vectorId = "mock_entity_vector_" + UUID.randomUUID();
                log.info("Mock Milvus entity embeddings upserted, entityId={}, entityType={}, vectorId={}",
                        entityId, entityType, vectorId);
                return vectorId;
            }

            @Override
            public String insertImageEmbedding(String assetId, String contentId,
                                               String platform, float aigcScore,
                                               float[] embedding) {
                String vectorId = "mock_image_vector_" + UUID.randomUUID();
                log.info("Mock Milvus image embedding inserted, assetId={}, vectorId={}", assetId, vectorId);
                return vectorId;
            }

            @Override
            public String insertImageEmbedding(String assetId, String sourceAssetId, String contentId,
                                               String platform, float aigcScore,
                                               float[] embedding) {
                String vectorId = "mock_image_vector_" + UUID.randomUUID();
                log.info("Mock Milvus image embedding inserted, assetId={}, sourceAssetId={}, contentId={}, platform={}, vectorId={}",
                        assetId, sourceAssetId, contentId, platform, vectorId);
                return vectorId;
            }

            @Override
            public String insertImageOcrEmbedding(String assetId, String contentId,
                                                  String platform, float[] embedding) {
                String vectorId = "mock_image_ocr_vector_" + UUID.randomUUID();
                log.info("Mock Milvus image OCR embedding inserted, assetId={}, vectorId={}", assetId, vectorId);
                return vectorId;
            }

            @Override
            public String insertImageOcrEmbedding(String assetId, String sourceAssetId, String contentId,
                                                  String platform, float[] embedding) {
                String vectorId = "mock_image_ocr_vector_" + UUID.randomUUID();
                log.info("Mock Milvus image OCR embedding inserted, assetId={}, sourceAssetId={}, contentId={}, platform={}, vectorId={}",
                        assetId, sourceAssetId, contentId, platform, vectorId);
                return vectorId;
            }
        };
    }

    @Bean
    @Primary
    public MediaContentEsService mockMediaContentEsService() {
        return new MediaContentEsService(null) {
            @Override
            public void index(String contentId, Object document) {
                log.info("Mock Elasticsearch indexed media content, contentId={}", contentId);
            }
        };
    }

    @Bean
    @Primary
    public MediaAssetEsService mockMediaAssetEsService() {
        return new MediaAssetEsService(null) {
            @Override
            public void indexImageAsset(com.idata.profile.entity.content.MediaAsset asset) {
                indexAsset(asset);
            }

            @Override
            public void indexAsset(com.idata.profile.entity.content.MediaAsset asset) {
                log.info("Mock Elasticsearch indexed image asset, assetId={}",
                        asset != null ? asset.getId() : null);
            }
        };
    }
}

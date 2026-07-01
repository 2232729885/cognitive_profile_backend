package com.idata.profile.config;

import com.idata.profile.infra.elasticsearch.MediaContentEsService;
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
            public String insertImageEmbedding(String assetId, String contentId,
                                               String platform, float aigcScore,
                                               float[] embedding) {
                String vectorId = "mock_image_vector_" + UUID.randomUUID();
                log.info("Mock Milvus image embedding inserted, assetId={}, vectorId={}", assetId, vectorId);
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
}

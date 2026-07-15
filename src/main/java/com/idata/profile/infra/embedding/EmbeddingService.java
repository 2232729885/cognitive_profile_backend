package com.idata.profile.infra.embedding;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final RestClient embeddingRestClient = RestClient.create();

    @Value("${llm.embedding.base-url}")
    private String embeddingBaseUrl;

    @Value("${llm.embedding.api-key}")
    private String embeddingApiKey;

    @Value("${llm.embedding.model}")
    private String embeddingModel;

    public float[] generateTextEmbedding(String text) {
        return generateEmbedding(text, "text");
    }

    public float[] generateImageEmbedding(String imageUrl) {
        return generateEmbedding(imageUrl, "image");
    }

    private float[] generateEmbedding(String input, String inputType) {
        if (!hasText(input)) {
            return null;
        }
        try {
            EmbeddingApiResponse apiResponse = embeddingRestClient.post()
                    .uri(normalizeBaseUrl(embeddingBaseUrl) + "/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + embeddingApiKey)
                    .body(Map.of("model", embeddingModel, "input", input))
                    .retrieve()
                    .body(EmbeddingApiResponse.class);

            if (apiResponse == null || apiResponse.getData() == null || apiResponse.getData().isEmpty()
                    || apiResponse.getData().get(0).getEmbedding() == null) {
                return null;
            }

            List<Double> doubles = apiResponse.getData().get(0).getEmbedding();
            float[] embedding = new float[doubles.size()];
            for (int i = 0; i < doubles.size(); i++) {
                embedding[i] = doubles.get(i).floatValue();
            }
            return embedding;
        } catch (Exception e) {
            log.warn("[EmbeddingService] {} embedding failed", inputType, e);
            return null;
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @Data
    private static class EmbeddingApiResponse {
        private List<EmbeddingData> data;
    }

    @Data
    private static class EmbeddingData {
        private List<Double> embedding;
    }
}

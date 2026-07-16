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
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private static final Semaphore IMAGE_EMBEDDING_SEMAPHORE = new Semaphore(1);
    private static final Semaphore TEXT_EMBEDDING_SEMAPHORE = new Semaphore(2);

    private final RestClient embeddingRestClient = RestClient.create();

    @Value("${llm.embedding.base-url}")
    private String embeddingBaseUrl;

    @Value("${llm.embedding.api-key}")
    private String embeddingApiKey;

    @Value("${llm.embedding.model}")
    private String embeddingModel;

    public float[] generateTextEmbedding(String text) {
        if (!hasText(text)) {
            return null;
        }
        boolean acquired = false;
        try {
            TEXT_EMBEDDING_SEMAPHORE.acquire();
            acquired = true;
            return generateEmbedding(Map.of("model", embeddingModel, "input", text), "text");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[EmbeddingService] text embedding interrupted", e);
            return null;
        } finally {
            if (acquired) {
                TEXT_EMBEDDING_SEMAPHORE.release();
            }
        }
    }

    public float[] generateImageEmbedding(String imageUrl) {
        if (!hasText(imageUrl)) {
            return null;
        }
        boolean acquired = false;
        try {
            IMAGE_EMBEDDING_SEMAPHORE.acquire();
            acquired = true;
            Map<String, Object> imageContent = Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", imageUrl));
            Map<String, Object> userMessage = Map.of(
                    "role", "user",
                    "content", List.of(imageContent));
            return generateEmbedding(Map.of(
                    "model", embeddingModel,
                    "messages", List.of(userMessage)), "image", imageUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[EmbeddingService] image embedding interrupted, imageUrl={}", imageUrl, e);
            return null;
        } finally {
            if (acquired) {
                IMAGE_EMBEDDING_SEMAPHORE.release();
            }
        }
    }

    private float[] generateEmbedding(Map<String, Object> requestBody, String inputType) {
        return generateEmbedding(requestBody, inputType, null);
    }

    private float[] generateEmbedding(Map<String, Object> requestBody, String inputType, String source) {
        try {
            EmbeddingApiResponse apiResponse = embeddingRestClient.post()
                    .uri(normalizeBaseUrl(embeddingBaseUrl) + "/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + embeddingApiKey)
                    .body(requestBody)
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
            if (hasText(source)) {
                log.warn("[EmbeddingService] {} embedding failed, source={}", inputType, source, e);
            } else {
                log.warn("[EmbeddingService] {} embedding failed", inputType, e);
            }
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

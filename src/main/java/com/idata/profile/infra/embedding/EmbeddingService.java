package com.idata.profile.infra.embedding;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    @Value("${llm.embedding.base-url}")
    private String embeddingBaseUrl;

    @Value("${llm.embedding.api-key}")
    private String embeddingApiKey;

    @Value("${llm.embedding.model}")
    private String embeddingModel;

    @Value("${llm.embedding.text-concurrency:1}")
    private int textConcurrency;

    @Value("${llm.embedding.image-concurrency:1}")
    private int imageConcurrency;

    @Value("${llm.embedding.timeout-seconds:60}")
    private int timeoutSeconds;

    private Semaphore textSemaphore;
    private Semaphore imageSemaphore;

    public float[] generateTextEmbedding(String text) {
        if (!hasText(text)) {
            return null;
        }
        boolean acquired = false;
        try {
            acquired = textSemaphore().tryAcquire(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[EmbeddingService] text embedding skipped because route is busy");
                return null;
            }
            return generateEmbedding(Map.of("model", embeddingModel, "input", text), "text");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[EmbeddingService] text embedding interrupted");
            return null;
        } finally {
            if (acquired) {
                textSemaphore().release();
            }
        }
    }

    public float[] generateImageEmbedding(String imageUrl) {
        if (!hasText(imageUrl)) {
            return null;
        }
        boolean acquired = false;
        try {
            acquired = imageSemaphore().tryAcquire(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[EmbeddingService] image embedding skipped because route is busy, imageUrl={}", imageUrl);
                return null;
            }
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
            log.warn("[EmbeddingService] image embedding interrupted, imageUrl={}", imageUrl);
            return null;
        } finally {
            if (acquired) {
                imageSemaphore().release();
            }
        }
    }

    private float[] generateEmbedding(Map<String, Object> requestBody, String inputType) {
        return generateEmbedding(requestBody, inputType, null);
    }

    private float[] generateEmbedding(Map<String, Object> requestBody, String inputType, String source) {
        try {
            EmbeddingApiResponse apiResponse = restClient().post()
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
                log.warn("[EmbeddingService] {} embedding failed, source={}, reason={}",
                        inputType, source, rootMessage(e));
            } else {
                log.warn("[EmbeddingService] {} embedding failed, reason={}", inputType, rootMessage(e));
            }
            return null;
        }
    }

    private RestClient restClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    private synchronized Semaphore textSemaphore() {
        if (textSemaphore == null) {
            textSemaphore = new Semaphore(Math.max(1, textConcurrency));
        }
        return textSemaphore;
    }

    private synchronized Semaphore imageSemaphore() {
        if (imageSemaphore == null) {
            imageSemaphore = new Semaphore(Math.max(1, imageConcurrency));
        }
        return imageSemaphore;
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

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
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

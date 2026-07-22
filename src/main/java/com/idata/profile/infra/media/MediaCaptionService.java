package com.idata.profile.infra.media;

import com.idata.profile.common.util.TextEncodingRepairUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MediaCaptionService {

    private static final String SYSTEM_PROMPT = """
            You describe media for search indexing. Return concise factual text only.
            Mention visible entities, scene, objects, on-screen text, location hints, and actions.
            Do not add Markdown.
            """;
    private static final String USER_PROMPT = """
            Describe this image/key frame for retrieval. Include visible text if any.
            Output one concise paragraph.
            """;

    private Semaphore captionSemaphore;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.model:Qwen3-VL-32B-Instruct}")
    private String model;

    @Value("${media.caption.enabled:true}")
    private boolean enabled;

    @Value("${media.caption.timeout-seconds:45}")
    private int timeoutSeconds;

    @Value("${media.caption.queue-timeout-seconds:5}")
    private int queueTimeoutSeconds;

    @Value("${media.caption.concurrency:1}")
    private int concurrency;

    @Value("${media.caption.max-tokens:256}")
    private int maxTokens;

    public String describeImageUrl(String imageUrl) {
        if (!enabled || !hasText(imageUrl)) {
            return null;
        }
        return describeImageReference(imageUrl, imageUrl);
    }

    public String describeImageFile(Path imageFile) {
        if (!enabled || imageFile == null || !Files.isRegularFile(imageFile)) {
            return null;
        }
        try {
            String dataUrl = "data:image/jpeg;base64,"
                    + Base64.getEncoder().encodeToString(Files.readAllBytes(imageFile));
            return describeImageReference(dataUrl, imageFile.toString());
        } catch (IOException e) {
            log.warn("[MediaCaptionService] failed to read image file for caption, file={}", imageFile, e);
            return null;
        }
    }

    private String describeImageReference(String imageReference, String logSource) {
        boolean acquired = false;
        try {
            acquired = captionSemaphore().tryAcquire(Math.max(1, queueTimeoutSeconds), TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[MediaCaptionService] caption skipped because model route is busy, source={}, concurrency={}, queueTimeoutSeconds={}",
                        logSource, Math.max(1, concurrency), Math.max(1, queueTimeoutSeconds));
                return null;
            }
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "temperature", 0,
                    "max_tokens", Math.max(64, maxTokens),
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of(
                                    "role", "user",
                                    "content", List.of(
                                            Map.of("type", "text", "text", USER_PROMPT),
                                            Map.of(
                                                    "type", "image_url",
                                                    "image_url", Map.of("url", imageReference))))));

            ChatCompletionResponse response = restClient().post()
                    .uri(normalizeBaseUrl(baseUrl) + "/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(requestBody)
                    .retrieve()
                    .body(ChatCompletionResponse.class);

            String text = response == null || response.getChoices() == null || response.getChoices().isEmpty()
                    || response.getChoices().getFirst().getMessage() == null
                    ? null
                    : response.getChoices().getFirst().getMessage().getContent();
            return sanitize(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[MediaCaptionService] caption interrupted, source={}", logSource, e);
            return null;
        } catch (Exception e) {
            log.warn("[MediaCaptionService] caption failed, source={}, reason={}",
                    logSource, rootMessage(e));
            return null;
        } finally {
            if (acquired) {
                captionSemaphore().release();
            }
        }
    }

    private synchronized Semaphore captionSemaphore() {
        int permits = Math.max(1, concurrency);
        if (captionSemaphore == null) {
            captionSemaphore = new Semaphore(permits);
        }
        return captionSemaphore;
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

    private String sanitize(String text) {
        if (text == null) {
            return null;
        }
        String result = text.trim();
        if ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("'") && result.endsWith("'"))) {
            result = result.substring(1, result.length() - 1).trim();
        }
        if (result.isBlank() || "null".equalsIgnoreCase(result)) {
            return null;
        }
        return TextEncodingRepairUtil.repairLikelyUtf8Mojibake(result);
    }

    private String normalizeBaseUrl(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
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
    private static class ChatCompletionResponse {
        private List<Choice> choices;
    }

    @Data
    private static class Choice {
        private Message message;
    }

    @Data
    private static class Message {
        private String content;
    }
}

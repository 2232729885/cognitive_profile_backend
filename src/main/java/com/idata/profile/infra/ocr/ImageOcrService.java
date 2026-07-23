package com.idata.profile.infra.ocr;

import com.idata.profile.common.util.TextEncodingRepairUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ImageOcrService {

    private static final String SYSTEM_PROMPT = """
            You are an OCR engine. Return only text visibly printed in the image.
            Do not summarize, explain, infer, translate, or add Markdown.
            """;
    private static final String USER_PROMPT = """
            OCR the image. Output only visible text, preserving line breaks as much as possible.
            If no text is visible, output an empty string.
            """;

    private Semaphore ocrSemaphore;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.model:Qwen3-VL-32B-Instruct}")
    private String model;

    @Value("${media.ocr.concurrency:4}")
    private int concurrency;

    @Value("${media.ocr.timeout-seconds:180}")
    private int timeoutSeconds;

    @Value("${media.ocr.max-tokens:1024}")
    private int maxTokens;

    public String extractText(String imageUrl) {
        if (!hasText(imageUrl)) {
            return null;
        }
        boolean acquired = false;
        try {
            acquired = ocrSemaphore().tryAcquire(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[ImageOcrService] OCR skipped because model route is busy, imageUrl={}", imageUrl);
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
                                                    "image_url", Map.of("url", imageUrl))))));

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
            log.warn("[ImageOcrService] OCR interrupted, imageUrl={}", imageUrl);
            return null;
        } catch (Exception e) {
            log.warn("[ImageOcrService] OCR failed, imageUrl={}, reason={}", imageUrl, rootMessage(e));
            return null;
        } finally {
            if (acquired) {
                ocrSemaphore().release();
            }
        }
    }

    public String extractTextFromImageFile(Path imageFile) {
        if (imageFile == null || !Files.isRegularFile(imageFile)) {
            return null;
        }
        try {
            String dataUrl = "data:image/jpeg;base64,"
                    + Base64.getEncoder().encodeToString(Files.readAllBytes(imageFile));
            return extractText(dataUrl);
        } catch (Exception e) {
            log.warn("[ImageOcrService] failed to read image file for OCR, file={}, reason={}",
                    imageFile, rootMessage(e));
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

    private synchronized Semaphore ocrSemaphore() {
        if (ocrSemaphore == null) {
            ocrSemaphore = new Semaphore(Math.max(1, concurrency));
        }
        return ocrSemaphore;
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
        if ("null".equalsIgnoreCase(result)
                || "no visible text".equalsIgnoreCase(result)
                || "no text visible".equalsIgnoreCase(result)) {
            return "";
        }
        return TextEncodingRepairUtil.repairLikelyUtf8Mojibake(result);
    }

    private String normalizeBaseUrl(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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

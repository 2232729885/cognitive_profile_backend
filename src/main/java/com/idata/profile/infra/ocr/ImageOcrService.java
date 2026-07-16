package com.idata.profile.infra.ocr;

import lombok.Data;
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
public class ImageOcrService {

    private static final String SYSTEM_PROMPT = """
            You are an OCR engine. Return only text visibly printed in the image.
            Do not summarize, explain, infer, translate, or add Markdown.
            """;
    private static final String USER_PROMPT = """
            OCR the image. Output only visible text, preserving line breaks as much as possible.
            If no text is visible, output an empty string.
            """;

    private final RestClient restClient = RestClient.create();

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.model:Qwen3-VL-32B-Instruct}")
    private String model;

    public String extractText(String imageUrl) {
        if (!hasText(imageUrl)) {
            return null;
        }
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "temperature", 0,
                    "max_tokens", 1024,
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of(
                                    "role", "user",
                                    "content", List.of(
                                            Map.of("type", "text", "text", USER_PROMPT),
                                            Map.of(
                                                    "type", "image_url",
                                                    "image_url", Map.of("url", imageUrl))))));

            ChatCompletionResponse response = restClient.post()
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
        } catch (Exception e) {
            log.warn("[ImageOcrService] OCR failed, imageUrl={}", imageUrl, e);
            return null;
        }
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
        return result;
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

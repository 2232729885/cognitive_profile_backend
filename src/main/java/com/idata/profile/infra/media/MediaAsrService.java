package com.idata.profile.infra.media;

import com.idata.profile.common.util.TextEncodingRepairUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@Slf4j
@Service
public class MediaAsrService {

    @Value("${llm.asr.base-url:http://172.16.40.232:18014/v1}")
    private String baseUrl;

    @Value("${llm.asr.api-key:idata@123456}")
    private String apiKey;

    @Value("${llm.asr.model:Qwen3-ASR-1.7B}")
    private String model;

    @Value("${llm.asr.timeout-seconds:180}")
    private int timeoutSeconds;

    public String transcribe(Path audioFile) {
        if (audioFile == null || !Files.isRegularFile(audioFile)) {
            return null;
        }
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("model", model);
            body.add("file", new FileSystemResource(audioFile));

            JsonNode response = restClient().post()
                    .uri(normalizeBaseUrl(baseUrl) + "/audio/transcriptions")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            String errorMessage = extractErrorMessage(response);
            if (errorMessage != null) {
                log.warn("[MediaAsrService] ASR returned error body, audioFile={}, error={}",
                        audioFile, errorMessage);
                return null;
            }
            String text = extractText(response);
            String result = sanitize(text);
            if (result == null) {
                log.warn("[MediaAsrService] ASR returned empty text, audioFile={}, response={}",
                        audioFile, response);
            }
            return result;
        } catch (Exception e) {
            log.warn("[MediaAsrService] ASR failed, audioFile={}, reason={}", audioFile, rootMessage(e));
            return null;
        }
    }

    private String extractErrorMessage(JsonNode response) {
        if (response == null || response.isNull()) {
            return null;
        }
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String message = text(error.path("message"));
            return message != null ? message : error.toString();
        }
        String code = text(response.path("code"));
        if (code != null && !"0".equals(code) && !"200".equals(code)) {
            return firstText(text(response.path("message")), text(response.path("msg")), response.toString());
        }
        return null;
    }

    private String extractText(JsonNode response) {
        if (response == null || response.isNull()) {
            return null;
        }
        String text = text(response.path("text"));
        if (text != null) {
            return text;
        }
        text = text(response.path("transcription"));
        if (text != null) {
            return text;
        }
        text = text(response.path("result"));
        if (text != null) {
            return text;
        }
        JsonNode data = response.path("data");
        text = text(data.path("text"));
        if (text != null) {
            return text;
        }
        text = text(data.path("transcription"));
        if (text != null) {
            return text;
        }
        text = text(data.path("result"));
        if (text != null) {
            return text;
        }
        JsonNode choices = response.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode first = choices.get(0);
            text = text(first.path("text"));
            if (text != null) {
                return text;
            }
            return text(first.path("message").path("content"));
        }
        return null;
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
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

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}

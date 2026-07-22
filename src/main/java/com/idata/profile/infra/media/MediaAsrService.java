package com.idata.profile.infra.media;

import com.idata.profile.common.util.TextEncodingRepairUtil;
import lombok.Data;
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

            AsrResponse response = restClient().post()
                    .uri(normalizeBaseUrl(baseUrl) + "/audio/transcriptions")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(AsrResponse.class);
            return sanitize(response == null ? null : response.getText());
        } catch (Exception e) {
            log.warn("[MediaAsrService] ASR failed, audioFile={}, reason={}", audioFile, rootMessage(e));
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

    @Data
    private static class AsrResponse {
        private String text;
    }
}

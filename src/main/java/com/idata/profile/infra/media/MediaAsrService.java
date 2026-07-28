package com.idata.profile.infra.media;

import com.idata.profile.common.util.TextEncodingRepairUtil;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class MediaAsrService {

    /**
     * ASR 服务端当前按单并发运行。使用独立的有界单线程队列，避免多个
     * pipeline 线程直接同时访问 ASR，或在 Semaphore 上超时后丢弃任务。
     */
    private ThreadPoolExecutor asrExecutor;

    @Value("${llm.asr.base-url:http://172.16.40.232:18014/v1}")
    private String baseUrl;

    @Value("${llm.asr.api-key:idata@123456}")
    private String apiKey;

    @Value("${llm.asr.model:Qwen3-ASR-1.7B}")
    private String model;

    @Value("${llm.asr.timeout-seconds:180}")
    private int timeoutSeconds;

    @Value("${llm.asr.concurrency:1}")
    private int concurrency;

    @Value("${llm.asr.queue-capacity:16}")
    private int queueCapacity;

    @Value("${llm.asr.max-retries:0}")
    private int maxRetries;

    @Value("${llm.asr.retry-backoff-ms:1000}")
    private long retryBackoffMs;

    public String transcribe(Path audioFile) {
        return transcribeResult(audioFile).text();
    }

    public TranscriptionResult transcribeResult(Path audioFile) {
        if (audioFile == null || !Files.isRegularFile(audioFile)) {
            return TranscriptionResult.empty();
        }

        ThreadPoolExecutor executor = asrExecutor();
        try {
            // queueCapacity 同时限制运行中和排队中的任务数量，防止请求无限堆积。
            if (executor.getQueue().remainingCapacity() == 0 && executor.getActiveCount() > 0) {
                log.warn("[MediaAsrService] ASR queue is full, audioFile={}, queueCapacity={}",
                        audioFile, Math.max(1, queueCapacity));
                return TranscriptionResult.failed("Local ASR route is busy");
            }

            Future<TranscriptionResult> future = executor.submit(() -> transcribeWithRetries(audioFile));
            // 调用方等待当前任务完成，后续任务由 asr-worker 串行处理。
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[MediaAsrService] ASR interrupted, audioFile={}", audioFile);
            return TranscriptionResult.failed("ASR interrupted");
        } catch (ExecutionException e) {
            log.warn("[MediaAsrService] ASR worker failed, audioFile={}, reason={}",
                    audioFile, rootMessage(e));
            return TranscriptionResult.failed(rootMessage(e));
        } catch (RejectedExecutionException e) {
            log.warn("[MediaAsrService] ASR queue rejected task, audioFile={}, queueCapacity={}",
                    audioFile, Math.max(1, queueCapacity));
            return TranscriptionResult.failed("Local ASR route is busy");
        }
    }

    private TranscriptionResult transcribeWithRetries(Path audioFile) {
        int attempts = Math.max(0, maxRetries) + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return doTranscribe(audioFile);
            } catch (Exception e) {
                if (attempt >= attempts) {
                    log.warn("[MediaAsrService] ASR failed, audioFile={}, attempts={}, reason={}",
                            audioFile, attempts, rootMessage(e));
                    return TranscriptionResult.failed(rootMessage(e));
                }
                log.warn("[MediaAsrService] ASR attempt failed, audioFile={}, attempt={}/{}, reason={}",
                        audioFile, attempt, attempts, rootMessage(e));
                sleepBeforeRetry();
            }
        }
        return TranscriptionResult.failed("ASR failed without a response");
    }

    private TranscriptionResult doTranscribe(Path audioFile) {
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
            throw new IllegalStateException("ASR returned error body: " + errorMessage);
        }
        String text = extractText(response);
        String result = sanitize(text);
        if (result == null) {
            log.info("[MediaAsrService] ASR returned empty text, audioFile={}, response={}",
                    audioFile, response);
            return TranscriptionResult.empty();
        }
        return TranscriptionResult.success(result);
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(Math.max(0L, retryBackoffMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized ThreadPoolExecutor asrExecutor() {
        if (asrExecutor == null) {
            int capacity = Math.max(1, queueCapacity);
            asrExecutor = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(capacity),
                    runnable -> {
                        Thread thread = new Thread(runnable,
                                "asr-worker-" + ASR_THREAD_ID.incrementAndGet());
                        thread.setDaemon(false);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
            log.info("[MediaAsrService] ASR serial queue initialized, concurrency=1, queueCapacity={}, configuredConcurrency={}",
                    capacity, Math.max(1, concurrency));
        }
        return asrExecutor;
    }

    private static final AtomicInteger ASR_THREAD_ID = new AtomicInteger();

    @PreDestroy
    public synchronized void shutdown() {
        if (asrExecutor != null) {
            asrExecutor.shutdownNow();
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
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current != null && current.getMessage() != null
                ? current.getMessage() : String.valueOf(throwable);
    }

    public record TranscriptionResult(TranscriptionStatus status, String text, String error) {
        public static TranscriptionResult success(String text) {
            return new TranscriptionResult(TranscriptionStatus.SUCCESS, text, null);
        }

        public static TranscriptionResult empty() {
            return new TranscriptionResult(TranscriptionStatus.EMPTY, null, null);
        }

        public static TranscriptionResult failed(String error) {
            return new TranscriptionResult(TranscriptionStatus.FAILED, null, error);
        }
    }

    public enum TranscriptionStatus {
        SUCCESS,
        EMPTY,
        FAILED
    }
}

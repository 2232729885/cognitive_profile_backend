package com.idata.profile.search;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SearchQueryTranslationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int QUERY_CACHE_MAX_SIZE = 2_000;
    private static final int CONTENT_TEXT_MAX_LENGTH = 4_000;

    private static final String QUERY_SYSTEM_PROMPT = """
            You expand multilingual search queries. Return only one JSON object.
            Detect the query language and create concise search variants in English, Chinese, Japanese, Korean,
            Arabic, Russian, and the original language when useful.
            Keep named entities, tickers, hashtags, handles, and URLs unchanged.
            Do not add explanations.
            JSON schema:
            {"detectedLanguage":"en|zh|ja|ko|ar|ru|other","queries":["..."]}
            """;

    private static final String CONTENT_SYSTEM_PROMPT = """
            You translate social media search index text to English. Return only one JSON object.
            Preserve named entities, tickers, hashtags, handles, URLs, measurements, dates, and numbers.
            Keep the translation factual and compact. Do not summarize beyond necessary cleanup.
            JSON schema:
            {"title":"...","bodyText":"...","summary":"..."}
            """;

    private static final String MEDIA_TEXT_SYSTEM_PROMPT = """
            You translate media search index text to English. Return only one JSON object.
            The fields may come from image OCR, audio/video ASR, or visual captioning.
            Preserve named entities, tickers, hashtags, handles, URLs, measurements, dates, and numbers.
            Keep each field separate. Do not add explanations.
            JSON schema:
            {"ocrText":"...","asrText":"...","captionText":"..."}
            """;

    private final Semaphore semaphore;
    private final Map<String, List<String>> queryCache = new ConcurrentHashMap<>();

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.model:Qwen3-VL-32B-Instruct}")
    private String model;

    @Value("${search.translation.enabled:true}")
    private boolean enabled;

    @Value("${search.translation.timeout-seconds:20}")
    private int timeoutSeconds;

    @Value("${search.translation.max-tokens:1024}")
    private int maxTokens;

    public SearchQueryTranslationService(@Value("${search.translation.concurrency:4}") int concurrency) {
        this.semaphore = new Semaphore(Math.max(1, concurrency));
    }

    public List<String> expandQuery(String queryText) {
        if (!hasText(queryText)) {
            return List.of();
        }
        String normalized = normalizeWhitespace(queryText);
        if (!enabled || isIdentifierLikeQuery(normalized)) {
            return List.of(normalized);
        }
        List<String> cached = queryCache.get(normalized);
        if (cached != null) {
            return cached;
        }

        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(normalized);
        if (!tryAcquire()) {
            return List.copyOf(variants);
        }
        try {
            String raw = callJsonLlm(QUERY_SYSTEM_PROMPT, "Query:\n" + normalized);
            QueryExpansionResponse response = OBJECT_MAPPER.readValue(cleanJson(raw), QueryExpansionResponse.class);
            if (response.getQueries() != null) {
                response.getQueries().stream()
                        .map(this::normalizeWhitespace)
                        .filter(this::hasText)
                        .filter(value -> value.length() <= 300)
                        .forEach(variants::add);
            }
        } catch (Exception e) {
            log.debug("[SearchTranslation] query expansion failed, query={}, reason={}",
                    normalized, rootMessage(e));
        } finally {
            semaphore.release();
        }
        List<String> result = variants.stream().limit(8).toList();
        cacheQuery(normalized, result);
        return result;
    }

    public TranslatedContent translateContent(String title, String bodyText, String summary, String language) {
        if (!enabled) {
            return TranslatedContent.empty();
        }
        if (!hasText(title) && !hasText(bodyText) && !hasText(summary)) {
            return TranslatedContent.empty();
        }
        if (isEnglish(language)) {
            return new TranslatedContent(title, bodyText, summary);
        }
        if (!tryAcquire()) {
            return TranslatedContent.empty();
        }
        try {
            String userPrompt = """
                    Source language: %s

                    Title:
                    %s

                    Body:
                    %s

                    Summary:
                    %s
                    """.formatted(
                    hasText(language) ? language : "unknown",
                    truncate(title, 500),
                    truncate(bodyText, CONTENT_TEXT_MAX_LENGTH),
                    truncate(summary, 1_500));
            String raw = callJsonLlm(CONTENT_SYSTEM_PROMPT, userPrompt);
            ContentTranslationResponse response =
                    OBJECT_MAPPER.readValue(cleanJson(raw), ContentTranslationResponse.class);
            return new TranslatedContent(
                    blankToNull(response.getTitle()),
                    blankToNull(response.getBodyText()),
                    blankToNull(response.getSummary()));
        } catch (Exception e) {
            log.debug("[SearchTranslation] content translation failed, language={}, reason={}",
                    language, rootMessage(e));
            return TranslatedContent.empty();
        } finally {
            semaphore.release();
        }
    }

    public TranslatedMediaText translateMediaText(String ocrText, String asrText,
                                                  String captionText, String language) {
        if (!enabled) {
            return TranslatedMediaText.empty();
        }
        if (!hasText(ocrText) && !hasText(asrText) && !hasText(captionText)) {
            return TranslatedMediaText.empty();
        }
        if (isEnglish(language) && allPresentTextProbablyEnglish(ocrText, asrText, captionText)) {
            return new TranslatedMediaText(ocrText, asrText, captionText);
        }
        if (!tryAcquire()) {
            return TranslatedMediaText.empty();
        }
        try {
            String sourceLanguage = isEnglish(language)
                    && !allPresentTextProbablyEnglish(ocrText, asrText, captionText)
                    ? "unknown"
                    : firstText(language, "unknown");
            String userPrompt = """
                    Source language: %s

                    OCR text:
                    %s

                    ASR text:
                    %s

                    Caption text:
                    %s
                    """.formatted(
                    sourceLanguage,
                    truncate(ocrText, CONTENT_TEXT_MAX_LENGTH),
                    truncate(asrText, CONTENT_TEXT_MAX_LENGTH),
                    truncate(captionText, 1_500));
            String raw = callJsonLlm(MEDIA_TEXT_SYSTEM_PROMPT, userPrompt);
            MediaTextTranslationResponse response =
                    OBJECT_MAPPER.readValue(cleanJson(raw), MediaTextTranslationResponse.class);
            return new TranslatedMediaText(
                    englishPivotText(response.getOcrText(), ocrText),
                    englishPivotText(response.getAsrText(), asrText),
                    englishPivotText(response.getCaptionText(), captionText));
        } catch (Exception e) {
            log.debug("[SearchTranslation] media text translation failed, language={}, reason={}",
                    language, rootMessage(e));
            return TranslatedMediaText.empty();
        } finally {
            semaphore.release();
        }
    }

    private String callJsonLlm(String systemPrompt, String userPrompt) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "temperature", 0,
                "max_tokens", Math.max(128, maxTokens),
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));

        ChatCompletionResponse response = restClient().post()
                .uri(normalizeBaseUrl(baseUrl) + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .body(requestBody)
                .retrieve()
                .body(ChatCompletionResponse.class);

        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()
                || response.getChoices().getFirst().getMessage() == null) {
            return "{}";
        }
        return response.getChoices().getFirst().getMessage().getContent();
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

    private void cacheQuery(String query, List<String> variants) {
        if (queryCache.size() >= QUERY_CACHE_MAX_SIZE) {
            queryCache.clear();
        }
        queryCache.put(query, variants);
    }

    private boolean tryAcquire() {
        try {
            return semaphore.tryAcquire(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String cleanJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "{}";
    }

    private boolean isIdentifierLikeQuery(String queryText) {
        if (!hasText(queryText)) {
            return false;
        }
        String value = queryText.trim();
        return value.length() >= 3 && value.length() <= 48
                && !value.contains(" ")
                && value.matches("[A-Za-z0-9_@#.$:/-]+")
                && value.matches(".*[A-Za-z0-9].*");
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value != null ? value.trim() : "";
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            return normalized;
        }
        return normalized + "/v1";
    }

    private String normalizeWhitespace(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String englishPivotText(String translated, String original) {
        String clean = blankToNull(translated);
        if (isProbablyEnglishText(original)) {
            return hasText(clean) ? clean : blankToNull(original);
        }
        return isProbablyEnglishText(clean) ? clean : null;
    }

    private boolean allPresentTextProbablyEnglish(String... values) {
        boolean hasAnyText = false;
        for (String value : values) {
            if (!hasText(value)) {
                continue;
            }
            hasAnyText = true;
            if (!isProbablyEnglishText(value)) {
                return false;
            }
        }
        return hasAnyText;
    }

    private boolean isProbablyEnglishText(String text) {
        if (!hasText(text)) {
            return false;
        }
        int latinLetters = 0;
        int nonLatinLetters = 0;
        int totalLetters = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) {
                continue;
            }
            totalLetters++;
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN) {
                latinLetters++;
            } else {
                nonLatinLetters++;
            }
        }
        if (totalLetters == 0) {
            return true;
        }
        return nonLatinLetters == 0 || latinLetters / (double) totalLetters >= 0.85D;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean isEnglish(String language) {
        if (!hasText(language)) {
            return false;
        }
        String value = language.trim().toLowerCase();
        return "en".equals(value) || value.startsWith("en-") || value.startsWith("en_");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String rootMessage(Throwable error) {
        Throwable root = error;
        while (root != null && root.getCause() != null) {
            root = root.getCause();
        }
        return root != null && root.getMessage() != null ? root.getMessage() : String.valueOf(error);
    }

    public record TranslatedContent(String title, String bodyText, String summary) {
        public static TranslatedContent empty() {
            return new TranslatedContent(null, null, null);
        }
    }

    public record TranslatedMediaText(String ocrText, String asrText, String captionText) {
        public static TranslatedMediaText empty() {
            return new TranslatedMediaText(null, null, null);
        }
    }

    @Data
    private static class QueryExpansionResponse {
        private String detectedLanguage;
        private List<String> queries = new ArrayList<>();
    }

    @Data
    private static class ContentTranslationResponse {
        private String title;
        private String bodyText;
        private String summary;
    }

    @Data
    private static class MediaTextTranslationResponse {
        private String ocrText;
        private String asrText;
        private String captionText;
    }

    @Data
    private static class ChatCompletionResponse {
        private List<Choice> choices;

        @Data
        private static class Choice {
            private Message message;
        }

        @Data
        private static class Message {
            private String content;
        }
    }
}

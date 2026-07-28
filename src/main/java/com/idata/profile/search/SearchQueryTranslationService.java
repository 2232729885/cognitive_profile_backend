package com.idata.profile.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.idata.profile.common.util.TextEncodingRepairUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
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
            You translate multilingual search queries into English. Return only one JSON object.
            Detect the query language and create exactly one concise English search query.
            Keep named entities, tickers, hashtags, handles, and URLs unchanged.
            Do not return Chinese, Japanese, Korean, Arabic, Russian, or other non-English variants.
            Do not add explanations.
            JSON schema:
            {"detectedLanguage":"en|zh|ja|ko|ar|ru|other","queries":["one English query"]}
            """;

    private static final String CONTENT_SYSTEM_PROMPT = """
            You translate social media search index text to English. Return only one JSON object.
            Preserve named entities, tickers, hashtags, handles, URLs, measurements, dates, and numbers.
            Keep the translation factual and compact. Do not summarize beyond necessary cleanup.
            JSON schema:
            {"title":"...","bodyText":"...","summary":"..."}
            """;

    private static final String MEDIA_TEXT_SYSTEM_PROMPT = """
            You translate one media search index text field to English. Return only one JSON object.
            The text may come from image OCR, audio/video ASR, or visual captioning.
            Preserve named entities, tickers, hashtags, handles, URLs, measurements, dates, and numbers.
            Preserve text that is already English and translate every non-English span.
            Do not repeat, expand, summarize, or add information.
            For East Asian proper nouns, provide a concise English translation or romanization.
            Do not add explanations.
            JSON schema:
            {"translatedText":"..."}
            """;

    private final Semaphore semaphore;
    private final Map<String, List<String>> queryCache = new ConcurrentHashMap<>();

    @Value("${search.translation.base-url:${spring.ai.openai.base-url}}")
    private String baseUrl;

    @Value("${search.translation.api-key:${spring.ai.openai.api-key}}")
    private String apiKey;

    @Value("${search.translation.model:${spring.ai.openai.chat.options.model:Qwen3-32B}}")
    private String model;

    @Value("${search.translation.enabled:true}")
    private boolean enabled;

    @Value("${search.translation.timeout-seconds:20}")
    private int timeoutSeconds;

    @Value("${search.translation.max-tokens:1024}")
    private int maxTokens;

    @Value("${search.translation.queue-timeout-seconds:10}")
    private int queueTimeoutSeconds;

    @Value("${search.translation.media-chunk-chars:1500}")
    private int mediaChunkChars;

    @Value("${search.translation.media-max-chunks:4}")
    private int mediaMaxChunks;

    @Value("${search.translation.media-field-retries:1}")
    private int mediaFieldRetries;

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
                        .limit(1)
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
        if (allPresentTextProbablyEnglish(title, bodyText, summary)) {
            return TranslatedContent.empty();
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
                    englishPivotText(response.getTitle(), title),
                    englishPivotText(response.getBodyText(), bodyText),
                    englishPivotText(response.getSummary(), summary));
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
            log.warn("[SearchTranslation] media text translation skipped because translation is disabled, language={}, hasOcr={}, hasAsr={}, hasCaption={}",
                    language, hasText(ocrText), hasText(asrText), hasText(captionText));
            return TranslatedMediaText.empty();
        }
        String normalizedOcrText = TextEncodingRepairUtil.repairLikelyUtf8Mojibake(ocrText);
        String normalizedAsrText = TextEncodingRepairUtil.repairLikelyUtf8Mojibake(asrText);
        String normalizedCaptionText = TextEncodingRepairUtil.repairLikelyUtf8Mojibake(captionText);
        if (!hasText(normalizedOcrText) && !hasText(normalizedAsrText) && !hasText(normalizedCaptionText)) {
            return TranslatedMediaText.empty();
        }
        String ocrToTranslate = nonEnglishText(normalizedOcrText);
        String asrToTranslate = nonEnglishText(normalizedAsrText);
        String captionToTranslate = nonEnglishText(normalizedCaptionText);
        if (!hasText(ocrToTranslate) && !hasText(asrToTranslate) && !hasText(captionToTranslate)) {
            return TranslatedMediaText.empty();
        }
        if (!tryAcquire()) {
            log.warn("[SearchTranslation] media text translation skipped because translation route is busy, language={}, ocrLength={}, asrLength={}, captionLength={}",
                    language, textLength(normalizedOcrText), textLength(normalizedAsrText),
                    textLength(normalizedCaptionText));
            return TranslatedMediaText.empty();
        }
        try {
            String sourceLanguage = resolveMediaSourceLanguage(
                    ocrToTranslate, asrToTranslate, captionToTranslate, language);
            TranslatedMediaText translated = new TranslatedMediaText(
                    translateMediaField("ocrText", ocrToTranslate, sourceLanguage),
                    translateMediaField("asrText", asrToTranslate, sourceLanguage),
                    translateMediaField("captionText", captionToTranslate, sourceLanguage));
            List<String> incompleteFields = incompleteMediaTranslationFields(
                    normalizedOcrText, normalizedAsrText, normalizedCaptionText, translated);
            if (!incompleteFields.isEmpty()) {
                log.debug("[SearchTranslation] media text translation incomplete, language={}, sourceLanguage={}, incompleteFields={}, ocrOutputLength={}, asrOutputLength={}, captionOutputLength={}",
                        language, sourceLanguage, incompleteFields,
                        textLength(translated.ocrText()), textLength(translated.asrText()),
                        textLength(translated.captionText()));
            }
            return translated;
        } catch (Exception e) {
            log.warn("[SearchTranslation] media text translation failed, language={}, reason={}",
                    language, rootMessage(e));
            return TranslatedMediaText.empty();
        } finally {
            semaphore.release();
        }
    }

    private String callJsonLlm(String systemPrompt, String userPrompt) {
        return callJsonLlmDetailed(systemPrompt, userPrompt).content();
    }

    private LlmOutput callJsonLlmDetailed(String systemPrompt, String userPrompt) {
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
            return new LlmOutput("{}", null);
        }
        ChatCompletionResponse.Choice choice = response.getChoices().getFirst();
        return new LlmOutput(choice.getMessage().getContent(), choice.getFinishReason());
    }

    private String translateMediaField(String fieldName, String sourceText, String sourceLanguage) {
        if (!hasText(sourceText)) {
            return null;
        }
        List<String> chunks = splitMediaTranslationChunks(sourceText);
        List<String> translatedChunks = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            String translated = translateMediaChunk(
                    fieldName, chunks.get(index), sourceLanguage, index, chunks.size());
            if (!hasText(translated)) {
                return null;
            }
            translatedChunks.add(translated);
        }
        String combined = String.join("\n", translatedChunks).trim();
        return isProbablyEnglishText(combined) ? combined : null;
    }

    private String translateMediaChunk(String fieldName, String sourceText, String sourceLanguage,
                                       int chunkIndex, int chunkCount) {
        LlmOutput lastOutput = new LlmOutput("{}", null);
        Exception lastError = null;
        int attempts = Math.max(0, mediaFieldRetries) + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String userPrompt = """
                        Source language: %s
                        Field: %s
                        Chunk: %d of %d

                        Translate the complete text below to English exactly once:
                        %s
                        """.formatted(
                        sourceLanguage,
                        fieldName,
                        chunkIndex + 1,
                        chunkCount,
                        sourceText);
                lastOutput = callJsonLlmDetailed(MEDIA_TEXT_SYSTEM_PROMPT, userPrompt);
                String translated = parseTranslatedText(lastOutput.content(), fieldName);
                if (isProbablyEnglishText(translated)) {
                    return translated.trim();
                }
            } catch (Exception e) {
                lastError = e;
            }
        }

        String message = "[SearchTranslation] media field translation failed, field={}, sourceLanguage={}, "
                + "chunk={}/{}, inputLength={}, finishReason={}, rawLength={}, rawPreview={}, reason={}";
        Object[] arguments = {
                fieldName, sourceLanguage, chunkIndex + 1, chunkCount, textLength(sourceText),
                lastOutput.finishReason(), textLength(lastOutput.content()), preview(lastOutput.content(), 200),
                lastError != null ? rootMessage(lastError) : "empty or non-English output"
        };
        if (meaningfulNonLatinLetters(sourceText) >= 2) {
            log.warn(message, arguments);
        } else {
            log.debug(message, arguments);
        }
        return null;
    }

    private String parseTranslatedText(String raw, String fieldName) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(cleanJson(raw));
        return firstText(
                jsonText(root, "translatedText"),
                jsonText(root, "translated_text"),
                jsonText(root, fieldName),
                "ocrText".equals(fieldName) ? jsonText(root, "ocr_text") : null,
                "asrText".equals(fieldName) ? jsonText(root, "asr_text") : null,
                "captionText".equals(fieldName) ? jsonText(root, "caption_text") : null);
    }

    List<String> splitMediaTranslationChunks(String text) {
        if (!hasText(text)) {
            return List.of();
        }
        int targetLength = Math.max(200, mediaChunkChars);
        int maxChunks = Math.max(1, mediaMaxChunks);
        List<String> chunks = new ArrayList<>(maxChunks);
        int start = 0;
        while (start < text.length() && chunks.size() < maxChunks) {
            int hardEnd = Math.min(text.length(), start + targetLength);
            int end = hardEnd;
            if (hardEnd < text.length()) {
                int minimumBoundary = start + targetLength / 2;
                for (int i = hardEnd; i >= minimumBoundary; i--) {
                    char value = text.charAt(i - 1);
                    if (value == '\n' || value == '\r' || value == '.'
                            || value == '!' || value == '?' || value == '。'
                            || value == '！' || value == '？' || value == ';' || value == '；') {
                        end = i;
                        break;
                    }
                }
            }
            String chunk = text.substring(start, end).trim();
            if (hasText(chunk)) {
                chunks.add(chunk);
            }
            start = end;
        }
        if (start < text.length()) {
            log.debug("[SearchTranslation] media field truncated for translation, originalLength={}, translatedLength={}, chunks={}",
                    text.length(), start, chunks.size());
        }
        return List.copyOf(chunks);
    }

    private int meaningfulNonLatinLetters(String text) {
        int count = 0;
        if (text == null) {
            return count;
        }
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isLetter(codePoint)
                    && Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN) {
                count++;
            }
        }
        return count;
    }

    private String jsonText(JsonNode root, String fieldName) {
        if (root == null || root.isMissingNode() || root.isNull() || fieldName == null) {
            return null;
        }
        return text(root.path(fieldName));
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
            return semaphore.tryAcquire(Math.max(1, queueTimeoutSeconds), TimeUnit.SECONDS);
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

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return hasText(value) ? value.trim() : null;
    }

    private String englishPivotText(String translated, String original) {
        String clean = blankToNull(translated);
        if (isProbablyEnglishText(original)) {
            return null;
        }
        return isProbablyEnglishText(clean) ? clean : null;
    }

    private String nonEnglishText(String value) {
        return hasText(value) && !isProbablyEnglishText(value) ? value : null;
    }

    private List<String> incompleteMediaTranslationFields(
            String originalOcr, String originalAsr, String originalCaption,
            TranslatedMediaText translated) {
        List<String> fields = new ArrayList<>(3);
        addIncompleteMediaField(fields, "ocrText", originalOcr, translated.ocrText());
        addIncompleteMediaField(fields, "asrText", originalAsr, translated.asrText());
        addIncompleteMediaField(fields, "captionText", originalCaption, translated.captionText());
        return List.copyOf(fields);
    }

    private void addIncompleteMediaField(List<String> fields, String fieldName,
                                         String original, String translated) {
        if (hasText(original) && !isProbablyEnglishText(original) && !hasText(translated)) {
            fields.add(fieldName);
        }
    }

    public boolean needsPivotTranslation(String text) {
        String normalized = TextEncodingRepairUtil.repairLikelyUtf8Mojibake(text);
        return hasText(normalized) && !isProbablyEnglishText(normalized);
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
        if (isAsciiDominantIndexText(text)) {
            return true;
        }
        if (TextEncodingRepairUtil.looksLikeUtf8Mojibake(text)) {
            return false;
        }
        int asciiLetters = 0;
        int totalLetters = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if ((codePoint >= 0x80 && codePoint <= 0x9F) || codePoint == 0xFFFD) {
                return false;
            }
            if (!Character.isLetter(codePoint)) {
                continue;
            }
            totalLetters++;
            if ((codePoint >= 'A' && codePoint <= 'Z') || (codePoint >= 'a' && codePoint <= 'z')) {
                asciiLetters++;
            }
        }
        if (totalLetters == 0) {
            return true;
        }
        return asciiLetters / (double) totalLetters >= 0.85D;
    }

    private boolean isAsciiDominantIndexText(String text) {
        int usefulChars = 0;
        int asciiUsefulChars = 0;
        int latinLetters = 0;
        int totalLetters = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
                continue;
            }
            if (Character.isLetter(codePoint)) {
                totalLetters++;
                Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
                if (script == Character.UnicodeScript.LATIN) {
                    latinLetters++;
                }
            }
            usefulChars++;
            if (codePoint >= 0x20 && codePoint <= 0x7E) {
                asciiUsefulChars++;
            }
        }
        if (usefulChars == 0) {
            return true;
        }
        double asciiRatio = asciiUsefulChars / (double) usefulChars;
        double latinLetterRatio = totalLetters == 0 ? 1D : latinLetters / (double) totalLetters;
        return asciiRatio >= 0.92D && latinLetterRatio >= 0.95D;
    }

    private String resolveMediaSourceLanguage(String ocrText, String asrText, String captionText, String language) {
        if (hasText(language) && !isEnglish(language)) {
            return language.trim();
        }
        String scriptLanguage = detectDominantScriptLanguage(ocrText, asrText, captionText);
        return hasText(scriptLanguage) ? scriptLanguage : firstText(language, "unknown");
    }

    private String detectDominantScriptLanguage(String... values) {
        int han = 0;
        int hangul = 0;
        int hiraganaKatakana = 0;
        int arabic = 0;
        int cyrillic = 0;
        int totalLetters = 0;
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!hasText(value) || isProbablyEnglishText(value)) {
                continue;
            }
            for (int i = 0; i < value.length(); ) {
                int codePoint = value.codePointAt(i);
                i += Character.charCount(codePoint);
                if (!Character.isLetter(codePoint)) {
                    continue;
                }
                totalLetters++;
                Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
                if (script == Character.UnicodeScript.HANGUL) {
                    hangul++;
                } else if (script == Character.UnicodeScript.HAN) {
                    han++;
                } else if (script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA) {
                    hiraganaKatakana++;
                } else if (script == Character.UnicodeScript.ARABIC) {
                    arabic++;
                } else if (script == Character.UnicodeScript.CYRILLIC) {
                    cyrillic++;
                }
            }
        }
        if (totalLetters == 0) {
            return null;
        }
        if (hangul / (double) totalLetters >= 0.2D) {
            return "Korean";
        }
        if (hiraganaKatakana / (double) totalLetters >= 0.1D) {
            return "Japanese";
        }
        if (han / (double) totalLetters >= 0.2D) {
            return "Chinese or Japanese";
        }
        if (arabic / (double) totalLetters >= 0.2D) {
            return "Arabic";
        }
        if (cyrillic / (double) totalLetters >= 0.2D) {
            return "Russian";
        }
        return null;
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

    private int textLength(String value) {
        return value == null ? 0 : value.length();
    }

    private String preview(String value, int maxLength) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = normalizeWhitespace(value);
        int safeMaxLength = Math.max(1, maxLength);
        return normalized.length() <= safeMaxLength
                ? normalized
                : normalized.substring(0, safeMaxLength);
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

    private record LlmOutput(String content, String finishReason) {
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
    private static class ChatCompletionResponse {
        private List<Choice> choices;

        @Data
        private static class Choice {
            private Message message;
            @JsonProperty("finish_reason")
            private String finishReason;
        }

        @Data
        private static class Message {
            private String content;
        }
    }
}

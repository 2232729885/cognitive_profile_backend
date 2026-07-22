package com.idata.profile.pipeline.step;

import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.common.util.T1AnnotationView;
import com.idata.profile.common.util.TextEncodingRepairUtil;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.infra.elasticsearch.EntityEsService;
import com.idata.profile.infra.elasticsearch.MediaContentEsService;
import com.idata.profile.infra.embedding.EmbeddingService;
import com.idata.profile.infra.milvus.MilvusVectorService;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import com.idata.profile.search.SearchQueryTranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class T4IndexingStep {

    private static final int MAX_EMBEDDING_TEXT_LENGTH = 6000;
    private static final int CONTENT_NODE_NAME_MAX_LENGTH = 160;

    private final EmbeddingService embeddingService;
    private final MilvusVectorService milvusVectorService;
    private final EntityEsService entityEsService;
    private final MediaContentEsService mediaContentEsService;
    private final MediaContentMapper mediaContentMapper;
    private final RawRecordMapper rawRecordMapper;
    private final PipelineTaskMapper pipelineTaskMapper;
    private final SearchQueryTranslationService translationService;

    public void run(PipelineTask task) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        task.setT4Status("running");
        task.setT4StartedAt(startedAt);
        pipelineTaskMapper.updateById(task);

        MediaContent mc = mediaContentMapper.selectById(task.getContentId());
        T1AnnotationView t1View = T1AnnotationView.parse(mc.getT1Annotation());

        String summaryText = t1View.summaryText();
        String bodyEmbeddingText = buildContentBodyEmbeddingText(mc, t1View);
        String contentDescriptionText = combineText(summaryText, bodyEmbeddingText);
        String contentNodeName = buildContentNodeName(mc, summaryText);
        String beforeTranslatedTitle = mc.getTranslatedTitle();
        String beforeTranslatedBodyText = mc.getTranslatedBodyText();
        String beforeTranslatedSummary = mc.getTranslatedSummary();
        SearchQueryTranslationService.TranslatedContent translated = resolveTranslatedContent(mc, summaryText);
        if (translatedFieldsChanged(mc, beforeTranslatedTitle, beforeTranslatedBodyText, beforeTranslatedSummary)) {
            mediaContentMapper.updateTranslationFields(
                    mc.getId(),
                    mc.getTranslatedTitle(),
                    mc.getTranslatedBodyText(),
                    mc.getTranslatedSummary());
        }
        float[] titleEmbedding = generateEmbedding(mc.getTitle());
        float[] contentNodeNameEmbedding = generateEmbedding(contentNodeName);
        float[] summaryEmbedding = generateEmbedding(summaryText);
        float[] bodyEmbedding = generateEmbedding(bodyEmbeddingText);
        float[] pivotTitleEmbedding = generateEmbedding(translated.title());
        float[] pivotSummaryEmbedding = generateEmbedding(translated.summary());
        float[] pivotBodyEmbedding = generateEmbedding(buildPivotBodyEmbeddingText(mc, t1View, translated));
        boolean textEmbeddingSkipped = titleEmbedding == null && summaryEmbedding == null && bodyEmbedding == null;
        boolean pivotTextEmbeddingSkipped = pivotTitleEmbedding == null
                && pivotSummaryEmbedding == null
                && pivotBodyEmbedding == null;
        String vectorId = null;
        String pivotVectorId = null;
        if (textEmbeddingSkipped) {
            log.warn("[T4] media content vectorization skipped, contentId={}", mc.getId());
        } else {
            vectorId = milvusVectorService.insertMediaContentEmbedding(
                    mc.getId().toString(),
                    mc.getPlatform(),
                    mc.getLanguage(),
                    mc.getContentType(),
                    mc.getPublishedAt() != null ? mc.getPublishedAt().toEpochSecond() : 0L,
                    titleEmbedding,
                    summaryEmbedding,
                    bodyEmbedding);
            milvusVectorService.upsertEntityEmbedding(
                    mc.getId().toString(),
                    "MediaContent",
                    contentNodeName,
                    null,
                    mc.getPlatformContentId(),
                    mc.getPlatform(),
                    contentDescriptionText,
                    firstEmbedding(titleEmbedding, contentNodeNameEmbedding),
                    null,
                    firstEmbedding(bodyEmbedding, summaryEmbedding));
        }
        if (!pivotTextEmbeddingSkipped) {
            pivotVectorId = milvusVectorService.insertMediaContentPivotEmbedding(
                    mc.getId().toString(),
                    mc.getPlatform(),
                    mc.getLanguage(),
                    mc.getContentType(),
                    mc.getPublishedAt() != null ? mc.getPublishedAt().toEpochSecond() : 0L,
                    pivotTitleEmbedding,
                    pivotSummaryEmbedding,
                    pivotBodyEmbedding);
        }

        mediaContentEsService.index(mc.getId().toString(), buildEsDocument(mc, t1View, translated));
        entityEsService.indexEntity(
                mc.getId().toString(),
                contentNodeName,
                List.of(),
                "MediaContent",
                0D,
                contentEntityFields(mc));

        RawRecord rawRecord = rawRecordMapper.selectById(task.getRawRecordId());
        rawRecord.setT4Output(buildT4Output(vectorId, pivotVectorId, textEmbeddingSkipped, pivotTextEmbeddingSkipped));
        rawRecord.setPipelineStatus(PipelineStatus.T4_INDEXED.name());
        rawRecordMapper.updateById(rawRecord);

        task.setT4Status("done");
        task.setT4DoneAt(OffsetDateTime.now());
        task.setT4DurationMs((int) java.time.Duration.between(startedAt, OffsetDateTime.now()).toMillis());
        pipelineTaskMapper.updateById(task);
    }

    private float[] generateEmbedding(String text) {
        if (!hasText(text)) {
            return null;
        }
        return embeddingService.generateTextEmbedding(truncateForEmbedding(text));
    }

    private SearchQueryTranslationService.TranslatedContent resolveTranslatedContent(MediaContent mc, String summaryText) {
        if (mc == null) {
            return SearchQueryTranslationService.TranslatedContent.empty();
        }
        String translatedTitle = cleanText(mc.getTranslatedTitle());
        String translatedBodyText = cleanText(mc.getTranslatedBodyText());
        String translatedSummary = cleanText(mc.getTranslatedSummary());

        boolean needsTitle = hasText(mc.getTitle()) && !hasText(translatedTitle);
        boolean needsBodyText = hasText(mc.getBodyText()) && !hasText(translatedBodyText);
        boolean needsSummary = hasText(summaryText) && !hasText(translatedSummary);
        if (needsTitle || needsBodyText || needsSummary) {
            SearchQueryTranslationService.TranslatedContent generated = translationService.translateContent(
                    needsTitle ? mc.getTitle() : null,
                    needsBodyText ? mc.getBodyText() : null,
                    needsSummary ? summaryText : null,
                    mc.getLanguage());
            if (needsTitle && hasText(generated.title())) {
                translatedTitle = generated.title();
            }
            if (needsBodyText && hasText(generated.bodyText())) {
                translatedBodyText = generated.bodyText();
            }
            if (needsSummary && hasText(generated.summary())) {
                translatedSummary = generated.summary();
            }
        }

        mc.setTranslatedTitle(cleanText(translatedTitle));
        mc.setTranslatedBodyText(cleanText(translatedBodyText));
        mc.setTranslatedSummary(cleanText(translatedSummary));
        return new SearchQueryTranslationService.TranslatedContent(
                mc.getTranslatedTitle(),
                mc.getTranslatedBodyText(),
                mc.getTranslatedSummary());
    }

    private boolean translatedFieldsChanged(MediaContent mc,
                                            String beforeTranslatedTitle,
                                            String beforeTranslatedBodyText,
                                            String beforeTranslatedSummary) {
        return mc != null
                && (!sameText(beforeTranslatedTitle, mc.getTranslatedTitle())
                || !sameText(beforeTranslatedBodyText, mc.getTranslatedBodyText())
                || !sameText(beforeTranslatedSummary, mc.getTranslatedSummary()));
    }

    private String buildContentBodyEmbeddingText(MediaContent mc, T1AnnotationView t1View) {
        if (mc == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        appendEmbeddingField(text, "body_text", mc.getBodyText());
        appendEmbeddingField(text, "hashtags", mc.getHashtags());
        appendEmbeddingField(text, "mentions", mc.getMentions());
        appendEmbeddingField(text, "news_source_name", mc.getNewsSourceName());
        appendEmbeddingField(text, "news_domain", mc.getNewsDomain());
        appendEmbeddingField(text, "news_author", mc.getNewsAuthor());
        appendEmbeddingField(text, "news_section", mc.getNewsSection());
        appendEmbeddingField(text, "news_tags", mc.getNewsTags());
        appendEmbeddingField(text, "topic_category", t1View.topicCategory());
        appendEmbeddingField(text, "topic_type", t1View.topicTypeLabel());
        appendEmbeddingField(text, "sentiment", t1View.sentimentLabel());
        appendEmbeddingField(text, "stance", t1View.stanceLabel());
        appendEmbeddingField(text, "ideology", t1View.ideologyLabel());
        appendEmbeddingField(text, "language_styles", t1View.languageStyleLabels());
        appendEmbeddingField(text, "manipulation_methods", t1View.manipulationMethodLabels());
        appendEmbeddingField(text, "risk_label", t1View.riskLabel());
        appendEmbeddingField(text, "risk_types", t1View.riskTypes());
        appendEmbeddingField(text, "platform", mc.getPlatform());
        appendEmbeddingField(text, "language", mc.getLanguage());
        return text.toString();
    }

    private String buildPivotBodyEmbeddingText(MediaContent mc, T1AnnotationView t1View,
                                               SearchQueryTranslationService.TranslatedContent translated) {
        if (mc == null || translated == null) {
            return null;
        }
        if (!hasText(translated.title()) && !hasText(translated.bodyText()) && !hasText(translated.summary())) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        appendEmbeddingField(text, "translated_body_text", translated.bodyText());
        appendEmbeddingField(text, "translated_summary", translated.summary());
        appendEmbeddingField(text, "hashtags", mc.getHashtags());
        appendEmbeddingField(text, "mentions", mc.getMentions());
        appendEmbeddingField(text, "news_source_name", mc.getNewsSourceName());
        appendEmbeddingField(text, "news_domain", mc.getNewsDomain());
        appendEmbeddingField(text, "news_author", mc.getNewsAuthor());
        appendEmbeddingField(text, "news_section", mc.getNewsSection());
        appendEmbeddingField(text, "news_tags", mc.getNewsTags());
        appendEmbeddingField(text, "topic_category", t1View.topicCategory());
        appendEmbeddingField(text, "topic_type", t1View.topicTypeLabel());
        appendEmbeddingField(text, "sentiment", t1View.sentimentLabel());
        appendEmbeddingField(text, "stance", t1View.stanceLabel());
        appendEmbeddingField(text, "ideology", t1View.ideologyLabel());
        appendEmbeddingField(text, "language_styles", t1View.languageStyleLabels());
        appendEmbeddingField(text, "manipulation_methods", t1View.manipulationMethodLabels());
        appendEmbeddingField(text, "risk_label", t1View.riskLabel());
        appendEmbeddingField(text, "risk_types", t1View.riskTypes());
        appendEmbeddingField(text, "platform", mc.getPlatform());
        appendEmbeddingField(text, "source_language", mc.getLanguage());
        return text.toString();
    }

    private String combineText(String... values) {
        if (values == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            if (hasText(value)) {
                text.append(value.trim()).append('\n');
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    private float[] firstEmbedding(float[]... embeddings) {
        if (embeddings == null) {
            return null;
        }
        for (float[] embedding : embeddings) {
            if (embedding != null) {
                return embedding;
            }
        }
        return null;
    }

    private Object buildEsDocument(MediaContent mc, T1AnnotationView t1View,
                                   SearchQueryTranslationService.TranslatedContent translated) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", mc.getId() != null ? mc.getId().toString() : null);
        document.put("raw_record_id", mc.getRawRecordId() != null ? mc.getRawRecordId().toString() : null);
        document.put("platform", mc.getPlatform());
        document.put("content_type", mc.getContentType());
        document.put("platform_content_id", mc.getPlatformContentId());
        document.put("author_platform_user_id", mc.getAuthorPlatformUserId());
        document.put("title", mc.getTitle());
        document.put("body_text", mc.getBodyText());
        document.put("translated_title", translated.title());
        document.put("translated_body_text", translated.bodyText());
        document.put("translated_summary", translated.summary());
        document.put("language", mc.getLanguage());
        document.put("published_at", mc.getPublishedAt() != null ? mc.getPublishedAt().toString() : null);
        document.put("url", mc.getUrl());
        document.put("hashtags", mc.getHashtags());
        document.put("mentions", mc.getMentions());
        document.put("external_urls", mc.getExternalUrls());
        document.put("like_count", mc.getLikeCount());
        document.put("comment_count", mc.getCommentCount());
        document.put("share_count", mc.getShareCount());
        document.put("repost_count", mc.getRepostCount());
        document.put("quote_count", mc.getQuoteCount());
        document.put("view_count", mc.getViewCount());
        document.put("reaction_count", mc.getReactionCount());
        document.put("topic_category", t1View.topicCategory());
        document.put("topic_type", t1View.topicTypeLabel());
        document.put("sentiment_label", t1View.sentimentLabel());
        document.put("stance_label", t1View.stanceLabel());
        document.put("aigc_score", t1View.aigcScore());
        document.put("aigc_type", t1View.aigcType());
        document.put("summary", t1View.summaryText());
        document.put("ideology_label", t1View.ideologyLabel());
        document.put("language_style_labels", t1View.languageStyleLabels());
        document.put("manipulation_methods", t1View.manipulationMethodLabels());
        document.put("risk_label", t1View.riskLabel());
        document.put("risk_types", t1View.riskTypes());
        document.put("entities_hint", t1View.entitiesHintJson());
        document.put("updated_at", mc.getUpdatedAt() != null ? mc.getUpdatedAt().toString() : null);
        return document;
    }

    private Map<String, Object> contentEntityFields(MediaContent mc) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("platform", mc.getPlatform());
        fields.put("source_id", mc.getPlatformContentId());
        fields.put("content_type", mc.getContentType());
        return fields;
    }

    private String buildContentNodeName(MediaContent mc, String summaryText) {
        if (mc == null) {
            return null;
        }
        return firstText(
                readableSnippet(mc.getTitle(), CONTENT_NODE_NAME_MAX_LENGTH),
                readableSnippet(summaryText, CONTENT_NODE_NAME_MAX_LENGTH),
                readableSnippet(mc.getBodyText(), CONTENT_NODE_NAME_MAX_LENGTH));
    }

    private String buildT4Output(String vectorId, String pivotVectorId,
                                 boolean textEmbeddingSkipped, boolean pivotTextEmbeddingSkipped) {
        String vectorValue = vectorId != null ? "\"" + vectorId + "\"" : "null";
        String pivotVectorValue = pivotVectorId != null ? "\"" + pivotVectorId + "\"" : "null";
        return "{\"textVectorId\":" + vectorValue
                + ",\"mediaContentVectorId\":" + vectorValue
                + ",\"pivotTextVectorId\":" + pivotVectorValue
                + ",\"mediaContentPivotVectorId\":" + pivotVectorValue
                + ",\"textEmbeddingSkipped\":" + textEmbeddingSkipped
                + ",\"pivotTextEmbeddingSkipped\":" + pivotTextEmbeddingSkipped + "}";
    }

    private void appendEmbeddingField(StringBuilder text, String field, String value) {
        if (!hasText(value)) {
            return;
        }
        text.append(field).append(": ").append(value.trim()).append('\n');
    }

    private void appendEmbeddingField(StringBuilder text, String field, String[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        appendEmbeddingField(text, field, String.join(" / ",
                Arrays.stream(values).filter(this::hasText).toList()));
    }

    private void appendEmbeddingField(StringBuilder text, String field, Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        appendEmbeddingField(text, field, String.join(" / ",
                values.stream().filter(this::hasText).toList()));
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim());
    }

    private String cleanText(String value) {
        if (!hasText(value)) {
            return null;
        }
        return TextEncodingRepairUtil.repairLikelyUtf8Mojibake(value.trim());
    }

    private boolean sameText(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private String truncateForEmbedding(String text) {
        if (text == null || text.length() <= MAX_EMBEDDING_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_EMBEDDING_TEXT_LENGTH);
    }

    private String readableSnippet(String text, int maxLength) {
        if (!hasText(text)) {
            return null;
        }
        String normalized = text
                .replaceAll("https?://\\S+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (!hasText(normalized)) {
            return null;
        }
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength).trim();
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
}

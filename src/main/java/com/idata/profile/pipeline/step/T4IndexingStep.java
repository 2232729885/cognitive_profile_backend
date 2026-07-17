package com.idata.profile.pipeline.step;

import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.common.util.T1AnnotationView;
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

    private final EmbeddingService embeddingService;
    private final MilvusVectorService milvusVectorService;
    private final EntityEsService entityEsService;
    private final MediaContentEsService mediaContentEsService;
    private final MediaContentMapper mediaContentMapper;
    private final RawRecordMapper rawRecordMapper;
    private final PipelineTaskMapper pipelineTaskMapper;

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
        float[] titleEmbedding = generateEmbedding(mc.getTitle());
        float[] summaryEmbedding = generateEmbedding(summaryText);
        float[] bodyEmbedding = generateEmbedding(bodyEmbeddingText);
        boolean textEmbeddingSkipped = titleEmbedding == null && summaryEmbedding == null && bodyEmbedding == null;
        String vectorId = null;
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
                    firstText(mc.getTitle(), mc.getPlatformContentId(), mc.getUrl()),
                    null,
                    mc.getPlatformContentId(),
                    mc.getPlatform(),
                    contentDescriptionText,
                    titleEmbedding,
                    null,
                    firstEmbedding(bodyEmbedding, summaryEmbedding));
        }

        mediaContentEsService.index(mc.getId().toString(), buildEsDocument(mc, t1View));
        entityEsService.indexEntity(
                mc.getId().toString(),
                firstText(mc.getTitle(), mc.getPlatformContentId(), mc.getUrl()),
                List.of(),
                "MediaContent",
                0D,
                contentEntityFields(mc));

        RawRecord rawRecord = rawRecordMapper.selectById(task.getRawRecordId());
        rawRecord.setT4Output(buildT4Output(vectorId, textEmbeddingSkipped));
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

    private Object buildEsDocument(MediaContent mc, T1AnnotationView t1View) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", mc.getId() != null ? mc.getId().toString() : null);
        document.put("raw_record_id", mc.getRawRecordId() != null ? mc.getRawRecordId().toString() : null);
        document.put("platform", mc.getPlatform());
        document.put("content_type", mc.getContentType());
        document.put("platform_content_id", mc.getPlatformContentId());
        document.put("author_platform_user_id", mc.getAuthorPlatformUserId());
        document.put("title", mc.getTitle());
        document.put("body_text", mc.getBodyText());
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

    private String buildT4Output(String vectorId, boolean textEmbeddingSkipped) {
        String vectorValue = vectorId != null ? "\"" + vectorId + "\"" : "null";
        return "{\"textVectorId\":" + vectorValue
                + ",\"mediaContentVectorId\":" + vectorValue
                + ",\"textEmbeddingSkipped\":" + textEmbeddingSkipped + "}";
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
        return value != null && !value.trim().isEmpty();
    }

    private String truncateForEmbedding(String text) {
        if (text == null || text.length() <= MAX_EMBEDDING_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_EMBEDDING_TEXT_LENGTH);
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

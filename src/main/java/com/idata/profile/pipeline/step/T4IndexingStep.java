package com.idata.profile.pipeline.step;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingRequest;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingResponse;
import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.common.util.T1AnnotationView;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.infra.elasticsearch.MediaContentEsService;
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
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class T4IndexingStep {

    /**
     * 中文/CJK文本很多分词器编码效率不如英文，几千个字符就可能超过embedding模型的token上限
     * （real case: 4063个字符被编码成8193个token，超过当时8192的上限）。这里按字符数保守截断，
     * 不追求精确算token数，留足够余量，截断只影响这次送去向量化的文本，不影响正文本身的存储/展示。
     *
     * vLLM部署的 --max-model-len 已经从8192调到16384（模型本身原生支持到32768，
     * 8192只是之前部署时设的偏保守的值），这里的截断阈值跟着放宽到6000字符——
     * 按最坏情况每字符2个token估算，6000字符约12000个token，离16384还留了近30%余量，
     * 绝大多数内容根本不会被截断，只有极端长的文章才会碰到这条线。
     */
    private static final int MAX_EMBEDDING_TEXT_LENGTH = 6000;

    private final AgentProxyClient agentProxyClient;
    private final MilvusVectorService milvusVectorService;
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
        String embeddingText = buildContentEmbeddingText(mc, t1View);

        String textVectorId = null;
        boolean textEmbeddingSkipped = !hasText(embeddingText);
        if (textEmbeddingSkipped) {
            log.info("[T4] 跳过文本向量化：embedding text为空, contentId={}", mc.getId());
        } else {
            T4EmbeddingRequest request = new T4EmbeddingRequest();
            request.setText(truncateForEmbedding(embeddingText));
            T4EmbeddingResponse embeddingResponse = agentProxyClient.call(
                    "T4", "generate_text_embedding", request, T4EmbeddingResponse.class);
            float[] embedding = embeddingResponse == null ? null : embeddingResponse.getEmbedding();
            if (embedding == null) {
                // 调用失败（比如超过模型token上限、网络问题等）不应该让整条流水线任务失败，
                // 按"跳过文本向量化"处理，其余步骤（ES索引、pipeline状态推进）照常进行，
                // t4_output.textEmbeddingSkipped=true 会如实记录这次没有向量，方便后续排查/重跑
                log.warn("[T4] 文本向量化调用失败，跳过本次向量化，contentId={}", mc.getId());
                textEmbeddingSkipped = true;
            } else {
                textVectorId = milvusVectorService.insertTextEmbedding(
                        mc.getId().toString(),
                        "media_content",
                        mc.getPlatform(),
                        mc.getLanguage(),
                        mc.getPublishedAt() != null ? mc.getPublishedAt().toEpochSecond() : 0L,
                        0f,
                        embedding);
            }
        }

        mediaContentEsService.index(mc.getId().toString(), buildEsDocument(mc, t1View));

        RawRecord rawRecord = rawRecordMapper.selectById(task.getRawRecordId());
        rawRecord.setT4Output(buildT4Output(textVectorId, textEmbeddingSkipped));
        rawRecord.setPipelineStatus(PipelineStatus.T4_INDEXED.name());
        rawRecordMapper.updateById(rawRecord);

        task.setT4Status("done");
        task.setT4DoneAt(OffsetDateTime.now());
        task.setT4DurationMs((int) java.time.Duration.between(startedAt, OffsetDateTime.now()).toMillis());
        pipelineTaskMapper.updateById(task);
    }

    private String buildContentEmbeddingText(MediaContent mc, T1AnnotationView t1View) {
        if (mc == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        appendEmbeddingField(text, "title", mc.getTitle());
        appendEmbeddingField(text, "summary", t1View.summaryText());
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

    private String buildT4Output(String textVectorId, boolean textEmbeddingSkipped) {
        String vectorValue = textVectorId != null ? "\"" + textVectorId + "\"" : "null";
        return "{\"textVectorId\":" + vectorValue
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
}

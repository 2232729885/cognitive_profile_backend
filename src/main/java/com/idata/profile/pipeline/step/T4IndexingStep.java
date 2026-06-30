package com.idata.profile.pipeline.step;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingRequest;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingResponse;
import com.idata.profile.common.constant.PipelineStatus;
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
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class T4IndexingStep {

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

        T4EmbeddingRequest request = new T4EmbeddingRequest();
        request.setText(mc.getBodyText());
        T4EmbeddingResponse embeddingResponse = agentProxyClient.call(
                "T4", "generate_text_embedding", request, T4EmbeddingResponse.class);
        float[] embedding = embeddingResponse == null ? null : embeddingResponse.getEmbedding();

        String textVectorId = milvusVectorService.insertTextEmbedding(
                mc.getId().toString(),
                "media_content",
                mc.getPlatform(),
                mc.getLanguage(),
                mc.getPublishedAt() != null ? mc.getPublishedAt().toEpochSecond() : 0L,
                0f,
                embedding);

        mediaContentEsService.index(mc.getId().toString(), buildEsDocument(mc));

        RawRecord rawRecord = rawRecordMapper.selectById(task.getRawRecordId());
        rawRecord.setT4Output("{\"textVectorId\":\"" + textVectorId + "\"}");
        rawRecord.setPipelineStatus(PipelineStatus.T4_INDEXED.name());
        rawRecordMapper.updateById(rawRecord);

        task.setT4Status("done");
        task.setT4DoneAt(OffsetDateTime.now());
        task.setT4DurationMs((int) java.time.Duration.between(startedAt, OffsetDateTime.now()).toMillis());
        pipelineTaskMapper.updateById(task);
    }

    private Object buildEsDocument(MediaContent mc) {
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
        document.put("topic_category", mc.getTopicCategory());
        document.put("topic_subcategory", mc.getTopicSubcategory());
        document.put("sentiment_label", mc.getSentimentLabel());
        document.put("sentiment_score", mc.getSentimentScore());
        document.put("stance_label", mc.getStanceLabel());
        document.put("stance_target", mc.getStanceTarget());
        document.put("aigc_score", mc.getAigcScore());
        document.put("aigc_type", mc.getAigcType());
        document.put("narrative_hint", mc.getNarrativeHint());
        document.put("updated_at", mc.getUpdatedAt() != null ? mc.getUpdatedAt().toString() : null);
        return document;
    }
}

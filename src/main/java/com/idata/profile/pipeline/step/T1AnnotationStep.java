package com.idata.profile.pipeline.step;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateRequest;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateResponse;
import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.common.util.ImageAnnotationUtil;
import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.content.MediaAssetMapper;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class T1AnnotationStep {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentProxyClient agentProxyClient;
    private final MediaContentMapper mediaContentMapper;
    private final MediaAssetMapper mediaAssetMapper;
    private final RawRecordMapper rawRecordMapper;
    private final PipelineTaskMapper pipelineTaskMapper;
    private final ImageAnnotationUtil imageAnnotationUtil;
    private final Neo4jGraphService neo4jGraphService;

    public void run(PipelineTask task) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        task.setT1Status("running");
        task.setT1StartedAt(startedAt);
        pipelineTaskMapper.updateById(task);

        MediaContent mc = mediaContentMapper.selectById(task.getContentId());
        T1AnnotateResponse response = null;

        boolean hasText = hasText(mc.getBodyText());
        List<MediaAsset> assets = (mc.getMediaAssetIds() != null && mc.getMediaAssetIds().length > 0)
                ? mediaAssetMapper.selectByIds(List.of(mc.getMediaAssetIds()))
                : List.of();

        if (hasText || !assets.isEmpty()) {
            T1AnnotateRequest request = buildRequest(mc, assets);
            response = agentProxyClient.call("T1", "annotate_content", request, T1AnnotateResponse.class);
            applyAnnotations(mc, response);
            mediaContentMapper.updateById(mc);
            if (response != null) {
                syncT1AnnotationsToNeo4j(mc, response);
                markAssetsAnnotated(assets);
            }
        } else {
            log.info("[T1] skip annotation: text and media assets are empty, contentId={}", mc.getId());
        }

        completeTask(task, startedAt, response);
    }

    private T1AnnotateRequest buildRequest(MediaContent mc, List<MediaAsset> assets) {
        T1AnnotateRequest request = new T1AnnotateRequest();
        request.setTitle(mc.getTitle());
        request.setText(mc.getBodyText());
        request.setLanguage(mc.getLanguage());

        request.setMedias(assets.stream()
                .filter(a -> "image".equals(a.getAssetType()) || "video".equals(a.getAssetType()))
                .map(a -> {
                    T1AnnotateRequest.MediaItem item = new T1AnnotateRequest.MediaItem();
                    item.setId(a.getId().toString());
                    item.setUrl(imageAnnotationUtil.buildImageUrl(a));
                    item.setMediaType(a.getAssetType());
                    return item;
                })
                .filter(item -> item.getUrl() != null)
                .toList());

        T1AnnotateRequest.Context context = new T1AnnotateRequest.Context();
        context.setContentId(mc.getId().toString());
        context.setPlatform(mc.getPlatform());
        context.setContentType(mc.getContentType());
        context.setAuthorHandle(mc.getAuthorPlatformUserId());
        context.setPublishedAt(mc.getPublishedAt() != null ? mc.getPublishedAt().toString() : null);
        context.setHashtags(stringList(mc.getHashtags()));
        context.setLikeCount(countOrZero(mc.getLikeCount()));
        context.setCommentCount(countOrZero(mc.getCommentCount()));
        context.setShareCount(countOrZero(mc.getShareCount()));
        context.setRepostCount(countOrZero(mc.getRepostCount()));
        context.setViewCount(countOrZero(mc.getViewCount()));
        context.setParentContentId(mc.getParentContentId());
        context.setUrl(mc.getUrl());
        request.setContext(context);
        return request;
    }

    private void markAssetsAnnotated(List<MediaAsset> assets) {
        for (MediaAsset asset : assets) {
            try {
                mediaAssetMapper.markT1Annotated(asset.getId());
            } catch (Exception e) {
                log.warn("[T1] failed to mark asset annotated, assetId={}", asset.getId(), e);
            }
        }
    }

    private void completeTask(PipelineTask task, OffsetDateTime startedAt, T1AnnotateResponse response) {
        RawRecord rawRecord = rawRecordMapper.selectById(task.getRawRecordId());
        rawRecord.setT1Output(response != null ? toJson(response) : null);
        rawRecord.setPipelineStatus(PipelineStatus.T1_DONE.name());
        rawRecordMapper.updateById(rawRecord);

        task.setT1Status("done");
        task.setT1DoneAt(OffsetDateTime.now());
        task.setT1DurationMs((int) java.time.Duration.between(startedAt, OffsetDateTime.now()).toMillis());
        pipelineTaskMapper.updateById(task);
    }

    private void applyAnnotations(MediaContent mc, T1AnnotateResponse response) {
        try {
            mc.setT1Annotation(OBJECT_MAPPER.writeValueAsString(response));
        } catch (JacksonException e) {
            log.warn("Failed to serialize T1 annotation, contentId={}", mc.getId(), e);
        }
        mc.setT1AnnotatedAt(OffsetDateTime.now());
    }

    private void syncT1AnnotationsToNeo4j(MediaContent mc, T1AnnotateResponse response) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(response);
            neo4jGraphService.mergeNode("MediaContent", mc.getId().toString(),
                    Map.of("t1Annotation", json));
            log.debug("[T1] Neo4j sync success, contentId={}", mc.getId());
        } catch (Exception e) {
            log.warn("[T1] Neo4j sync failed, contentId={}", mc.getId(), e);
        }
    }

    private String toJson(T1AnnotateResponse response) {
        try {
            return OBJECT_MAPPER.writeValueAsString(response);
        } catch (JacksonException e) {
            log.warn("Failed to serialize T1 response");
            return null;
        }
    }

    private List<String> stringList(String[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private Long countOrZero(Long value) {
        return value != null ? value : 0L;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

package com.idata.profile.batch.asset;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateRequest;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateResponse;
import com.idata.profile.common.util.ImageAnnotationUtil;
import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.mapper.content.MediaAssetMapper;
import com.idata.profile.mapper.content.MediaContentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageEmbeddingJob {

    private static final int BATCH_LIMIT = 200;
    private static final int T1_BACKFILL_LIMIT = 200;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ImageEmbeddingService imageEmbeddingService;
    private final MediaAssetMapper mediaAssetMapper;
    private final MediaContentMapper mediaContentMapper;
    private final AgentProxyClient agentProxyClient;
    private final ImageAnnotationUtil imageAnnotationUtil;

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void run() {
        log.info("[ImageEmbeddingJob] start");

        backfillContentIds();
        backfillT1ImageAnnotations();

        int successCount = imageEmbeddingService.processPending(BATCH_LIMIT);
        if (successCount > 0) {
            log.info("Image embedding scheduled job indexed {} assets", successCount);
        }
        int ocrSuccessCount = imageEmbeddingService.processPendingOcr(BATCH_LIMIT);
        if (ocrSuccessCount > 0) {
            log.info("Image OCR scheduled job processed {} assets", ocrSuccessCount);
        }
        int captionSuccessCount = imageEmbeddingService.processPendingCaption(BATCH_LIMIT);
        if (captionSuccessCount > 0) {
            log.info("Image caption scheduled job processed {} assets", captionSuccessCount);
        }
        int esBackfillCount = imageEmbeddingService.backfillImageAssetEsIndex(BATCH_LIMIT);
        if (esBackfillCount > 0) {
            log.info("Image asset ES index backfilled {} assets", esBackfillCount);
        }
    }

    private void backfillContentIds() {
        int updated = mediaContentMapper.backfillMediaAssetIds();
        if (updated > 0) {
            log.info("[ImageEmbeddingJob] media_contents.media_asset_ids backfilled, updated={}", updated);
        }
    }

    private void backfillT1ImageAnnotations() {
        List<UUID> contentIds = mediaAssetMapper.selectContentIdsPendingT1Annotation(T1_BACKFILL_LIMIT);
        if (contentIds.isEmpty()) {
            return;
        }

        log.info("[ImageEmbeddingJob] T1 multimodal re-annotation backfill, count={}", contentIds.size());
        int success = 0;
        for (UUID contentId : contentIds) {
            try {
                MediaContent mc = mediaContentMapper.selectById(contentId);
                if (mc == null) {
                    continue;
                }
                List<MediaAsset> assets = mediaAssetMapper.selectByContentId(contentId);

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
                context.setHashtags(mc.getHashtags() != null ? List.of(mc.getHashtags()) : null);
                context.setParentContentId(mc.getParentContentId());
                context.setUrl(mc.getUrl());
                request.setContext(context);

                T1AnnotateResponse response = agentProxyClient.call(
                        "T1", "annotate_content", request, T1AnnotateResponse.class);
                if (response == null) {
                    continue;
                }

                mc.setT1Annotation(OBJECT_MAPPER.writeValueAsString(response));
                mc.setT1AnnotatedAt(java.time.OffsetDateTime.now());
                mediaContentMapper.updateById(mc);

                for (MediaAsset asset : assets) {
                    mediaAssetMapper.markT1Annotated(asset.getId());
                }

                success++;
            } catch (Exception e) {
                log.warn("[ImageEmbeddingJob] T1 multimodal re-annotation failed, contentId={}", contentId, e);
            }
        }
        log.info("[ImageEmbeddingJob] T1 multimodal re-annotation completed, success={}/{}", success, contentIds.size());
    }
}

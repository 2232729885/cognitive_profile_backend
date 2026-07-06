package com.idata.profile.batch.asset;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateRequest;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateResponse;
import com.idata.profile.common.util.ImageAnnotationUtil;
import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.mapper.content.MediaAssetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageEmbeddingJob {

    private static final int BATCH_LIMIT = 200;
    private static final int T1_BACKFILL_LIMIT = 200;

    private final ImageEmbeddingService imageEmbeddingService;
    private final MediaAssetMapper mediaAssetMapper;
    private final AgentProxyClient agentProxyClient;
    private final ImageAnnotationUtil imageAnnotationUtil;

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void run() {
        log.info("[ImageEmbeddingJob] 开始处理");

        backfillT1ImageAnnotations();

        int successCount = imageEmbeddingService.processPending(BATCH_LIMIT);
        if (successCount > 0) {
            log.info("Image embedding scheduled job indexed {} assets", successCount);
        }
    }

    private void backfillT1ImageAnnotations() {
        List<MediaAsset> pending = mediaAssetMapper.selectPendingT1Annotation(T1_BACKFILL_LIMIT);
        if (pending.isEmpty()) {
            return;
        }

        log.info("[ImageEmbeddingJob] T1图像标注回填, count={}", pending.size());
        int success = 0;
        for (MediaAsset asset : pending) {
            try {
                String imageUrl = imageAnnotationUtil.buildImageUrl(asset);
                if (imageUrl == null) {
                    log.debug("[ImageEmbeddingJob] 图片无法构造URL，跳过, assetId={}", asset.getId());
                    continue;
                }

                T1AnnotateRequest request = new T1AnnotateRequest();
                request.setImageUrl(imageUrl);
                request.setAnnotationTypes(List.of("objects", "scene", "text_ocr"));

                T1AnnotateResponse response = agentProxyClient.call(
                        "T1", "annotate_image", request, T1AnnotateResponse.class);
                if (response == null) {
                    continue;
                }

                imageAnnotationUtil.applyImageAnnotations(asset, response);
                asset.setT1Annotated(true);
                mediaAssetMapper.updateById(asset);
                success++;
            } catch (Exception e) {
                log.warn("[ImageEmbeddingJob] T1图像标注回填失败, assetId={}", asset.getId(), e);
            }
        }
        log.info("[ImageEmbeddingJob] T1图像标注回填完成, success={}/{}", success, pending.size());
    }
}

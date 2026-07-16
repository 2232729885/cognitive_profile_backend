package com.idata.profile.batch.asset;

import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.common.util.ImageAnnotationUtil;
import com.idata.profile.infra.embedding.EmbeddingService;
import com.idata.profile.infra.milvus.MilvusVectorService;
import com.idata.profile.mapper.content.MediaAssetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageEmbeddingService {

    private final MediaAssetMapper mediaAssetMapper;
    private final EmbeddingService embeddingService;
    private final MilvusVectorService milvusVectorService;
    private final ExecutorService pipelineThreadPool;
    private final ImageAnnotationUtil imageAnnotationUtil;

    public void submitAfterCommit(UUID assetId) {
        Runnable task = () -> pipelineThreadPool.submit(() -> processById(assetId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }

    public int processPending(int limit) {
        List<MediaAsset> pending = mediaAssetMapper.selectPendingEmbedding(limit);
        int successCount = 0;
        for (MediaAsset asset : pending) {
            if (process(asset)) {
                successCount++;
            }
        }
        return successCount;
    }

    public boolean processById(UUID assetId) {
        MediaAsset asset = mediaAssetMapper.selectById(assetId);
        if (asset == null) {
            log.warn("Media asset not found for image embedding, assetId={}", assetId);
            return false;
        }
        if (asset.getEmbeddingId() != null && !asset.getEmbeddingId().isBlank()) {
            return true;
        }
        return process(asset);
    }

    private boolean process(MediaAsset asset) {
        try {
            if (!"image".equalsIgnoreCase(asset.getAssetType())) {
                log.debug("Skip non-image asset embedding, assetId={}, assetType={}",
                        asset.getId(), asset.getAssetType());
                return false;
            }
            String imageUrl = resolveImageUrl(asset);
            float[] embedding = embeddingService.generateImageEmbedding(imageUrl);
            if (embedding == null) {
                return false;
            }

            String vectorId = milvusVectorService.insertImageEmbedding(
                    asset.getId().toString(),
                    asset.getContentId() != null ? asset.getContentId().toString() : null,
                    null,
                    0f,
                    embedding);

            asset.setEmbeddingId(vectorId);
            mediaAssetMapper.updateById(asset);
            log.info("Image embedding indexed, assetId={}, vectorId={}", asset.getId(), vectorId);
            return true;
        } catch (Exception e) {
            log.error("Image embedding failed, assetId={}, assetType={}, imageUrl={}, sourceUrl={}, storageUri={}",
                    asset.getId(), asset.getAssetType(), safeImageUrl(asset),
                    asset.getSourceUrl(), asset.getStorageUri(), e);
            return false;
        }
    }

    private String resolveImageUrl(MediaAsset asset) {
        String imageUrl = imageAnnotationUtil.buildImageUrl(asset);
        if (imageUrl != null && !imageUrl.isBlank()) {
            return imageUrl;
        }
        throw new IllegalArgumentException("Media asset has no image URL or storage URI, assetId=" + asset.getId());
    }

    private String safeImageUrl(MediaAsset asset) {
        try {
            return resolveImageUrl(asset);
        } catch (Exception e) {
            return null;
        }
    }
}

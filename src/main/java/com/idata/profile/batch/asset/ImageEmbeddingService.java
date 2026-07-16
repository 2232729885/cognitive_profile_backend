package com.idata.profile.batch.asset;

import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.common.util.ImageAnnotationUtil;
import com.idata.profile.infra.elasticsearch.MediaAssetEsService;
import com.idata.profile.infra.embedding.EmbeddingService;
import com.idata.profile.infra.milvus.MilvusVectorService;
import com.idata.profile.infra.ocr.ImageOcrService;
import com.idata.profile.mapper.content.MediaAssetMapper;
import com.idata.profile.mapper.content.MediaContentMapper;
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
    private final MediaContentMapper mediaContentMapper;
    private final MediaAssetEsService mediaAssetEsService;
    private final EmbeddingService embeddingService;
    private final MilvusVectorService milvusVectorService;
    private final ImageOcrService imageOcrService;
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

    public int processPendingOcr(int limit) {
        List<MediaAsset> pending = mediaAssetMapper.selectPendingOcr(limit);
        int successCount = 0;
        for (MediaAsset asset : pending) {
            if (process(asset)) {
                successCount++;
            }
        }
        return successCount;
    }

    public int backfillImageAssetEsIndex(int limit) {
        List<MediaAsset> assets = mediaAssetMapper.selectImageAssetsWithOcrText(limit);
        for (MediaAsset asset : assets) {
            mediaAssetEsService.indexImageAsset(asset);
        }
        return assets.size();
    }

    public boolean processById(UUID assetId) {
        MediaAsset asset = mediaAssetMapper.selectById(assetId);
        if (asset == null) {
            log.warn("Media asset not found for image embedding, assetId={}", assetId);
            return false;
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
            boolean processed = false;
            boolean changed = false;
            MediaContent content = resolveLinkedContent(asset);
            if (asset.getContentId() == null && content != null && content.getId() != null) {
                asset.setContentId(content.getId());
                changed = true;
            }
            String contentId = asset.getContentId() != null ? asset.getContentId().toString() : null;
            String platform = content != null ? content.getPlatform() : null;

            if (asset.getOcrText() == null) {
                String ocrText = imageOcrService.extractText(imageUrl);
                if (ocrText != null) {
                    asset.setOcrText(ocrText);
                    changed = true;
                    processed = true;
                    log.info("Image OCR completed, assetId={}, textLength={}",
                            asset.getId(), ocrText.length());
                }
            }

            if (asset.getEmbeddingId() == null || asset.getEmbeddingId().isBlank()) {
                float[] embedding = embeddingService.generateImageEmbedding(imageUrl);
                if (embedding != null) {
                    String vectorId = milvusVectorService.insertImageEmbedding(
                            asset.getId().toString(),
                            asset.getSourceAssetId(),
                            contentId,
                            platform,
                            0f,
                            embedding);

                    asset.setEmbeddingId(vectorId);
                    changed = true;
                    processed = true;
                    log.info("Image embedding indexed, assetId={}, vectorId={}", asset.getId(), vectorId);
                }
            }

            if (hasText(asset.getOcrText())) {
                float[] ocrEmbedding = embeddingService.generateTextEmbedding(asset.getOcrText());
                if (ocrEmbedding != null) {
                    String vectorId = milvusVectorService.insertImageOcrEmbedding(
                            asset.getId().toString(),
                            asset.getSourceAssetId(),
                            contentId,
                            platform,
                            ocrEmbedding);
                    processed = true;
                    log.info("Image OCR embedding indexed, assetId={}, vectorId={}", asset.getId(), vectorId);
                }
            }

            if (changed) {
                mediaAssetMapper.updateById(asset);
            }
            mediaAssetEsService.indexImageAsset(asset);
            return processed;
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

    private MediaContent resolveLinkedContent(MediaAsset asset) {
        if (asset == null) {
            return null;
        }
        if (asset.getContentId() != null) {
            return mediaContentMapper.selectById(asset.getContentId());
        }
        if (hasText(asset.getSourceAssetId())) {
            return mediaContentMapper.selectBySourceMediaAssetId(asset.getSourceAssetId());
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

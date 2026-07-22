package com.idata.profile.batch.asset;

import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.common.util.ImageAnnotationUtil;
import com.idata.profile.infra.elasticsearch.MediaAssetEsService;
import com.idata.profile.infra.embedding.EmbeddingService;
import com.idata.profile.infra.media.MediaAsrService;
import com.idata.profile.infra.media.MediaCaptionService;
import com.idata.profile.infra.media.MediaSegmentService;
import com.idata.profile.infra.milvus.MilvusVectorService;
import com.idata.profile.infra.ocr.ImageOcrService;
import com.idata.profile.mapper.content.MediaAssetMapper;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.search.SearchQueryTranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
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
    private final MediaCaptionService mediaCaptionService;
    private final MediaAsrService mediaAsrService;
    private final MediaSegmentService mediaSegmentService;
    private final ExecutorService pipelineThreadPool;
    private final ImageAnnotationUtil imageAnnotationUtil;
    private final SearchQueryTranslationService translationService;

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
            mediaAssetEsService.indexAsset(asset);
        }
        return assets.size();
    }

    public int processPendingCaption(int limit) {
        List<MediaAsset> pending = mediaAssetMapper.selectPendingCaption(limit);
        int successCount = 0;
        for (MediaAsset asset : pending) {
            if (processMissingCaption(asset)) {
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
        return process(asset);
    }

    private boolean process(MediaAsset asset) {
        try {
            boolean processed = false;
            boolean changed = false;
            MediaContent content = resolveLinkedContent(asset);
            if (asset.getContentId() == null && content != null && content.getId() != null) {
                asset.setContentId(content.getId());
                changed = true;
            }
            String contentId = asset.getContentId() != null ? asset.getContentId().toString() : null;
            String platform = content != null ? content.getPlatform() : null;
            String mediaSource = resolveMediaSource(asset);

            if (isImage(asset)) {
                if (!hasText(mediaSource)) {
                    throw new IllegalArgumentException("Image asset has no URL or storage URI, assetId=" + asset.getId());
                }
            }

            if (isImage(asset) && asset.getOcrText() == null) {
                String ocrText = imageOcrService.extractText(mediaSource);
                if (ocrText != null) {
                    asset.setOcrText(ocrText);
                    changed = true;
                    processed = true;
                    log.info("Image OCR completed, assetId={}, textLength={}",
                            asset.getId(), ocrText.length());
                }
            }

            if (isAudioOrVideo(asset) && !hasText(asset.getAsrText())) {
                Path audioFile = mediaSegmentService.extractAudio(mediaSource);
                try {
                    String asrText = mediaAsrService.transcribe(audioFile);
                    if (hasText(asrText)) {
                        asset.setAsrText(asrText);
                        changed = true;
                        processed = true;
                        log.info("Media ASR completed, assetId={}, textLength={}",
                                asset.getId(), asrText.length());
                    }
                } finally {
                    mediaSegmentService.deleteQuietly(audioFile);
                }
            }

            String captionText = resolveCaptionText(asset, mediaSource);
            if (hasText(captionText) && !captionText.equals(asset.getCaptionText())) {
                asset.setCaptionText(captionText);
                changed = true;
            }
            String contentLanguage = content != null ? content.getLanguage() : null;
            SearchQueryTranslationService.TranslatedMediaText translatedMediaText =
                    translateMediaText(asset.getOcrText(), asset.getAsrText(), captionText, contentLanguage);
            float[] visualEmbedding = isImage(asset) ? embeddingService.generateImageEmbedding(mediaSource) : null;
            float[] ocrEmbedding = null;
            if (hasText(asset.getOcrText())) {
                ocrEmbedding = embeddingService.generateTextEmbedding(asset.getOcrText());
            }
            float[] asrEmbedding = null;
            if (hasText(asset.getAsrText())) {
                asrEmbedding = embeddingService.generateTextEmbedding(asset.getAsrText());
            }
            float[] captionEmbedding = null;
            if (hasText(captionText)) {
                captionEmbedding = embeddingService.generateTextEmbedding(captionText);
            }
            if (visualEmbedding != null || ocrEmbedding != null
                    || asrEmbedding != null || captionEmbedding != null) {
                String vectorId = milvusVectorService.upsertMediaAssetEmbedding(
                        asset.getId().toString(),
                        asset.getSourceAssetId(),
                        contentId,
                        platform,
                        asset.getAssetType(),
                        asset.getMimeType(),
                        null,
                        null,
                        visualEmbedding,
                        ocrEmbedding,
                        asrEmbedding,
                        captionEmbedding);
                upsertMediaAssetPivotEmbedding(
                        asset,
                        null,
                        contentId,
                        platform,
                        null,
                        null,
                        contentLanguage,
                        asset.getOcrText(),
                        asset.getAsrText(),
                        captionText,
                        translatedMediaText);
                asset.setEmbeddingId(vectorId);
                changed = true;
                processed = true;
                log.info("Media asset embeddings indexed, assetId={}, vectorId={}, hasVisualEmbedding={}, hasOcrEmbedding={}, hasAsrEmbedding={}, hasCaptionEmbedding={}",
                        asset.getId(), vectorId, visualEmbedding != null, ocrEmbedding != null,
                        asrEmbedding != null, captionEmbedding != null);
            }

            if (isVideo(asset) && hasText(mediaSource)) {
                boolean segmentProcessed = indexVideoSegments(asset, contentId, platform,
                        content != null ? content.getLanguage() : null, mediaSource);
                if (segmentProcessed) {
                    processed = true;
                    changed = true;
                }
            }

            if (changed) {
                mediaAssetMapper.updateById(asset);
            }
            mediaAssetEsService.indexAssetSegment(asset, null, null, null, captionText,
                    translatedMediaText.ocrText(), translatedMediaText.asrText(), translatedMediaText.captionText());
            return processed;
        } catch (Exception e) {
            log.error("Image embedding failed, assetId={}, assetType={}, imageUrl={}, sourceUrl={}, storageUri={}",
                    asset.getId(), asset.getAssetType(), safeImageUrl(asset),
                    asset.getSourceUrl(), asset.getStorageUri(), e);
            return false;
        }
    }

    private boolean processMissingCaption(MediaAsset asset) {
        if (asset == null || asset.getId() == null || hasText(asset.getCaptionText()) || !isImage(asset)) {
            return false;
        }
        try {
            String mediaSource = resolveMediaSource(asset);
            if (!hasText(mediaSource)) {
                return false;
            }
            String captionText = resolveCaptionText(asset, mediaSource);
            if (!hasText(captionText)) {
                return false;
            }

            asset.setCaptionText(captionText);
            MediaContent content = resolveLinkedContent(asset);
            if (asset.getContentId() == null && content != null && content.getId() != null) {
                asset.setContentId(content.getId());
            }
            mediaAssetMapper.updateById(asset);
            SearchQueryTranslationService.TranslatedMediaText translatedMediaText =
                    translateMediaText(asset.getOcrText(), null, captionText,
                            content != null ? content.getLanguage() : null);
            mediaAssetEsService.indexAssetSegment(asset, null, null, null, captionText,
                    translatedMediaText.ocrText(), translatedMediaText.asrText(), translatedMediaText.captionText());
            backfillCaptionEmbedding(asset, content, mediaSource, captionText, translatedMediaText);
            log.info("Image caption backfilled, assetId={}, textLength={}",
                    asset.getId(), captionText.length());
            return true;
        } catch (Exception e) {
            log.warn("Image caption backfill failed, assetId={}", asset.getId(), e);
            return false;
        }
    }

    private void backfillCaptionEmbedding(MediaAsset asset, MediaContent content,
                                          String mediaSource, String captionText,
                                          SearchQueryTranslationService.TranslatedMediaText translatedMediaText) {
        try {
            String contentId = asset.getContentId() != null ? asset.getContentId().toString() : null;
            String platform = content != null ? content.getPlatform() : null;
            float[] visualEmbedding = embeddingService.generateImageEmbedding(mediaSource);
            float[] ocrEmbedding = hasText(asset.getOcrText())
                    ? embeddingService.generateTextEmbedding(asset.getOcrText())
                    : null;
            float[] captionEmbedding = embeddingService.generateTextEmbedding(captionText);
            String vectorId = milvusVectorService.upsertMediaAssetEmbedding(
                    asset.getId().toString(),
                    asset.getSourceAssetId(),
                    contentId,
                    platform,
                    asset.getAssetType(),
                    asset.getMimeType(),
                    null,
                    null,
                    visualEmbedding,
                    ocrEmbedding,
                    null,
                    captionEmbedding);
            upsertMediaAssetPivotEmbedding(
                    asset,
                    null,
                    contentId,
                    platform,
                    null,
                    null,
                    content != null ? content.getLanguage() : null,
                    asset.getOcrText(),
                    null,
                    captionText,
                    translatedMediaText);
            if (hasText(vectorId) && !vectorId.equals(asset.getEmbeddingId())) {
                asset.setEmbeddingId(vectorId);
                mediaAssetMapper.updateById(asset);
            }
        } catch (Exception e) {
            log.warn("Image caption embedding backfill failed, assetId={}", asset.getId(), e);
        }
    }

    private boolean indexVideoSegments(MediaAsset asset, String contentId, String platform,
                                       String language, String mediaSource) {
        boolean processed = false;
        List<MediaSegmentService.VideoSegmentFrame> frames =
                mediaSegmentService.extractVideoSegmentFrames(mediaSource, asset.getDurationSeconds());
        for (MediaSegmentService.VideoSegmentFrame frame : frames) {
            try {
                String caption = mediaCaptionService.describeImageFile(frame.frameFile());
                float[] visualEmbedding = embeddingService.generateImageEmbedding(toDataUrl(frame.frameFile()));
                float[] captionEmbedding = hasText(caption)
                        ? embeddingService.generateTextEmbedding(caption)
                        : null;
                SearchQueryTranslationService.TranslatedMediaText translatedCaption =
                        translateMediaText(null, null, caption, language);
                String vectorId = milvusVectorService.upsertMediaAssetEmbedding(
                        asset.getId().toString(),
                        frame.segmentId(),
                        asset.getSourceAssetId(),
                        contentId,
                        platform,
                        asset.getAssetType(),
                        asset.getMimeType(),
                        frame.segmentStart(),
                        frame.segmentEnd(),
                        visualEmbedding,
                        null,
                        null,
                        captionEmbedding);
                upsertMediaAssetPivotEmbedding(
                        asset,
                        frame.segmentId(),
                        contentId,
                        platform,
                        frame.segmentStart(),
                        frame.segmentEnd(),
                        language,
                        null,
                        null,
                        caption,
                        translatedCaption);
                mediaAssetEsService.indexAssetSegment(asset, frame.segmentId(),
                        frame.segmentStart(), frame.segmentEnd(), caption,
                        translatedCaption.ocrText(), translatedCaption.asrText(), translatedCaption.captionText());
                if (vectorId != null) {
                    asset.setEmbeddingId(vectorId);
                    processed = true;
                }
                log.info("Video segment indexed, assetId={}, segmentId={}, vectorId={}, hasCaption={}",
                        asset.getId(), frame.segmentId(), vectorId, hasText(caption));
            } catch (Exception e) {
                log.warn("Video segment indexing failed, assetId={}, segmentId={}",
                        asset.getId(), frame.segmentId(), e);
            } finally {
                mediaSegmentService.deleteQuietly(frame.frameFile());
            }
        }
        return processed;
    }

    private String upsertMediaAssetPivotEmbedding(MediaAsset asset, String segmentId,
                                                  String contentId, String platform,
                                                  Float segmentStart, Float segmentEnd,
                                                  String language,
                                                  String ocrText, String asrText, String captionText,
                                                  SearchQueryTranslationService.TranslatedMediaText translated) {
        if (asset == null || asset.getId() == null
                || (!hasText(ocrText) && !hasText(asrText) && !hasText(captionText))) {
            return null;
        }
        try {
            SearchQueryTranslationService.TranslatedMediaText safeTranslated =
                    translated != null ? translated : translateMediaText(ocrText, asrText, captionText, language);
            float[] pivotOcrEmbedding = hasText(safeTranslated.ocrText())
                    ? embeddingService.generateTextEmbedding(safeTranslated.ocrText())
                    : null;
            float[] pivotAsrEmbedding = hasText(safeTranslated.asrText())
                    ? embeddingService.generateTextEmbedding(safeTranslated.asrText())
                    : null;
            float[] pivotCaptionEmbedding = hasText(safeTranslated.captionText())
                    ? embeddingService.generateTextEmbedding(safeTranslated.captionText())
                    : null;
            if (pivotOcrEmbedding == null && pivotAsrEmbedding == null && pivotCaptionEmbedding == null) {
                return null;
            }
            return milvusVectorService.upsertMediaAssetPivotEmbedding(
                    asset.getId().toString(),
                    segmentId,
                    asset.getSourceAssetId(),
                    contentId,
                    platform,
                    asset.getAssetType(),
                    asset.getMimeType(),
                    segmentStart,
                    segmentEnd,
                    pivotOcrEmbedding,
                    pivotAsrEmbedding,
                    pivotCaptionEmbedding);
        } catch (Exception e) {
            log.debug("Media asset pivot embedding skipped, assetId={}, segmentId={}, reason={}",
                    asset.getId(), segmentId, rootMessage(e));
            return null;
        }
    }

    private SearchQueryTranslationService.TranslatedMediaText translateMediaText(
            String ocrText, String asrText, String captionText, String language) {
        try {
            return translationService.translateMediaText(ocrText, asrText, captionText, language);
        } catch (Exception e) {
            log.debug("Media asset text translation skipped, reason={}", rootMessage(e));
            return SearchQueryTranslationService.TranslatedMediaText.empty();
        }
    }

    private String toDataUrl(Path imageFile) throws IOException {
        if (imageFile == null || !Files.isRegularFile(imageFile)) {
            return null;
        }
        return "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(Files.readAllBytes(imageFile));
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

    private String resolveMediaSource(MediaAsset asset) {
        return imageAnnotationUtil.buildImageUrl(asset);
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

    private boolean isImage(MediaAsset asset) {
        return asset != null && "image".equalsIgnoreCase(asset.getAssetType());
    }

    private boolean isVideo(MediaAsset asset) {
        return asset != null && "video".equalsIgnoreCase(asset.getAssetType());
    }

    private boolean isAudioOrVideo(MediaAsset asset) {
        return asset != null && ("video".equalsIgnoreCase(asset.getAssetType())
                || "audio".equalsIgnoreCase(asset.getAssetType()));
    }

    private String resolveCaptionText(MediaAsset asset, String mediaSource) {
        String storedCaption = asset != null ? asset.getCaptionText() : null;
        if (hasText(storedCaption)) {
            return storedCaption.trim();
        }
        String legacyCaption = buildCaptionText(asset);
        if (isImage(asset)) {
            return firstText(mediaCaptionService.describeImageUrl(mediaSource), legacyCaption);
        }
        return legacyCaption;
    }

    @SuppressWarnings("deprecation")
    private String buildCaptionText(MediaAsset asset) {
        if (asset == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        appendCaptionPart(text, asset.getSceneLabel());
        appendCaptionPart(text, asset.getObjectAnnotations());
        return text.isEmpty() ? null : text.toString();
    }

    private void appendCaptionPart(StringBuilder text, String value) {
        if (!hasText(value)) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(value.trim());
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String rootMessage(Throwable error) {
        Throwable root = error;
        while (root != null && root.getCause() != null) {
            root = root.getCause();
        }
        return root != null && root.getMessage() != null ? root.getMessage() : String.valueOf(error);
    }
}

package com.idata.profile.batch.asset;

import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.common.util.ImageAnnotationUtil;
import com.idata.profile.common.util.TextEncodingRepairUtil;
import com.idata.profile.infra.elasticsearch.MediaAssetEsService;
import com.idata.profile.infra.embedding.EmbeddingService;
import com.idata.profile.infra.media.MediaAsrService;
import com.idata.profile.infra.media.MediaCaptionService;
import com.idata.profile.infra.media.MediaSegmentService;
import com.idata.profile.infra.minio.MinioStorageService;
import com.idata.profile.infra.milvus.MilvusVectorService;
import com.idata.profile.infra.ocr.ImageOcrService;
import com.idata.profile.mapper.content.MediaAssetMapper;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.search.SearchQueryTranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final MinioStorageService minioStorageService;

    @Value("${minio.endpoint}")
    private String minioEndpoint;

    private static final String MEDIA_BUCKET = "media-assets";

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
            boolean changed = cleanStoredMediaText(asset);
            MediaContent content = resolveLinkedContent(asset);
            String beforeTranslatedOcrText = asset.getTranslatedOcrText();
            String beforeTranslatedAsrText = asset.getTranslatedAsrText();
            String beforeTranslatedCaptionText = asset.getTranslatedCaptionText();
            SearchQueryTranslationService.TranslatedMediaText translatedMediaText =
                    resolveTranslatedMediaText(asset, asset.getCaptionText(),
                            content != null ? content.getLanguage() : null);
            changed |= translatedFieldsChanged(asset, beforeTranslatedOcrText,
                    beforeTranslatedAsrText, beforeTranslatedCaptionText);
            if (changed) {
                mediaAssetMapper.updateById(asset);
            }
            mediaAssetEsService.indexAssetSegment(asset, null, null, null,
                    asset.getOcrText(), asset.getAsrText(), asset.getCaptionText(),
                    translatedMediaText.ocrText(), translatedMediaText.asrText(), translatedMediaText.captionText());
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
            changed |= cleanStoredMediaText(asset);

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
                    mediaAssetMapper.updateById(asset);
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
                        mediaAssetMapper.updateById(asset);
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
            String beforeTranslatedOcrText = asset.getTranslatedOcrText();
            String beforeTranslatedAsrText = asset.getTranslatedAsrText();
            String beforeTranslatedCaptionText = asset.getTranslatedCaptionText();
            SearchQueryTranslationService.TranslatedMediaText translatedMediaText =
                    resolveTranslatedMediaText(asset, captionText, contentLanguage);
            changed |= translatedFieldsChanged(asset, beforeTranslatedOcrText,
                    beforeTranslatedAsrText, beforeTranslatedCaptionText);
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
            mediaAssetEsService.indexAssetSegment(asset, null, null, null,
                    asset.getOcrText(), asset.getAsrText(), captionText,
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
            SearchQueryTranslationService.TranslatedMediaText translatedMediaText =
                    resolveTranslatedMediaText(asset, captionText,
                            content != null ? content.getLanguage() : null);
            mediaAssetMapper.updateById(asset);
            mediaAssetEsService.indexAssetSegment(asset, null, null, null,
                    asset.getOcrText(), asset.getAsrText(), captionText,
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
        Map<String, String> segmentAsrCache = new LinkedHashMap<>();
        for (MediaSegmentService.VideoSegmentFrame frame : frames) {
            try {
                String caption = mediaCaptionService.describeImageFile(frame.frameFile());
                String ocrText = imageOcrService.extractTextFromImageFile(frame.frameFile());
                String asrText = resolveSegmentAsrText(mediaSource, frame, segmentAsrCache);
                UploadedFrame uploadedFrame = uploadFrame(asset, frame);
                float[] visualEmbedding = embeddingService.generateImageEmbedding(toDataUrl(frame.frameFile()));
                float[] ocrEmbedding = hasText(ocrText)
                        ? embeddingService.generateTextEmbedding(ocrText)
                        : null;
                float[] asrEmbedding = hasText(asrText)
                        ? embeddingService.generateTextEmbedding(asrText)
                        : null;
                float[] captionEmbedding = hasText(caption)
                        ? embeddingService.generateTextEmbedding(caption)
                        : null;
                SearchQueryTranslationService.TranslatedMediaText translatedCaption =
                        translateMediaText(ocrText, asrText, caption, language);
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
                        ocrEmbedding,
                        asrEmbedding,
                        captionEmbedding);
                upsertMediaAssetPivotEmbedding(
                        asset,
                        frame.segmentId(),
                        contentId,
                        platform,
                        frame.segmentStart(),
                        frame.segmentEnd(),
                        language,
                        ocrText,
                        asrText,
                        caption,
                        translatedCaption);
                mediaAssetEsService.indexAssetSegment(asset, frame.segmentId(),
                        frame.segmentStart(), frame.segmentEnd(), ocrText, asrText, caption,
                        translatedCaption.ocrText(), translatedCaption.asrText(), translatedCaption.captionText(),
                        uploadedFrame != null ? uploadedFrame.url() : null,
                        uploadedFrame != null ? uploadedFrame.bucket() : null,
                        uploadedFrame != null ? uploadedFrame.key() : null);
                if (vectorId != null) {
                    asset.setEmbeddingId(vectorId);
                    processed = true;
                }
                log.info("Video segment indexed, assetId={}, segmentId={}, vectorId={}, hasOcr={}, hasAsr={}, hasCaption={}",
                        asset.getId(), frame.segmentId(), vectorId,
                        hasText(ocrText), hasText(asrText), hasText(caption));
            } catch (Exception e) {
                log.warn("Video segment indexing failed, assetId={}, segmentId={}",
                        asset.getId(), frame.segmentId(), e);
            } finally {
                mediaSegmentService.deleteQuietly(frame.frameFile());
            }
        }
        return processed;
    }

    private String resolveSegmentAsrText(String mediaSource, MediaSegmentService.VideoSegmentFrame frame,
                                         Map<String, String> segmentAsrCache) {
        if (frame == null || segmentAsrCache == null) {
            return null;
        }
        String key = segmentRangeKey(frame);
        if (segmentAsrCache.containsKey(key)) {
            return segmentAsrCache.get(key);
        }
        Path audioFile = mediaSegmentService.extractAudioSegment(
                mediaSource, frame.segmentStart(), frame.segmentEnd());
        try {
            String asrText = mediaAsrService.transcribe(audioFile);
            segmentAsrCache.put(key, asrText);
            return asrText;
        } finally {
            mediaSegmentService.deleteQuietly(audioFile);
        }
    }

    private String segmentRangeKey(MediaSegmentService.VideoSegmentFrame frame) {
        return frame.segmentStart() + ":" + frame.segmentEnd();
    }

    private UploadedFrame uploadFrame(MediaAsset asset, MediaSegmentService.VideoSegmentFrame frame) {
        if (asset == null || asset.getId() == null || frame == null
                || frame.frameFile() == null || !Files.isRegularFile(frame.frameFile())) {
            return null;
        }
        String key = "media-derived/video-frames/" + asset.getId() + "/" + frame.segmentId() + ".jpg";
        try {
            minioStorageService.upload(MEDIA_BUCKET, key, Files.readAllBytes(frame.frameFile()), "image/jpeg");
            return new UploadedFrame(MEDIA_BUCKET, key, buildMinioUrl(MEDIA_BUCKET, key));
        } catch (Exception e) {
            log.warn("Video segment frame upload failed, assetId={}, segmentId={}, key={}",
                    asset.getId(), frame.segmentId(), key, e);
            return null;
        }
    }

    private String buildMinioUrl(String bucket, String key) {
        return trimTrailingSlash(minioEndpoint) + "/" + bucket + "/" + key;
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
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

    private SearchQueryTranslationService.TranslatedMediaText resolveTranslatedMediaText(
            MediaAsset asset, String captionText, String language) {
        if (asset == null) {
            return SearchQueryTranslationService.TranslatedMediaText.empty();
        }
        String translatedOcrText = cleanMediaText(asset.getTranslatedOcrText());
        String translatedAsrText = cleanMediaText(asset.getTranslatedAsrText());
        String translatedCaptionText = cleanMediaText(asset.getTranslatedCaptionText());

        if (!translationService.needsPivotTranslation(asset.getOcrText())) {
            translatedOcrText = null;
        }
        if (!translationService.needsPivotTranslation(asset.getAsrText())) {
            translatedAsrText = null;
        }
        if (!translationService.needsPivotTranslation(captionText)) {
            translatedCaptionText = null;
        }

        boolean needsOcr = translationService.needsPivotTranslation(asset.getOcrText()) && !hasText(translatedOcrText);
        boolean needsAsr = translationService.needsPivotTranslation(asset.getAsrText()) && !hasText(translatedAsrText);
        boolean needsCaption = translationService.needsPivotTranslation(captionText) && !hasText(translatedCaptionText);
        if (needsOcr || needsAsr || needsCaption) {
            SearchQueryTranslationService.TranslatedMediaText generated = translateMediaText(
                    needsOcr ? asset.getOcrText() : null,
                    needsAsr ? asset.getAsrText() : null,
                    needsCaption ? captionText : null,
                    language);
            if (needsOcr && hasText(generated.ocrText())) {
                translatedOcrText = generated.ocrText();
            }
            if (needsAsr && hasText(generated.asrText())) {
                translatedAsrText = generated.asrText();
            }
            if (needsCaption && hasText(generated.captionText())) {
                translatedCaptionText = generated.captionText();
            }
            if ((needsOcr && !hasText(generated.ocrText()))
                    || (needsAsr && !hasText(generated.asrText()))
                    || (needsCaption && !hasText(generated.captionText()))) {
                log.warn("Media asset text translation produced empty result, assetId={}, contentId={}, language={}, needsOcr={}, needsAsr={}, needsCaption={}, ocrLength={}, asrLength={}, captionLength={}",
                        asset.getId(), asset.getContentId(), language, needsOcr, needsAsr, needsCaption,
                        textLength(asset.getOcrText()), textLength(asset.getAsrText()), textLength(captionText));
            }
        }

        asset.setTranslatedOcrText(cleanMediaText(translatedOcrText));
        asset.setTranslatedAsrText(cleanMediaText(translatedAsrText));
        asset.setTranslatedCaptionText(cleanMediaText(translatedCaptionText));
        return new SearchQueryTranslationService.TranslatedMediaText(
                asset.getTranslatedOcrText(),
                asset.getTranslatedAsrText(),
                asset.getTranslatedCaptionText());
    }

    private boolean translatedFieldsChanged(MediaAsset asset,
                                            String beforeTranslatedOcrText,
                                            String beforeTranslatedAsrText,
                                            String beforeTranslatedCaptionText) {
        return asset != null
                && (!sameText(beforeTranslatedOcrText, asset.getTranslatedOcrText())
                || !sameText(beforeTranslatedAsrText, asset.getTranslatedAsrText())
                || !sameText(beforeTranslatedCaptionText, asset.getTranslatedCaptionText()));
    }

    private boolean cleanStoredMediaText(MediaAsset asset) {
        if (asset == null) {
            return false;
        }
        boolean changed = false;
        String ocrText = cleanMediaText(asset.getOcrText());
        if (!sameText(asset.getOcrText(), ocrText)) {
            asset.setOcrText(ocrText);
            changed = true;
        }
        String asrText = cleanMediaText(asset.getAsrText());
        if (!sameText(asset.getAsrText(), asrText)) {
            asset.setAsrText(asrText);
            changed = true;
        }
        String captionText = cleanMediaText(asset.getCaptionText());
        if (!sameText(asset.getCaptionText(), captionText)) {
            asset.setCaptionText(captionText);
            changed = true;
        }
        String translatedOcrText = cleanMediaText(asset.getTranslatedOcrText());
        if (!sameText(asset.getTranslatedOcrText(), translatedOcrText)) {
            asset.setTranslatedOcrText(translatedOcrText);
            changed = true;
        }
        String translatedAsrText = cleanMediaText(asset.getTranslatedAsrText());
        if (!sameText(asset.getTranslatedAsrText(), translatedAsrText)) {
            asset.setTranslatedAsrText(translatedAsrText);
            changed = true;
        }
        String translatedCaptionText = cleanMediaText(asset.getTranslatedCaptionText());
        if (!sameText(asset.getTranslatedCaptionText(), translatedCaptionText)) {
            asset.setTranslatedCaptionText(translatedCaptionText);
            changed = true;
        }
        return changed;
    }

    private String cleanMediaText(String value) {
        if (!hasText(value) || "null".equalsIgnoreCase(value.trim())) {
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

    private int textLength(String value) {
        return value == null ? 0 : value.length();
    }

    private String rootMessage(Throwable error) {
        Throwable root = error;
        while (root != null && root.getCause() != null) {
            root = root.getCause();
        }
        return root != null && root.getMessage() != null ? root.getMessage() : String.valueOf(error);
    }

    private record UploadedFrame(String bucket, String key, String url) {
    }
}

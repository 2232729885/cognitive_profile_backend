package com.idata.profile.pipeline.step;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateRequest;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateResponse;
import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.mapper.content.MediaAssetMapper;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class T1AnnotationStep {

    private static final BigDecimal AIGC_REVIEW_THRESHOLD = new BigDecimal("0.8");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentProxyClient agentProxyClient;
    private final MediaContentMapper mediaContentMapper;
    private final MediaAssetMapper mediaAssetMapper;
    private final RawRecordMapper rawRecordMapper;
    private final PipelineTaskMapper pipelineTaskMapper;

    @Value("${minio.endpoint}")
    private String minioEndpoint;

    public void run(PipelineTask task) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        task.setT1Status("running");
        task.setT1StartedAt(startedAt);
        pipelineTaskMapper.updateById(task);

        MediaContent mc = mediaContentMapper.selectById(task.getContentId());

        T1AnnotateRequest request = new T1AnnotateRequest();
        request.setText(mc.getBodyText());
        request.setLanguage(mc.getLanguage());

        T1AnnotateResponse response = agentProxyClient.call("T1", "annotate_text", request, T1AnnotateResponse.class);
        applyAnnotations(mc, response);
        mediaContentMapper.updateById(mc);
        if (mc.getMediaAssetIds() != null && mc.getMediaAssetIds().length > 0) {
            annotateImages(mc.getMediaAssetIds());
        }

        RawRecord rawRecord = rawRecordMapper.selectById(task.getRawRecordId());
        rawRecord.setT1Output(response.getRaw());
        rawRecord.setPipelineStatus(PipelineStatus.T1_DONE.name());
        rawRecordMapper.updateById(rawRecord);

        task.setT1Status("done");
        task.setT1DoneAt(OffsetDateTime.now());
        task.setT1DurationMs((int) java.time.Duration.between(startedAt, OffsetDateTime.now()).toMillis());
        pipelineTaskMapper.updateById(task);
    }

    private void applyAnnotations(MediaContent mc, T1AnnotateResponse response) {
        T1AnnotateResponse.Annotations annotations = response.getAnnotations();
        if (annotations != null) {
            if (annotations.getTopics() != null && !annotations.getTopics().isEmpty()) {
                mc.setTopicCategory(annotations.getTopics().get(0));
                if (annotations.getTopics().size() > 1) {
                    mc.setTopicSubcategory(annotations.getTopics().get(1));
                }
            }

            if (annotations.getSentiment() != null) {
                mc.setSentimentLabel(annotations.getSentiment().getLabel());
                if (annotations.getSentiment().getScore() != null) {
                    mc.setSentimentScore(BigDecimal.valueOf(annotations.getSentiment().getScore()));
                }
            }

            BigDecimal aigcScore = toAigcScore(annotations.getAigcSuspicion());
            if (aigcScore != null) {
                mc.setAigcScore(aigcScore);
                mc.setNeedHumanReview(aigcScore.compareTo(AIGC_REVIEW_THRESHOLD) > 0);
            }

            if (annotations.getEntitiesHint() != null && !annotations.getEntitiesHint().isEmpty()) {
                try {
                    String hintsJson = OBJECT_MAPPER.writeValueAsString(annotations.getEntitiesHint());
                    mc.setEntitiesHint(hintsJson);
                    mc.setNarrativeHint(hintsJson);
                } catch (JacksonException e) {
                    log.warn("Failed to serialize T1 entities hint, contentId={}", mc.getId(), e);
                }
            }
        }

        if (response.getQualityControl() != null) {
            if (response.getQualityControl().getNeedHumanReview() != null) {
                mc.setNeedHumanReview(response.getQualityControl().getNeedHumanReview());
            }
            mc.setT1ModelVersion(response.getQualityControl().getModelVersion());
        }
        mc.setT1AnnotatedAt(OffsetDateTime.now());
    }

    private BigDecimal toAigcScore(String aigcSuspicion) {
        if (aigcSuspicion == null) {
            return null;
        }
        return switch (aigcSuspicion.toLowerCase()) {
            case "none" -> BigDecimal.ZERO;
            case "low" -> new BigDecimal("0.2");
            case "medium" -> new BigDecimal("0.5");
            case "high" -> new BigDecimal("0.85");
            default -> null;
        };
    }

    private void annotateImages(UUID[] assetIds) {
        for (UUID assetId : assetIds) {
            try {
                MediaAsset asset = mediaAssetMapper.selectById(assetId);
                if (asset == null || !"image".equals(asset.getAssetType())) {
                    continue;
                }

                String imageUrl = buildImageUrl(asset);
                if (imageUrl == null) {
                    log.warn("[T1] 图片无法构造访问URL, assetId={}", assetId);
                    continue;
                }

                T1AnnotateRequest imageRequest = new T1AnnotateRequest();
                imageRequest.setImageUrl(imageUrl);
                imageRequest.setAnnotationTypes(List.of("objects", "scene", "text_ocr"));

                T1AnnotateResponse imageResponse = agentProxyClient.call(
                        "T1", "annotate_image", imageRequest, T1AnnotateResponse.class);
                if (imageResponse == null) {
                    continue;
                }

                applyImageAnnotations(asset, imageResponse);
                mediaAssetMapper.updateById(asset);
            } catch (Exception e) {
                log.warn("[T1] 图像标注失败, assetId={}", assetId, e);
            }
        }
    }

    private String buildImageUrl(MediaAsset asset) {
        if (hasText(asset.getMinioBucket()) && hasText(asset.getMinioKey())) {
            return stripTrailingSlash(minioEndpoint) + "/" + asset.getMinioBucket() + "/" + asset.getMinioKey();
        }
        if (hasText(asset.getSourceUrl())) {
            return asset.getSourceUrl();
        }
        return null;
    }

    private void applyImageAnnotations(MediaAsset asset, T1AnnotateResponse response) {
        T1AnnotateResponse.Annotations annotations = response.getAnnotations();
        if (annotations == null) {
            return;
        }

        if (annotations.getTextOcr() != null) {
            asset.setOcrText(annotations.getTextOcr());
        }

        if (annotations.getAigcSuspicion() != null) {
            asset.setAigcScore(toAigcScore(annotations.getAigcSuspicion()));
        }

        if (annotations.getObjects() != null) {
            try {
                asset.setObjectAnnotations(OBJECT_MAPPER.writeValueAsString(annotations.getObjects()));
            } catch (JacksonException e) {
                log.warn("Failed to serialize objects annotation, assetId={}", asset.getId(), e);
            }
        }

        if (annotations.getScene() != null) {
            asset.setSceneLabel(annotations.getScene());
        }
    }

    private String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

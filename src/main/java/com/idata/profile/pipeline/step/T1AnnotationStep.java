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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ImageAnnotationUtil imageAnnotationUtil;
    private final Neo4jGraphService neo4jGraphService;

    public void run(PipelineTask task) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        task.setT1Status("running");
        task.setT1StartedAt(startedAt);
        pipelineTaskMapper.updateById(task);

        MediaContent mc = mediaContentMapper.selectById(task.getContentId());
        T1AnnotateResponse textResponse = null;

        if (hasText(mc.getBodyText())) {
            T1AnnotateRequest request = new T1AnnotateRequest();
            request.setText(mc.getBodyText());
            request.setLanguage(mc.getLanguage());

            textResponse = agentProxyClient.call("T1", "annotate_text", request, T1AnnotateResponse.class);
            applyAnnotations(mc, textResponse);
            mediaContentMapper.updateById(mc);
            if (textResponse != null) {
                syncT1AnnotationsToNeo4j(mc, textResponse);
            }
        } else {
            log.info("[T1] 跳过文本标注：bodyText为空, contentId={}", mc.getId());
        }

        if (mc.getMediaAssetIds() != null && mc.getMediaAssetIds().length > 0) {
            annotateImages(mc.getMediaAssetIds());
        }

        completeTask(task, startedAt, textResponse);
    }

    private void completeTask(PipelineTask task, OffsetDateTime startedAt, T1AnnotateResponse textResponse) {
        RawRecord rawRecord = rawRecordMapper.selectById(task.getRawRecordId());
        rawRecord.setT1Output(textResponse != null ? textResponse.getRaw() : null);
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

    private void syncT1AnnotationsToNeo4j(MediaContent mc, T1AnnotateResponse response) {
        try {
            Map<String, Object> props = new LinkedHashMap<>();
            T1AnnotateResponse.Annotations ann = response.getAnnotations();

            if (ann != null) {
                // topics / topicCategory / topicSubcategory
                if (ann.getTopics() != null && !ann.getTopics().isEmpty()) {
                    props.put("topics", ann.getTopics().toArray(new String[0]));
                    props.put("topicCategory", ann.getTopics().get(0));
                    if (ann.getTopics().size() > 1) {
                        props.put("topicSubcategory", ann.getTopics().get(1));
                    }
                }
                // keywords / summary
                putStrArray(props, "keywords", ann.getKeywords());
                putStr(props, "summary", ann.getSummary());
                // sentiment
                if (ann.getSentiment() != null) {
                    putStr(props, "sentimentLabel", ann.getSentiment().getLabel());
                    if (ann.getSentiment().getScore() != null) {
                        props.put("sentimentScore", ann.getSentiment().getScore());
                    }
                }
                // languageStyle
                if (ann.getLanguageStyle() != null) {
                    putStr(props, "languageStyleFormality",
                            ann.getLanguageStyle().getFormality());
                    putStr(props, "languageStyleEmotionalIntensity",
                            ann.getLanguageStyle().getEmotionalIntensity());
                }
                // eventType / contentPurpose / aigcSuspicion
                putStr(props, "eventType", ann.getEventType());
                putStr(props, "contentPurpose", ann.getContentPurpose());
                putStr(props, "aigcSuspicion", ann.getAigcSuspicion());
                // entitiesHint -> entityTypeHints
                if (ann.getEntitiesHint() != null && !ann.getEntitiesHint().isEmpty()) {
                    String[] typeHints = ann.getEntitiesHint().stream()
                            .map(T1AnnotateResponse.Annotations.EntityHint::getTypeHint)
                            .filter(t -> t != null && !t.isBlank())
                            .distinct()
                            .toArray(String[]::new);
                    if (typeHints.length > 0) {
                        props.put("entityTypeHints", typeHints);
                    }
                }
                // image annotations
                putStr(props, "sceneLabel", ann.getScene());
                putStr(props, "textOcr", ann.getTextOcr());
            }
            // qualityControl
            if (response.getQualityControl() != null) {
                putStr(props, "t1ModelVersion",
                        response.getQualityControl().getModelVersion());
                if (Boolean.TRUE.equals(response.getQualityControl().getNeedHumanReview())) {
                    props.put("needHumanReview", true);
                }
            }
            if (response.getConfidence() != null) {
                props.put("t1Confidence", response.getConfidence());
            }

            if (!props.isEmpty()) {
                neo4jGraphService.mergeNode("MediaContent", mc.getId().toString(), props);
                log.debug("[T1] Neo4j同步成功, contentId={}, fields={}",
                        mc.getId(), props.keySet());
            }
        } catch (Exception e) {
            log.warn("[T1] Neo4j同步失败, contentId={}", mc.getId(), e);
        }
    }

    private void putStr(Map<String, Object> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v);
        }
    }

    private void putStrArray(Map<String, Object> m, String k, List<String> list) {
        if (list != null && !list.isEmpty()) {
            m.put(k, list.toArray(new String[0]));
        }
    }

    private void annotateImages(UUID[] assetIds) {
        for (UUID assetId : assetIds) {
            try {
                MediaAsset asset = mediaAssetMapper.selectById(assetId);
                if (asset == null || !"image".equals(asset.getAssetType())) {
                    continue;
                }

                String imageUrl = imageAnnotationUtil.buildImageUrl(asset);
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

                imageAnnotationUtil.applyImageAnnotations(asset, imageResponse);
                asset.setT1Annotated(true);
                mediaAssetMapper.updateById(asset);
                imageAnnotationUtil.updateNeo4jAnnotations(asset);
            } catch (Exception e) {
                log.warn("[T1] 图像标注失败, assetId={}", assetId, e);
            }
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

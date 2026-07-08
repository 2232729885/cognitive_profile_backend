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
            T1AnnotateRequest.Context context = new T1AnnotateRequest.Context();
            context.setDocId(mc.getId().toString());
            context.setPlatform(mc.getPlatform());
            context.setContentType(mc.getContentType());
            context.setAuthorHandle(mc.getAuthorPlatformUserId());
            context.setPublishedAt(mc.getPublishedAt() != null ? mc.getPublishedAt().toString() : null);
            context.setHashtags(mc.getHashtags() != null ? List.of(mc.getHashtags()) : null);
            context.setParentContentId(mc.getParentContentId());
            request.setContext(context);

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
        rawRecord.setT1Output(textResponse != null ? toJson(textResponse) : null);
        rawRecord.setPipelineStatus(PipelineStatus.T1_DONE.name());
        rawRecordMapper.updateById(rawRecord);

        task.setT1Status("done");
        task.setT1DoneAt(OffsetDateTime.now());
        task.setT1DurationMs((int) java.time.Duration.between(startedAt, OffsetDateTime.now()).toMillis());
        pipelineTaskMapper.updateById(task);
    }

    private void applyAnnotations(MediaContent mc, T1AnnotateResponse response) {
        try {
            mc.setT1AnnotationV05(OBJECT_MAPPER.writeValueAsString(response));
        } catch (JacksonException e) {
            log.warn("Failed to serialize full T1 v0.5 response, contentId={}", mc.getId(), e);
        }

        T1AnnotateResponse.AigcDetection aigc = response.getAigcDetection();
        if (aigc != null) {
            mc.setAigcType(aigc.getOverallAigcLabel());
            if (aigc.getOverallAigcScore() != null) {
                mc.setAigcScore(BigDecimal.valueOf(aigc.getOverallAigcScore()));
            }
        }

        T1AnnotateResponse.Annotations annotations = response.getAnnotations();
        if (annotations != null) {
            T1AnnotateResponse.Annotations.HighValueSubjective hvs = annotations.getHighValueSubjective();
            if (hvs != null) {
                if (hvs.getIdeology() != null) {
                    mc.setIdeologyLabel(hvs.getIdeology().getIdeologyLabel());
                }
                if (hvs.getCoreStance() != null) {
                    mc.setOverallStance(hvs.getCoreStance().getStanceLabel());
                    mc.setCoreStanceStrength(hvs.getCoreStance().getStanceStrength());
                }
                if (hvs.getPublicAttitude() != null) {
                    mc.setPublicAttitudeGroup(hvs.getPublicAttitude().getPublicGroup());
                    mc.setPublicAttitudeLabel(hvs.getPublicAttitude().getAttitudeLabel());
                    mc.setPublicAttitudeIntensity(hvs.getPublicAttitude().getAttitudeIntensity());
                }
                if (hvs.getOpinionEmotion() != null) {
                    mc.setSentimentLabel(hvs.getOpinionEmotion().getSentimentPolarity());
                    if (hvs.getOpinionEmotion().getEmotionLabels() != null
                            && !hvs.getOpinionEmotion().getEmotionLabels().isEmpty()) {
                        mc.setEmotionLabels(hvs.getOpinionEmotion().getEmotionLabels().toArray(new String[0]));
                    }
                }
                if (hvs.getEventHeat() != null) {
                    mc.setEventHeat(hvs.getEventHeat().getHeatLevel());
                    if (hvs.getEventHeat().getHeatScore() != null) {
                        mc.setEventHeatScore(BigDecimal.valueOf(hvs.getEventHeat().getHeatScore()));
                    }
                }
                if (hvs.getLanguageStyle() != null && hvs.getLanguageStyle().getStyleLabels() != null
                        && !hvs.getLanguageStyle().getStyleLabels().isEmpty()) {
                    mc.setLanguageStyleTags(hvs.getLanguageStyle().getStyleLabels().toArray(new String[0]));
                }
                if (hvs.getContentPurpose() != null) {
                    mc.setContentPurposePrimary(hvs.getContentPurpose().getPrimaryPurpose());
                }
                if (hvs.getRiskLevel() != null) {
                    mc.setRiskLevel(hvs.getRiskLevel().getRiskLabel());
                    if (hvs.getRiskLevel().getRiskTypes() != null && !hvs.getRiskLevel().getRiskTypes().isEmpty()) {
                        mc.setRiskTypes(hvs.getRiskLevel().getRiskTypes().toArray(new String[0]));
                    }
                }
            }

            T1AnnotateResponse.Annotations.BasicObjective bo = annotations.getBasicObjective();
            if (bo != null) {
                if (bo.getTopicTags() != null) {
                    mc.setTopicCategory(bo.getTopicTags().getPrimaryDomain());
                    if (bo.getTopicTags().getSubtopicTags() != null
                            && !bo.getTopicTags().getSubtopicTags().isEmpty()) {
                        mc.setTopicSubcategory(bo.getTopicTags().getSubtopicTags().get(0));
                    }
                }
                if (bo.getAccountType() != null) {
                    mc.setAccountTypeHint(bo.getAccountType().getPrimaryAccountCategory());
                }
                if (bo.getEntitiesHint() != null && !bo.getEntitiesHint().isEmpty()) {
                    try {
                        String hintsJson = OBJECT_MAPPER.writeValueAsString(bo.getEntitiesHint());
                        mc.setEntitiesHint(hintsJson);
                        mc.setNarrativeHint(hintsJson);
                    } catch (JacksonException e) {
                        log.warn("Failed to serialize T1 entities hint, contentId={}", mc.getId(), e);
                    }
                }
                if (bo.getKeywords() != null && !bo.getKeywords().isEmpty()) {
                    String[] keywordTexts = bo.getKeywords().stream()
                            .map(T1AnnotateResponse.Annotations.BasicObjective.Keyword::getKeywordText)
                            .filter(t -> t != null && !t.isBlank())
                            .toArray(String[]::new);
                    if (keywordTexts.length > 0) {
                        mc.setKeywords(keywordTexts);
                    }
                }
                if (bo.getSummary() != null) {
                    mc.setSummary(bo.getSummary().getSummaryText());
                }
                if (bo.getEventType() != null) {
                    mc.setEventTypeLabel(bo.getEventType().getEventTypeLabel());
                }
            }
        }

        if (response.getQualityControl() != null) {
            T1AnnotateResponse.QualityControl qc = response.getQualityControl();
            if (qc.getNeedHumanReview() != null) {
                mc.setNeedHumanReview(qc.getNeedHumanReview());
            }
            if (qc.getReviewReasons() != null && !qc.getReviewReasons().isEmpty()) {
                mc.setT1ReviewReasons(qc.getReviewReasons().toArray(new String[0]));
            }
            if (qc.getFailedModules() != null && !qc.getFailedModules().isEmpty()) {
                mc.setT1FailedModules(qc.getFailedModules().toArray(new String[0]));
            }
        }
        if (response.getOverallConfidence() != null) {
            mc.setT1OverallConfidence(BigDecimal.valueOf(response.getOverallConfidence()));
        }

        mc.setT1AnnotatedAt(OffsetDateTime.now());
    }

    private void syncT1AnnotationsToNeo4j(MediaContent mc, T1AnnotateResponse response) {
        try {
            Map<String, Object> props = new LinkedHashMap<>();

            T1AnnotateResponse.AigcDetection aigc = response.getAigcDetection();
            if (aigc != null) {
                putStr(props, "aigcLabel", aigc.getOverallAigcLabel());
                if (aigc.getOverallAigcScore() != null) {
                    props.put("aigcScore", aigc.getOverallAigcScore());
                }
            }

            T1AnnotateResponse.Annotations annotations = response.getAnnotations();
            if (annotations != null) {
                T1AnnotateResponse.Annotations.HighValueSubjective hvs = annotations.getHighValueSubjective();
                if (hvs != null) {
                    if (hvs.getIdeology() != null) {
                        putStr(props, "ideologyLabel", hvs.getIdeology().getIdeologyLabel());
                    }
                    if (hvs.getCoreStance() != null) {
                        putStr(props, "coreStanceLabel", hvs.getCoreStance().getStanceLabel());
                        putStr(props, "coreStanceStrength", hvs.getCoreStance().getStanceStrength());
                    }
                    if (hvs.getPublicAttitude() != null) {
                        putStr(props, "publicAttitudeGroup", hvs.getPublicAttitude().getPublicGroup());
                        putStr(props, "publicAttitudeLabel", hvs.getPublicAttitude().getAttitudeLabel());
                    }
                    if (hvs.getOpinionEmotion() != null) {
                        putStr(props, "sentimentPolarity", hvs.getOpinionEmotion().getSentimentPolarity());
                        putStrArray(props, "emotionLabels", hvs.getOpinionEmotion().getEmotionLabels());
                    }
                    if (hvs.getEventHeat() != null) {
                        putStr(props, "eventHeatLevel", hvs.getEventHeat().getHeatLevel());
                        if (hvs.getEventHeat().getHeatScore() != null) {
                            props.put("eventHeatScore", hvs.getEventHeat().getHeatScore());
                        }
                    }
                    if (hvs.getLanguageStyle() != null) {
                        putStrArray(props, "languageStyleTags", hvs.getLanguageStyle().getStyleLabels());
                    }
                    if (hvs.getContentPurpose() != null) {
                        putStr(props, "contentPurposePrimary", hvs.getContentPurpose().getPrimaryPurpose());
                    }
                    if (hvs.getRiskLevel() != null) {
                        putStr(props, "riskLabel", hvs.getRiskLevel().getRiskLabel());
                        putStrArray(props, "riskTypes", hvs.getRiskLevel().getRiskTypes());
                    }
                }

                T1AnnotateResponse.Annotations.BasicObjective bo = annotations.getBasicObjective();
                if (bo != null) {
                    if (bo.getTopicTags() != null) {
                        putStr(props, "topicPrimaryDomain", bo.getTopicTags().getPrimaryDomain());
                        putStrArray(props, "topicSubtags", bo.getTopicTags().getSubtopicTags());
                    }
                    if (bo.getAccountType() != null) {
                        putStr(props, "accountTypeHint", bo.getAccountType().getPrimaryAccountCategory());
                    }
                    if (bo.getEntitiesHint() != null && !bo.getEntitiesHint().isEmpty()) {
                        String[] typeHints = bo.getEntitiesHint().stream()
                                .map(T1AnnotateResponse.Annotations.BasicObjective.EntityHint::getTypeHint)
                                .filter(t -> t != null && !t.isBlank())
                                .distinct()
                                .toArray(String[]::new);
                        if (typeHints.length > 0) {
                            props.put("entityTypeHints", typeHints);
                        }
                    }
                    if (bo.getKeywords() != null && !bo.getKeywords().isEmpty()) {
                        String[] keywordTexts = bo.getKeywords().stream()
                                .map(T1AnnotateResponse.Annotations.BasicObjective.Keyword::getKeywordText)
                                .filter(t -> t != null && !t.isBlank())
                                .toArray(String[]::new);
                        if (keywordTexts.length > 0) {
                            props.put("keywords", keywordTexts);
                        }
                    }
                    if (bo.getSummary() != null) {
                        putStr(props, "summary", bo.getSummary().getSummaryText());
                    }
                    if (bo.getEventType() != null) {
                        putStr(props, "eventTypeLabel", bo.getEventType().getEventTypeLabel());
                    }
                }
            }

            if (response.getQualityControl() != null
                    && Boolean.TRUE.equals(response.getQualityControl().getNeedHumanReview())) {
                props.put("needHumanReview", true);
            }
            if (response.getOverallConfidence() != null) {
                props.put("t1Confidence", response.getOverallConfidence());
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

    private String toJson(T1AnnotateResponse response) {
        try {
            return OBJECT_MAPPER.writeValueAsString(response);
        } catch (JacksonException e) {
            log.warn("Failed to serialize T1 response");
            return null;
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

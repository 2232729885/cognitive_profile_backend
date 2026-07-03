package com.idata.profile.pipeline.step;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateRequest;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateResponse;
import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class T1AnnotationStep {

    private static final BigDecimal AIGC_REVIEW_THRESHOLD = new BigDecimal("0.8");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentProxyClient agentProxyClient;
    private final MediaContentMapper mediaContentMapper;
    private final RawRecordMapper rawRecordMapper;
    private final PipelineTaskMapper pipelineTaskMapper;

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
}

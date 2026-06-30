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

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * T1自动标注步骤。
 * 见 docs/01-CODEGEN-CONTEXT.md 3.1节 runT1 伪代码。
 * 失败处理交由 pipeline.PipelineExecutor 的catch块统一调用 RetryHandler。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class T1AnnotationStep {

    private static final BigDecimal AIGC_REVIEW_THRESHOLD = new BigDecimal("0.8");

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
        request.setBodyText(mc.getBodyText());
        request.setLanguage(mc.getLanguage());
        request.setPlatform(mc.getPlatform());

        T1AnnotateResponse response = agentProxyClient.call("T1", "annotate_text", request, T1AnnotateResponse.class);

        mc.setTopicCategory(response.getTopicCategory());
        mc.setTopicSubcategory(response.getTopicSubcategory());
        mc.setEventHeatScore(response.getEventHeatScore());
        mc.setSentimentLabel(response.getSentimentLabel());
        mc.setSentimentScore(response.getSentimentScore());
        mc.setStanceLabel(response.getStanceLabel());
        mc.setAigcScore(response.getAigcScore());
        mc.setAigcType(response.getAigcType());
        mc.setNarrativeHint(response.getNarrativeHint());
        mc.setNeedHumanReview(response.getAigcScore() != null
                && response.getAigcScore().compareTo(AIGC_REVIEW_THRESHOLD) > 0);
        mc.setT1AnnotatedAt(OffsetDateTime.now());
        mc.setT1ModelVersion(response.getModelVersion());
        // entitiesHint是Object，需序列化为JSON字符串后SET，TODO: 补充JSON序列化工具调用
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
}

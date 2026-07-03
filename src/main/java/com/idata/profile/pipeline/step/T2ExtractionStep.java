package com.idata.profile.pipeline.step;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t2.T2ExtractRequest;
import com.idata.profile.agentproxy.dto.t2.T2ExtractResponse;
import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.mapper.graph.EventMapper;
import com.idata.profile.mapper.graph.NarrativeMapper;
import com.idata.profile.mapper.graph.OrganizationMapper;
import com.idata.profile.mapper.graph.PersonMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class T2ExtractionStep {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentProxyClient agentProxyClient;
    private final MediaContentMapper mediaContentMapper;
    private final RawRecordMapper rawRecordMapper;
    private final PipelineTaskMapper pipelineTaskMapper;
    private final PersonMapper personMapper;
    private final OrganizationMapper organizationMapper;
    private final EventMapper eventMapper;
    private final NarrativeMapper narrativeMapper;

    public void run(PipelineTask task) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        task.setT2Status("running");
        task.setT2StartedAt(startedAt);
        pipelineTaskMapper.updateById(task);

        MediaContent mc = mediaContentMapper.selectById(task.getContentId());

        T2ExtractRequest request = new T2ExtractRequest();
        request.setBodyText(mc.getBodyText());
        request.setNarrativeHint(mc.getNarrativeHint());
        request.setHashtags(mc.getHashtags());
        request.setMentions(mc.getMentions());
        request.setParentContentId(mc.getParentContentId());
        request.setRepostOfContentId(mc.getRepostOfContentId());
        request.setQuotedContentId(mc.getQuotedContentId());

        T2ExtractResponse response = agentProxyClient.call("T2", "extract_entities", request, T2ExtractResponse.class);

        if (response.getEntities() != null) {
            for (T2ExtractResponse.ExtractedEntity entity : response.getEntities()) {
                insertEntity(entity);
            }
        }
        if (response.getEvents() != null) {
            for (T2ExtractResponse.ExtractedEvent event : response.getEvents()) {
                insertEvent(event);
            }
        }

        if (mc.getAuthorAccountId() == null && response.getResolvedAuthorAccountId() != null) {
            mc.setAuthorAccountId(java.util.UUID.fromString(response.getResolvedAuthorAccountId()));
            mediaContentMapper.updateById(mc);
        }

        RawRecord rawRecord = rawRecordMapper.selectById(task.getRawRecordId());
        rawRecord.setT2Output(response.getRaw());
        rawRecord.setPipelineStatus(PipelineStatus.T2_DONE.name());
        rawRecordMapper.updateById(rawRecord);

        task.setT2Status("done");
        task.setT2DoneAt(OffsetDateTime.now());
        task.setT2DurationMs((int) java.time.Duration.between(startedAt, OffsetDateTime.now()).toMillis());
        pipelineTaskMapper.updateById(task);
    }

    private void insertEntity(T2ExtractResponse.ExtractedEntity entity) {
        if (entity == null || !hasText(entity.getType()) || !hasText(entity.getCanonicalName())) {
            log.warn("Skip invalid T2 extracted entity: {}", entity);
            return;
        }

        String canonicalName = entity.getCanonicalName().trim();
        BigDecimal importanceScore = entity.getImportanceScore() != null
                ? entity.getImportanceScore() : BigDecimal.ZERO;

        switch (entity.getType()) {
            case "person" -> personMapper.insertEntity(canonicalName, importanceScore);
            case "organization" -> organizationMapper.insertEntity(canonicalName, importanceScore);
            case "event" -> eventMapper.insertEntity(canonicalName, importanceScore);
            case "narrative" -> narrativeMapper.insertEntity(
                    canonicalName, importanceScore, buildClaimAtoms(canonicalName, importanceScore));
            default -> log.warn("Unknown extracted entity type: {}", entity.getType());
        }
    }

    private void insertEvent(T2ExtractResponse.ExtractedEvent event) {
        if (event == null || !hasText(event.getCanonicalName())) {
            return;
        }

        double confidence = event.getConfidence() != null ? event.getConfidence() : 0.5D;
        BigDecimal importanceScore = BigDecimal.valueOf(confidence * 100D);
        eventMapper.insertEntity(event.getCanonicalName().trim(), importanceScore);
    }

    private String buildClaimAtoms(String canonicalName, BigDecimal importanceScore) {
        try {
            BigDecimal confidence = importanceScore
                    .divide(new BigDecimal("100"), 3, java.math.RoundingMode.HALF_UP)
                    .min(BigDecimal.ONE)
                    .max(BigDecimal.ZERO);
            return OBJECT_MAPPER.writeValueAsString(List.of(Map.of(
                    "claim", canonicalName,
                    "confidence", confidence
            )));
        } catch (JacksonException e) {
            log.warn("Failed to serialize narrative claim atom, canonicalName={}", canonicalName, e);
            return "[]";
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

}

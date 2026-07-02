package com.idata.profile.pipeline.step;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t2.T2ExtractRequest;
import com.idata.profile.agentproxy.dto.t2.T2ExtractResponse;
import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.mapper.account.SocialAccountMapper;
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
import java.util.UUID;

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
    private final SocialAccountMapper socialAccountMapper;

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
                upsertEntity(entity, mc);
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

    private void upsertEntity(T2ExtractResponse.ExtractedEntity entity, MediaContent mc) {
        if (entity == null || !hasText(entity.getType()) || !hasText(entity.getCanonicalName())) {
            log.warn("Skip invalid T2 extracted entity: {}", entity);
            return;
        }

        String canonicalName = entity.getCanonicalName().trim();
        BigDecimal importanceScore = entity.getImportanceScore() != null
                ? entity.getImportanceScore() : BigDecimal.ZERO;

        switch (entity.getType()) {
            case "person" -> upsertPerson(entity, mc, canonicalName, importanceScore);
            case "organization" -> organizationMapper.upsertByCanonicalName(canonicalName, importanceScore);
            case "event" -> eventMapper.upsertByCanonicalName(canonicalName, importanceScore);
            case "narrative" -> narrativeMapper.upsertByCanonicalLabel(
                    canonicalName, importanceScore, buildClaimAtoms(canonicalName, importanceScore));
            default -> log.warn("Unknown extracted entity type: {}", entity.getType());
        }
    }

    private void upsertPerson(T2ExtractResponse.ExtractedEntity entity,
                              MediaContent mc,
                              String canonicalName,
                              BigDecimal importanceScore) {
        personMapper.upsertByCanonicalName(canonicalName, importanceScore);

        UUID personId = personMapper.selectIdByCanonicalName(canonicalName);
        if (personId == null) {
            log.warn("Skip entityPersonId backfill because person was not found, canonicalName={}", canonicalName);
            return;
        }

        if (mc.getAuthorAccountId() != null) {
            int updated = socialAccountMapper.updateEntityPersonId(mc.getAuthorAccountId(), personId);
            if (updated == 0) {
                log.warn("No social_account updated for authorAccountId={}, personId={}", mc.getAuthorAccountId(), personId);
            }
        }

        if (!hasText(entity.getMatchedAccountId())) {
            return;
        }

        UUID accountId = parseUuid(entity.getMatchedAccountId());
        if (accountId == null) {
            log.warn("Skip entityPersonId backfill because matchedAccountId is invalid: {}",
                    entity.getMatchedAccountId());
            return;
        }

        int updated = socialAccountMapper.updateEntityPersonId(accountId, personId);
        if (updated == 0) {
            log.warn("No social_account updated for matchedAccountId={}, personId={}", accountId, personId);
        }
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

    private UUID parseUuid(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

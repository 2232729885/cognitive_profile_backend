package com.idata.profile.identification;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t6.T6IdentifyRequest;
import com.idata.profile.agentproxy.dto.t6.T6IdentifyResponse;
import com.idata.profile.entity.graph.Narrative;
import com.idata.profile.entity.task.IdentificationResult;
import com.idata.profile.entity.task.IdentificationTask;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.graph.NarrativeMapper;
import com.idata.profile.mapper.task.IdentificationResultMapper;
import com.idata.profile.mapper.task.IdentificationTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdentificationTaskService {

    private static final String TRIGGER_NARRATIVE = "narrative";
    private static final String TRIGGER_ACCOUNT_LIST = "account_list";
    private static final String TRIGGER_MANUAL = "manual";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final IdentificationTaskMapper identificationTaskMapper;
    private final IdentificationResultMapper identificationResultMapper;
    private final NarrativeMapper narrativeMapper;
    private final Neo4jGraphService neo4jGraphService;
    private final AgentProxyClient agentProxyClient;

    @Transactional
    public IdentificationTask identifyByNarrative(UUID narrativeId) {
        IdentificationTask task = new IdentificationTask();
        task.setId(UUID.randomUUID());
        task.setTriggerType(TRIGGER_NARRATIVE);
        task.setNarrativeId(narrativeId);
        task.setStatus("PENDING");
        task.setAccountsAnalyzed(0);
        task.setTargetsIdentified(0);
        task.setGroupsDetected(0);
        identificationTaskMapper.insert(task);
        return runTask(task.getId());
    }

    @Transactional
    public IdentificationTask identifyByAccountList(UUID[] accountIds) {
        IdentificationTask task = new IdentificationTask();
        task.setId(UUID.randomUUID());
        task.setTriggerType(TRIGGER_ACCOUNT_LIST);
        task.setInputAccountIds(accountIds);
        task.setStatus("PENDING");
        task.setAccountsAnalyzed(0);
        task.setTargetsIdentified(0);
        task.setGroupsDetected(0);
        identificationTaskMapper.insert(task);
        return runTask(task.getId());
    }

    @Transactional
    public IdentificationTask runTask(UUID taskId) {
        IdentificationTask task = identificationTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Identification task not found: " + taskId);
        }

        OffsetDateTime startedAt = OffsetDateTime.now();
        task.setStatus("RUNNING");
        task.setStartedAt(startedAt);
        identificationTaskMapper.updateById(task);

        try {
            T6IdentifyRequest request = buildRequest(task);
            T6IdentifyResponse response = agentProxyClient.call(
                    "T6", "identify_targets", request, T6IdentifyResponse.class);

            identificationResultMapper.deleteByTaskId(task.getId());
            int inserted = persistResults(task.getId(), response);

            task.setInputAccountIds(request.getInputAccountIds());
            task.setAccountsAnalyzed(resolveAccountsAnalyzed(response, request.getInputAccountIds()));
            task.setTargetsIdentified(inserted);
            task.setGroupsDetected(response != null && response.getGroupsDetected() != null
                    ? response.getGroupsDetected() : 0);
            task.setStatus("DONE");
            task.setCompletedAt(OffsetDateTime.now());
            identificationTaskMapper.updateById(task);
            return task;
        } catch (RuntimeException e) {
            log.error("T6 identification task failed, taskId={}", task.getId(), e);
            task.setStatus("FAILED");
            task.setCompletedAt(OffsetDateTime.now());
            identificationTaskMapper.updateById(task);
            return task;
        }
    }

    private T6IdentifyRequest buildRequest(IdentificationTask task) {
        T6IdentifyRequest request = new T6IdentifyRequest();
        request.setTriggerType(task.getTriggerType());
        request.setNarrativeId(task.getNarrativeId());
        request.setInputAccountIds(task.getInputAccountIds());

        if (TRIGGER_NARRATIVE.equals(task.getTriggerType())) {
            populateNarrativeRequest(task, request);
            return request;
        }

        if (TRIGGER_ACCOUNT_LIST.equals(task.getTriggerType()) || TRIGGER_MANUAL.equals(task.getTriggerType())) {
            if (request.getInputAccountIds() == null || request.getInputAccountIds().length == 0) {
                throw new IllegalArgumentException("inputAccountIds is required for triggerType=" + task.getTriggerType());
            }
            return request;
        }

        throw new IllegalArgumentException("Unsupported triggerType: " + task.getTriggerType());
    }

    private void populateNarrativeRequest(IdentificationTask task, T6IdentifyRequest request) {
        if (task.getNarrativeId() == null) {
            throw new IllegalArgumentException("narrativeId is required for triggerType=narrative");
        }

        Narrative narrative = narrativeMapper.selectById(task.getNarrativeId());
        if (narrative == null) {
            throw new IllegalArgumentException("Narrative not found: " + task.getNarrativeId());
        }

        List<UUID> accountIds = neo4jGraphService.findSocialAccountIdsByNarrative(task.getNarrativeId());
        request.setInputAccountIds(accountIds.toArray(UUID[]::new));
        request.setNarrativeLabel(narrative.getCanonicalLabel());
        request.setNarrativeFrameType(narrative.getFrameType());
        request.setNarrativeLifecycleState(narrative.getLifecycleState());
        request.setNarrativeContentCount(narrative.getContentCount());
        request.setNarrativeAccountCount(narrative.getAccountCount());
        request.setNarrativeImportanceScore(narrative.getImportanceScore());
        request.setNarrativeClaimAtoms(narrative.getClaimAtoms());
    }

    private int persistResults(UUID taskId, T6IdentifyResponse response) {
        if (response == null || response.getTargets() == null || response.getTargets().isEmpty()) {
            return 0;
        }

        int inserted = 0;
        for (T6IdentifyResponse.IdentifiedTarget target : response.getTargets()) {
            UUID targetEntityId = parseUuid(target.getTargetEntityId());
            if (targetEntityId == null) {
                log.warn("Skip T6 target with invalid targetEntityId: {}", target.getTargetEntityId());
                continue;
            }

            IdentificationResult result = new IdentificationResult();
            result.setId(UUID.randomUUID());
            result.setTaskId(taskId);
            result.setTargetType(target.getTargetType());
            result.setTargetEntityType(target.getTargetEntityType());
            result.setTargetEntityId(targetEntityId);
            result.setConfidence(target.getConfidence());
            result.setBendDistribution(toJson(target.getBendDistribution()));
            result.setEvidenceText(target.getEvidenceText());
            result.setEvidenceContentIds(parseUuidArray(target.getEvidenceContentIds()));
            identificationResultMapper.insert(result);
            inserted++;
        }
        return inserted;
    }

    private int resolveAccountsAnalyzed(T6IdentifyResponse response, UUID[] inputAccountIds) {
        if (response != null && response.getAccountsAnalyzed() != null) {
            return response.getAccountsAnalyzed();
        }
        return inputAccountIds == null ? 0 : inputAccountIds.length;
    }

    private UUID[] parseUuidArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }

        List<UUID> uuids = new ArrayList<>();
        for (String value : values) {
            UUID uuid = parseUuid(value);
            if (uuid != null) {
                uuids.add(uuid);
            }
        }
        return uuids.isEmpty() ? null : uuids.toArray(UUID[]::new);
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Failed to serialize T6 bendDistribution", e);
        }
    }
}

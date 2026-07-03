package com.idata.profile.identification;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t6.T6IdentifyRequest;
import com.idata.profile.agentproxy.dto.t6.T6IdentifyResponse;
import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.graph.Narrative;
import com.idata.profile.entity.graph.Person;
import com.idata.profile.entity.task.IdentificationResult;
import com.idata.profile.entity.task.IdentificationTask;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.account.SocialAccountMapper;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.mapper.graph.NarrativeMapper;
import com.idata.profile.mapper.graph.PersonMapper;
import com.idata.profile.mapper.task.IdentificationResultMapper;
import com.idata.profile.mapper.task.IdentificationTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdentificationTaskService {

    private static final String TRIGGER_NARRATIVE = "narrative";
    private static final String TRIGGER_ACCOUNT_LIST = "account_list";
    private static final String TRIGGER_MANUAL = "manual";
    private static final int MAX_MEDIA_CONTENTS_PER_REQUEST = 500;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final IdentificationTaskMapper identificationTaskMapper;
    private final IdentificationResultMapper identificationResultMapper;
    private final NarrativeMapper narrativeMapper;
    private final PersonMapper personMapper;
    private final SocialAccountMapper socialAccountMapper;
    private final MediaContentMapper mediaContentMapper;
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

            task.setInputAccountIds(extractRequestAccountIds(request));
            task.setAccountsAnalyzed(resolveAccountsAnalyzed(response, request));
            task.setTargetsIdentified(inserted);
            task.setGroupsDetected(resolveGroupsDetected(response));
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
        if (TRIGGER_NARRATIVE.equals(task.getTriggerType())) {
            return buildNarrativeRequest(task);
        }
        if (TRIGGER_ACCOUNT_LIST.equals(task.getTriggerType()) || TRIGGER_MANUAL.equals(task.getTriggerType())) {
            return buildAccountListRequest(task);
        }
        throw new IllegalArgumentException("Unsupported triggerType: " + task.getTriggerType());
    }

    private T6IdentifyRequest buildNarrativeRequest(IdentificationTask task) {
        if (task.getNarrativeId() == null) {
            throw new IllegalArgumentException("narrativeId is required for triggerType=narrative");
        }

        Narrative narrative = narrativeMapper.selectById(task.getNarrativeId());
        if (narrative == null) {
            throw new IllegalArgumentException("Narrative not found: " + task.getNarrativeId());
        }

        List<UUID> accountUuids = neo4jGraphService.findSocialAccountIdsByNarrative(task.getNarrativeId());
        if (accountUuids.isEmpty()) {
            log.warn("No social accounts found for narrativeId={}", task.getNarrativeId());
        }

        List<SocialAccount> accounts = accountUuids.isEmpty()
                ? List.of()
                : socialAccountMapper.selectBatchIds(accountUuids);
        List<MediaContent> contents = accountUuids.isEmpty()
                ? List.of()
                : mediaContentMapper.selectByAuthorAccountIds(accountUuids, MAX_MEDIA_CONTENTS_PER_REQUEST);

        T6IdentifyRequest request = new T6IdentifyRequest();
        request.setNarrativeId(task.getNarrativeId().toString());
        request.setPersons(buildPersonRefs(accounts));
        request.setOrganizations(List.of());
        request.setSocialAccounts(accounts.stream()
                .map(this::toSocialAccountRef)
                .collect(Collectors.toList()));
        request.setMediaContents(contents.stream()
                .map(this::toMediaContentRef)
                .collect(Collectors.toList()));
        return request;
    }

    private T6IdentifyRequest buildAccountListRequest(IdentificationTask task) {
        if (task.getInputAccountIds() == null || task.getInputAccountIds().length == 0) {
            throw new IllegalArgumentException(
                    "inputAccountIds is required for triggerType=" + task.getTriggerType());
        }

        List<UUID> accountUuids = Arrays.asList(task.getInputAccountIds());
        List<SocialAccount> accounts = socialAccountMapper.selectBatchIds(accountUuids);
        List<MediaContent> contents = accountUuids.isEmpty()
                ? List.of()
                : mediaContentMapper.selectByAuthorAccountIds(accountUuids, MAX_MEDIA_CONTENTS_PER_REQUEST);

        T6IdentifyRequest request = new T6IdentifyRequest();
        request.setNarrativeId(null);
        request.setPersons(buildPersonRefs(accounts));
        request.setOrganizations(List.of());
        request.setSocialAccounts(accounts.stream()
                .map(this::toSocialAccountRef)
                .collect(Collectors.toList()));
        request.setMediaContents(contents.stream()
                .map(this::toMediaContentRef)
                .collect(Collectors.toList()));
        return request;
    }

    private T6IdentifyRequest.SocialAccountRef toSocialAccountRef(SocialAccount sa) {
        T6IdentifyRequest.SocialAccountRef ref = new T6IdentifyRequest.SocialAccountRef();
        ref.setAccountId(sa.getId() != null ? sa.getId().toString() : null);
        ref.setHandle(sa.getHandle());
        ref.setDisplayName(sa.getDisplayName());
        ref.setSourcePlatformId(sa.getPlatform());
        ref.setRegisterTime(sa.getAccountCreatedAt() != null ? sa.getAccountCreatedAt().toString() : null);

        T6IdentifyRequest.AccountMetrics metrics = new T6IdentifyRequest.AccountMetrics();
        metrics.setFollowers(sa.getFollowersCount());
        metrics.setFollowing(sa.getFollowingCount());
        metrics.setPosts(sa.getPostCount());
        metrics.setSnapshotTime(sa.getLatestSnapshotAt() != null ? sa.getLatestSnapshotAt().toString() : null);
        ref.setAccountMetrics(metrics);

        T6IdentifyRequest.VerificationStatus verificationStatus = new T6IdentifyRequest.VerificationStatus();
        verificationStatus.setVerified(sa.getVerified());
        verificationStatus.setVerifiedType(sa.getVerifiedType());
        ref.setVerificationStatus(verificationStatus);

        return ref;
    }

    private T6IdentifyRequest.MediaContentRef toMediaContentRef(MediaContent mc) {
        T6IdentifyRequest.MediaContentRef ref = new T6IdentifyRequest.MediaContentRef();
        ref.setContentId(mc.getId() != null ? mc.getId().toString() : null);
        ref.setSourceAccountId(mc.getAuthorAccountId() != null ? mc.getAuthorAccountId().toString() : null);
        ref.setBodyText(mc.getBodyText());
        ref.setPublishedAt(mc.getPublishedAt() != null ? mc.getPublishedAt().toString() : null);
        ref.setMessageType(toT6MessageType(mc.getContentType()));
        ref.setTopics(mc.getHashtags() != null ? Arrays.asList(mc.getHashtags()) : List.of());
        ref.setMentionedEntityIds(List.of());
        ref.setRootContentId(mc.getRootContentId());
        ref.setParentContentId(mc.getParentContentId());
        return ref;
    }

    private List<T6IdentifyRequest.PersonRef> buildPersonRefs(List<SocialAccount> accounts) {
        List<UUID> personIds = accounts.stream()
                .map(SocialAccount::getEntityPersonId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (personIds.isEmpty()) {
            return List.of();
        }

        return personMapper.selectBatchIds(personIds).stream()
                .map(person -> toPersonRef(person, accounts))
                .collect(Collectors.toList());
    }

    private T6IdentifyRequest.PersonRef toPersonRef(Person person, List<SocialAccount> accounts) {
        T6IdentifyRequest.PersonRef ref = new T6IdentifyRequest.PersonRef();
        ref.setPersonId(person.getId() != null ? person.getId().toString() : null);
        ref.setCanonicalName(person.getCanonicalName());
        ref.setLinkedAccountIds(accounts.stream()
                .filter(sa -> person.getId() != null && person.getId().equals(sa.getEntityPersonId()))
                .map(SocialAccount::getId)
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .collect(Collectors.toList()));
        return ref;
    }

    private int persistResults(UUID taskId, T6IdentifyResponse response) {
        if (response == null) {
            return 0;
        }

        int inserted = 0;
        if (response.getAccountIdentifyResult() != null) {
            for (T6IdentifyResponse.AccountIdentifyResult accountResult : response.getAccountIdentifyResult()) {
                if (accountResult == null || "T00".equals(accountResult.getTargetType())) {
                    continue;
                }
                UUID targetEntityId = parseUuid(accountResult.getAccountId());
                if (targetEntityId == null) {
                    log.warn("Skip T6 account result with invalid accountId: {}", accountResult.getAccountId());
                    continue;
                }

                IdentificationResult result = new IdentificationResult();
                result.setId(UUID.randomUUID());
                result.setTaskId(taskId);
                result.setTargetType(accountResult.getTargetType());
                result.setTargetEntityType("social_account");
                result.setTargetEntityId(targetEntityId);
                result.setConfidence(accountResult.getConfidence());
                result.setBendDistribution(toJson(accountResult.getEvidence()));
                result.setEvidenceText(accountResult.getEvidence() != null
                        ? accountResult.getEvidence().getEvidence() : null);
                identificationResultMapper.insert(result);
                inserted++;
            }
        }

        if (response.getEntityIdentifyResult() != null) {
            for (T6IdentifyResponse.EntityIdentifyResult entityResult : response.getEntityIdentifyResult()) {
                if (entityResult == null) {
                    continue;
                }
                UUID targetEntityId = parseUuid(entityResult.getEntityId());
                if (targetEntityId == null) {
                    log.warn("Skip T6 entity result with invalid entityId: {}", entityResult.getEntityId());
                    continue;
                }

                IdentificationResult result = new IdentificationResult();
                result.setId(UUID.randomUUID());
                result.setTaskId(taskId);
                result.setTargetType(entityResult.getTargetType());
                result.setTargetEntityType(entityResult.getEntityType());
                result.setTargetEntityId(targetEntityId);
                result.setConfidence(entityResult.getConfidence());
                result.setEvidenceText(entityResult.getEvidence());
                identificationResultMapper.insert(result);
                inserted++;
            }
        }
        return inserted;
    }

    private int resolveAccountsAnalyzed(T6IdentifyResponse response, T6IdentifyRequest request) {
        if (response != null && response.getSummary() != null
                && response.getSummary().getTotalAccounts() != null) {
            return response.getSummary().getTotalAccounts();
        }
        return request.getSocialAccounts() == null ? 0 : request.getSocialAccounts().size();
    }

    private int resolveGroupsDetected(T6IdentifyResponse response) {
        if (response != null && response.getSummary() != null
                && response.getSummary().getGroupCount() != null) {
            return response.getSummary().getGroupCount();
        }
        return response == null || response.getGroupIdentifyResult() == null
                ? 0 : response.getGroupIdentifyResult().size();
    }

    private UUID[] extractRequestAccountIds(T6IdentifyRequest request) {
        if (request.getSocialAccounts() == null || request.getSocialAccounts().isEmpty()) {
            return null;
        }
        List<UUID> accountIds = request.getSocialAccounts().stream()
                .map(T6IdentifyRequest.SocialAccountRef::getAccountId)
                .map(this::parseUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return accountIds.isEmpty() ? null : accountIds.toArray(UUID[]::new);
    }

    private String toT6MessageType(String contentType) {
        if (!hasText(contentType)) {
            return null;
        }
        return switch (contentType.trim().toLowerCase()) {
            case "post", "original" -> "original";
            case "repost" -> "repost";
            case "reply" -> "reply";
            case "quote" -> "quote";
            default -> contentType;
        };
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

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Failed to serialize T6 evidence", e);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

package com.idata.profile.ingestion.consumer;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateAccountRequest;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateAccountResponse;
import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.common.constant.RecordType;
import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.infra.kafka.KafkaTopicConstants;
import com.idata.profile.ingestion.normalizer.SocialAccountNormalizer;
import com.idata.profile.mapper.account.SocialAccountMapper;
import com.idata.profile.mapper.account.SocialAccountSnapshotMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SocialAccountConsumer {

    private final SocialAccountNormalizer normalizer;
    private final RawRecordMapper rawRecordMapper;
    private final SocialAccountMapper socialAccountMapper;
    private final SocialAccountSnapshotMapper snapshotMapper;
    private final AgentProxyClient agentProxyClient;

    @KafkaListener(topics = KafkaTopicConstants.SOCIAL_ACCOUNT, groupId = "cognitive-profile-ingestion")
    @Transactional
    public void onMessage(String rawMessage) {
        Object kafkaMessage = parseMessage(rawMessage);
        String sourceRecordId = extractSourceRecordId(kafkaMessage);
        String payloadHash = extractPayloadHash(kafkaMessage);

        if (!isValidSchema(kafkaMessage)) {
            log.error("Schema validation failed, sourceRecordId={}", sourceRecordId);
            return;
        }
        RawRecord rawRecord = rawRecordMapper.selectBySourceRecordId(sourceRecordId);
        if (rawRecord != null) {
            if (PipelineStatus.NORMALIZED.name().equals(rawRecord.getPipelineStatus())) {
                log.debug("Duplicate message skipped, sourceRecordId={}", sourceRecordId);
                return;
            }
            log.warn("Reprocessing incomplete social_account raw record, sourceRecordId={}, status={}",
                    sourceRecordId, rawRecord.getPipelineStatus());
        } else {
            if (rawRecordMapper.existsByPayloadHash(payloadHash)) {
                log.debug("Duplicate payload skipped, sourceRecordId={}", sourceRecordId);
                return;
            }
            rawRecord = buildRawRecord(kafkaMessage);
            rawRecord.setPipelineStatus(PipelineStatus.RECEIVED.name());
            rawRecordMapper.insert(rawRecord);
        }

        SocialAccountNormalizer.NormalizedAccount normalized = normalizer.normalize(kafkaMessage, rawRecord);
        annotateAccountType(normalized.getAccount());
        UUID accountId = socialAccountMapper.upsertByPlatformAndUserId(normalized.getAccount());
        normalized.getSnapshot().setAccountId(accountId);
        snapshotMapper.insert(normalized.getSnapshot());
        rawRecord.setPipelineStatus(PipelineStatus.NORMALIZED.name());
        rawRecordMapper.updateById(rawRecord);
    }

    private void annotateAccountType(SocialAccount account) {
        try {
            T1AnnotateAccountRequest request = new T1AnnotateAccountRequest();
            request.setPlatform(account.getPlatform());
            request.setPlatformUserId(account.getPlatformUserId());
            request.setAccountEntityType(account.getAccountEntityType());
            request.setPlatformNativeType(account.getPlatformNativeType());
            request.setHandle(account.getHandle());
            request.setDisplayName(account.getDisplayName());
            request.setBio(account.getBio());
            request.setSelfDeclaredLocation(account.getSelfDeclaredLocation());
            request.setVerified(account.getVerified());
            request.setVerifiedType(account.getVerifiedType());
            request.setIsSuspended(account.getIsSuspended());
            request.setAccountCreatedAt(account.getAccountCreatedAt() != null
                    ? account.getAccountCreatedAt().toString() : null);
            request.setFollowersCount(account.getFollowersCount());
            request.setFollowingCount(account.getFollowingCount());
            request.setSubscriberCount(account.getSubscriberCount());
            request.setMemberCount(account.getMemberCount());
            request.setPostCount(account.getPostCount());
            request.setViewCount(account.getViewCount());
            request.setRecentPostSamples(List.of());

            T1AnnotateAccountResponse response = agentProxyClient.call(
                    "T1", "annotate_account", request, T1AnnotateAccountResponse.class);

            if (response != null && response.getAccountType() != null
                    && response.getAccountType().getPrimaryAccountCategory() != null) {
                account.setAccountType(response.getAccountType().getPrimaryAccountCategory().getCategoryLabel());
                if (response.getOverallConfidence() != null) {
                    account.setAccountTypeConfidence(BigDecimal.valueOf(response.getOverallConfidence()));
                }
            }
        } catch (Exception e) {
            log.warn("[SocialAccountConsumer] T1 annotate_account failed, platform={}, platformUserId={}",
                    account.getPlatform(), account.getPlatformUserId(), e);
        }
    }

    private Object parseMessage(String rawMessage) {
        return IngestionMessageSupport.parseMessage(rawMessage);
    }

    private boolean isValidSchema(Object kafkaMessage) {
        if (!IngestionMessageSupport.hasCommonEnvelope(kafkaMessage, RecordType.SOCIAL_ACCOUNT.getCode())) {
            return false;
        }
        JsonNode root = IngestionMessageSupport.root(kafkaMessage);
        JsonNode data = IngestionMessageSupport.data(kafkaMessage);
        return IngestionMessageSupport.hasText(IngestionMessageSupport.text(root, "platform"))
                && IngestionMessageSupport.hasText(IngestionMessageSupport.text(data, "platform_user_id"));
    }

    private String extractSourceRecordId(Object kafkaMessage) {
        return IngestionMessageSupport.extractSourceRecordId(kafkaMessage);
    }

    private String extractPayloadHash(Object kafkaMessage) {
        return IngestionMessageSupport.extractPayloadHash(kafkaMessage);
    }

    private RawRecord buildRawRecord(Object kafkaMessage) {
        return IngestionMessageSupport.buildRawRecord(kafkaMessage, KafkaTopicConstants.SOCIAL_ACCOUNT);
    }
}

package com.idata.profile.ingestion.consumer;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateAccountRequest;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateAccountResponse;
import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.common.constant.RecordType;
import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.infra.elasticsearch.EntityEsService;
import com.idata.profile.infra.embedding.EmbeddingService;
import com.idata.profile.infra.kafka.KafkaTopicConstants;
import com.idata.profile.infra.milvus.MilvusVectorService;
import com.idata.profile.ingestion.normalizer.SocialAccountNormalizer;
import com.idata.profile.mapper.account.SocialAccountMapper;
import com.idata.profile.mapper.account.SocialAccountSnapshotMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
    private final EntityEsService entityEsService;
    private final MilvusVectorService milvusVectorService;
    private final EmbeddingService embeddingService;

    @KafkaListener(topics = KafkaTopicConstants.SOCIAL_ACCOUNT, groupId = "cognitive-profile-ingestion")
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

        try {
            SocialAccountNormalizer.NormalizedAccount normalized = normalizer.normalize(kafkaMessage, rawRecord);
            annotateAccountType(normalized.getAccount());
            UUID accountId = socialAccountMapper.upsertByPlatformAndUserId(normalized.getAccount());
            normalized.getAccount().setId(accountId);
            normalized.getSnapshot().setAccountId(accountId);
            snapshotMapper.insert(normalized.getSnapshot());

            rawRecord.setPipelineStatus(PipelineStatus.NORMALIZED.name());
            rawRecord.setErrorMessage(null);
            rawRecordMapper.updateById(rawRecord);

            indexAccountToEs(normalized.getAccount());
            indexAccountEmbedding(normalized.getAccount());
        } catch (Exception e) {
            markFailed(rawRecord, e);
            log.error("[SocialAccountConsumer] social_account processing failed, sourceRecordId={}, platform={}",
                    sourceRecordId, IngestionMessageSupport.text(IngestionMessageSupport.root(kafkaMessage), "platform"), e);
        }
    }

    private void indexAccountToEs(SocialAccount account) {
        if (account == null || account.getId() == null) {
            return;
        }
        try {
            Map<String, Object> extraFields = new LinkedHashMap<>();
            extraFields.put("platform", account.getPlatform());
            extraFields.put("source_id", account.getPlatformUserId());
            extraFields.put("account_entity_type", account.getAccountEntityType());
            extraFields.put("account_type", account.getAccountType());
            entityEsService.indexEntity(
                    account.getId().toString(),
                    firstText(account.getDisplayName(), account.getHandle(), account.getPlatformUserId()),
                    accountAliases(account),
                    "SocialAccount",
                    account.getFollowersCount() != null ? account.getFollowersCount().doubleValue() : 0D,
                    extraFields);
        } catch (Exception e) {
            log.warn("[SocialAccountConsumer] account ES index failed, accountId={}",
                    account != null ? account.getId() : null, e);
        }
    }

    private List<String> accountAliases(SocialAccount account) {
        List<String> aliases = new ArrayList<>();
        addAlias(aliases, account.getHandle());
        addAlias(aliases, account.getPlatformUserId());
        return aliases;
    }

    private void addAlias(List<String> aliases, String value) {
        if (IngestionMessageSupport.hasText(value)
                && aliases.stream().noneMatch(existing -> existing.equalsIgnoreCase(value.trim()))) {
            aliases.add(value.trim());
        }
    }

    /**
     * bio为空时没有语义可提取，直接跳过；调用失败也不影响主流程，只记警告。
     */
    private void indexAccountEmbedding(SocialAccount account) {
        if (account == null || account.getId() == null) {
            return;
        }
        String canonicalName = firstText(account.getDisplayName(), account.getHandle(), account.getPlatformUserId());
        String aliases = aliasText(account);
        String descriptionText = buildAccountEmbeddingText(account);
        if (!IngestionMessageSupport.hasText(canonicalName)
                && !IngestionMessageSupport.hasText(aliases)
                && !IngestionMessageSupport.hasText(descriptionText)) {
            return;
        }
        try {
            float[] nameEmbedding = generateEmbedding(canonicalName);
            float[] aliasEmbedding = generateEmbedding(aliases);
            float[] descriptionEmbedding = generateEmbedding(descriptionText);
            if (nameEmbedding != null || aliasEmbedding != null || descriptionEmbedding != null) {
                milvusVectorService.upsertEntityEmbedding(
                        account.getId().toString(),
                        "SocialAccount",
                        canonicalName,
                        aliases,
                        account.getPlatformUserId(),
                        account.getPlatform(),
                        nameEmbedding,
                        aliasEmbedding,
                        descriptionEmbedding);
            }
        } catch (Exception e) {
            log.warn("[SocialAccountConsumer] account embedding failed, accountId={}", account.getId(), e);
        }
    }

    private float[] generateEmbedding(String text) {
        if (!IngestionMessageSupport.hasText(text)) {
            return null;
        }
        return embeddingService.generateTextEmbedding(text);
    }

    private String aliasText(SocialAccount account) {
        String handle = account.getHandle();
        String displayName = account.getDisplayName();
        if (!IngestionMessageSupport.hasText(handle)) {
            return null;
        }
        if (IngestionMessageSupport.hasText(displayName)
                && handle.trim().equalsIgnoreCase(displayName.trim())) {
            return null;
        }
        return handle;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (IngestionMessageSupport.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String buildAccountEmbeddingText(SocialAccount account) {
        if (account == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        appendEmbeddingField(text, "display_name", account.getDisplayName());
        appendEmbeddingField(text, "handle", account.getHandle());
        appendEmbeddingField(text, "bio", account.getBio());
        appendEmbeddingField(text, "location", account.getSelfDeclaredLocation());
        appendEmbeddingField(text, "account_type", account.getAccountType());
        appendEmbeddingField(text, "account_entity_type", account.getAccountEntityType());
        appendEmbeddingField(text, "platform", account.getPlatform());
        return text.toString();
    }

    private void appendEmbeddingField(StringBuilder text, String field, String value) {
        if (!IngestionMessageSupport.hasText(value)) {
            return;
        }
        text.append(field).append(": ").append(value.trim()).append('\n');
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
                    "T1", "annotate_account_type", request, T1AnnotateAccountResponse.class);

            if (response != null && response.getAccountType() != null
                    && response.getAccountType().getPrimaryAccountCategory() != null) {
                account.setAccountType(response.getAccountType().getPrimaryAccountCategory().getCategoryLabel());
                if (response.getOverallConfidence() != null) {
                    account.setAccountTypeConfidence(BigDecimal.valueOf(response.getOverallConfidence()));
                }
            }
        } catch (Exception e) {
            log.warn("[SocialAccountConsumer] T1 annotate_account_type failed, platform={}, platformUserId={}",
                    account.getPlatform(), account.getPlatformUserId(), e);
        }
    }

    private void markFailed(RawRecord rawRecord, Exception error) {
        if (rawRecord == null || rawRecord.getId() == null) {
            return;
        }
        try {
            rawRecord.setPipelineStatus(PipelineStatus.FAILED.name());
            rawRecord.setErrorMessage(errorMessage(error));
            short retryCount = rawRecord.getRetryCount() == null ? 0 : rawRecord.getRetryCount();
            rawRecord.setRetryCount((short) (retryCount + 1));
            rawRecordMapper.updateById(rawRecord);
        } catch (Exception updateError) {
            log.warn("[SocialAccountConsumer] failed to mark raw record as FAILED, rawRecordId={}",
                    rawRecord.getId(), updateError);
        }
    }

    private String errorMessage(Exception error) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        if (!IngestionMessageSupport.hasText(message) && error.getCause() != null) {
            message = error.getCause().getMessage();
        }
        if (!IngestionMessageSupport.hasText(message)) {
            message = error.getClass().getName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
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

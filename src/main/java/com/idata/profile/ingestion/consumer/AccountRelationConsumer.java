package com.idata.profile.ingestion.consumer;

import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.common.constant.RecordType;
import com.idata.profile.entity.account.AccountRelation;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.infra.kafka.KafkaTopicConstants;
import com.idata.profile.ingestion.normalizer.AccountRelationNormalizer;
import com.idata.profile.mapper.account.AccountRelationMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountRelationConsumer {

    private final AccountRelationNormalizer normalizer;
    private final RawRecordMapper rawRecordMapper;
    private final AccountRelationMapper accountRelationMapper;

    @KafkaListener(topics = KafkaTopicConstants.ACCOUNT_RELATION, groupId = "cognitive-profile-ingestion")
    @Transactional
    public void onMessage(String rawMessage) {
        Object kafkaMessage = parseMessage(rawMessage);
        String sourceRecordId = extractSourceRecordId(kafkaMessage);

        if (!isValidSchema(kafkaMessage)) {
            log.error("Schema validation failed, sourceRecordId={}", sourceRecordId);
            return;
        }
        if (isDuplicateRelation(kafkaMessage)) {
            log.debug("Duplicate account relation skipped, sourceRecordId={}", sourceRecordId);
            return;
        }

        RawRecord rawRecord = buildRawRecord(kafkaMessage);
        rawRecord.setPipelineStatus(PipelineStatus.RECEIVED.name());
        rawRecordMapper.insert(rawRecord);

        AccountRelation relation = normalizer.normalize(kafkaMessage, rawRecord);
        accountRelationMapper.insert(relation);

        rawRecord.setPipelineStatus(PipelineStatus.NORMALIZED.name());
        rawRecordMapper.updateById(rawRecord);
    }

    private Object parseMessage(String rawMessage) {
        return IngestionMessageSupport.parseMessage(rawMessage);
    }

    private boolean isValidSchema(Object kafkaMessage) {
        if (!IngestionMessageSupport.hasCommonEnvelope(kafkaMessage, RecordType.ACCOUNT_RELATION.getCode())) {
            return false;
        }
        JsonNode root = IngestionMessageSupport.root(kafkaMessage);
        JsonNode data = IngestionMessageSupport.data(kafkaMessage);
        return IngestionMessageSupport.hasText(IngestionMessageSupport.text(root, "platform"))
                && IngestionMessageSupport.hasText(IngestionMessageSupport.text(data, "source_platform_user_id"))
                && IngestionMessageSupport.hasText(IngestionMessageSupport.text(data, "target_platform_user_id"))
                && IngestionMessageSupport.hasText(IngestionMessageSupport.text(data, "relation_type"))
                && IngestionMessageSupport.parseOffsetDateTime(data.path("observed_at")) != null;
    }

    private String extractSourceRecordId(Object kafkaMessage) {
        return IngestionMessageSupport.extractSourceRecordId(kafkaMessage);
    }

    private String extractPayloadHash(Object kafkaMessage) {
        return IngestionMessageSupport.extractPayloadHash(kafkaMessage);
    }

    private RawRecord buildRawRecord(Object kafkaMessage) {
        return IngestionMessageSupport.buildRawRecord(kafkaMessage, KafkaTopicConstants.ACCOUNT_RELATION);
    }

    private boolean isDuplicateRelation(Object kafkaMessage) {
        JsonNode data = IngestionMessageSupport.data(kafkaMessage);
        String sourcePlatformUserId = IngestionMessageSupport.text(data, "source_platform_user_id");
        String targetPlatformUserId = IngestionMessageSupport.text(data, "target_platform_user_id");
        String relationType = IngestionMessageSupport.text(data, "relation_type");
        OffsetDateTime observedAt = IngestionMessageSupport.parseOffsetDateTime(data.path("observed_at"));
        return accountRelationMapper.existsByNaturalKey(
                sourcePlatformUserId,
                targetPlatformUserId,
                relationType,
                observedAt);
    }
}

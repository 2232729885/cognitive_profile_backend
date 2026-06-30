package com.idata.profile.ingestion.consumer;

import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.common.constant.RecordType;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.infra.kafka.KafkaTopicConstants;
import com.idata.profile.ingestion.dedup.DeduplicationChecker;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class SocialAccountConsumer {

    private final DeduplicationChecker deduplicationChecker;
    private final SocialAccountNormalizer normalizer;
    private final RawRecordMapper rawRecordMapper;
    private final SocialAccountMapper socialAccountMapper;
    private final SocialAccountSnapshotMapper snapshotMapper;

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
        if (deduplicationChecker.isDuplicate(sourceRecordId, payloadHash)) {
            log.debug("Duplicate message skipped, sourceRecordId={}", sourceRecordId);
            return;
        }

        RawRecord rawRecord = buildRawRecord(kafkaMessage);
        rawRecord.setPipelineStatus(PipelineStatus.RECEIVED.name());
        rawRecordMapper.insert(rawRecord);

        SocialAccountNormalizer.NormalizedAccount normalized = normalizer.normalize(kafkaMessage, rawRecord);
        socialAccountMapper.upsertByPlatformAndUserId(normalized.getAccount());
        snapshotMapper.insert(normalized.getSnapshot());
        rawRecord.setPipelineStatus(PipelineStatus.NORMALIZED.name());
        rawRecordMapper.updateById(rawRecord);
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

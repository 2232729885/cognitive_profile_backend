package com.idata.profile.ingestion.consumer;

import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.common.constant.RecordType;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.infra.kafka.KafkaTopicConstants;
import com.idata.profile.ingestion.dedup.DeduplicationChecker;
import com.idata.profile.ingestion.normalizer.SocialContentNormalizer;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import com.idata.profile.pipeline.PipelineExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * social_content / news_article consume entry. These record types run the full
 * T1 -> T2 -> T3 -> T4 pipeline after L0/L1 ingestion.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SocialContentConsumer {

    private static final String SCHEMA_VERSION = "kt3_to_kt4_v1";

    private final DeduplicationChecker deduplicationChecker;
    private final SocialContentNormalizer normalizer;
    private final RawRecordMapper rawRecordMapper;
    private final MediaContentMapper mediaContentMapper;
    private final PipelineTaskMapper pipelineTaskMapper;
    private final PipelineExecutor pipelineExecutor;

    @KafkaListener(topics = KafkaTopicConstants.SOCIAL_CONTENT, groupId = "cognitive-profile-ingestion")
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

        processInTransaction(kafkaMessage);
    }

    @Transactional
    public void processInTransaction(Object kafkaMessage) {
        RawRecord rawRecord = buildRawRecord(kafkaMessage);
        rawRecord.setPipelineStatus(PipelineStatus.RECEIVED.name());
        rawRecordMapper.insert(rawRecord);

        MediaContent mediaContent = normalizer.normalize(kafkaMessage, rawRecord);
        mediaContentMapper.insert(mediaContent);

        PipelineTask task = new PipelineTask();
        task.setId(UUID.randomUUID());
        task.setRawRecordId(rawRecord.getId());
        task.setContentId(mediaContent.getId());
        task.setStatus("PENDING");
        pipelineTaskMapper.insert(task);

        rawRecord.setPipelineTaskId(task.getId());
        rawRecord.setPipelineStatus(PipelineStatus.NORMALIZED.name());
        rawRecordMapper.updateById(rawRecord);

        pipelineExecutor.submitAfterCommit(task.getId());
    }

    private Object parseMessage(String rawMessage) {
        return IngestionMessageSupport.parseMessage(rawMessage);
    }

    private boolean isValidSchema(Object kafkaMessage) {
        JsonNode root = root(kafkaMessage);
        if (root == null || !root.isObject()) {
            return false;
        }

        String schemaVersion = text(root, "schema_version");
        String recordType = text(root, "record_type");
        JsonNode data = root.path("data");

        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !isPipelineRecordType(recordType)
                || !hasText(extractSourceRecordId(kafkaMessage))
                || !hasText(extractPayloadHash(kafkaMessage))
                || !hasText(text(root, "platform"))
                || data.isMissingNode()
                || !data.isObject()) {
            return false;
        }

        if (RecordType.SOCIAL_CONTENT.getCode().equals(recordType)) {
            return hasText(text(data, "platform_content_id"))
                    && hasText(text(data, "author_platform_user_id"));
        }

        return hasText(text(root, "source_url")) || hasText(text(data, "url"));
    }

    private String extractSourceRecordId(Object kafkaMessage) {
        return IngestionMessageSupport.extractSourceRecordId(kafkaMessage);
    }

    private String extractPayloadHash(Object kafkaMessage) {
        return IngestionMessageSupport.extractPayloadHash(kafkaMessage);
    }

    private RawRecord buildRawRecord(Object kafkaMessage) {
        String recordType = text(root(kafkaMessage), "record_type");
        return IngestionMessageSupport.buildRawRecord(kafkaMessage, resolveSourceTopic(recordType));
    }

    private JsonNode root(Object kafkaMessage) {
        return IngestionMessageSupport.root(kafkaMessage);
    }

    private boolean isPipelineRecordType(String recordType) {
        return RecordType.SOCIAL_CONTENT.getCode().equals(recordType)
                || RecordType.NEWS_ARTICLE.getCode().equals(recordType);
    }

    private String resolveSourceTopic(String recordType) {
        if (RecordType.NEWS_ARTICLE.getCode().equals(recordType)) {
            return KafkaTopicConstants.NEWS_ARTICLE;
        }
        return KafkaTopicConstants.SOCIAL_CONTENT;
    }

    private String text(JsonNode node, String fieldName) {
        return IngestionMessageSupport.text(node, fieldName);
    }

    private boolean hasText(String value) {
        return IngestionMessageSupport.hasText(value);
    }
}

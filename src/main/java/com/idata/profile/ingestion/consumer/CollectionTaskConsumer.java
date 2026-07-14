package com.idata.profile.ingestion.consumer;

import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.common.constant.RecordType;
import com.idata.profile.common.util.HashUtil;
import com.idata.profile.entity.content.CollectionTask;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.infra.kafka.KafkaTopicConstants;
import com.idata.profile.ingestion.dedup.DeduplicationChecker;
import com.idata.profile.ingestion.normalizer.CollectionTaskNormalizer;
import com.idata.profile.mapper.content.CollectionTaskMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CollectionTaskConsumer {

    private final DeduplicationChecker deduplicationChecker;
    private final CollectionTaskNormalizer normalizer;
    private final RawRecordMapper rawRecordMapper;
    private final CollectionTaskMapper collectionTaskMapper;

    @KafkaListener(topics = KafkaTopicConstants.COLLECTION_TASK, groupId = "cognitive-profile-ingestion")
    @Transactional
    public void onMessage(String rawMessage) {
        Object kafkaMessage = parseMessage(rawMessage);
        String sourceRecordId = extractSourceRecordId(kafkaMessage);

        if (!isValidSchema(kafkaMessage)) {
            log.error("Schema validation failed, sourceRecordId={}", sourceRecordId);
            return;
        }
        if (deduplicationChecker.isDuplicateBySourceRecordId(sourceRecordId)) {
            log.debug("Duplicate message skipped, sourceRecordId={}", sourceRecordId);
            return;
        }

        RawRecord rawRecord = buildRawRecord(kafkaMessage);
        // collection_task raw_payload is often empty, so upstream raw_payload_hash can collide across tasks.
        // This record type is identified by sourceRecordId/crawl_task_id, so derive a unique payload hash from it.
        rawRecord.setPayloadHash(HashUtil.sha256(sourceRecordId));
        rawRecord.setPipelineStatus(PipelineStatus.RECEIVED.name());
        rawRecordMapper.insert(rawRecord);

        CollectionTask task = normalizer.normalize(kafkaMessage, rawRecord);
        collectionTaskMapper.upsertByCrawlTaskId(task);

        rawRecord.setPipelineStatus(PipelineStatus.NORMALIZED.name());
        rawRecordMapper.updateById(rawRecord);
    }

    private Object parseMessage(String rawMessage) {
        return IngestionMessageSupport.parseMessage(rawMessage);
    }

    private boolean isValidSchema(Object kafkaMessage) {
        return IngestionMessageSupport.hasCommonEnvelope(kafkaMessage, RecordType.COLLECTION_TASK.getCode())
                && IngestionMessageSupport.hasText(
                IngestionMessageSupport.text(IngestionMessageSupport.root(kafkaMessage), "crawl_task_id"));
    }

    private String extractSourceRecordId(Object kafkaMessage) {
        return IngestionMessageSupport.extractSourceRecordId(kafkaMessage);
    }

    private RawRecord buildRawRecord(Object kafkaMessage) {
        return IngestionMessageSupport.buildRawRecord(kafkaMessage, KafkaTopicConstants.COLLECTION_TASK);
    }
}

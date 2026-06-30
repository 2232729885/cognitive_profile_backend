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

@Slf4j
@Component
@RequiredArgsConstructor
public class NewsArticleConsumer {

    private final DeduplicationChecker deduplicationChecker;
    private final SocialContentNormalizer normalizer;
    private final RawRecordMapper rawRecordMapper;
    private final MediaContentMapper mediaContentMapper;
    private final PipelineTaskMapper pipelineTaskMapper;
    private final PipelineExecutor pipelineExecutor;

    @KafkaListener(topics = KafkaTopicConstants.NEWS_ARTICLE, groupId = "cognitive-profile-ingestion")
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
        if (!IngestionMessageSupport.hasCommonEnvelope(kafkaMessage, RecordType.NEWS_ARTICLE.getCode())) {
            return false;
        }
        JsonNode root = IngestionMessageSupport.root(kafkaMessage);
        JsonNode data = IngestionMessageSupport.data(kafkaMessage);
        return IngestionMessageSupport.hasText(IngestionMessageSupport.text(root, "platform"))
                && (IngestionMessageSupport.hasText(IngestionMessageSupport.text(root, "source_url"))
                || IngestionMessageSupport.hasText(IngestionMessageSupport.text(data, "url")));
    }

    private String extractSourceRecordId(Object kafkaMessage) {
        return IngestionMessageSupport.extractSourceRecordId(kafkaMessage);
    }

    private String extractPayloadHash(Object kafkaMessage) {
        return IngestionMessageSupport.extractPayloadHash(kafkaMessage);
    }

    private RawRecord buildRawRecord(Object kafkaMessage) {
        return IngestionMessageSupport.buildRawRecord(kafkaMessage, KafkaTopicConstants.NEWS_ARTICLE);
    }
}

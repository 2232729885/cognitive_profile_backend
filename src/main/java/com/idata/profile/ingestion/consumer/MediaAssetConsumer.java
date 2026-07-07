package com.idata.profile.ingestion.consumer;

import com.idata.profile.batch.asset.ImageEmbeddingService;
import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.common.constant.RecordType;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.infra.kafka.KafkaTopicConstants;
import com.idata.profile.ingestion.dedup.DeduplicationChecker;
import com.idata.profile.ingestion.normalizer.MediaAssetNormalizer;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.mapper.content.MediaAssetMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
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
public class MediaAssetConsumer {

    private final DeduplicationChecker deduplicationChecker;
    private final MediaAssetNormalizer normalizer;
    private final RawRecordMapper rawRecordMapper;
    private final MediaAssetMapper mediaAssetMapper;
    private final MediaContentMapper mediaContentMapper;
    private final ImageEmbeddingService imageEmbeddingService;

    @KafkaListener(topics = KafkaTopicConstants.MEDIA_ASSET, groupId = "cognitive-profile-ingestion")
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

        MediaAsset asset = normalizer.normalize(kafkaMessage, rawRecord);
        UUID linkedContentId = linkContent(kafkaMessage, asset, rawRecord);
        int inserted = mediaAssetMapper.insertIgnoreOnConflictSha256(asset);
        MediaAsset indexedAsset = inserted > 0 ? asset : mediaAssetMapper.selectBySha256(asset.getSha256());
        if (linkedContentId != null && indexedAsset != null) {
            mediaContentMapper.appendMediaAssetId(linkedContentId, indexedAsset.getId().toString());
        }

        rawRecord.setPipelineStatus(PipelineStatus.NORMALIZED.name());
        rawRecordMapper.updateById(rawRecord);

        if (indexedAsset != null) {
            imageEmbeddingService.submitAfterCommit(indexedAsset.getId());
        }
    }

    private Object parseMessage(String rawMessage) {
        return IngestionMessageSupport.parseMessage(rawMessage);
    }

    private boolean isValidSchema(Object kafkaMessage) {
        if (!IngestionMessageSupport.hasCommonEnvelope(kafkaMessage, RecordType.MEDIA_ASSET.getCode())) {
            return false;
        }
        JsonNode data = IngestionMessageSupport.data(kafkaMessage);
        return IngestionMessageSupport.hasText(IngestionMessageSupport.text(data, "sha256"))
                && IngestionMessageSupport.hasText(IngestionMessageSupport.text(data, "asset_type"));
    }

    private String extractSourceRecordId(Object kafkaMessage) {
        return IngestionMessageSupport.extractSourceRecordId(kafkaMessage);
    }

    private String extractPayloadHash(Object kafkaMessage) {
        return IngestionMessageSupport.extractPayloadHash(kafkaMessage);
    }

    private RawRecord buildRawRecord(Object kafkaMessage) {
        return IngestionMessageSupport.buildRawRecord(kafkaMessage, KafkaTopicConstants.MEDIA_ASSET);
    }

    private UUID linkContent(Object kafkaMessage, MediaAsset asset, RawRecord rawRecord) {
        String platformContentId = IngestionMessageSupport.text(
                IngestionMessageSupport.data(kafkaMessage), "platform_content_id");
        if (!IngestionMessageSupport.hasText(platformContentId)) {
            return null;
        }
        MediaContent content = mediaContentMapper.selectByPlatformAndContentId(
                rawRecord.getPlatform(), platformContentId);
        if (content != null) {
            asset.setContentId(content.getId());
            return content.getId();
        }
        return null;
    }
}

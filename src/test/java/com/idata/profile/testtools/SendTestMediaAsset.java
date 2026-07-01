package com.idata.profile.testtools;

import com.idata.profile.common.util.HashUtil;
import com.idata.profile.infra.kafka.KafkaTopicConstants;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public class SendTestMediaAsset {

    private static final String BOOTSTRAP_SERVERS = "172.16.40.232:9092";
    private static final String SCHEMA_VERSION = "kt3_to_kt4_v1";
    private static final String RECORD_TYPE = "media_asset";
    private static final String PLATFORM = "mock";

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        String uniqueId = UUID.randomUUID().toString();
        String sourceRecordId = "local_media_asset_" + uniqueId;
        String sourceAssetId = "local_asset_" + uniqueId;
        String assetSha256 = UUID.randomUUID().toString();
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();

        String messageWithoutHash = buildMessage(sourceRecordId, "", sourceAssetId, assetSha256, now);
        String rawPayloadHash = HashUtil.sha256(messageWithoutHash);
        String message = buildMessage(sourceRecordId, rawPayloadHash, sourceAssetId, assetSha256, now);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    KafkaTopicConstants.MEDIA_ASSET,
                    sourceRecordId,
                    message
            );
            RecordMetadata metadata = producer.send(record).get();
            producer.flush();

            System.out.println("Sent Kafka message successfully.");
            System.out.println("topic=" + metadata.topic()
                    + ", partition=" + metadata.partition()
                    + ", offset=" + metadata.offset());
            System.out.println("sourceRecordId=" + sourceRecordId);
        }
    }

    private static String buildMessage(
            String sourceRecordId,
            String rawPayloadHash,
            String sourceAssetId,
            String assetSha256,
            String now
    ) {
        return String.format("""
                {
                  "schema_version": "%s",
                  "record_type": "%s",
                  "raw_record_id": "%s",
                  "raw_payload_hash": "%s",
                  "collected_at": "%s",
                  "platform": "%s",
                  "data": {
                    "source_asset_id": "%s",
                    "asset_type": "image",
                    "source_url": "https://mock.local/media/%s.jpg",
                    "storage_uri": "s3://kt3-media/local/%s.jpg",
                    "mime_type": "image/jpeg",
                    "sha256": "%s",
                    "file_size_bytes": 204800,
                    "width": 1280,
                    "height": 720,
                    "duration_seconds": 0,
                    "thumbnail_uri": "s3://kt3-media/local/%s-thumb.jpg",
                    "ocr_text": "Local media asset OCR text.",
                    "asr_text": ""
                  }
                }
                """,
                json(SCHEMA_VERSION),
                json(RECORD_TYPE),
                json(sourceRecordId),
                json(rawPayloadHash),
                json(now),
                json(PLATFORM),
                json(sourceAssetId),
                json(sourceAssetId),
                json(sourceAssetId),
                json(assetSha256),
                json(sourceAssetId)
        );
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

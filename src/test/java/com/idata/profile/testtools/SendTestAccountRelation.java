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

public class SendTestAccountRelation {

    private static final String BOOTSTRAP_SERVERS = "172.16.40.232:9092";
    private static final String SCHEMA_VERSION = "kt3_to_kt4_v1";
    private static final String RECORD_TYPE = "account_relation";
    private static final String PLATFORM = "mock";
    private static final String SOURCE_PLATFORM_USER_ID = "test_user_001";
    private static final String TARGET_PLATFORM_USER_ID = "test_channel_001";

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        String uniqueId = UUID.randomUUID().toString();
        String sourceRecordId = "local_account_relation_" + uniqueId;
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();

        String messageWithoutHash = buildMessage(
                sourceRecordId,
                "",
                SOURCE_PLATFORM_USER_ID,
                TARGET_PLATFORM_USER_ID,
                now
        );
        String rawPayloadHash = HashUtil.sha256(messageWithoutHash);
        String message = buildMessage(
                sourceRecordId,
                rawPayloadHash,
                SOURCE_PLATFORM_USER_ID,
                TARGET_PLATFORM_USER_ID,
                now
        );

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    KafkaTopicConstants.ACCOUNT_RELATION,
                    sourceRecordId,
                    message
            );
            RecordMetadata metadata = producer.send(record).get();
            producer.flush();

            System.out.println("Sent Kafka message successfully.");
            System.out.println("topic=" + metadata.topic()
                    + ", partition=" + metadata.partition()
                    + ", offset=" + metadata.offset());
            System.out.println("sourceRecordId=" + sourceRecordId
                    + ", platform=" + PLATFORM
                    + ", sourcePlatformUserId=" + SOURCE_PLATFORM_USER_ID
                    + ", targetPlatformUserId=" + TARGET_PLATFORM_USER_ID);
        }
    }

    private static String buildMessage(
            String sourceRecordId,
            String rawPayloadHash,
            String sourcePlatformUserId,
            String targetPlatformUserId,
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
                    "source_platform_user_id": "%s",
                    "target_platform_user_id": "%s",
                    "relation_type": "following",
                    "observed_at": "%s",
                    "occurred_at": "%s",
                    "source": "following_list"
                  }
                }
                """,
                json(SCHEMA_VERSION),
                json(RECORD_TYPE),
                json(sourceRecordId),
                json(rawPayloadHash),
                json(now),
                json(PLATFORM),
                json(sourcePlatformUserId),
                json(targetPlatformUserId),
                json(now),
                json(now)
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

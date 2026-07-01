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

public class SendTestSocialAccount {

    private static final String BOOTSTRAP_SERVERS = "172.16.40.232:9092";
    private static final String SCHEMA_VERSION = "kt3_to_kt4_v1";
    private static final String RECORD_TYPE = "social_account";
    private static final String PLATFORM = "mock";
    private static final String SOURCE_PLATFORM_USER_ID = "test_user_001";
    private static final String TARGET_PLATFORM_USER_ID = "test_channel_001";

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            sendAccount(producer, SOURCE_PLATFORM_USER_ID, "user", "mock_user",
                    "@test_user_001", "Test User 001", 1000L, 200L);
            sendAccount(producer, TARGET_PLATFORM_USER_ID, "channel", "mock_channel",
                    "@test_channel_001", "Test Channel 001", 5000L, 12L);
            producer.flush();
        }
    }

    private static void sendAccount(
            KafkaProducer<String, String> producer,
            String platformUserId,
            String accountEntityType,
            String platformNativeType,
            String handle,
            String displayName,
            long followersCount,
            long followingCount
    ) throws ExecutionException, InterruptedException {
        String uniqueId = UUID.randomUUID().toString();
        String sourceRecordId = "local_social_account_" + platformUserId + "_" + uniqueId;
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();

        String messageWithoutHash = buildMessage(
                sourceRecordId,
                "",
                platformUserId,
                accountEntityType,
                platformNativeType,
                handle,
                displayName,
                followersCount,
                followingCount,
                now
        );
        String rawPayloadHash = HashUtil.sha256(messageWithoutHash);
        String message = buildMessage(
                sourceRecordId,
                rawPayloadHash,
                platformUserId,
                accountEntityType,
                platformNativeType,
                handle,
                displayName,
                followersCount,
                followingCount,
                now
        );

        ProducerRecord<String, String> record = new ProducerRecord<>(
                KafkaTopicConstants.SOCIAL_ACCOUNT,
                sourceRecordId,
                message
        );
        RecordMetadata metadata = producer.send(record).get();

        System.out.println("Sent social_account Kafka message successfully.");
        System.out.println("topic=" + metadata.topic()
                + ", partition=" + metadata.partition()
                + ", offset=" + metadata.offset());
        System.out.println("sourceRecordId=" + sourceRecordId
                + ", platform=" + PLATFORM
                + ", platformUserId=" + platformUserId);
    }

    private static String buildMessage(
            String sourceRecordId,
            String rawPayloadHash,
            String platformUserId,
            String accountEntityType,
            String platformNativeType,
            String handle,
            String displayName,
            long followersCount,
            long followingCount,
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
                    "platform_user_id": "%s",
                    "account_entity_type": "%s",
                    "platform_native_type": "%s",
                    "handle": "%s",
                    "display_name": "%s",
                    "bio": "Test account for account_relation backfill",
                    "avatar_url": "https://mock.local/avatar/%s.png",
                    "profile_url": "https://mock.local/account/%s",
                    "self_declared_location": "Shanghai",
                    "verified": true,
                    "verified_type": "blue",
                    "is_suspended": false,
                    "account_created_at": "%s",
                    "metrics": {
                      "followers_count": %d,
                      "following_count": %d,
                      "subscriber_count": 300,
                      "member_count": 0,
                      "post_count": 50,
                      "view_count": 10000
                    }
                  }
                }
                """,
                json(SCHEMA_VERSION),
                json(RECORD_TYPE),
                json(sourceRecordId),
                json(rawPayloadHash),
                json(now),
                json(PLATFORM),
                json(platformUserId),
                json(accountEntityType),
                json(platformNativeType),
                json(handle),
                json(displayName),
                json(platformUserId),
                json(platformUserId),
                json(now),
                followersCount,
                followingCount
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

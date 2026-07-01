package com.idata.profile.testtools;

import com.idata.profile.infra.kafka.KafkaTopicConstants;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public class SendTestSocialContent {

    private static final String BOOTSTRAP_SERVERS = "172.16.40.232:9092";
    private static final String SCHEMA_VERSION = "kt3_to_kt4_v1";
    private static final String RECORD_TYPE = "social_content";
    private static final String PLATFORM = "mock";

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        String uniqueId = UUID.randomUUID().toString();
        String sourceRecordId = "local_social_content_" + uniqueId;
        String platformContentId = "local_post_" + uniqueId;
        String authorPlatformUserId = "local_author_001";
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();

        String messageWithoutHash = buildMessage(
                sourceRecordId,
                "",
                platformContentId,
                authorPlatformUserId,
                now
        );
        String rawPayloadHash = sha256(messageWithoutHash);
        String message = buildMessage(
                sourceRecordId,
                rawPayloadHash,
                platformContentId,
                authorPlatformUserId,
                now
        );

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    KafkaTopicConstants.SOCIAL_CONTENT,
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
            String platformContentId,
            String authorPlatformUserId,
            String now
    ) {
        String sourceUrl = "https://mock.local/social/" + platformContentId;
        return String.format("""
                {
                  "schema_version": "%s",
                  "record_type": "%s",
                  "raw_record_id": "%s",
                  "raw_payload_hash": "%s",
                  "crawl_task_id": "local_social_content_check",
                  "collected_at": "%s",
                  "source_url": "%s",
                  "platform": "%s",
                  "language": "zh-CN",
                  "data": {
                    "content_type": "post",
                    "platform_content_id": "%s",
                    "author_platform_user_id": "%s",
                    "text": "Local kafka social_content integration test message.",
                    "published_at": "%s",
                    "url": "%s",
                    "hashtags": ["local", "kafka", "social_content"],
                    "mentions": ["%s"],
                    "external_urls": ["https://mock.local/reference"],
                    "like_count": 12,
                    "comment_count": 3,
                    "share_count": 2,
                    "view_count": 120
                  }
                }
                """,
                json(SCHEMA_VERSION),
                json(RECORD_TYPE),
                json(sourceRecordId),
                json(rawPayloadHash),
                json(now),
                json(sourceUrl),
                json(PLATFORM),
                json(platformContentId),
                json(authorPlatformUserId),
                json(now),
                json(sourceUrl),
                json(authorPlatformUserId)
        );
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
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

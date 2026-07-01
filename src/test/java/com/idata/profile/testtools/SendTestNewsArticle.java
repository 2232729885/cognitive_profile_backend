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

public class SendTestNewsArticle {

    private static final String BOOTSTRAP_SERVERS = "172.16.40.232:9092";
    private static final String SCHEMA_VERSION = "kt3_to_kt4_v1";
    private static final String RECORD_TYPE = "news_article";
    private static final String PLATFORM = "news";

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        String uniqueId = UUID.randomUUID().toString();
        String sourceRecordId = "local_news_article_" + uniqueId;
        String platformContentId = "local_news_" + uniqueId;
        String sourceUrl = "https://mock.local/news/" + platformContentId;
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();

        String messageWithoutHash = buildMessage(sourceRecordId, "", platformContentId, sourceUrl, now);
        String rawPayloadHash = HashUtil.sha256(messageWithoutHash);
        String message = buildMessage(sourceRecordId, rawPayloadHash, platformContentId, sourceUrl, now);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    KafkaTopicConstants.NEWS_ARTICLE,
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
            String sourceUrl,
            String now
    ) {
        return String.format("""
                {
                  "schema_version": "%s",
                  "record_type": "%s",
                  "raw_record_id": "%s",
                  "raw_payload_hash": "%s",
                  "crawl_task_id": "local_news_article_check",
                  "collected_at": "%s",
                  "source_url": "%s",
                  "platform": "%s",
                  "language": "zh-CN",
                  "data": {
                    "content_type": "article",
                    "platform_content_id": "%s",
                    "author_platform_user_id": "local_news_author_001",
                    "title": "Local News Article Integration Test",
                    "body_text": "This is a local kafka news_article integration test message.",
                    "published_at": "%s",
                    "url": "%s",
                    "source_name": "Mock News",
                    "domain": "mock.local",
                    "author": "Test Reporter",
                    "section": "technology",
                    "tags": ["local", "kafka", "news_article"],
                    "external_urls": ["https://mock.local/reference"],
                    "view_count": 1200,
                    "reaction_count": 8
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
                json(now),
                json(sourceUrl)
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

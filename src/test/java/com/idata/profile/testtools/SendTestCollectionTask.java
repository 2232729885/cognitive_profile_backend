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

public class SendTestCollectionTask {

    private static final String BOOTSTRAP_SERVERS = "172.16.40.232:9092";
    private static final String SCHEMA_VERSION = "kt3_to_kt4_v1";
    private static final String RECORD_TYPE = "collection_task";
    private static final String PLATFORM = "mock";

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        String uniqueId = UUID.randomUUID().toString();
        String sourceRecordId = "local_collection_task_" + uniqueId;
        String crawlTaskId = "local_crawl_task_" + uniqueId;
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        String timeWindowStart = OffsetDateTime.now(ZoneOffset.UTC).minusHours(6).toString();
        String timeWindowEnd = OffsetDateTime.now(ZoneOffset.UTC).toString();

        String messageWithoutHash = buildMessage(sourceRecordId, "", crawlTaskId, now, timeWindowStart, timeWindowEnd);
        String rawPayloadHash = HashUtil.sha256(messageWithoutHash);
        String message = buildMessage(sourceRecordId, rawPayloadHash, crawlTaskId, now, timeWindowStart, timeWindowEnd);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    KafkaTopicConstants.COLLECTION_TASK,
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
            String crawlTaskId,
            String now,
            String timeWindowStart,
            String timeWindowEnd
    ) {
        return String.format("""
                {
                  "schema_version": "%s",
                  "record_type": "%s",
                  "raw_record_id": "%s",
                  "raw_payload_hash": "%s",
                  "crawl_task_id": "%s",
                  "collected_at": "%s",
                  "platform": "%s",
                  "language": "zh-CN",
                  "data": {
                    "collection_method": "api",
                    "seed_type": "keyword",
                    "seed_value": "local integration test",
                    "query_expression": "local integration test lang:zh",
                    "time_window_start": "%s",
                    "time_window_end": "%s",
                    "target_languages": ["zh-CN", "en"],
                    "target_regions": ["CN", "US"],
                    "collector_version": "local-test-1.0",
                    "records_collected": 128
                  }
                }
                """,
                json(SCHEMA_VERSION),
                json(RECORD_TYPE),
                json(sourceRecordId),
                json(rawPayloadHash),
                json(crawlTaskId),
                json(now),
                json(PLATFORM),
                json(timeWindowStart),
                json(timeWindowEnd)
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

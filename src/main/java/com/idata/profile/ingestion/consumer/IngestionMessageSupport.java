package com.idata.profile.ingestion.consumer;

import com.idata.profile.common.constant.RecordType;
import com.idata.profile.common.util.HashUtil;
import com.idata.profile.entity.raw.RawRecord;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;

public final class IngestionMessageSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private IngestionMessageSupport() {
    }

    public static ParsedKafkaMessage parseMessage(String rawMessage) {
        try {
            return new ParsedKafkaMessage(OBJECT_MAPPER.readTree(rawMessage), rawMessage);
        } catch (JacksonException e) {
            return new ParsedKafkaMessage(null, rawMessage);
        }
    }

    public static String extractSourceRecordId(Object kafkaMessage) {
        return firstText(root(kafkaMessage), "raw_record_id", "source_record_id");
    }

    public static String extractPayloadHash(Object kafkaMessage) {
        ParsedKafkaMessage message = asMessage(kafkaMessage);
        String payloadHash = firstText(message.root(), "raw_payload_hash", "payload_hash");
        if (hasText(payloadHash)) {
            return normalizePayloadHash(payloadHash);
        }
        if (hasText(message.rawPayload())) {
            return HashUtil.sha256(message.rawPayload());
        }
        return null;
    }

    public static RawRecord buildRawRecord(Object kafkaMessage, String sourceTopic) {
        ParsedKafkaMessage message = asMessage(kafkaMessage);
        JsonNode root = message.root();
        JsonNode data = root.path("data");
        String recordType = text(root, "record_type");

        RawRecord rawRecord = new RawRecord();
        rawRecord.setId(UUID.randomUUID());
        rawRecord.setSchemaVersion(text(root, "schema_version"));
        rawRecord.setRecordType(recordType);
        rawRecord.setSourceRecordId(extractSourceRecordId(kafkaMessage));
        rawRecord.setCrawlTaskId(text(root, "crawl_task_id"));
        rawRecord.setCollectedAt(parseOffsetDateTime(root.path("collected_at")));
        rawRecord.setSourceUrl(firstText(root, "source_url", "url"));
        if (!hasText(rawRecord.getSourceUrl())) {
            rawRecord.setSourceUrl(text(data, "url"));
        }
        rawRecord.setPayloadHash(extractPayloadHash(kafkaMessage));
        rawRecord.setSourceType("kafka");
        rawRecord.setSourceTopic(sourceTopic);
        rawRecord.setPlatform(text(root, "platform"));
        rawRecord.setLanguage(text(root, "language"));
        rawRecord.setContentType(text(data, "content_type"));
        if (!hasText(rawRecord.getContentType())
                && RecordType.NEWS_ARTICLE.getCode().equals(recordType)) {
            rawRecord.setContentType("article");
        }
        rawRecord.setRawPayload(message.rawPayload());
        rawRecord.setRawPublishedAt(parseOffsetDateTime(data.path("published_at")));
        rawRecord.setRetryCount((short) 0);
        return rawRecord;
    }

    public static boolean hasCommonEnvelope(Object kafkaMessage, String recordType) {
        JsonNode root = root(kafkaMessage);
        JsonNode data = data(kafkaMessage);
        return root != null
                && root.isObject()
                && recordType.equals(text(root, "record_type"))
                && hasText(extractSourceRecordId(kafkaMessage))
                && hasText(extractPayloadHash(kafkaMessage))
                && data != null
                && data.isObject();
    }

    public static JsonNode root(Object kafkaMessage) {
        return asMessage(kafkaMessage).root();
    }

    public static JsonNode data(Object kafkaMessage) {
        JsonNode root = root(kafkaMessage);
        return root == null ? null : root.path("data");
    }

    public static ParsedKafkaMessage asMessage(Object kafkaMessage) {
        if (!(kafkaMessage instanceof ParsedKafkaMessage message)) {
            throw new IllegalArgumentException("Unsupported kafka message type: " + kafkaMessage);
        }
        return message;
    }

    public static String firstText(JsonNode node, String... fieldNames) {
        if (node == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            String value = text(node, fieldName);
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    public static String text(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return hasText(text) ? text.trim() : null;
    }

    public static OffsetDateTime parseOffsetDateTime(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            long epoch = node.asLong();
            if (epoch > 10_000_000_000L) {
                return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneOffset.UTC);
            }
            return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.UTC);
        }

        String value = node.asText();
        if (!hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.ofInstant(Instant.parse(value.trim()), ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    public static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String normalizePayloadHash(String payloadHash) {
        String value = payloadHash.trim();
        if (value.regionMatches(true, 0, "sha256:", 0, "sha256:".length())) {
            return value.substring("sha256:".length()).trim();
        }
        return value;
    }

    public record ParsedKafkaMessage(JsonNode root, String rawPayload) {
    }
}

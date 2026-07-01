package com.idata.profile.ingestion.normalizer;

import com.idata.profile.entity.content.CollectionTask;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.ingestion.consumer.IngestionMessageSupport;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class CollectionTaskNormalizer {

    public CollectionTask normalize(Object kafkaMessage, RawRecord rawRecord) {
        JsonNode root = IngestionMessageSupport.root(kafkaMessage);
        JsonNode data = IngestionMessageSupport.data(kafkaMessage);

        CollectionTask task = new CollectionTask();
        task.setId(UUID.randomUUID());
        task.setRawRecordId(rawRecord.getId());
        task.setCrawlTaskId(rawRecord.getCrawlTaskId());
        task.setCollectionMethod(IngestionMessageSupport.text(data, "collection_method"));
        task.setSeedType(IngestionMessageSupport.text(data, "seed_type"));
        task.setSeedValue(IngestionMessageSupport.text(data, "seed_value"));
        task.setQueryExpression(IngestionMessageSupport.text(data, "query_expression"));
        task.setPlatform(IngestionMessageSupport.text(root, "platform"));
        task.setTimeWindowStart(IngestionMessageSupport.parseOffsetDateTime(data.path("time_window_start")));
        task.setTimeWindowEnd(IngestionMessageSupport.parseOffsetDateTime(data.path("time_window_end")));
        task.setTargetLanguages(readStringArray(data.path("target_languages")));
        task.setTargetRegions(readStringArray(data.path("target_regions")));
        task.setCollectorVersion(IngestionMessageSupport.text(data, "collector_version"));
        task.setRecordsCollected(readInteger(data, "records_collected"));
        task.setRawPayload(IngestionMessageSupport.asMessage(kafkaMessage).rawPayload());

        return task;
    }

    private Integer readInteger(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        String text = value.asText();
        if (!IngestionMessageSupport.hasText(text)) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String[] readStringArray(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }

        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText();
                if (IngestionMessageSupport.hasText(value)) {
                    values.add(value.trim());
                }
            }
        } else {
            String value = node.asText();
            if (IngestionMessageSupport.hasText(value)) {
                values.add(value.trim());
            }
        }
        return values.isEmpty() ? null : values.toArray(String[]::new);
    }
}

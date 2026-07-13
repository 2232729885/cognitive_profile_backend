package com.idata.profile.batch.ingestion;

import com.idata.profile.entity.system.BatchImportTask;
import com.idata.profile.infra.kafka.KafkaTopicConstants;
import com.idata.profile.infra.minio.MinioStorageService;
import com.idata.profile.mapper.system.BatchImportTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchImportJob {

    private static final String BATCH_IMPORT_BUCKET = "batch-imports";
    private static final String MEDIA_BUCKET = "media-assets";
    private static final int BATCH_LIMIT = 5;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final BatchImportTaskMapper batchImportTaskMapper;
    private final MinioStorageService minioStorageService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 60 * 1000)
    public void run() {
        List<BatchImportTask> tasks = batchImportTaskMapper.selectPendingTasks(BATCH_LIMIT);
        for (BatchImportTask task : tasks) {
            try {
                processTask(task);
            } catch (Exception e) {
                log.error("[BatchImportJob] task failed, taskId={}", task.getId(), e);
                failTask(task, e.getMessage());
            }
        }
    }

    private void processTask(BatchImportTask task) throws Exception {
        task.setStatus("RUNNING");
        task.setStartedAt(OffsetDateTime.now());
        task.setErrorMessage(null);
        batchImportTaskMapper.updateById(task);

        String format = task.getFileFormat();
        if ("csv".equals(format) || "excel".equals(format)) {
            failTask(task, "CSV/Excel 格式暂不支持批量导入，请使用 JSON/JSONL/ZIP 格式");
            return;
        }

        byte[] fileBytes = minioStorageService.download(BATCH_IMPORT_BUCKET, task.getMinioKey());

        Counters counters = new Counters();
        if ("zip".equals(format)) {
            processZip(fileBytes, counters);
        } else if ("jsonl".equals(format)) {
            processJsonl(new String(fileBytes, StandardCharsets.UTF_8), counters, Set.of());
        } else if ("json".equals(format)) {
            processJson(new String(fileBytes, StandardCharsets.UTF_8), counters, Set.of());
        } else {
            failTask(task, "不支持的文件格式: " + format);
            return;
        }

        task.setTotalRecords(counters.total);
        task.setProcessedRecords(counters.total);
        task.setSuccessRecords(counters.success);
        task.setFailedRecords(counters.failed);
        task.setStatus(counters.failed == 0 ? "DONE" : (counters.success == 0 ? "FAILED" : "PARTIAL"));
        task.setCompletedAt(OffsetDateTime.now());
        batchImportTaskMapper.updateById(task);
    }

    private void processZip(byte[] zipBytes, Counters counters) throws Exception {
        Map<String, byte[]> jsonlFiles = new LinkedHashMap<>();
        Map<String, byte[]> jsonFiles = new LinkedHashMap<>();
        Map<String, byte[]> mediaFiles = new LinkedHashMap<>();

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                zis.transferTo(buffer);
                String name = entry.getName();
                if (name.endsWith(".jsonl")) {
                    jsonlFiles.put(name, buffer.toByteArray());
                } else if (name.endsWith(".json")) {
                    jsonFiles.put(name, buffer.toByteArray());
                } else {
                    mediaFiles.put(name, buffer.toByteArray());
                }
            }
        }

        if (jsonlFiles.isEmpty() && jsonFiles.isEmpty()) {
            throw new IllegalArgumentException("ZIP 中未找到可导入的 JSON/JSONL 文件");
        }

        Set<String> uploadedMediaPaths = new LinkedHashSet<>();
        for (Map.Entry<String, byte[]> media : mediaFiles.entrySet()) {
            try {
                minioStorageService.upload(MEDIA_BUCKET, media.getKey(), media.getValue(), null);
                uploadedMediaPaths.add(media.getKey());
            } catch (Exception e) {
                log.warn("[BatchImportJob] media upload failed, path={}", media.getKey(), e);
            }
        }

        for (Map.Entry<String, byte[]> jsonl : jsonlFiles.entrySet()) {
            processJsonl(new String(jsonl.getValue(), StandardCharsets.UTF_8), counters, uploadedMediaPaths);
        }
        for (Map.Entry<String, byte[]> json : jsonFiles.entrySet()) {
            processJson(new String(json.getValue(), StandardCharsets.UTF_8), counters, uploadedMediaPaths);
        }
    }

    private void processJsonl(String content, Counters counters, Set<String> uploadedMediaPaths) {
        for (String line : content.split("\\r?\\n")) {
            processLine(enrichMediaAssetLine(line, uploadedMediaPaths), counters);
        }
    }

    private void processJson(String content, Counters counters, Set<String> uploadedMediaPaths) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(content);
            if (root.isArray()) {
                for (JsonNode item : root) {
                    processLine(enrichMediaAssetLine(item.toString(), uploadedMediaPaths), counters);
                }
            } else {
                processLine(enrichMediaAssetLine(content, uploadedMediaPaths), counters);
            }
        } catch (Exception e) {
            counters.total++;
            counters.failed++;
            log.warn("[BatchImportJob] invalid json content", e);
        }
    }

    private String enrichMediaAssetLine(String line, Set<String> uploadedMediaPaths) {
        if (line == null || !line.contains("\"media_asset\"") || uploadedMediaPaths.isEmpty()) {
            return line;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(line);
            JsonNode dataNode = root.path("data");
            String storageUri = text(dataNode.path("storage_uri"));
            if (storageUri != null && uploadedMediaPaths.contains(storageUri) && dataNode.isObject()) {
                ObjectNode dataObjectNode = (ObjectNode) dataNode;
                dataObjectNode.put("minio_bucket", MEDIA_BUCKET);
                dataObjectNode.put("minio_key", storageUri);
                return root.toString();
            }
        } catch (Exception e) {
            log.warn("[BatchImportJob] failed to enrich media_asset line", e);
        }
        return line;
    }

    private void processLine(String line, Counters counters) {
        if (line == null || line.isBlank()) {
            return;
        }
        counters.total++;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(line);
            String recordType = text(root.path("record_type"));
            String topic = resolveTopic(recordType);
            if (topic == null) {
                counters.failed++;
                return;
            }
            kafkaTemplate.send(topic, line).get();
            counters.success++;
        } catch (Exception e) {
            counters.failed++;
            log.warn("[BatchImportJob] failed to send record to kafka", e);
        }
    }

    private String resolveTopic(String recordType) {
        if (recordType == null) {
            return null;
        }
        return switch (recordType) {
            case "social_content" -> KafkaTopicConstants.SOCIAL_CONTENT;
            case "social_account" -> KafkaTopicConstants.SOCIAL_ACCOUNT;
            case "account_relation" -> KafkaTopicConstants.ACCOUNT_RELATION;
            case "media_asset" -> KafkaTopicConstants.MEDIA_ASSET;
            case "news_article" -> KafkaTopicConstants.NEWS_ARTICLE;
            case "collection_task" -> KafkaTopicConstants.COLLECTION_TASK;
            default -> null;
        };
    }

    private void failTask(BatchImportTask task, String errorMessage) {
        task.setStatus("FAILED");
        task.setErrorMessage(errorMessage);
        task.setCompletedAt(OffsetDateTime.now());
        batchImportTaskMapper.updateById(task);
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static class Counters {
        private int total;
        private int success;
        private int failed;
    }
}

package com.idata.profile.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.idata.profile.auth.JwtAuthFilter;
import com.idata.profile.common.response.Result;
import com.idata.profile.entity.content.CollectionTask;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.system.BatchImportTask;
import com.idata.profile.infra.minio.MinioStorageService;
import com.idata.profile.mapper.content.CollectionTaskMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.mapper.system.BatchImportTaskMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/ingestion")
@RequiredArgsConstructor
public class DataIngestionController {

    private static final String BATCH_IMPORT_BUCKET = "batch-imports";
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("json", "jsonl", "csv", "xlsx", "zip");

    private final BatchImportTaskMapper batchImportTaskMapper;
    private final RawRecordMapper rawRecordMapper;
    private final CollectionTaskMapper collectionTaskMapper;
    private final MinioStorageService minioStorageService;

    @PostMapping("/upload")
    public Result<BatchImportTask> upload(@RequestParam("file") MultipartFile file,
                                          HttpServletRequest request) throws IOException {
        if (file == null || file.isEmpty()) {
            return Result.fail("INVALID_FILE", "\u4e0a\u4f20\u6587\u4ef6\u4e0d\u80fd\u4e3a\u7a7a");
        }

        UUID userId = (UUID) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        String originalFilename = safeFilename(file.getOriginalFilename());
        String extension = extensionOf(originalFilename);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            return Result.fail("INVALID_FORMAT", "\u4e0d\u652f\u6301\u7684\u6587\u4ef6\u683c\u5f0f");
        }

        String key = "uploads/" + userId + "/" + System.currentTimeMillis() + "_" + originalFilename;
        minioStorageService.upload(BATCH_IMPORT_BUCKET, key, file.getBytes(), file.getContentType());

        BatchImportTask task = new BatchImportTask();
        task.setId(UUID.randomUUID());
        task.setUserId(userId);
        task.setFileName(originalFilename);
        task.setFileFormat(toFileFormat(extension));
        task.setMinioKey(key);
        task.setTotalRecords(0);
        task.setProcessedRecords(0);
        task.setSuccessRecords(0);
        task.setFailedRecords(0);
        task.setDuplicateRecords(0);
        task.setStatus("PENDING");
        task.setCreatedAt(OffsetDateTime.now());
        batchImportTaskMapper.insert(task);

        return Result.ok(task);
    }

    @GetMapping("/tasks")
    public Result<IPage<BatchImportTask>> listTasks(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    HttpServletRequest request) {
        UUID userId = (UUID) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        Page<BatchImportTask> pageRequest = new Page<>(Math.max(page, 0) + 1L, normalizeSize(size));
        IPage<BatchImportTask> result = batchImportTaskMapper.selectPage(pageRequest,
                new LambdaQueryWrapper<BatchImportTask>()
                        .eq(BatchImportTask::getUserId, userId)
                        .orderByDesc(BatchImportTask::getCreatedAt));
        return Result.ok(result);
    }

    @GetMapping("/tasks/{taskId}")
    public Result<BatchImportTask> getTask(@PathVariable UUID taskId) {
        BatchImportTask task = batchImportTaskMapper.selectById(taskId);
        if (task == null) {
            return Result.fail("NOT_FOUND", "\u4efb\u52a1\u4e0d\u5b58\u5728");
        }
        return Result.ok(task);
    }

    @GetMapping("/pipeline/stats")
    public Result<Map<String, Object>> pipelineStats() {
        List<Map<String, Object>> rows = rawRecordMapper.selectMaps(
                new QueryWrapper<RawRecord>()
                        .select("pipeline_status", "COUNT(*) AS cnt")
                        .groupBy("pipeline_status")
                        .orderByAsc("pipeline_status"));

        Map<String, Object> statusDistribution = new LinkedHashMap<>();
        long totalCount = 0L;
        for (Map<String, Object> row : rows) {
            String status = stringValue(row.get("pipeline_status"));
            long count = numberValue(row.get("cnt"));
            statusDistribution.put(status, count);
            totalCount += count;
        }

        Long last24hCount = rawRecordMapper.selectCount(
                new QueryWrapper<RawRecord>()
                        .gt("created_at", OffsetDateTime.now().minusHours(24)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statusDistribution", statusDistribution);
        result.put("last24hCount", last24hCount == null ? 0L : last24hCount);
        result.put("totalCount", totalCount);
        return Result.ok(result);
    }

    @GetMapping("/collection-tasks")
    public Result<IPage<CollectionTask>> listCollectionTasks(@RequestParam(required = false) String platform,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        LambdaQueryWrapper<CollectionTask> wrapper = new LambdaQueryWrapper<CollectionTask>()
                .eq(hasText(platform), CollectionTask::getPlatform, platform)
                .orderByDesc(CollectionTask::getCreatedAt);
        return Result.ok(collectionTaskMapper.selectPage(
                new Page<>(Math.max(page, 0) + 1L, normalizeSize(size)), wrapper));
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, 100);
    }

    private String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "upload.bin";
        }
        return filename.replace("\\", "_").replace("/", "_");
    }

    private String extensionOf(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1).toLowerCase();
    }

    private String toFileFormat(String extension) {
        return "xlsx".equals(extension) ? "excel" : extension;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private long numberValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(value.toString());
    }
}

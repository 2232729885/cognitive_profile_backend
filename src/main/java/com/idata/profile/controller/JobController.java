package com.idata.profile.controller;

import com.idata.profile.batch.asset.ImageEmbeddingJob;
import com.idata.profile.batch.asset.ImageEmbeddingService;
import com.idata.profile.batch.content.ContentPropagationBackfillJob;
import com.idata.profile.batch.profile.PersonProfileGenerationJob;
import com.idata.profile.batch.relation.AccountRelationBackfillJob;
import com.idata.profile.common.response.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutorService;

@Slf4j
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final ImageEmbeddingJob imageEmbeddingJob;
    private final ImageEmbeddingService imageEmbeddingService;
    private final AccountRelationBackfillJob accountRelationBackfillJob;
    private final ContentPropagationBackfillJob contentPropagationBackfillJob;
    private final PersonProfileGenerationJob personProfileGenerationJob;
    private final ExecutorService pipelineThreadPool;
    private final com.idata.profile.mapper.raw.RawRecordMapper rawRecordMapper;
    private final com.idata.profile.pipeline.PipelineExecutor pipelineExecutor;

    @PostMapping("/image-embedding/trigger")
    public Result<String> triggerImageEmbedding() {
        return trigger("image-embedding", imageEmbeddingJob::run);
    }

    @PostMapping("/image-embedding/{assetId}/reindex")
    public Result<String> reindexMediaAsset(@org.springframework.web.bind.annotation.PathVariable java.util.UUID assetId) {
        boolean accepted = imageEmbeddingService.processById(assetId);
        return accepted ? Result.ok("reindexed: " + assetId) : Result.fail("REINDEX_FAILED", "media asset reindex failed: " + assetId);
    }

    @PostMapping("/account-relation/trigger")
    public Result<String> triggerAccountRelation() {
        return trigger("account-relation", accountRelationBackfillJob::run);
    }

    @PostMapping("/content-propagation/trigger")
    public Result<String> triggerContentPropagation() {
        return trigger("content-propagation", contentPropagationBackfillJob::run);
    }

    @PostMapping("/profile-generation/trigger")
    public Result<String> triggerProfileGeneration() {
        return trigger("profile-generation", personProfileGenerationJob::run);
    }

    @PostMapping("/pipeline/retry/{rawRecordId}")
    public Result<String> retryPipelineTask(@org.springframework.web.bind.annotation.PathVariable java.util.UUID rawRecordId) {
        com.idata.profile.entity.raw.RawRecord rawRecord = rawRecordMapper.selectById(rawRecordId);
        if (rawRecord == null) {
            return Result.fail("NOT_FOUND", "raw_record不存在: " + rawRecordId);
        }
        if (rawRecord.getPipelineTaskId() == null) {
            return Result.fail("NO_PIPELINE_TASK", "这条记录还没有生成pipeline_task，可能是非流水线类型或者还没开始处理");
        }
        log.info("[JobController] manual pipeline retry, rawRecordId={}, pipelineTaskId={}",
                rawRecordId, rawRecord.getPipelineTaskId());
        pipelineExecutor.submit(rawRecord.getPipelineTaskId());
        return Result.ok("已重新提交，taskId=" + rawRecord.getPipelineTaskId());
    }

    @PostMapping("/pipeline/retry-stuck")
    public Result<String> retryStuckPipelineTasks() {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.idata.profile.entity.raw.RawRecord> query =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        query.in(com.idata.profile.entity.raw.RawRecord::getRecordType, "social_content", "news_article");
        query.notIn(com.idata.profile.entity.raw.RawRecord::getPipelineStatus, "T4_INDEXED", "FAILED");
        query.isNotNull(com.idata.profile.entity.raw.RawRecord::getPipelineTaskId);
        query.lt(com.idata.profile.entity.raw.RawRecord::getUpdatedAt,
                java.time.OffsetDateTime.now().minusMinutes(5));
        java.util.List<com.idata.profile.entity.raw.RawRecord> stuckRecords = rawRecordMapper.selectList(query);
        for (com.idata.profile.entity.raw.RawRecord record : stuckRecords) {
            log.info("[JobController] bulk pipeline retry, rawRecordId={}, pipelineTaskId={}, pipelineStatus={}",
                    record.getId(), record.getPipelineTaskId(), record.getPipelineStatus());
            pipelineExecutor.submit(record.getPipelineTaskId());
        }
        return Result.ok("已重新提交 " + stuckRecords.size() + " 条卡住的记录");
    }

    private Result<String> trigger(String jobName, Runnable job) {
        String jobId = jobName + "-" + System.currentTimeMillis();
        pipelineThreadPool.submit(() -> {
            try {
                log.info("[JobController] manual job started, jobId={}", jobId);
                job.run();
                log.info("[JobController] manual job completed, jobId={}", jobId);
            } catch (Exception e) {
                log.error("[JobController] manual job failed, jobId={}", jobId, e);
            }
        });
        return Result.ok("triggered: " + jobId);
    }
}

package com.idata.profile.controller;

import com.idata.profile.batch.asset.ImageEmbeddingJob;
import com.idata.profile.batch.asset.ImageEmbeddingService;
import com.idata.profile.batch.content.ContentPropagationBackfillJob;
import com.idata.profile.batch.profile.PersonProfileGenerationJob;
import com.idata.profile.batch.relation.AccountRelationBackfillJob;
import com.idata.profile.common.response.Result;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.pipeline.PipelineExecutor;
import com.idata.profile.pipeline.retry.PipelineRecoveryJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
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
    private final PipelineRecoveryJob pipelineRecoveryJob;
    private final ExecutorService pipelineThreadPool;
    private final RawRecordMapper rawRecordMapper;
    private final PipelineExecutor pipelineExecutor;

    @PostMapping("/image-embedding/trigger")
    public Result<String> triggerImageEmbedding() {
        return trigger("image-embedding", imageEmbeddingJob::run);
    }

    @PostMapping("/image-embedding/{assetId}/reindex")
    public Result<String> reindexMediaAsset(@PathVariable UUID assetId) {
        boolean accepted = imageEmbeddingService.processById(assetId);
        return accepted
                ? Result.ok("reindexed: " + assetId)
                : Result.fail("REINDEX_FAILED", "media asset reindex failed: " + assetId);
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
    public Result<String> retryPipelineTask(@PathVariable UUID rawRecordId) {
        RawRecord rawRecord = rawRecordMapper.selectById(rawRecordId);
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
        int count = pipelineRecoveryJob.recoverStuckTasks("manual");
        return Result.ok("已重新提交 " + count + " 条卡住的记录");
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

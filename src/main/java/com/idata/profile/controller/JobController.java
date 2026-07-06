package com.idata.profile.controller;

import com.idata.profile.batch.asset.ImageEmbeddingJob;
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
    private final AccountRelationBackfillJob accountRelationBackfillJob;
    private final ContentPropagationBackfillJob contentPropagationBackfillJob;
    private final PersonProfileGenerationJob personProfileGenerationJob;
    private final ExecutorService pipelineThreadPool;

    @PostMapping("/image-embedding/trigger")
    public Result<String> triggerImageEmbedding() {
        return trigger("image-embedding", imageEmbeddingJob::run);
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

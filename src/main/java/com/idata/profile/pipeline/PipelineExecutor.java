package com.idata.profile.pipeline;

import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import com.idata.profile.pipeline.retry.RetryHandler;
import com.idata.profile.pipeline.step.T1AnnotationStep;
import com.idata.profile.pipeline.step.T2ExtractionStep;
import com.idata.profile.pipeline.step.T4IndexingStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineExecutor {

    private final ExecutorService pipelineThreadPool;
    private final PipelineTaskMapper pipelineTaskMapper;
    private final T1AnnotationStep t1Step;
    private final T2ExtractionStep t2Step;
    private final T4IndexingStep t4Step;
    private final RetryHandler retryHandler;

    @Value("${pipeline.recovery.running-stuck-minutes:30}")
    private int runningStuckMinutes;

    @Value("${pipeline.recovery.queued-stuck-minutes:30}")
    private int queuedStuckMinutes;

    public void submitAfterCommit(UUID taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            submit(taskId);
                        }
                    });
        } else {
            submit(taskId);
        }
    }

    public void submit(UUID taskId) {
        if (taskId == null) {
            return;
        }
        int queued = pipelineTaskMapper.queueTask(
                taskId,
                Math.max(1, queuedStuckMinutes),
                Math.max(1, runningStuckMinutes));
        if (queued <= 0) {
            log.info("[PipelineExecutor] skip duplicate, completed, or active task, taskId={}", taskId);
            return;
        }
        try {
            pipelineThreadPool.submit(() -> execute(taskId));
        } catch (RejectedExecutionException e) {
            pipelineTaskMapper.releaseQueuedTask(taskId);
            throw e;
        }
    }

    private void execute(UUID taskId) {
        if (taskId == null) {
            return;
        }

        int claimed = pipelineTaskMapper.claimQueuedTask(taskId);
        if (claimed <= 0) {
            log.info("[PipelineExecutor] skip duplicate or already claimed task, taskId={}", taskId);
            return;
        }

        PipelineTask task = pipelineTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("[PipelineExecutor] claimed task not found, taskId={}", taskId);
            return;
        }
        String currentStep = "UNKNOWN";
        try {
            if (!"done".equals(task.getT1Status())) {
                currentStep = "T1";
                t1Step.run(task);
            }
            if (!"done".equals(task.getT2Status())) {
                currentStep = "T2";
                t2Step.run(task);
            }
            if (!"done".equals(task.getT4Status())) {
                currentStep = "T4";
                t4Step.run(task);
            }

            task.setStatus("DONE");
            pipelineTaskMapper.updateById(task);
        } catch (Exception e) {
            log.error("流水线执行失败, taskId={}", taskId, e);
            retryHandler.markFailedAndRetry(task, currentStep, e);
        }
    }
}

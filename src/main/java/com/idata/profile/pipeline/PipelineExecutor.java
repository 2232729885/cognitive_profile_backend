package com.idata.profile.pipeline;

import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import com.idata.profile.pipeline.retry.RetryHandler;
import com.idata.profile.pipeline.step.T1AnnotationStep;
import com.idata.profile.pipeline.step.T2ExtractionStep;
import com.idata.profile.pipeline.step.T4IndexingStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.ExecutorService;

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
        pipelineThreadPool.submit(() -> execute(taskId));
    }

    private void execute(UUID taskId) {
        PipelineTask task = pipelineTaskMapper.selectById(taskId);
        try {
            t1Step.run(task);
            t2Step.run(task);
            t4Step.run(task);

            task.setStatus("DONE");
            pipelineTaskMapper.updateById(task);
        } catch (Exception e) {
            log.error("流水线执行失败, taskId={}", taskId, e);
            retryHandler.markFailedAndRetry(task, "UNKNOWN", e);
        }
    }
}

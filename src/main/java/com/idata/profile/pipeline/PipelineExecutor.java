package com.idata.profile.pipeline;

import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import com.idata.profile.pipeline.retry.RetryHandler;
import com.idata.profile.pipeline.step.T1AnnotationStep;
import com.idata.profile.pipeline.step.T2ExtractionStep;
import com.idata.profile.pipeline.step.T3FusionStep;
import com.idata.profile.pipeline.step.T4IndexingStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * T1-T4流水线调度核心。Week1方案：写完pipeline_tasks后直接提交线程池异步执行。
 * 生产环境可改为写表后发kt4.pipeline.pending内部Kafka消息，
 * 由独立pipeline-consumer拉起处理，支持多实例横向扩展。
 *
 * 见 docs/01-CODEGEN-CONTEXT.md 3.1节 executePipeline 伪代码。
 * 线程池配置见 config.ThreadPoolConfig。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineExecutor {

    private final ExecutorService pipelineThreadPool;  // 见 config.ThreadPoolConfig 中的Bean定义
    private final PipelineTaskMapper pipelineTaskMapper;
    private final T1AnnotationStep t1Step;
    private final T2ExtractionStep t2Step;
    private final T3FusionStep t3Step;
    private final T4IndexingStep t4Step;
    private final RetryHandler retryHandler;

    /**
     * 在调用方事务提交后才真正提交执行，避免T1读到尚未COMMIT的media_contents数据。
     * 调用方（如ingestion.consumer.SocialContentConsumer）在@Transactional方法内调用本方法。
     */
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

            // T3和T4并行：T4只依赖body_text，不依赖T3融合结果
            CompletableFuture<Void> t3Future = CompletableFuture.runAsync(() -> t3Step.run(task), pipelineThreadPool);
            CompletableFuture<Void> t4Future = CompletableFuture.runAsync(() -> t4Step.run(task), pipelineThreadPool);
            CompletableFuture.allOf(t3Future, t4Future).join();

            task.setStatus("DONE");
            pipelineTaskMapper.updateById(task);
        } catch (Exception e) {
            log.error("流水线执行失败, taskId={}", taskId, e);
            retryHandler.markFailedAndRetry(task, "UNKNOWN", e);
        }
    }
}

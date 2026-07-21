package com.idata.profile.pipeline.retry;

import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.entity.task.PipelineTaskFailure;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.mapper.task.PipelineTaskFailureMapper;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import com.idata.profile.pipeline.PipelineExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Unified retry handling for pipeline step failures.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryHandler {

    private final PipelineTaskMapper pipelineTaskMapper;
    private final PipelineTaskFailureMapper pipelineTaskFailureMapper;
    private final RawRecordMapper rawRecordMapper;
    private final TaskScheduler taskScheduler;
    private final ObjectProvider<PipelineExecutor> pipelineExecutorProvider;

    public void markFailedAndRetry(PipelineTask task, String failedStep, Exception error) {
        short newRetryCount = (short) (task.getRetryCount() + 1);
        task.setRetryCount(newRetryCount);
        task.setErrorStep(failedStep);
        task.setErrorMessage(error.getMessage());

        switch (failedStep) {
            case "T1" -> task.setT1Status("failed");
            case "T2" -> task.setT2Status("failed");
            case "T3" -> task.setT3Status("failed");
            case "T4" -> task.setT4Status("failed");
            default -> log.warn("Unknown failed pipeline step: {}", failedStep);
        }

        boolean willRetry = newRetryCount < task.getMaxRetries();
        recordFailureAttempt(task, failedStep, error, newRetryCount, willRetry);

        if (newRetryCount >= task.getMaxRetries()) {
            task.setStatus("FAILED");
            pipelineTaskMapper.updateById(task);

            RawRecord rawRecord = rawRecordMapper.selectById(task.getRawRecordId());
            rawRecord.setPipelineStatus(PipelineStatus.FAILED.name());
            rawRecord.setErrorMessage(error.getMessage());
            rawRecordMapper.updateById(rawRecord);

            sendToDeadLetterQueue(task);
        } else {
            pipelineTaskMapper.updateById(task);
            long delaySeconds = (long) Math.pow(2, newRetryCount - 1);
            scheduleRetry(task.getId(), Duration.ofSeconds(delaySeconds));
        }
    }

    private void recordFailureAttempt(PipelineTask task, String failedStep, Exception error,
                                      short attemptNo, boolean willRetry) {
        try {
            PipelineTaskFailure failure = new PipelineTaskFailure();
            failure.setTaskId(task.getId());
            failure.setRawRecordId(task.getRawRecordId());
            failure.setContentId(task.getContentId());
            failure.setFailedStep(failedStep);
            failure.setAttemptNo(attemptNo);
            failure.setMaxRetries(task.getMaxRetries());
            failure.setWillRetry(willRetry);
            failure.setTaskStatus(willRetry ? task.getStatus() : "FAILED");
            failure.setStepStatus("failed");
            failure.setErrorClass(error.getClass().getName());
            failure.setErrorMessage(error.getMessage());

            Throwable root = rootCause(error);
            failure.setRootErrorClass(root.getClass().getName());
            failure.setRootErrorMessage(root.getMessage());
            pipelineTaskFailureMapper.insert(failure);
        } catch (Exception insertError) {
            log.warn("Failed to record pipeline task failure, taskId={}, failedStep={}",
                    task.getId(), failedStep, insertError);
        }
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void scheduleRetry(java.util.UUID taskId, Duration delay) {
        taskScheduler.schedule(
                () -> pipelineExecutorProvider.getObject().submit(taskId),
                java.time.Instant.now().plus(delay));
    }

    private void sendToDeadLetterQueue(PipelineTask task) {
        log.error("Pipeline task entered dead-letter queue, taskId={}, rawRecordId={}",
                task.getId(), task.getRawRecordId());
        // TODO: Add a real dead-letter queue or manual review table if needed.
    }
}

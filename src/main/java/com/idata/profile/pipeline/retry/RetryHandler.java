package com.idata.profile.pipeline.retry;

import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 失败重试统一处理。见 docs/数据处理流程.md 第九章。
 * 指数退避：1s/2s/4s（重试次数从0开始，delay = 2^retryCount秒）。
 * 超过max_retries(默认3)后推死信队列，不影响其他记录继续处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryHandler {

    private final PipelineTaskMapper pipelineTaskMapper;
    private final RawRecordMapper rawRecordMapper;
    private final TaskScheduler taskScheduler;  // 见 config.ThreadPoolConfig

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
            default -> log.warn("未知失败步骤: {}", failedStep);
        }

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

    private void scheduleRetry(java.util.UUID taskId, Duration delay) {
        // TODO: 重新提交到 pipeline.PipelineExecutor，从task.errorStep对应的步骤重跑
        // taskScheduler.schedule(() -> pipelineExecutor.submit(taskId),
        //         java.time.Instant.now().plus(delay));
    }

    private void sendToDeadLetterQueue(PipelineTask task) {
        log.error("任务进入死信队列, taskId={}, rawRecordId={}", task.getId(), task.getRawRecordId());
        // TODO: 实际死信队列实现（如写专门的表，或发Kafka到死信topic，供人工核查）
    }
}

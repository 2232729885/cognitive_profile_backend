package com.idata.profile.pipeline.retry;

import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.pipeline.PipelineExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineRecoveryJob {

    private final RawRecordMapper rawRecordMapper;
    private final PipelineExecutor pipelineExecutor;

    @Value("${pipeline.recovery.enabled:true}")
    private boolean enabled;

    @Value("${pipeline.recovery.stuck-minutes:5}")
    private int stuckMinutes;

    @Value("${pipeline.recovery.running-stuck-minutes:30}")
    private int runningStuckMinutes;

    @Value("${pipeline.recovery.batch-limit:200}")
    private int batchLimit;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recoverStuckTasks("startup");
    }

    @Scheduled(fixedDelayString = "${pipeline.recovery.fixed-delay-ms:300000}",
            initialDelayString = "${pipeline.recovery.initial-delay-ms:60000}")
    public void scheduledRecovery() {
        recoverStuckTasks("scheduled");
    }

    public int recoverStuckTasks(String trigger) {
        if (!enabled) {
            log.debug("[PipelineRecoveryJob] skipped because recovery is disabled, trigger={}", trigger);
            return 0;
        }
        int safeStuckMinutes = Math.max(1, stuckMinutes);
        int safeRunningStuckMinutes = Math.max(safeStuckMinutes, runningStuckMinutes);
        int safeBatchLimit = Math.max(1, batchLimit);
        List<RawRecord> stuckRecords = rawRecordMapper.selectStuckPipelineRecords(
                safeStuckMinutes, safeRunningStuckMinutes, safeBatchLimit);
        for (RawRecord record : stuckRecords) {
            log.info("[PipelineRecoveryJob] resubmit stuck pipeline task, trigger={}, rawRecordId={}, taskId={}, pipelineStatus={}",
                    trigger, record.getId(), record.getPipelineTaskId(), record.getPipelineStatus());
            pipelineExecutor.submit(record.getPipelineTaskId());
        }
        if (!stuckRecords.isEmpty()) {
            log.info("[PipelineRecoveryJob] recovered stuck pipeline tasks, trigger={}, count={}",
                    trigger, stuckRecords.size());
        }
        return stuckRecords.size();
    }
}

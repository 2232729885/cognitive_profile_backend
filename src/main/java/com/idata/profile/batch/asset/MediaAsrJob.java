package com.idata.profile.batch.asset;

import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.mapper.content.MediaAssetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class MediaAsrJob {

    private final MediaAssetMapper mediaAssetMapper;
    private final ImageEmbeddingService imageEmbeddingService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${media.asr-job.enabled:true}")
    private boolean enabled;

    @Value("${media.asr-job.batch-limit:1}")
    private int batchLimit;

    @Value("${media.asr-job.max-attempts:3}")
    private int maxAttempts;

    @Value("${media.asr-job.retry-delay-seconds:60}")
    private int retryDelaySeconds;

    @Value("${media.asr-job.stale-running-seconds:900}")
    private int staleRunningSeconds;

    @Scheduled(fixedDelayString = "${media.asr-job.fixed-delay-ms:5000}")
    public void run() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            int recovered = mediaAssetMapper.recoverStaleAsr(
                    Math.max(60, staleRunningSeconds), Math.max(1, maxAttempts));
            if (recovered > 0) {
                log.warn("[MediaAsrJob] recovered {} stale RUNNING tasks", recovered);
            }

            List<MediaAsset> pending = mediaAssetMapper.selectPendingAsr(
                    Math.max(1, batchLimit),
                    Math.max(1, maxAttempts),
                    Math.max(0, retryDelaySeconds));
            for (MediaAsset asset : pending) {
                imageEmbeddingService.processAsrById(asset.getId(), Math.max(1, maxAttempts));
            }
        } catch (Exception e) {
            log.error("[MediaAsrJob] execution failed", e);
        } finally {
            running.set(false);
        }
    }
}

package com.idata.profile.batch.asset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageEmbeddingJob {

    private static final int BATCH_LIMIT = 200;

    private final ImageEmbeddingService imageEmbeddingService;

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void run() {
        int successCount = imageEmbeddingService.processPending(BATCH_LIMIT);
        if (successCount > 0) {
            log.info("Image embedding scheduled job indexed {} assets", successCount);
        }
    }
}

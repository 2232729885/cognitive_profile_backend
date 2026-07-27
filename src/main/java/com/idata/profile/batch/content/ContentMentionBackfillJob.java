package com.idata.profile.batch.content;

import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.service.ContentMentionSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContentMentionBackfillJob {

    private static final int BATCH_LIMIT = 500;
    private static final int ORPHAN_ALERT_THRESHOLD_DAYS = 7;
    private static final int ORPHAN_COUNT_ALERT_THRESHOLD = 1000;

    private final MediaContentMapper mediaContentMapper;
    private final ContentMentionSyncService contentMentionSyncService;

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void run() {
        List<MediaContent> pending = mediaContentMapper.selectPendingMentionSync(BATCH_LIMIT);
        for (MediaContent content : pending) {
            try {
                if (contentMentionSyncService.syncMentionedAccountRelations(content, "mentions_field_backfill")) {
                    mediaContentMapper.markMentionsSyncedToNeo4j(content.getId());
                }
            } catch (Exception e) {
                log.warn("Failed to backfill content mentioned account relations, contentId={}",
                        content.getId(), e);
            }
        }
        if (!pending.isEmpty()) {
            log.info("Content mention backfill scanned: {}", pending.size());
        }
        checkOrphanRelations();
    }

    private void checkOrphanRelations() {
        long orphanCount = mediaContentMapper.countPendingMentionSyncOlderThan(ORPHAN_ALERT_THRESHOLD_DAYS);
        if (orphanCount > ORPHAN_COUNT_ALERT_THRESHOLD) {
            log.warn("Pending content mention relation count exceeds threshold: total={}, olderThanDays={}. "
                            + "Mentioned social accounts may never be ingested.",
                    orphanCount, ORPHAN_ALERT_THRESHOLD_DAYS);
        }
    }
}

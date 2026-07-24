package com.idata.profile.batch.content;

import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.content.MediaContentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContentPropagationBackfillJob {

    private static final int BATCH_LIMIT = 500;
    private static final int ORPHAN_ALERT_THRESHOLD_DAYS = 7;
    private static final int ORPHAN_COUNT_ALERT_THRESHOLD = 1000;

    private final MediaContentMapper mediaContentMapper;
    private final Neo4jGraphService neo4jGraphService;

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void run() {
        List<MediaContent> pending = mediaContentMapper.selectPendingPropagationSync(BATCH_LIMIT);
        for (MediaContent content : pending) {
            try {
                if (syncPropagationRelations(content)) {
                    mediaContentMapper.markPropagationSyncedToNeo4j(content.getId());
                }
            } catch (Exception e) {
                log.warn("Failed to backfill content propagation relations, contentId={}",
                        content.getId(), e);
            }
        }
        if (!pending.isEmpty()) {
            log.info("Content propagation backfill scanned: {}", pending.size());
        }
        checkOrphanRelations();
    }

    private boolean syncPropagationRelations(MediaContent content) {
        mergeMediaContentNode(content);

        boolean allSynced = true;
        allSynced &= writePropagationRelation(content, content.getParentContentId(), "REPLY_TO");
        allSynced &= writePropagationRelation(content, content.getRepostOfContentId(), "REPOSTS");
        if (!sameContentId(content.getRootContentId(), content.getPlatformContentId())) {
            allSynced &= writePropagationRelation(content, content.getRootContentId(), "REPLY_TO");
        }
        return allSynced;
    }

    private boolean writePropagationRelation(MediaContent content, String targetPlatformContentId, String relationType) {
        if (!hasText(targetPlatformContentId)) {
            return true;
        }
        if (!hasText(content.getPlatform())) {
            log.debug("Skip propagation backfill because platform is empty, contentId={}, relationType={}, "
                            + "targetPlatformContentId={}",
                    content.getId(), relationType, targetPlatformContentId);
            return false;
        }

        try {
            MediaContent target = mediaContentMapper.selectByPlatformAndContentId(
                    content.getPlatform(), targetPlatformContentId.trim());
            if (target == null) {
                log.debug("Skip propagation backfill because target content was not found, contentId={}, "
                                + "relationType={}, platform={}, targetPlatformContentId={}",
                        content.getId(), relationType, content.getPlatform(), targetPlatformContentId);
                return false;
            }
            mergeMediaContentNode(target);
            neo4jGraphService.mergeRelation(
                    "MediaContent", content.getId().toString(),
                    "MediaContent", target.getId().toString(),
                    relationType,
                    Map.of("source", "backend_structural",
                            "extraction_method", "propagation_chain_backfill"));
            return true;
        } catch (Exception e) {
            log.warn("Failed to backfill propagation relation to Neo4j, contentId={}, relationType={}, "
                            + "targetPlatformContentId={}",
                    content.getId(), relationType, targetPlatformContentId, e);
            return false;
        }
    }

    private void mergeMediaContentNode(MediaContent content) {
        Map<String, Object> contentProps = new HashMap<>();
        putIfHasText(contentProps, "title", content.getTitle());
        putIfHasText(contentProps, "bodyText", content.getBodyText());
        putIfHasText(contentProps, "translatedTitle", content.getTranslatedTitle());
        putIfHasText(contentProps, "translatedBodyText", content.getTranslatedBodyText());
        putIfHasText(contentProps, "translatedSummary", content.getTranslatedSummary());
        putIfHasText(contentProps, "platform", content.getPlatform());
        putIfHasText(contentProps, "contentType", content.getContentType());
        putIfHasText(contentProps, "language", content.getLanguage());
        putIfHasText(contentProps, "platformContentId", content.getPlatformContentId());
        putIfNotNull(contentProps, "publishedAt",
                content.getPublishedAt() != null ? content.getPublishedAt().toString() : null);
        putIfHasText(contentProps, "url", content.getUrl());
        putIfHasText(contentProps, "t1Annotation", content.getT1Annotation());
        contentProps.put("source", "backend_structural");
        neo4jGraphService.mergeNode("MediaContent", content.getId().toString(), contentProps);
    }

    private void checkOrphanRelations() {
        long orphanCount = mediaContentMapper.countPendingPropagationOlderThan(ORPHAN_ALERT_THRESHOLD_DAYS);
        if (orphanCount > ORPHAN_COUNT_ALERT_THRESHOLD) {
            log.warn("Pending content propagation relation count exceeds threshold: total={}, olderThanDays={}. "
                            + "Target media contents may never be ingested.",
                    orphanCount, ORPHAN_ALERT_THRESHOLD_DAYS);
        }
    }

    private boolean sameContentId(String left, String right) {
        return hasText(left) && hasText(right) && left.trim().equals(right.trim());
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value);
        }
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

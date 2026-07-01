package com.idata.profile.batch.relation;

import com.idata.profile.common.constant.RelationType;
import com.idata.profile.entity.account.AccountRelation;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.account.AccountRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountRelationBackfillJob {

    private static final int BATCH_LIMIT = 500;
    private static final int ORPHAN_ALERT_THRESHOLD_DAYS = 7;
    private static final int ORPHAN_COUNT_ALERT_THRESHOLD = 1000;

    private final AccountRelationMapper accountRelationMapper;
    private final Neo4jGraphService neo4jGraphService;

    @Scheduled(fixedDelay = 1 * 60 * 1000)
    public void run() {
        int fromBackfilled = accountRelationMapper.backfillFromAccountId();
        int toBackfilled = accountRelationMapper.backfillToAccountId();
        log.info("Account relation UUID backfilled: from={}, to={}", fromBackfilled, toBackfilled);

        syncReadyRelationsToNeo4j();
        checkOrphanRelations();
    }

    private void syncReadyRelationsToNeo4j() {
        List<AccountRelation> ready = accountRelationMapper.selectReadyForNeo4jSync(BATCH_LIMIT);
        for (AccountRelation relation : ready) {
            try {
                RelationType type = RelationType.fromCode(relation.getRelationType());
                neo4jGraphService.mergeRelationWithNodes(
                        "SocialAccount", relation.getFromAccountId().toString(),
                        socialAccountProperties(relation.getPlatform(), relation.getSourcePlatformUserId()),
                        "SocialAccount", relation.getToAccountId().toString(),
                        socialAccountProperties(relation.getPlatform(), relation.getTargetPlatformUserId()),
                        type.getNeo4jRelationLabel(),
                        relationProperties(relation));
                accountRelationMapper.markSyncedToNeo4j(relation.getId());
            } catch (Exception e) {
                log.error("Failed to write account relation to Neo4j, relationId={}", relation.getId(), e);
            }
        }
        if (!ready.isEmpty()) {
            log.info("Account relation Neo4j sync completed: {}", ready.size());
        }
    }

    private Map<String, Object> socialAccountProperties(String platform, String platformUserId) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("platform", platform);
        properties.put("platformUserId", platformUserId);
        properties.put("source", "account_relation_backfill");
        return properties;
    }

    private Map<String, Object> relationProperties(AccountRelation relation) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("relationId", relation.getId().toString());
        if (relation.getRawRecordId() != null) {
            properties.put("rawRecordId", relation.getRawRecordId().toString());
        }
        properties.put("platform", relation.getPlatform());
        if (relation.getObservedAt() != null) {
            properties.put("observedAt", relation.getObservedAt().toString());
        }
        properties.put("confidence", toNeo4jNumber(relation.getConfidence()));
        properties.put("source", relation.getSource() != null ? relation.getSource() : "");
        properties.put("detectedBy", "kt3");
        return properties;
    }

    private double toNeo4jNumber(BigDecimal value) {
        return value == null ? 1.0D : value.doubleValue();
    }

    private void checkOrphanRelations() {
        List<Map<String, Object>> orphans = accountRelationMapper.countOrphansOlderThan(ORPHAN_ALERT_THRESHOLD_DAYS);
        long totalOrphans = orphans.stream()
                .mapToLong(row -> ((Number) row.get("orphanCount")).longValue())
                .sum();
        if (totalOrphans > ORPHAN_COUNT_ALERT_THRESHOLD) {
            log.warn("Orphan account relation count exceeds threshold: total={}, details={}", totalOrphans, orphans);
        }
    }
}

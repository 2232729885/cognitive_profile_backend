package com.idata.profile.batch.relation;

import com.idata.profile.common.constant.RelationType;
import com.idata.profile.entity.account.AccountRelation;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.account.AccountRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * account_relations 的UUID回填批处理。每5分钟执行一次，与入库主流程完全解耦。
 * 见 docs/课题四_数据处理流程_v2.md 第四章 Step5。
 *
 * 三件事：
 * 1. 按platform+platformUserId匹配，回填fromAccountId/toAccountId
 * 2. 把两端UUID均就位、尚未同步的记录写入Neo4j，标记syncedToNeo4j=true
 * 3. 监控长期未回填的孤儿关系数量
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountRelationBackfillJob {

    private static final int BATCH_LIMIT = 500;
    private static final int ORPHAN_ALERT_THRESHOLD_DAYS = 7;
    private static final int ORPHAN_COUNT_ALERT_THRESHOLD = 1000;  // TODO: 按实际数据量调整阈值

    private final AccountRelationMapper accountRelationMapper;
    private final Neo4jGraphService neo4jGraphService;

    @Scheduled(fixedDelay = 5 * 60 * 1000)  // 每5分钟
    public void run() {
        int fromBackfilled = accountRelationMapper.backfillFromAccountId();
        int toBackfilled = accountRelationMapper.backfillToAccountId();
        log.info("账号关系UUID回填: from={}, to={}", fromBackfilled, toBackfilled);

        syncReadyRelationsToNeo4j();
        checkOrphanRelations();
    }

    private void syncReadyRelationsToNeo4j() {
        List<AccountRelation> ready = accountRelationMapper.selectReadyForNeo4jSync(BATCH_LIMIT);
        for (AccountRelation relation : ready) {
            try {
                RelationType type = RelationType.fromCode(relation.getRelationType());
                neo4jGraphService.mergeRelation(
                        "SocialAccount", relation.getFromAccountId().toString(),
                        "SocialAccount", relation.getToAccountId().toString(),
                        type.getNeo4jRelationLabel(),
                        Map.of(
                                "observedAt", relation.getObservedAt().toString(),
                                "confidence", relation.getConfidence(),
                                "source", relation.getSource() != null ? relation.getSource() : ""
                        ));
                accountRelationMapper.markSyncedToNeo4j(relation.getId());
            } catch (Exception e) {
                log.error("写Neo4j失败, relationId={}", relation.getId(), e);
                // 不中断，继续下一条，下一轮会重试（syncedToNeo4j未置true）
            }
        }
        if (!ready.isEmpty()) {
            log.info("本轮同步Neo4j完成: {} 条", ready.size());
        }
    }

    private void checkOrphanRelations() {
        List<Map<String, Object>> orphans = accountRelationMapper.countOrphansOlderThan(ORPHAN_ALERT_THRESHOLD_DAYS);
        long totalOrphans = orphans.stream()
                .mapToLong(row -> ((Number) row.get("orphanCount")).longValue())
                .sum();
        if (totalOrphans > ORPHAN_COUNT_ALERT_THRESHOLD) {
            log.warn("孤儿关系数量超过阈值: total={}, 详情={}", totalOrphans, orphans);
            // TODO: 接入实际告警渠道（如发邮件、写notifications表）
        }
    }
}

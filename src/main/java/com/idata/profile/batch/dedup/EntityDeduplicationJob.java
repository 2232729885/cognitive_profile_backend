package com.idata.profile.batch.dedup;

import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.graph.EventMapper;
import com.idata.profile.mapper.graph.NarrativeMapper;
import com.idata.profile.mapper.graph.OrganizationMapper;
import com.idata.profile.mapper.graph.PersonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EntityDeduplicationJob {

    // 融合算法待算法组提供后实现。
    // 当前占位：定时扫描 dedup_status='pending' 的实体，
    // 按 canonical_name 相似度分组，提交给融合服务判断是否为同一实体。

    private static final int BATCH_LIMIT = 200;

    private final PersonMapper personMapper;
    private final OrganizationMapper organizationMapper;
    private final EventMapper eventMapper;
    private final NarrativeMapper narrativeMapper;
    @SuppressWarnings("unused")
    private final Neo4jGraphService neo4jGraphService;

    /**
     * 实体去重融合定时任务（占位）。
     * 融合策略：
     * 1. 扫描 dedup_status='pending' 的实体记录
     * 2. 按 canonical_name 分组，同组内视为候选重复实体
     * 3. 调用融合服务（TODO：算法组提供接口）判断哪些是真正的同一实体
     * 4. 融合操作：
     *    - PG：把被合并记录的 merge_history 指向 survivorId，dedup_status 置为 'deduplicated'
     *          survivor 记录 dedup_status 置为 'canonical'，content_count 累加
     *    - Neo4j：把被合并节点的所有关系重新指向 survivor 节点，删除被合并节点
     * 5. 孤儿监控：超过7天仍为 pending 的实体打 warn 日志
     *
     * 当前实现：仅打印统计日志，不执行实际融合（等待算法组接口）
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000)
    public void run() {
        log.info("[EntityDeduplicationJob] 占位运行，融合算法待算法组提供后实现，batchLimit={}", BATCH_LIMIT);
        logPendingStats();
    }

    private void logPendingStats() {
        long persons = personMapper.countByDedupStatus("pending");
        long organizations = organizationMapper.countByDedupStatus("pending");
        long events = eventMapper.countByDedupStatus("pending");
        long narratives = narrativeMapper.countByDedupStatus("pending");
        log.info("[EntityDeduplicationJob] pending stats: persons={}, organizations={}, events={}, narratives={}",
                persons, organizations, events, narratives);
    }

    /**
     * Neo4j 节点合并操作（占位）。
     * 真实实现需要：
     * 1. 查出 sourceId 节点的所有关系
     * 2. 在 targetId 节点上重建相同的关系
     * 3. 删除 sourceId 节点
     * 注意：需要确认 Neo4j 是否安装了 APOC 库，有 APOC 可以用
     * apoc.refactor.mergeNodes() 简化此操作
     */
    @SuppressWarnings("unused")
    private void mergeNeo4jNodes(String sourceId, String targetId, String label) {
        // TODO: 算法组提供融合策略后实现
        log.debug("[EntityDeduplicationJob] Neo4j节点合并占位: {} -> {}, label={}",
                sourceId, targetId, label);
    }
}

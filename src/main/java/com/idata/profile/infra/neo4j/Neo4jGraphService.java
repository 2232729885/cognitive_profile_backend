package com.idata.profile.infra.neo4j;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Neo4j图谱基础操作封装。基于 spring-boot-starter-data-neo4j（Bolt协议直连）。
 *
 * 不使用 Spring Data Neo4j 的 Repository/@Node 注解模式，原因：
 * 本系统的图谱节点/关系是T3根据T2抽取结果动态生成的（不是预定义的固定实体类），
 * 用 Neo4jClient 手写Cypher更灵活，能直接对应 docs/课题四_数据库设计_v3.md 第五章
 * 列出的节点类型和关系类型定义。
 *
 * 实体归一 vs 字段冲突覆盖的区别（重要，见调用方 T3FuseResponse 注释）：
 * - 归一：MERGE多个节点为一个，旧UUID进PG侧的mergeHistory，这里只处理存活节点的属性写入
 * - 冲突覆盖：MERGE...SET，新值直接覆盖旧值，不保留旧值历史
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Neo4jGraphService {

    private final Neo4jClient neo4jClient;

    /**
     * 写入/更新节点，新值直接覆盖旧值（对应T3的冲突覆盖策略）。
     *
     * @param label      节点标签：Person|Organization|Event|Narrative|SocialAccount
     * @param id         节点id，与PG侧UUID一致
     * @param properties 完整属性集
     */
    public void mergeNode(String label, String id, Map<String, Object> properties) {
        String cypher = String.format(
            "MERGE (n:%s {id: $id}) SET n += $properties", label);
        neo4jClient.query(cypher)
                .bind(id).to("id")
                .bind(properties).to("properties")
                .run();
    }

    /**
     * 写入/更新两节点间的关系。
     * 区分事实关系（如FOLLOWS，来自account_relation，confidence=1.0）
     * 和推断关系（如COORDINATES_WITH，T6推断，properties中带detected_by='T6'）。
     */
    public void mergeRelation(String fromLabel, String fromId,
                                String toLabel, String toId,
                                String relationType, Map<String, Object> properties) {
        String cypher = String.format(
            "MATCH (a:%s {id: $fromId}), (b:%s {id: $toId}) " +
            "MERGE (a)-[r:%s]->(b) SET r += $properties",
            fromLabel, toLabel, relationType);
        neo4jClient.query(cypher)
                .bind(fromId).to("fromId")
                .bind(toId).to("toId")
                .bind(properties).to("properties")
                .run();
    }

    public List<UUID> findSocialAccountIdsByNarrative(UUID narrativeId) {
        String cypher = """
                MATCH (a:SocialAccount)-[:PARTICIPATES_IN_NARRATIVE]->(n:Narrative {id: $narrativeId})
                RETURN a.id AS accountId
                """;
        return neo4jClient.query(cypher)
                .bind(narrativeId.toString()).to("narrativeId")
                .fetch()
                .all()
                .stream()
                .map(row -> row.get("accountId"))
                .map(this::toUuid)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid UUID returned from Neo4j: {}", value);
            return null;
        }
    }

    // TODO: 补充 docs/课题四_数据库设计_v3.md 5.3节列出的常用查询：
    //   - 2跳关系图查询（前端知识图谱可视化）
    //   - 叙事关联账号查询（T6识别入口）
    //   - 协同群组最短路径查询
}

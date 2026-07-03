// 清理历史遗留的错误 Neo4j 重复节点。
//
// 背景：
// 早期 EntityDeduplicationJob 曾使用 PG survivor UUID 在 Neo4j 中创建 source='entity_dedup_job'
// 的 Person/Organization/Event/Narrative 节点，和 T2ExtractionStep 使用的 stableUuid 节点不是同一套 id。
// 当前已统一为 stableUuid 节点 id，这些旧节点需要删除。
//
// 执行前建议先确认：
// MATCH (n) WHERE n.source = 'entity_dedup_job' RETURN n;
//
// 执行完之后，手动重跑一次 EntityDeduplicationJob，让融合任务用新逻辑更新 stableUuid 节点属性。
MATCH (n) WHERE n.source = 'entity_dedup_job' DETACH DELETE n;

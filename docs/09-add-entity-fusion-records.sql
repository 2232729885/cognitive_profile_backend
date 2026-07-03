-- 实体融合记录表，每次 EntityDeduplicationJob 执行融合操作都写一条记录
CREATE TABLE IF NOT EXISTS entity_fusion_records (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type          VARCHAR(20)  NOT NULL,    -- person/organization/event/narrative
    survivor_id          UUID         NOT NULL,    -- 融合后保留的主记录 UUID
    survivor_name        VARCHAR(512) NOT NULL,    -- survivor 的 canonical_name（冗余，方便展示）
    merged_ids           UUID[]       NOT NULL,    -- 被合并掉的旧记录 UUID 列表
    merged_names         TEXT[]       NOT NULL,    -- 被合并记录的 canonical_name 列表（冗余，方便展示）
    merged_count         INTEGER      NOT NULL,    -- 本次融合了几条记录（= merged_ids 数组长度）
    fusion_method        VARCHAR(50)  NOT NULL,    -- 融合方式：exact_name / manual
    content_count_before INTEGER,                  -- 融合前 survivor 的 content_count
    content_count_after  INTEGER,                  -- 融合后累加后的 content_count
    neo4j_merged         BOOLEAN      NOT NULL DEFAULT FALSE,  -- Neo4j 节点是否已完成合并
    job_run_id           UUID,                     -- 同一批次任务的批次 ID，方便按批次查询
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_fusion_records_entity_type ON entity_fusion_records(entity_type);
CREATE INDEX IF NOT EXISTS idx_fusion_records_survivor    ON entity_fusion_records(survivor_id);
CREATE INDEX IF NOT EXISTS idx_fusion_records_created_at  ON entity_fusion_records(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_fusion_records_job_run     ON entity_fusion_records(job_run_id);

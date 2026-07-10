ALTER TABLE events
  ADD COLUMN IF NOT EXISTS event_heat_level VARCHAR(20),
  ADD COLUMN IF NOT EXISTS event_heat_score NUMERIC(6,2),
  ADD COLUMN IF NOT EXISTS event_heat_confidence NUMERIC(4,3),
  ADD COLUMN IF NOT EXISTS event_related_content_count INTEGER,
  ADD COLUMN IF NOT EXISTS event_heat_computed_at TIMESTAMPTZ;

COMMENT ON COLUMN events.event_heat_level IS '事件热度等级：low|medium|high|explosive|unclear';
COMMENT ON COLUMN events.event_related_content_count IS '计算热度时，图谱里实际关联到的内容数量（不是events.content_count，那个字段含义是去重合并计数，语义不同）';
COMMENT ON COLUMN events.event_heat_computed_at IS '本次热度计算完成时间，NULL表示还没算过';

-- T1 标注扩展字段（对齐 T1_标注属性.md 10个维度）
ALTER TABLE media_contents
  ADD COLUMN IF NOT EXISTS keywords        VARCHAR[],
  ADD COLUMN IF NOT EXISTS summary         TEXT,
  ADD COLUMN IF NOT EXISTS ideology_label  VARCHAR(50),
  ADD COLUMN IF NOT EXISTS ideology_intensity VARCHAR(20),
  ADD COLUMN IF NOT EXISTS overall_stance  VARCHAR(20),
  ADD COLUMN IF NOT EXISTS event_heat      VARCHAR(20),
  ADD COLUMN IF NOT EXISTS account_type_hint VARCHAR(50),
  ADD COLUMN IF NOT EXISTS risk_level      VARCHAR(20),
  ADD COLUMN IF NOT EXISTS risk_types      VARCHAR[],
  ADD COLUMN IF NOT EXISTS risk_evidence   TEXT,
  ADD COLUMN IF NOT EXISTS language_style_tags VARCHAR[],
  ADD COLUMN IF NOT EXISTS sentiment_primary_emotion VARCHAR(30),
  ADD COLUMN IF NOT EXISTS bend_tactics    JSONB;

COMMENT ON COLUMN media_contents.keywords IS 'T1抽取关键词列表';
COMMENT ON COLUMN media_contents.summary IS 'T1生成一句话摘要';
COMMENT ON COLUMN media_contents.ideology_label IS '意识形态标签';
COMMENT ON COLUMN media_contents.overall_stance IS '内容级核心立场';
COMMENT ON COLUMN media_contents.event_heat IS '事件热度感知';
COMMENT ON COLUMN media_contents.account_type_hint IS '账户类别提示';
COMMENT ON COLUMN media_contents.risk_level IS '综合风险等级';
COMMENT ON COLUMN media_contents.risk_types IS '风险类型数组';
COMMENT ON COLUMN media_contents.bend_tactics IS 'BEND叙事操纵手法(JSONB)';

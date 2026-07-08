-- T1 v0.5 规约新增字段
ALTER TABLE media_contents
  -- 完整v0.5标注结果全量落库
  ADD COLUMN IF NOT EXISTS t1_annotation_v05 JSONB,
  -- 维度2扩展：内容级立场强度（v1.1没有这个字段）
  ADD COLUMN IF NOT EXISTS core_stance_strength VARCHAR(20),
  -- 维度5扩展：主要情绪类型数组（v1.1是单值sentiment_primary_emotion，v0.5是数组，新增独立列，旧列不再写入）
  ADD COLUMN IF NOT EXISTS emotion_labels VARCHAR[],
  -- 维度8：内容目的主分类（v1.1时期只同步到Neo4j，没有落PG，这次补上）
  ADD COLUMN IF NOT EXISTS content_purpose_primary VARCHAR(30),
  -- 维度4新增：民众态度（v1.1完全没有这个维度）
  ADD COLUMN IF NOT EXISTS public_attitude_group VARCHAR(30),
  ADD COLUMN IF NOT EXISTS public_attitude_label VARCHAR(30),
  ADD COLUMN IF NOT EXISTS public_attitude_intensity VARCHAR(20),
  -- 维度15：事件类型（v1.1时期只同步到Neo4j，没有落PG，这次补上）
  ADD COLUMN IF NOT EXISTS event_type_label VARCHAR(40),
  -- T1整体置信度（之前一直没有独立PG列）
  ADD COLUMN IF NOT EXISTS t1_overall_confidence NUMERIC(4,3),
  -- quality_control扩展
  ADD COLUMN IF NOT EXISTS t1_review_reasons VARCHAR[],
  ADD COLUMN IF NOT EXISTS t1_failed_modules VARCHAR[];

COMMENT ON COLUMN media_contents.t1_annotation_v05 IS 'T1 v0.5完整标注结果JSON，全字段可追溯';
COMMENT ON COLUMN media_contents.core_stance_strength IS 'v0.5维度2：内容级立场强度 weak|medium|strong|unclear';
COMMENT ON COLUMN media_contents.emotion_labels IS 'v0.5维度5：主要情绪类型数组';
COMMENT ON COLUMN media_contents.content_purpose_primary IS 'v0.5维度8：内容目的主分类';
COMMENT ON COLUMN media_contents.public_attitude_group IS 'v0.5维度4：民众/公众群体类型';
COMMENT ON COLUMN media_contents.public_attitude_label IS 'v0.5维度4：民众态度标签';
COMMENT ON COLUMN media_contents.public_attitude_intensity IS 'v0.5维度4：民众态度强度';
COMMENT ON COLUMN media_contents.event_type_label IS 'v0.5维度15：事件类型';
COMMENT ON COLUMN media_contents.t1_overall_confidence IS 'T1本次标注整体置信度';
COMMENT ON COLUMN media_contents.t1_review_reasons IS 'v0.5 quality_control.review_reasons';
COMMENT ON COLUMN media_contents.t1_failed_modules IS 'v0.5 quality_control.failed_modules';

-- 以下列在v0.5里语义复用（不新增列，只更新注释，说明现在存的是v0.5的哪个字段）：
COMMENT ON COLUMN media_contents.ideology_label IS 'v0.5维度1：ideology.ideology_label（18类枚举，跟v1.1不同）';
COMMENT ON COLUMN media_contents.overall_stance IS 'v0.5维度2：core_stance.stance_label（原v1.1同名字段复用）';
COMMENT ON COLUMN media_contents.event_heat IS 'v0.5维度6：event_heat.heat_level';
COMMENT ON COLUMN media_contents.event_heat_score IS 'v0.5维度6：event_heat.heat_score';
COMMENT ON COLUMN media_contents.account_type_hint IS 'v0.5维度11：account_type.primary_account_category（内容级弱信号兜底判断，权威判断见social_accounts.account_type）';
COMMENT ON COLUMN media_contents.risk_level IS 'v0.5维度9：risk_level.risk_label';
COMMENT ON COLUMN media_contents.risk_types IS 'v0.5维度9：risk_level.risk_types';
COMMENT ON COLUMN media_contents.language_style_tags IS 'v0.5维度7：language_style.style_labels';
COMMENT ON COLUMN media_contents.sentiment_label IS 'v0.5维度5：opinion_emotion.sentiment_polarity';
COMMENT ON COLUMN media_contents.keywords IS 'v0.5维度13：keywords[].keyword_text（只存文本列表，完整结构见t1_annotation_v05）';
COMMENT ON COLUMN media_contents.summary IS 'v0.5维度14：summary.summary_text';
COMMENT ON COLUMN media_contents.entities_hint IS 'v0.5维度12：entities_hint（完整JSON数组）';
COMMENT ON COLUMN media_contents.topic_category IS 'v0.5维度10：topic_tags.primary_domain';
COMMENT ON COLUMN media_contents.topic_subcategory IS 'v0.5维度10：topic_tags.subtopic_tags[0]';
COMMENT ON COLUMN media_contents.aigc_type IS 'v0.5：aigc_detection.overall_aigc_label';
COMMENT ON COLUMN media_contents.aigc_score IS 'v0.5：aigc_detection.overall_aigc_score';
COMMENT ON COLUMN media_contents.need_human_review IS 'v0.5：quality_control.need_human_review';

-- 以下列v0.5没有对应概念了，代码不再写入，保留列不删（可能有历史数据）：
-- ideology_intensity, sentiment_score, sentiment_primary_emotion, risk_evidence, bend_tactics, t1_model_version

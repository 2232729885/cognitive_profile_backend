-- ============================================================
-- 回填结果验证查询（触发AccountRelationBackfillJob之后执行）
-- 配合 docs/05-test-social-accounts.sql 使用
-- ============================================================

-- 验证1：场景A的3条关系，from_account_id/to_account_id应该都已回填（非NULL）
SELECT
    ar.source_platform_user_id,
    ar.target_platform_user_id,
    ar.relation_type,
    ar.from_account_id,
    ar.to_account_id,
    ar.synced_to_neo4j,
    sa_from.handle  AS from_handle,
    sa_to.handle    AS to_handle
FROM account_relations ar
LEFT JOIN social_accounts sa_from ON sa_from.id = ar.from_account_id
LEFT JOIN social_accounts sa_to   ON sa_to.id   = ar.to_account_id
WHERE ar.source_platform_user_id IN ('test_user_001', 'test_channel_001')
  AND ar.target_platform_user_id != 'nonexistent_account_999'
ORDER BY ar.observed_at DESC;

-- 期望结果：3行，from_account_id和to_account_id都不是NULL，
-- synced_to_neo4j应该是true（如果回填批处理的Neo4j同步步骤也跑过）

-- 验证2：场景B（孤儿关系）应该仍然是NULL，这是预期行为，不是bug
SELECT source_platform_user_id, target_platform_user_id, from_account_id, to_account_id
FROM account_relations
WHERE target_platform_user_id = 'nonexistent_account_999';

-- 期望结果：from_account_id应该已回填（因为source是真实账号），
-- to_account_id应该仍是NULL（因为target账号根本不存在）

-- 验证3：调用孤儿关系监控方法应该能查到这条（超过7天阈值的逻辑见AccountRelationMapper.countOrphansOlderThan）
-- 注意：测试数据里这条observed_at是NOW()-8天，刚好能触发7天阈值检测
SELECT relation_type, COUNT(*) AS orphan_count
FROM account_relations
WHERE (from_account_id IS NULL OR to_account_id IS NULL)
  AND created_at < NOW() - INTERVAL '7 days'
GROUP BY relation_type;

-- 注意：这条查询用的是created_at（数据插入时间）而不是observed_at，
-- 如果你是今天插入的测试数据，这条会查不到结果（created_at是NOW()，不会早于7天前），
-- 这是正常的——AccountRelationMapper.countOrphansOlderThan的真实判断条件本来就是created_at，
-- 如果想现在就测出孤儿告警效果，需要手动UPDATE这条记录的created_at往前调，
-- 或者直接修改阈值为更短的时间单独测试，不要因为这条查询返回空就认为代码有bug

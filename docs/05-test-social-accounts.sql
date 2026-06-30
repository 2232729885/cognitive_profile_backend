-- ============================================================
-- account_relation 回填验证用测试数据
-- 用途：往 social_accounts 插入几个账号，再往 account_relations
-- 插入引用这些账号的关系记录（fromAccountId/toAccountId 故意留空），
-- 用于验证 AccountRelationBackfillJob 的回填逻辑。
--
-- 执行顺序：先跑这个文件，再触发 AccountRelationBackfillJob（手动或等定时任务），
-- 然后跑底部的验证查询确认回填结果。
-- ============================================================

-- ── Step1：插入3个测试账号，覆盖不同platform和account_entity_type ──

INSERT INTO social_accounts (
    id, platform, platform_user_id, account_entity_type, platform_native_type,
    handle, display_name, bio, verified, verified_type,
    followers_count, following_count, post_count,
    account_type, latest_snapshot_at, first_seen_at
) VALUES
(
    gen_random_uuid(), 'x', 'test_user_001', 'user', NULL,
    '@test_alice', 'Alice Test', '测试账号-Alice', true, 'blue',
    48200, 320, 1205,
    'kol', NOW(), NOW()
),
(
    gen_random_uuid(), 'youtube', 'test_channel_001', 'channel', 'youtube_channel',
    'UCTestChannel001', 'Test News Channel', '测试频道-新闻类', true, 'org',
    125000, 0, 312,
    'media', NOW(), NOW()
),
(
    gen_random_uuid(), 'telegram', 'test_group_001', 'group', 'telegram_group',
    NULL, 'Test Discussion Group', '测试群组-讨论组', false, 'none',
    0, 0, 0,
    'normal', NOW(), NOW())
ON CONFLICT (platform, platform_user_id) DO NOTHING;

-- ── Step2：插入account_relations测试记录（fromAccountId/toAccountId故意留空）──
-- 场景A：source和target都能在social_accounts里找到匹配，回填应该成功
-- 场景B：target_platform_user_id故意写一个不存在的账号，验证孤儿关系监控逻辑

INSERT INTO account_relations (
    id, raw_record_id, source_platform_user_id, target_platform_user_id, platform,
    relation_type, observed_at, source, confidence,
    from_account_id, to_account_id, synced_to_neo4j
) VALUES
-- 场景A1：test_user_001 关注 test_channel_001（应能成功回填）
(
    gen_random_uuid(), NULL, 'test_user_001', 'test_channel_001', 'x',
    'following', NOW(), 'following_list', 1.0,
    NULL, NULL, false
),
-- 场景A2：test_user_001 是 test_group_001 的成员（应能成功回填，跨平台匹配测试）
(
    gen_random_uuid(), NULL, 'test_user_001', 'test_group_001', 'telegram',
    'member_of', NOW(), 'group_member_list', 1.0,
    NULL, NULL, false
),
-- 场景A3：test_channel_001 订阅 test_group_001（验证admin_of类型映射）
(
    gen_random_uuid(), NULL, 'test_channel_001', 'test_group_001', 'telegram',
    'admin_of', NOW(), 'channel_profile', 1.0,
    NULL, NULL, false
),
-- 场景B：target_platform_user_id 写一个不存在的账号ID，验证孤儿关系检测
(
    gen_random_uuid(), NULL, 'test_user_001', 'nonexistent_account_999', 'x',
    'following', NOW() - INTERVAL '8 days', 'following_list', 1.0,
    NULL, NULL, false
);

-- ============================================================
-- 验证查询（插入完成后先跑这个，确认数据写入正确）
-- ============================================================

-- 查看刚插入的3个账号
SELECT id, platform, platform_user_id, handle, account_entity_type
FROM social_accounts
WHERE platform_user_id IN ('test_user_001', 'test_channel_001', 'test_group_001')
ORDER BY platform;

-- 查看刚插入的4条关系（回填前，from/to_account_id应该全是NULL）
SELECT id, source_platform_user_id, target_platform_user_id, relation_type,
       from_account_id, to_account_id, synced_to_neo4j
FROM account_relations
WHERE source_platform_user_id = 'test_user_001'
   OR source_platform_user_id = 'test_channel_001'
ORDER BY observed_at DESC;

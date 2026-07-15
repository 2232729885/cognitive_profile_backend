-- ============================================================
-- 课题四全息画像系统 · PostgreSQL 建表脚本
-- 版本：v2.0 · 2026-06-25
-- 执行方式：psql -h 172.16.40.232 -U postgres -d postgres -f init_db.sql
-- 包含：21张业务表 + 77个索引 + 13个触发器 + 字段注释 + 初始化数据
-- ============================================================

-- 启用必要扩展
CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- 提供 gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "pg_trgm";    -- 提供模糊匹配索引支持

-- ============================================================
-- 版本变更记录
-- v2.2 → v2.3（2026-06-25）：
--   - account_relations 新增 synced_to_neo4j 字段（BOOLEAN DEFAULT FALSE）
--     标记关系是否已写入Neo4j，避免回填批处理重复扫描和重复写图谱
--   - 新增部分索引 idx_ar_pending_sync，加速"待同步记录"查询
-- v2.1 → v2.2（2026-06-25）：
--   - 删除 portrait_audit_logs 表（T3冲突不再推审核队列，直接覆盖）
--   - T3融合策略：字段冲突时新值直接覆盖旧值，不暂存不审核
--   - 实体归一改为 EntityDeduplicationJob 后台定时处理，T2 写入时允许重复实体
-- v2.0 → v2.1（2026-06-25）：
--   - person_profiles 去掉补全相关字段（completion_status/completeness_score/
--     completion_inventory/pending_candidates/conflicts/t5_task_id）
--   - 删除 portrait_review_items 表（补全流程移除）
--   - 画像生成改为定时任务批量执行，不做差量补全
-- ============================================================
-- 公共触发器函数：自动维护 updated_at 字段
-- ============================================================
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION update_updated_at() IS '通用触发器函数，在每次 UPDATE 时自动将 updated_at 设置为当前时间';

-- ============================================================
-- L0：原始采集层
-- 每条从 Kafka 或批量文件进来的消息必须先落这张表，一字不改全量保存。
-- T1/T2/T3/T4 的完整输出也存在这张表的 JSONB 字段里，实现单行可追溯。
-- ============================================================

CREATE TABLE IF NOT EXISTS raw_records (
                                           id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    schema_version      VARCHAR(32) NOT NULL DEFAULT 'kt3_to_kt4_v1',
    record_type         VARCHAR(32) NOT NULL,
    source_record_id    VARCHAR(256) NOT NULL,
    crawl_task_id       VARCHAR(256),
    collected_at        TIMESTAMPTZ,
    source_url          TEXT,
    payload_hash        VARCHAR(64) NOT NULL,
    source_type         VARCHAR(32) NOT NULL DEFAULT 'kafka',
    source_topic        VARCHAR(128),
    platform            VARCHAR(64),
    language            VARCHAR(16),
    content_type        VARCHAR(32),
    raw_payload         JSONB       NOT NULL,
    raw_published_at    TIMESTAMPTZ,
    normalized_at       TIMESTAMPTZ,
    pipeline_status     VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    pipeline_task_id    UUID,
    error_message       TEXT,
    retry_count         SMALLINT    NOT NULL DEFAULT 0,
    t1_output           JSONB,
    t2_output           JSONB,
    t3_output           JSONB,
    t4_output           JSONB,
    batch_import_id     UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  raw_records                  IS 'L0原始采集层：Kafka或文件导入的原始消息，每条必须先落这里，一字不改，支持任意步骤失败后重跑';
COMMENT ON COLUMN raw_records.id               IS '主键，UUID';
COMMENT ON COLUMN raw_records.schema_version   IS '课题三数据格式版本号，接入适配器据此选择字段映射逻辑，如 kt3_to_kt4_v1';
COMMENT ON COLUMN raw_records.record_type      IS '数据类型：social_content | social_account | account_relation | media_asset | news_article | collection_task';
COMMENT ON COLUMN raw_records.source_record_id IS '课题三全局唯一原始记录ID（raw_record_id），去重主键';
COMMENT ON COLUMN raw_records.crawl_task_id    IS '关联的课题三采集任务ID，用于追溯数据来源批次';
COMMENT ON COLUMN raw_records.collected_at     IS '课题三采集时间，区别于内容发布时间 raw_published_at';
COMMENT ON COLUMN raw_records.source_url       IS '原始内容/账号/新闻的URL';
COMMENT ON COLUMN raw_records.payload_hash     IS 'SHA256(raw_payload)，内容级去重，防止相同内容以不同ID重复入库';
COMMENT ON COLUMN raw_records.source_type      IS '数据来源渠道：kafka | file_upload';
COMMENT ON COLUMN raw_records.source_topic     IS 'Kafka topic名称，如 kt3.social_content';
COMMENT ON COLUMN raw_records.platform         IS '平台标识：x | youtube | telegram | tiktok | news 等';
COMMENT ON COLUMN raw_records.language         IS '内容语言：zh | en | ja | ko | vi | tl | ar | fa 等';
COMMENT ON COLUMN raw_records.content_type     IS '内容形态：post | comment | reply | quote | repost | article，仅 social_content/news_article 有值';
COMMENT ON COLUMN raw_records.raw_payload      IS '完整原始消息体（含外层格式），一字不改全量存储';
COMMENT ON COLUMN raw_records.raw_published_at IS '内容在平台的原始发布时间（data.published_at）';
COMMENT ON COLUMN raw_records.normalized_at    IS '完成L1标准化映射的时间';
COMMENT ON COLUMN raw_records.pipeline_status  IS '处理状态机：RECEIVED→NORMALIZED→T1_DONE→T2_DONE→T3_DONE→T4_INDEXED→FAILED';
COMMENT ON COLUMN raw_records.pipeline_task_id IS '关联 pipeline_tasks.id，Step4创建任务后回填';
COMMENT ON COLUMN raw_records.error_message    IS '流水线失败时的错误详情';
COMMENT ON COLUMN raw_records.retry_count      IS '当前已重试次数，超过 max_retries(3) 后状态改为 FAILED';
COMMENT ON COLUMN raw_records.t1_output        IS 'T1自动标注Agent的完整响应体（JSONB），用于处理过程追溯';
COMMENT ON COLUMN raw_records.t2_output        IS 'T2信息抽取Agent的完整响应体（JSONB），含实体/关系/叙事抽取结果';
COMMENT ON COLUMN raw_records.t3_output        IS 'T3信息融合Agent的完整响应体（JSONB），含本次图谱节点和关系写入结果';
COMMENT ON COLUMN raw_records.t4_output        IS 'T4索引构建结果（JSONB），含Milvus向量ID、ES文档ID等';
COMMENT ON COLUMN raw_records.batch_import_id  IS '批量文件导入任务ID，非Kafka来源时填，关联 batch_import_tasks.id';

CREATE UNIQUE INDEX IF NOT EXISTS uq_raw_source_id    ON raw_records(source_record_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_raw_payload_hash ON raw_records(payload_hash);
CREATE INDEX IF NOT EXISTS idx_raw_record_type ON raw_records(record_type);
CREATE INDEX IF NOT EXISTS idx_raw_status      ON raw_records(pipeline_status);
CREATE INDEX IF NOT EXISTS idx_raw_platform    ON raw_records(platform);
CREATE INDEX IF NOT EXISTS idx_raw_crawl_task  ON raw_records(crawl_task_id) WHERE crawl_task_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_raw_created     ON raw_records(created_at DESC);

CREATE TRIGGER trg_raw_records_updated_at
    BEFORE UPDATE ON raw_records
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================================
-- L1：证据标准化层
-- ============================================================

-- ------------------------------------------------------------
-- 社交内容和新闻报道
-- 适用 record_type：social_content / news_article
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS media_contents (
                                              id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_record_id           UUID        NOT NULL REFERENCES raw_records(id),
    platform                VARCHAR(64) NOT NULL,
    content_type            VARCHAR(32) NOT NULL,
    platform_content_id     VARCHAR(256),
    author_platform_user_id VARCHAR(256),
    author_account_id       UUID,
    title                   TEXT,
    body_text               TEXT,
    language                VARCHAR(16),
    published_at            TIMESTAMPTZ,
    url                     TEXT,
    parent_content_id       VARCHAR(256),
    root_content_id         VARCHAR(256),
    repost_of_content_id    VARCHAR(256),
    quoted_content_id       VARCHAR(256),
    hashtags                TEXT[],
    mentions                TEXT[],
    external_urls           TEXT[],
    like_count              BIGINT      NOT NULL DEFAULT 0,
    comment_count           BIGINT      NOT NULL DEFAULT 0,
    share_count             BIGINT      NOT NULL DEFAULT 0,
    repost_count            BIGINT      NOT NULL DEFAULT 0,
    quote_count             BIGINT      NOT NULL DEFAULT 0,
    view_count              BIGINT      NOT NULL DEFAULT 0,
    reaction_count          BIGINT      NOT NULL DEFAULT 0,
    media_asset_ids         UUID[],
    news_source_name        VARCHAR(256),
    news_domain             VARCHAR(128),
    news_author             VARCHAR(256),
    news_section            VARCHAR(64),
    news_tags               TEXT[],
    t1_annotated_at         TIMESTAMPTZ,
    human_review_status     VARCHAR(32),
    propagation_synced_to_neo4j BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    t1_annotation           JSONB,
    source_media_asset_ids  VARCHAR[]
    );

COMMENT ON TABLE  media_contents                      IS 'L1标准化层：社交内容和新闻报道。一行代表平台上一条可定位的内容对象，T1标注结果直接写在这张表里';
COMMENT ON COLUMN media_contents.id                   IS '主键，UUID';
COMMENT ON COLUMN media_contents.raw_record_id        IS '关联 raw_records.id，溯源到原始消息';
COMMENT ON COLUMN media_contents.platform             IS '平台标识：x | youtube | telegram | tiktok | news 等';
COMMENT ON COLUMN media_contents.content_type         IS '内容形态：post | channel_post | comment | reply | quote | repost | forward | forum_thread | article';
COMMENT ON COLUMN media_contents.platform_content_id  IS '平台原始内容ID，news_article 时填 URL 的 MD5';
COMMENT ON COLUMN media_contents.author_platform_user_id IS '作者的平台原始用户ID，来自课题三，入库时直接存字符串';
COMMENT ON COLUMN media_contents.author_account_id    IS '关联 social_accounts.id，T2处理后回填，入库时可为 null';
COMMENT ON COLUMN media_contents.title                IS '标题，新闻和视频使用';
COMMENT ON COLUMN media_contents.body_text            IS '正文内容，T1/T2/T4 的主要输入';
COMMENT ON COLUMN media_contents.language             IS '内容语言';
COMMENT ON COLUMN media_contents.published_at         IS '内容在平台的发布时间';
COMMENT ON COLUMN media_contents.url                  IS '内容原始URL';
COMMENT ON COLUMN media_contents.parent_content_id    IS '直接上级内容的平台原始ID（评论/回复的父帖），存字符串不要求FK';
COMMENT ON COLUMN media_contents.root_content_id      IS '传播链根内容的平台原始ID，T2从此字段重建传播链写Neo4j';
COMMENT ON COLUMN media_contents.repost_of_content_id IS '被纯转发内容的平台原始ID，T2从此字段推导转发关系';
COMMENT ON COLUMN media_contents.quoted_content_id    IS '被引用内容的平台原始ID，T2从此字段推导引用关系';
COMMENT ON COLUMN media_contents.hashtags             IS '话题标签数组，T2实体抽取的重要输入';
COMMENT ON COLUMN media_contents.mentions             IS '提及的账号数组（平台原始ID或handle），T2从此推导提及关系';
COMMENT ON COLUMN media_contents.external_urls        IS '正文中的外部链接数组';
COMMENT ON COLUMN media_contents.like_count           IS '点赞数，来自课题三 metrics 字段';
COMMENT ON COLUMN media_contents.comment_count        IS '评论数';
COMMENT ON COLUMN media_contents.share_count          IS '分享数';
COMMENT ON COLUMN media_contents.repost_count         IS '转发数';
COMMENT ON COLUMN media_contents.quote_count          IS '引用数';
COMMENT ON COLUMN media_contents.view_count           IS '浏览量';
COMMENT ON COLUMN media_contents.reaction_count       IS '反应数（Facebook等平台的 reaction）';
COMMENT ON COLUMN media_contents.media_asset_ids      IS '关联的 media_assets.id 数组';
COMMENT ON COLUMN media_contents.news_source_name     IS '新闻来源名称（如 BBC、路透社），仅 news_article 使用';
COMMENT ON COLUMN media_contents.news_domain          IS '新闻站点域名（如 bbc.com），仅 news_article 使用';
COMMENT ON COLUMN media_contents.news_author          IS '新闻作者，仅 news_article 使用';
COMMENT ON COLUMN media_contents.news_section         IS '新闻栏目（如 world/politics），仅 news_article 使用';
COMMENT ON COLUMN media_contents.news_tags            IS '新闻标签数组，仅 news_article 使用';
COMMENT ON COLUMN media_contents.t1_annotated_at      IS 'T1标注完成时间';
COMMENT ON COLUMN media_contents.human_review_status  IS '人工复核状态：pending | confirmed | modified | rejected';
COMMENT ON COLUMN media_contents.propagation_synced_to_neo4j IS '传播链关系是否已同步到Neo4j。对端内容尚未入库时保持false，由ContentPropagationBackfillJob后续回填';
COMMENT ON COLUMN media_contents.t1_annotation        IS 'T1完整标注结果JSON（当前schema_version见字段内容本身，不体现在列名里）';
COMMENT ON COLUMN media_contents.source_media_asset_ids IS '社交内容消息里 data.media_asset_ids 原始字符串ID列表，用于反查关联 media_assets.source_asset_id';

CREATE INDEX IF NOT EXISTS idx_mc_platform     ON media_contents(platform);
CREATE INDEX IF NOT EXISTS idx_mc_published    ON media_contents(published_at DESC);
CREATE INDEX IF NOT EXISTS idx_mc_author_acc   ON media_contents(author_account_id) WHERE author_account_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_mc_platform_id  ON media_contents(platform, platform_content_id);
CREATE INDEX IF NOT EXISTS idx_mc_content_type ON media_contents(content_type);
CREATE INDEX IF NOT EXISTS idx_mc_language     ON media_contents(language);
CREATE INDEX IF NOT EXISTS idx_mc_raw          ON media_contents(raw_record_id);
CREATE INDEX IF NOT EXISTS idx_mc_hashtags     ON media_contents USING GIN(hashtags);
CREATE INDEX IF NOT EXISTS idx_mc_pending_propagation_sync
    ON media_contents(propagation_synced_to_neo4j)
    WHERE propagation_synced_to_neo4j = FALSE;

CREATE TRIGGER trg_media_contents_updated_at
    BEFORE UPDATE ON media_contents
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ------------------------------------------------------------
-- 账号/频道/群组（最新状态快照）
-- 适用 record_type：social_account
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS social_accounts (
                                               id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    platform                VARCHAR(64) NOT NULL,
    platform_user_id        VARCHAR(256) NOT NULL,
    account_entity_type     VARCHAR(32),
    platform_native_type    VARCHAR(64),
    handle                  VARCHAR(256),
    display_name            VARCHAR(512),
    bio                     TEXT,
    avatar_url              TEXT,
    profile_url             TEXT,
    self_declared_location  TEXT,
    verified                BOOLEAN     NOT NULL DEFAULT FALSE,
    verified_type           VARCHAR(32),
    is_suspended            BOOLEAN     NOT NULL DEFAULT FALSE,
    account_created_at      TIMESTAMPTZ,
    followers_count         BIGINT      NOT NULL DEFAULT 0,
    following_count         BIGINT      NOT NULL DEFAULT 0,
    subscriber_count        BIGINT      NOT NULL DEFAULT 0,
    member_count            BIGINT      NOT NULL DEFAULT 0,
    post_count              BIGINT      NOT NULL DEFAULT 0,
    view_count              BIGINT      NOT NULL DEFAULT 0,
    account_type            VARCHAR(32),
    account_type_confidence NUMERIC(4,3),
    entity_person_id        UUID,
    entity_org_id           UUID,
    latest_snapshot_at      TIMESTAMPTZ,
    first_seen_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_active_at          TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    identity_resolved_at    TIMESTAMPTZ
    );

COMMENT ON TABLE  social_accounts                       IS 'L1标准化层：平台主体最新状态。主表只存最新快照，历史变化存 social_account_snapshots。按 (platform, platform_user_id) UPSERT';
COMMENT ON COLUMN social_accounts.id                    IS '主键，UUID，与 Neo4j SocialAccount 节点 ID 保持一致';
COMMENT ON COLUMN social_accounts.platform              IS '平台标识：x | youtube | telegram | tiktok | facebook 等';
COMMENT ON COLUMN social_accounts.platform_user_id      IS '平台原始用户/频道/群组ID，与 platform 联合唯一';
COMMENT ON COLUMN social_accounts.account_entity_type   IS '账号主体类型（课题三v2.0新增）：user | channel | page | group | community | forum_board | news_source | unknown';
COMMENT ON COLUMN social_accounts.platform_native_type  IS '平台原生类型（课题三v2.0新增），如 youtube_channel | telegram_group | facebook_page';
COMMENT ON COLUMN social_accounts.handle                IS '用户名/@handle';
COMMENT ON COLUMN social_accounts.display_name          IS '显示名称';
COMMENT ON COLUMN social_accounts.bio                   IS '个人简介/频道描述';
COMMENT ON COLUMN social_accounts.avatar_url            IS '头像URL';
COMMENT ON COLUMN social_accounts.profile_url           IS '主页URL';
COMMENT ON COLUMN social_accounts.self_declared_location IS '用户自填位置（来自课题三，不做解析和标准化）';
COMMENT ON COLUMN social_accounts.verified              IS '是否已认证';
COMMENT ON COLUMN social_accounts.verified_type         IS '认证类型：none | blue | org | government | media | unknown';
COMMENT ON COLUMN social_accounts.is_suspended          IS '是否已被平台封禁';
COMMENT ON COLUMN social_accounts.account_created_at    IS '账号在平台上的注册时间';
COMMENT ON COLUMN social_accounts.followers_count       IS '粉丝数（最新快照值）';
COMMENT ON COLUMN social_accounts.following_count       IS '关注数';
COMMENT ON COLUMN social_accounts.subscriber_count      IS '订阅数（YouTube等平台）';
COMMENT ON COLUMN social_accounts.member_count          IS '成员数（群组类账号）';
COMMENT ON COLUMN social_accounts.post_count            IS '发帖总数';
COMMENT ON COLUMN social_accounts.view_count            IS '总播放/浏览量';
COMMENT ON COLUMN social_accounts.account_type          IS 'T1标注的账号类型：official | media | kol | normal | bot | cyborg';
COMMENT ON COLUMN social_accounts.account_type_confidence IS 'T1标注账号类型的置信度 0-1';
COMMENT ON COLUMN social_accounts.entity_person_id      IS '关联 persons.id，T2/T3处理后回填，表示该账号对应哪个人物实体';
COMMENT ON COLUMN social_accounts.entity_org_id         IS '关联 organizations.id，T2/T3处理后回填，表示该账号对应哪个组织实体';
COMMENT ON COLUMN social_accounts.latest_snapshot_at    IS '最新快照的采集时间（来自课题三 collected_at）';
COMMENT ON COLUMN social_accounts.first_seen_at         IS '首次被系统采集到的时间';
COMMENT ON COLUMN social_accounts.last_active_at        IS '最近一次有活动内容的时间';
COMMENT ON COLUMN social_accounts.identity_resolved_at  IS '身份识别（匹配/新建Person或Organization、或判定跳过）完成时间，NULL表示还未处理';

CREATE UNIQUE INDEX IF NOT EXISTS uq_sa_platform_user ON social_accounts(platform, platform_user_id);
CREATE INDEX IF NOT EXISTS idx_sa_entity_person ON social_accounts(entity_person_id) WHERE entity_person_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sa_account_type  ON social_accounts(account_entity_type);
CREATE INDEX IF NOT EXISTS idx_sa_handle        ON social_accounts(handle);

CREATE TRIGGER trg_social_accounts_updated_at
    BEFORE UPDATE ON social_accounts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ------------------------------------------------------------
-- 账号历史快照（追加写入，不覆盖）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS social_account_snapshots (
                                                        id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          UUID        NOT NULL REFERENCES social_accounts(id),
    raw_record_id       UUID        REFERENCES raw_records(id),
    snapshot_at         TIMESTAMPTZ NOT NULL,
    display_name        VARCHAR(512),
    bio                 TEXT,
    verified            BOOLEAN,
    verified_type       VARCHAR(32),
    self_declared_location TEXT,
    is_suspended        BOOLEAN,
    followers_count     BIGINT,
    following_count     BIGINT,
    subscriber_count    BIGINT,
    member_count        BIGINT,
    post_count          BIGINT,
    view_count          BIGINT,
    snapshot_payload    JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  social_account_snapshots                IS '账号历史快照表。主表只存最新状态，每次课题三推来快照就追加一条到这里，不覆盖历史。用于分析账号在特定时间段的特征（如叙事高峰期粉丝数是否异常增长）';
COMMENT ON COLUMN social_account_snapshots.id             IS '主键，UUID';
COMMENT ON COLUMN social_account_snapshots.account_id     IS '关联 social_accounts.id';
COMMENT ON COLUMN social_account_snapshots.raw_record_id  IS '关联 raw_records.id，溯源到原始快照消息';
COMMENT ON COLUMN social_account_snapshots.snapshot_at    IS '本次快照的采集时间（课题三 collected_at）';
COMMENT ON COLUMN social_account_snapshots.display_name   IS '快照时的显示名称';
COMMENT ON COLUMN social_account_snapshots.bio            IS '快照时的简介';
COMMENT ON COLUMN social_account_snapshots.verified       IS '快照时的认证状态';
COMMENT ON COLUMN social_account_snapshots.verified_type  IS '快照时的认证类型';
COMMENT ON COLUMN social_account_snapshots.is_suspended   IS '快照时是否已封禁';
COMMENT ON COLUMN social_account_snapshots.followers_count IS '快照时的粉丝数';
COMMENT ON COLUMN social_account_snapshots.post_count     IS '快照时的发帖总数';
COMMENT ON COLUMN social_account_snapshots.snapshot_payload IS '完整快照原始字段（JSONB备用）';

CREATE INDEX IF NOT EXISTS idx_sas_account ON social_account_snapshots(account_id, snapshot_at DESC);
CREATE INDEX IF NOT EXISTS idx_sas_raw     ON social_account_snapshots(raw_record_id);

-- ------------------------------------------------------------
-- 账号关系（课题三直接交付的事实关系，v2.0新增类型）
-- 适用 record_type：account_relation
-- 注意：这是课题三告诉我们的事实，置信度1.0，区别于T6推断的协同关系
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_relations (
                                                 id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_record_id           UUID        REFERENCES raw_records(id),
    source_platform_user_id VARCHAR(256) NOT NULL,
    target_platform_user_id VARCHAR(256) NOT NULL,
    platform                VARCHAR(64),
    relation_type           VARCHAR(32) NOT NULL,
    observed_at             TIMESTAMPTZ NOT NULL,
    occurred_at             TIMESTAMPTZ,
    source                  VARCHAR(64),
    confidence              NUMERIC(4,3) NOT NULL DEFAULT 1.0,
    from_account_id         UUID        REFERENCES social_accounts(id),
    to_account_id           UUID        REFERENCES social_accounts(id),
    synced_to_neo4j         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  account_relations                         IS 'L1标准化层：账号间事实关系（关注/订阅/成员/管理员等）。课题三v2.0新增类型，替代原interaction中的账号关系部分。入库时from/to_account_id为null，独立的回填批处理任务负责匹配UUID并写入Neo4j（不依赖T3）';
COMMENT ON COLUMN account_relations.id                      IS '主键，UUID';
COMMENT ON COLUMN account_relations.raw_record_id           IS '关联 raw_records.id，溯源到原始消息';
COMMENT ON COLUMN account_relations.source_platform_user_id IS '关系源账号的平台原始ID（关注者/成员/管理员一侧），入库时直接存字符串不要求FK';
COMMENT ON COLUMN account_relations.target_platform_user_id IS '关系目标账号的平台原始ID（被关注/频道/群组一侧）';
COMMENT ON COLUMN account_relations.platform                IS '平台标识';
COMMENT ON COLUMN account_relations.relation_type           IS '关系类型：following | subscribe | member_of | owner_of | creator_of | admin_of | moderator_of';
COMMENT ON COLUMN account_relations.observed_at             IS '课题三采集到这条关系的时间';
COMMENT ON COLUMN account_relations.occurred_at             IS '关系真实发生时间（如平台显示的关注时间），可选';
COMMENT ON COLUMN account_relations.source                  IS '关系数据来源：following_list | follower_list | subscriber_list | group_member_list | channel_profile';
COMMENT ON COLUMN account_relations.confidence              IS '置信度，课题三直接交付的事实关系默认1.0，区别于T6推断关系';
COMMENT ON COLUMN account_relations.from_account_id         IS '关联 social_accounts.id（源账号），入库时为null，回填批处理任务按platform+platform_user_id匹配后写入';
COMMENT ON COLUMN account_relations.to_account_id           IS '关联 social_accounts.id（目标账号），入库时为null，回填批处理任务按platform+platform_user_id匹配后写入';
COMMENT ON COLUMN account_relations.synced_to_neo4j         IS '是否已同步写入Neo4j图谱。仅当from_account_id和to_account_id均回填完成后才会置true，避免回填批处理重复扫描和重复写图谱';

CREATE INDEX IF NOT EXISTS idx_ar_source_platform ON account_relations(source_platform_user_id);
CREATE INDEX IF NOT EXISTS idx_ar_target_platform ON account_relations(target_platform_user_id);
CREATE INDEX IF NOT EXISTS idx_ar_from_account    ON account_relations(from_account_id) WHERE from_account_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ar_to_account      ON account_relations(to_account_id) WHERE to_account_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ar_relation_type   ON account_relations(relation_type);
CREATE INDEX IF NOT EXISTS idx_ar_observed        ON account_relations(observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_ar_raw             ON account_relations(raw_record_id);
CREATE INDEX IF NOT EXISTS idx_ar_pending_sync    ON account_relations(synced_to_neo4j)
    WHERE synced_to_neo4j = FALSE AND from_account_id IS NOT NULL AND to_account_id IS NOT NULL;

-- ------------------------------------------------------------
-- 多媒体附件
-- 适用 record_type：media_asset（也可从 social_content.data.media[] 拆分）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS media_assets (
                                            id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_record_id       UUID        REFERENCES raw_records(id),
    content_id          UUID        REFERENCES media_contents(id),
    source_asset_id     VARCHAR(256),
    asset_type          VARCHAR(32) NOT NULL,
    source_url          TEXT,
    storage_uri         TEXT,
    mime_type           VARCHAR(128),
    sha256              VARCHAR(64),
    file_size_bytes     BIGINT,
    width               INTEGER,
    height              INTEGER,
    duration_seconds    INTEGER,
    thumbnail_uri       TEXT,
    ocr_text            TEXT,
    asr_text            TEXT,
    aigc_score          NUMERIC(4,3),
    object_annotations  TEXT,
    scene_label         VARCHAR(100),
    t1_annotated        BOOLEAN NOT NULL DEFAULT FALSE,
    minio_bucket        VARCHAR(128),
    minio_key           TEXT,
    embedding_id        VARCHAR(128),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  media_assets               IS 'L1标准化层：多媒体附件（图片/视频/音频/缩略图）。sha256去重，同一文件被多条内容引用时只存一份';
COMMENT ON COLUMN media_assets.id            IS '主键，UUID';
COMMENT ON COLUMN media_assets.raw_record_id IS '关联 raw_records.id，溯源到原始消息（独立投递时有值）';
COMMENT ON COLUMN media_assets.content_id    IS '关联 media_contents.id，所属内容';
COMMENT ON COLUMN media_assets.source_asset_id IS '课题三原始asset_id（data.asset_id），跨系统关联用';
COMMENT ON COLUMN media_assets.asset_type    IS '媒体类型：image | video | audio | thumbnail';
COMMENT ON COLUMN media_assets.source_url    IS '原始媒体URL（data.source_url）';
COMMENT ON COLUMN media_assets.storage_uri   IS '课题三对象存储路径（data.storage_uri），格式如 s3://kt3-media/...';
COMMENT ON COLUMN media_assets.mime_type     IS 'MIME类型，如 image/jpeg | video/mp4';
COMMENT ON COLUMN media_assets.sha256        IS '媒体文件内容哈希（data.sha256），用于去重和完整性校验';
COMMENT ON COLUMN media_assets.file_size_bytes IS '文件大小（字节）';
COMMENT ON COLUMN media_assets.width         IS '图片/视频宽度（像素）';
COMMENT ON COLUMN media_assets.height        IS '图片/视频高度（像素）';
COMMENT ON COLUMN media_assets.duration_seconds IS '视频/音频时长（秒）';
COMMENT ON COLUMN media_assets.thumbnail_uri IS '缩略图路径（data.thumbnail_uri）';
COMMENT ON COLUMN media_assets.ocr_text      IS '图片/视频帧OCR文本（课题三可选提供）';
COMMENT ON COLUMN media_assets.asr_text      IS '音视频语音转写文本（课题三可选提供，YouTube视频分析重要输入）';
COMMENT ON COLUMN media_assets.aigc_score    IS 'T1对图片/视频的AIGC可疑度评分 0.0-1.0';
COMMENT ON COLUMN media_assets.object_annotations IS 'T1图像标注：物体检测结果JSON';
COMMENT ON COLUMN media_assets.scene_label   IS 'T1图像标注：场景分类标签，如 outdoor/indoor/military/protest';
COMMENT ON COLUMN media_assets.t1_annotated  IS 'T1图像标注是否已完成';
COMMENT ON COLUMN media_assets.minio_bucket  IS '我方MinIO存储桶（本地镜像后填入）';
COMMENT ON COLUMN media_assets.minio_key     IS '我方MinIO对象存储路径';
COMMENT ON COLUMN media_assets.embedding_id  IS '对应Milvus image_embeddings中的向量ID（后端EmbeddingService写入）';

CREATE UNIQUE INDEX IF NOT EXISTS uq_ma_source_asset ON media_assets(source_asset_id) WHERE source_asset_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_ma_sha256    ON media_assets(sha256) WHERE sha256 IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ma_content ON media_assets(content_id) WHERE content_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ma_raw     ON media_assets(raw_record_id) WHERE raw_record_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ma_type    ON media_assets(asset_type);

ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS
    object_annotations TEXT;
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS
    scene_label VARCHAR(100);
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS
    t1_annotated BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_media_assets_t1_annotated
    ON media_assets(t1_annotated) WHERE t1_annotated = FALSE;

-- ------------------------------------------------------------
-- 采集任务元数据
-- 适用 record_type：collection_task
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS collection_tasks (
                                                id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_record_id       UUID        REFERENCES raw_records(id),
    crawl_task_id       VARCHAR(256) NOT NULL,
    collection_method   VARCHAR(32),
    seed_type           VARCHAR(32),
    seed_value          TEXT,
    query_expression    TEXT,
    platform            VARCHAR(64),
    time_window_start   TIMESTAMPTZ,
    time_window_end     TIMESTAMPTZ,
    target_languages    VARCHAR(16)[],
    target_regions      VARCHAR(16)[],
    collector_version   VARCHAR(64),
    records_collected   INTEGER,
    raw_payload         JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  collection_tasks                  IS 'L1标准化层：课题三采集任务元数据。验收时靠这张表核查数据覆盖范围（语种、平台、时间窗口、关键词等）';
COMMENT ON COLUMN collection_tasks.id               IS '主键，UUID';
COMMENT ON COLUMN collection_tasks.raw_record_id    IS '关联 raw_records.id';
COMMENT ON COLUMN collection_tasks.crawl_task_id    IS '课题三采集任务唯一ID，全局唯一';
COMMENT ON COLUMN collection_tasks.collection_method IS '采集方式：api | crawler | rss | manual_import';
COMMENT ON COLUMN collection_tasks.seed_type        IS '采集种子类型：keyword | account | url | topic | hashtag';
COMMENT ON COLUMN collection_tasks.seed_value       IS '采集种子内容（关键词/账号列表等）';
COMMENT ON COLUMN collection_tasks.query_expression IS '实际查询表达式（如 Twitter 高级搜索语法）';
COMMENT ON COLUMN collection_tasks.platform         IS '采集平台';
COMMENT ON COLUMN collection_tasks.time_window_start IS '采集时间窗口起始';
COMMENT ON COLUMN collection_tasks.time_window_end   IS '采集时间窗口结束';
COMMENT ON COLUMN collection_tasks.target_languages  IS '目标语种数组，如 {en,zh,vi,tl}';
COMMENT ON COLUMN collection_tasks.target_regions    IS '目标地区数组，如 {US,VN,PH}';
COMMENT ON COLUMN collection_tasks.collector_version IS '课题三采集器版本号';
COMMENT ON COLUMN collection_tasks.records_collected IS '本次任务实际采集的记录数（建议课题三提供，用于验收核查是否有数据丢失）';
COMMENT ON COLUMN collection_tasks.raw_payload       IS '完整任务元数据原始字段（JSONB备用）';

CREATE UNIQUE INDEX IF NOT EXISTS uq_ct_crawl_task ON collection_tasks(crawl_task_id);
CREATE INDEX IF NOT EXISTS idx_ct_platform  ON collection_tasks(platform);
CREATE INDEX IF NOT EXISTS idx_ct_time      ON collection_tasks(time_window_start, time_window_end);
CREATE INDEX IF NOT EXISTS idx_ct_languages ON collection_tasks USING GIN(target_languages);

-- ============================================================
-- L2：实体事实层（精简版）
-- 只存索引、统计、画像关联需要的核心字段（6-8个）。
-- 实体的详细属性（别名、国籍、职业、关系等）全部存 Neo4j。
-- 两套存储通过相同的 UUID 关联，Neo4j 节点 id = PG 主键。
-- ============================================================

CREATE TABLE IF NOT EXISTS persons (
                                       id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_name      VARCHAR(512) NOT NULL,
    importance_score    NUMERIC(5,2) NOT NULL DEFAULT 0,
    is_high_value       BOOLEAN     NOT NULL DEFAULT FALSE,
    content_count       INTEGER     NOT NULL DEFAULT 0,
    dedup_status        VARCHAR(20) NOT NULL DEFAULT 'pending',
    first_seen_at       TIMESTAMPTZ,
    last_seen_at        TIMESTAMPTZ,
    merge_history       UUID[],
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  persons                  IS 'L2实体层（精简）：人物实体索引表。详细属性（别名/国籍/职业等）存Neo4j，这里只存统计和检索需要的核心字段';
COMMENT ON COLUMN persons.id               IS '主键，UUID，与Neo4j Person节点id保持一致';
COMMENT ON COLUMN persons.canonical_name   IS '标准化姓名，T2识别后写入；允许同名多记录，后台融合任务处理归一';
COMMENT ON COLUMN persons.importance_score IS '重要性评分0-100，影响画像补全优先级和检索排序';
COMMENT ON COLUMN persons.is_high_value    IS '是否为高价值目标，T6识别后置true，触发T5优先补全画像';
COMMENT ON COLUMN persons.content_count    IS '出现在多少条媒体内容中，每次T2识别到时+1';
COMMENT ON COLUMN persons.dedup_status     IS '实体去重融合状态：pending=待融合；deduplicated=已被融合处理；canonical=融合后保留的主记录';
COMMENT ON COLUMN persons.first_seen_at    IS '首次被T2识别到的时间';
COMMENT ON COLUMN persons.last_seen_at     IS '最近一次被T2识别到的时间';
COMMENT ON COLUMN persons.merge_history    IS 'EntityDeduplicationJob融合时记录被合并掉的旧实体UUID数组，用于ID追溯';

CREATE INDEX IF NOT EXISTS idx_persons_name       ON persons(canonical_name);
CREATE INDEX IF NOT EXISTS idx_persons_high_value ON persons(is_high_value) WHERE is_high_value = TRUE;
CREATE INDEX IF NOT EXISTS idx_persons_importance ON persons(importance_score DESC);
CREATE INDEX IF NOT EXISTS idx_persons_dedup      ON persons(dedup_status);

CREATE TRIGGER trg_persons_updated_at
    BEFORE UPDATE ON persons
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE IF NOT EXISTS organizations (
                                             id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_name      VARCHAR(512) NOT NULL,
    org_type            VARCHAR(64),
    country             VARCHAR(64),
    importance_score    NUMERIC(5,2) NOT NULL DEFAULT 0,
    is_high_value       BOOLEAN     NOT NULL DEFAULT FALSE,
    content_count       INTEGER     NOT NULL DEFAULT 0,
    dedup_status        VARCHAR(20) NOT NULL DEFAULT 'pending',
    merge_history       UUID[],
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  organizations                IS 'L2实体层（精简）：组织实体索引表。详细属性存Neo4j';
COMMENT ON COLUMN organizations.id             IS '主键，UUID，与Neo4j Organization节点id保持一致';
COMMENT ON COLUMN organizations.canonical_name IS '标准化组织名称';
COMMENT ON COLUMN organizations.org_type       IS '组织类型：government | media | ngo | political_party | military | company | other';
COMMENT ON COLUMN organizations.country        IS '所属国家/地区';
COMMENT ON COLUMN organizations.importance_score IS '重要性评分0-100';
COMMENT ON COLUMN organizations.is_high_value  IS '是否为高价值目标';
COMMENT ON COLUMN organizations.content_count  IS '出现在多少条媒体内容中';
COMMENT ON COLUMN organizations.dedup_status   IS '实体去重融合状态：pending=待融合；deduplicated=已被融合处理；canonical=融合后保留的主记录';
COMMENT ON COLUMN organizations.merge_history  IS 'EntityDeduplicationJob融合时记录被合并掉的旧实体UUID数组';

CREATE INDEX IF NOT EXISTS idx_orgs_name       ON organizations(canonical_name);
CREATE INDEX IF NOT EXISTS idx_orgs_type       ON organizations(org_type);
CREATE INDEX IF NOT EXISTS idx_orgs_importance ON organizations(importance_score DESC);
CREATE INDEX IF NOT EXISTS idx_orgs_dedup      ON organizations(dedup_status);

CREATE TRIGGER trg_organizations_updated_at
    BEFORE UPDATE ON organizations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE IF NOT EXISTS events (
                                      id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_name      VARCHAR(512) NOT NULL,
    event_type          VARCHAR(64),
    occurred_at_start   TIMESTAMPTZ,
    occurred_at_end     TIMESTAMPTZ,
    country             VARCHAR(64),
    importance_score    NUMERIC(5,2) NOT NULL DEFAULT 0,
    content_count       INTEGER     NOT NULL DEFAULT 0,
    dedup_status        VARCHAR(20) NOT NULL DEFAULT 'pending',
    merge_history       UUID[],
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    event_heat_level             VARCHAR(20),
    event_heat_score             NUMERIC(6,2),
    event_heat_confidence        NUMERIC(4,3),
    event_related_content_count  INTEGER,
    event_heat_computed_at       TIMESTAMPTZ
    );

COMMENT ON TABLE  events                  IS 'L2实体层（精简）：事件实体索引表。详细属性存Neo4j';
COMMENT ON COLUMN events.id               IS '主键，UUID，与Neo4j Event节点id保持一致';
COMMENT ON COLUMN events.canonical_name   IS '标准化事件名称';
COMMENT ON COLUMN events.event_type       IS '事件类型：election | military | diplomatic | protest | disaster | other';
COMMENT ON COLUMN events.occurred_at_start IS '事件开始时间';
COMMENT ON COLUMN events.occurred_at_end   IS '事件结束时间（持续性事件用）';
COMMENT ON COLUMN events.country          IS '事件发生地国家/地区';
COMMENT ON COLUMN events.importance_score IS '重要性评分0-100';
COMMENT ON COLUMN events.content_count    IS '涉及该事件的内容数量';
COMMENT ON COLUMN events.dedup_status     IS '实体去重融合状态：pending=待融合；deduplicated=已被融合处理；canonical=融合后保留的主记录';
COMMENT ON COLUMN events.merge_history    IS 'EntityDeduplicationJob融合时记录被合并掉的旧实体UUID数组';
COMMENT ON COLUMN events.event_heat_level IS '事件热度等级：low|medium|high|explosive|unclear';
COMMENT ON COLUMN events.event_related_content_count IS '计算热度时，图谱里实际关联到的内容数量（不是events.content_count，那个字段含义是去重合并计数，语义不同）';
COMMENT ON COLUMN events.event_heat_computed_at IS '本次热度计算完成时间，NULL表示还没算过';

CREATE INDEX IF NOT EXISTS idx_events_name     ON events(canonical_name);
CREATE INDEX IF NOT EXISTS idx_events_type     ON events(event_type);
CREATE INDEX IF NOT EXISTS idx_events_occurred ON events(occurred_at_start DESC);
CREATE INDEX IF NOT EXISTS idx_events_dedup    ON events(dedup_status);

CREATE TRIGGER trg_events_updated_at
    BEFORE UPDATE ON events
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE IF NOT EXISTS narratives (
                                          id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_label     VARCHAR(512) NOT NULL,
    frame_type          VARCHAR(64),
    lifecycle_state     VARCHAR(32) NOT NULL DEFAULT 'emerging',
    content_count       INTEGER     NOT NULL DEFAULT 0,
    account_count       INTEGER     NOT NULL DEFAULT 0,
    importance_score    NUMERIC(5,2) NOT NULL DEFAULT 0,
    is_active           BOOLEAN     NOT NULL DEFAULT TRUE,
    dedup_status        VARCHAR(20) NOT NULL DEFAULT 'pending',
    first_detected_at   TIMESTAMPTZ,
    peak_at             TIMESTAMPTZ,
    merge_history       UUID[],
    claim_atoms         JSONB       NOT NULL DEFAULT '[]',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  narratives                IS 'L2实体层（精简）：叙事实体。由T2自动识别，非用户手动创建。T3负责跨源聚类合并';
COMMENT ON COLUMN narratives.id             IS '主键，UUID，与Neo4j Narrative节点id保持一致';
COMMENT ON COLUMN narratives.canonical_label IS '标准化叙事标签，T2识别时命名，T3聚类后可能更新';
COMMENT ON COLUMN narratives.frame_type     IS '叙事框架类型：attribution | threat | victim | conspiracy | economic | identity';
COMMENT ON COLUMN narratives.lifecycle_state IS '生命周期状态：emerging（萌发）| peak（峰值）| declining（衰退）| dormant（休眠）';
COMMENT ON COLUMN narratives.content_count  IS '涉及该叙事的内容数量';
COMMENT ON COLUMN narratives.account_count  IS '参与该叙事的账号数量';
COMMENT ON COLUMN narratives.importance_score IS '重要性评分0-100';
COMMENT ON COLUMN narratives.is_active      IS '是否仍在活跃传播';
COMMENT ON COLUMN narratives.dedup_status   IS '实体去重融合状态：pending=待融合；deduplicated=已被融合处理；canonical=融合后保留的主记录';
COMMENT ON COLUMN narratives.first_detected_at IS '首次被T2识别到的时间';
COMMENT ON COLUMN narratives.peak_at        IS '传播量达到峰值的时间';
COMMENT ON COLUMN narratives.merge_history  IS 'EntityDeduplicationJob融合时记录被合并掉的旧叙事UUID数组';
COMMENT ON COLUMN narratives.claim_atoms    IS '核心主张列表（JSONB），格式：[{"claim":"...","confidence":0.9}]，供T6入口查询使用';

CREATE INDEX IF NOT EXISTS idx_narratives_label     ON narratives(canonical_label);
CREATE INDEX IF NOT EXISTS idx_narratives_lifecycle ON narratives(lifecycle_state);
CREATE INDEX IF NOT EXISTS idx_narratives_active    ON narratives(is_active, importance_score DESC);
CREATE INDEX IF NOT EXISTS idx_narratives_dedup     ON narratives(dedup_status);

CREATE TRIGGER trg_narratives_updated_at
    BEFORE UPDATE ON narratives
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();


-- Entity deduplication status for existing databases. Duplicate canonical names are allowed;
-- EntityDeduplicationJob performs background consolidation.
ALTER TABLE persons ADD COLUMN IF NOT EXISTS
    dedup_status VARCHAR(20) NOT NULL DEFAULT 'pending';
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS
    dedup_status VARCHAR(20) NOT NULL DEFAULT 'pending';
ALTER TABLE events ADD COLUMN IF NOT EXISTS
    dedup_status VARCHAR(20) NOT NULL DEFAULT 'pending';
ALTER TABLE narratives ADD COLUMN IF NOT EXISTS
    dedup_status VARCHAR(20) NOT NULL DEFAULT 'pending';
DROP INDEX IF EXISTS uq_persons_canonical_name;
DROP INDEX IF EXISTS uq_orgs_canonical_name;
DROP INDEX IF EXISTS uq_events_canonical_name;
DROP INDEX IF EXISTS uq_narratives_canonical_label;
CREATE INDEX IF NOT EXISTS idx_persons_dedup ON persons(dedup_status);
CREATE INDEX IF NOT EXISTS idx_orgs_dedup ON organizations(dedup_status);
CREATE INDEX IF NOT EXISTS idx_events_dedup ON events(dedup_status);
CREATE INDEX IF NOT EXISTS idx_narratives_dedup ON narratives(dedup_status);
-- ============================================================
-- L4：画像应用层
-- ============================================================

CREATE TABLE IF NOT EXISTS person_profiles (
                                               id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id               UUID        NOT NULL REFERENCES persons(id),
    portrait_version        INTEGER     NOT NULL DEFAULT 1,
    status                  VARCHAR(32) NOT NULL DEFAULT 'active',
    -- active（当前版本，对外展示）| archived（历史版本）| draft（生成中）
    political_orientation   VARCHAR(64),
    political_score         NUMERIC(5,2),
    political_confidence    NUMERIC(4,3),
    emotion_profile         JSONB,
    stance_profile          JSONB,
    active_time_pattern     JSONB,
    post_frequency_daily    NUMERIC(7,2),
    content_original_ratio  NUMERIC(4,3),
    bend_profile            JSONB,
    influence_score         NUMERIC(5,2),
    reach_score             NUMERIC(5,2),
    virality_score          NUMERIC(5,2),
    mbti_type               VARCHAR(8),
    mbti_confidence         NUMERIC(4,3),
    decision_style          VARCHAR(32),
    language_style          VARCHAR(32),
    interest_domains        VARCHAR(64)[],
    coordination_network    JSONB,
    preferred_narratives    JSONB,
    target_type             VARCHAR(16),
    target_confidence       NUMERIC(4,3),
    target_evidence         TEXT,
    hidden_relations        JSONB,
    manipulation_risk       VARCHAR(16),
    manipulation_score      NUMERIC(5,2),
    model_versions          JSONB,     -- 生成各字段所用的模型版本，用于评测复现
    generated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_at             TIMESTAMPTZ,
    reviewer_id             UUID,      -- 人工修正时记录操作人
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  person_profiles                       IS 'L4画像层：人物全息画像。定时任务批量生成，每次生成新版本时旧版本自动归档。不做差量补全，T5一次性生成15维度全量画像';
COMMENT ON COLUMN person_profiles.id                    IS '主键，UUID';
COMMENT ON COLUMN person_profiles.person_id             IS '关联 persons.id';
COMMENT ON COLUMN person_profiles.portrait_version      IS '画像版本号，同一人物定时任务每次重新生成时自增，历史版本可追溯';
COMMENT ON COLUMN person_profiles.status                IS '画像状态：active（当前版本，对外展示，同一人物只有一个）| archived（历史版本）| draft（T5生成中）';
COMMENT ON COLUMN person_profiles.political_orientation IS '维度1：政治倾向标签，如 pro_west | anti_west | pro_russia | nationalist';
COMMENT ON COLUMN person_profiles.political_score       IS '维度1：政治倾向量化得分，-100（亲西方）到100（反西方）';
COMMENT ON COLUMN person_profiles.political_confidence  IS '维度1：置信度0-1';
COMMENT ON COLUMN person_profiles.emotion_profile       IS '维度2：10种复杂情感得分（JSONB），如 {"moral_outrage":0.89,"resentment":0.76,"fear_inducing":0.71,...}';
COMMENT ON COLUMN person_profiles.stance_profile        IS '维度3：关键议题立场列表（JSONB），如 [{"topic":"美伊核协议","stance":"strongly_oppose","confidence":0.91}]';
COMMENT ON COLUMN person_profiles.active_time_pattern   IS '维度4：活跃时段分布（JSONB），UTC小时→比例映射，如 {"0":0.02,"1":0.01,...,"14":0.15}';
COMMENT ON COLUMN person_profiles.post_frequency_daily  IS '维度4：日均发帖量';
COMMENT ON COLUMN person_profiles.content_original_ratio IS '维度4：原创内容比例0-1';
COMMENT ON COLUMN person_profiles.bend_profile          IS '维度5：BEND手法分布（JSONB），如 {"Distort":0.41,"Dismiss":0.28,"Amplify":0.19,"Narrativize":0.07,"other":0.05}';
COMMENT ON COLUMN person_profiles.influence_score       IS '维度6：综合影响力评分0-100';
COMMENT ON COLUMN person_profiles.reach_score           IS '维度6：触达人数估算得分';
COMMENT ON COLUMN person_profiles.virality_score        IS '维度6：内容传播力得分';
COMMENT ON COLUMN person_profiles.mbti_type             IS '维度7：MBTI类型推断，如 ENTJ | INTJ | ENFP';
COMMENT ON COLUMN person_profiles.mbti_confidence       IS '维度7：MBTI推断置信度0-1';
COMMENT ON COLUMN person_profiles.decision_style        IS '维度8：决策风格：aggressive | cautious | opportunistic | ideological';
COMMENT ON COLUMN person_profiles.language_style        IS '维度9：语言风格：inciting | rational | emotional | neutral';
COMMENT ON COLUMN person_profiles.interest_domains      IS '维度10：兴趣领域数组，如 {geopolitics,military,economics}';
COMMENT ON COLUMN person_profiles.coordination_network  IS '维度11：推断的协同账号/组织网络（JSONB），含实体ID、关系类型和置信度';
COMMENT ON COLUMN person_profiles.preferred_narratives  IS '维度12：倾向传播的叙事列表（JSONB），含叙事ID和传播频次';
COMMENT ON COLUMN person_profiles.target_type           IS '维度13：T6识别的操控目标类型，T00（正常用户）到T10（最高风险操控者）';
COMMENT ON COLUMN person_profiles.target_confidence     IS '维度13：目标类型置信度0-1';
COMMENT ON COLUMN person_profiles.target_evidence       IS '维度13：LLM生成的中文证据说明，前端直接展示';
COMMENT ON COLUMN person_profiles.hidden_relations      IS '维度14：图谱链路预测推断的隐性关系（JSONB），对应任务书"稀疏场景下隐性关系预测"指标';
COMMENT ON COLUMN person_profiles.manipulation_risk     IS '维度15：认知操控风险等级：critical | high | medium | low';
COMMENT ON COLUMN person_profiles.manipulation_score    IS '维度15：认知操控风险量化得分0-100';
COMMENT ON COLUMN person_profiles.model_versions        IS '生成各字段所使用的模型版本（JSONB），用于评测复现';
COMMENT ON COLUMN person_profiles.generated_at          IS '本版本画像由T5生成完成的时间';
COMMENT ON COLUMN person_profiles.reviewed_at           IS '人工直接修正画像字段时记录的操作时间';
COMMENT ON COLUMN person_profiles.reviewer_id           IS '执行人工修正操作的用户ID（前端直接编辑画像时填入）';

CREATE UNIQUE INDEX IF NOT EXISTS uq_pp_active ON person_profiles(person_id) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_pp_person       ON person_profiles(person_id, portrait_version DESC);
CREATE INDEX IF NOT EXISTS idx_pp_status       ON person_profiles(status);
CREATE INDEX IF NOT EXISTS idx_pp_target_type  ON person_profiles(target_type);
CREATE INDEX IF NOT EXISTS idx_pp_manipulation ON person_profiles(manipulation_score DESC) WHERE manipulation_score IS NOT NULL;

CREATE TRIGGER trg_person_profiles_updated_at
    BEFORE UPDATE ON person_profiles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();




-- ============================================================
-- 流水线与任务管理
-- ============================================================

CREATE TABLE IF NOT EXISTS pipeline_tasks (
                                              id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_record_id       UUID        NOT NULL REFERENCES raw_records(id),
    content_id          UUID        REFERENCES media_contents(id),
    status              VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    t1_status           VARCHAR(16) NOT NULL DEFAULT 'pending',
    t1_started_at       TIMESTAMPTZ,
    t1_done_at          TIMESTAMPTZ,
    t1_duration_ms      INTEGER,
    t2_status           VARCHAR(16) NOT NULL DEFAULT 'pending',
    t2_started_at       TIMESTAMPTZ,
    t2_done_at          TIMESTAMPTZ,
    t2_duration_ms      INTEGER,
    t3_status           VARCHAR(16) NOT NULL DEFAULT 'pending',
    t3_started_at       TIMESTAMPTZ,
    t3_done_at          TIMESTAMPTZ,
    t3_duration_ms      INTEGER,
    t4_status           VARCHAR(16) NOT NULL DEFAULT 'pending',
    t4_started_at       TIMESTAMPTZ,
    t4_done_at          TIMESTAMPTZ,
    t4_duration_ms      INTEGER,
    retry_count         SMALLINT    NOT NULL DEFAULT 0,
    max_retries         SMALLINT    NOT NULL DEFAULT 3,
    error_step          VARCHAR(4),
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  pipeline_tasks               IS '入库流水线调度表。仅 social_content / news_article 会创建对应的任务记录，其余类型到NORMALIZED即完成。T失败时按此表状态决定从哪步重跑';
COMMENT ON COLUMN pipeline_tasks.id            IS '主键，UUID';
COMMENT ON COLUMN pipeline_tasks.raw_record_id IS '关联 raw_records.id，一条原始记录对应一条任务';
COMMENT ON COLUMN pipeline_tasks.content_id    IS '关联 media_contents.id，Step3标准化映射后填入';
COMMENT ON COLUMN pipeline_tasks.status        IS '任务整体状态：PENDING | RUNNING | DONE | FAILED';
COMMENT ON COLUMN pipeline_tasks.t1_status     IS 'T1步骤状态：pending | running | done | failed | skipped';
COMMENT ON COLUMN pipeline_tasks.t1_started_at IS 'T1开始时间';
COMMENT ON COLUMN pipeline_tasks.t1_done_at    IS 'T1完成时间';
COMMENT ON COLUMN pipeline_tasks.t1_duration_ms IS 'T1实际耗时（毫秒），用于性能监控';
COMMENT ON COLUMN pipeline_tasks.t2_status     IS 'T2步骤状态：pending | running | done | failed';
COMMENT ON COLUMN pipeline_tasks.t2_duration_ms IS 'T2实际耗时（毫秒）';
COMMENT ON COLUMN pipeline_tasks.t3_status     IS 'T3步骤状态';
COMMENT ON COLUMN pipeline_tasks.t3_duration_ms IS 'T3实际耗时（毫秒）';
COMMENT ON COLUMN pipeline_tasks.t4_status     IS 'T4步骤状态（T4可与T3并行触发）';
COMMENT ON COLUMN pipeline_tasks.t4_duration_ms IS 'T4实际耗时（毫秒）';
COMMENT ON COLUMN pipeline_tasks.retry_count   IS '当前已重试次数';
COMMENT ON COLUMN pipeline_tasks.max_retries   IS '最大重试次数，默认3次';
COMMENT ON COLUMN pipeline_tasks.error_step    IS '失败发生在哪一步：T1 | T2 | T3 | T4';
COMMENT ON COLUMN pipeline_tasks.error_message IS '失败详细错误信息';

CREATE INDEX IF NOT EXISTS idx_pt_status  ON pipeline_tasks(status);
CREATE INDEX IF NOT EXISTS idx_pt_raw     ON pipeline_tasks(raw_record_id);
CREATE INDEX IF NOT EXISTS idx_pt_created ON pipeline_tasks(created_at DESC);

CREATE TRIGGER trg_pipeline_tasks_updated_at
    BEFORE UPDATE ON pipeline_tasks
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE IF NOT EXISTS workflow_tasks (
                                              id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          UUID,
    user_id             UUID,
    input_text          TEXT,
    input_files         JSONB,
    status              VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    intent_parsed       JSONB,
    execution_plan      JSONB,
    is_fallback         BOOLEAN     NOT NULL DEFAULT FALSE,
    fallback_reason     TEXT,
    steps               JSONB       NOT NULL DEFAULT '[]',
    result_summary      TEXT,
    result_data         JSONB,
    llm_tokens_used     INTEGER,
    total_duration_ms   INTEGER,
    error_message       TEXT,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  workflow_tasks                IS '分析线任务表。用户在前端发起的每个分析请求对应一条记录，协调Agent的执行过程和结果都存在这里';
COMMENT ON COLUMN workflow_tasks.id             IS '主键，UUID';
COMMENT ON COLUMN workflow_tasks.session_id     IS '关联 sessions.id，同一对话会话的多轮分析';
COMMENT ON COLUMN workflow_tasks.user_id        IS '发起分析的用户ID';
COMMENT ON COLUMN workflow_tasks.input_text     IS '用户输入的自然语言分析指令';
COMMENT ON COLUMN workflow_tasks.input_files    IS '用户上传的文件列表（JSONB），含MinIO路径';
COMMENT ON COLUMN workflow_tasks.status         IS '任务状态：PENDING | RUNNING | DONE | FAILED';
COMMENT ON COLUMN workflow_tasks.intent_parsed  IS '协调Agent解析的意图（JSONB）';
COMMENT ON COLUMN workflow_tasks.execution_plan IS 'LLM生成的执行计划（JSONB），含各步Agent调用顺序';
COMMENT ON COLUMN workflow_tasks.is_fallback    IS 'LLM编排是否失败降级为固定工作流';
COMMENT ON COLUMN workflow_tasks.fallback_reason IS '降级原因说明';
COMMENT ON COLUMN workflow_tasks.steps          IS '各步执行记录（JSONB数组），含耗时、输入输出摘要';
COMMENT ON COLUMN workflow_tasks.result_summary IS 'LLM生成的最终中文分析结论';
COMMENT ON COLUMN workflow_tasks.result_data    IS '结构化结果数据（JSONB）';
COMMENT ON COLUMN workflow_tasks.llm_tokens_used IS '本次任务消耗的LLM Token总数';
COMMENT ON COLUMN workflow_tasks.total_duration_ms IS '任务总耗时（毫秒）';

CREATE INDEX IF NOT EXISTS idx_wt_session ON workflow_tasks(session_id) WHERE session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_wt_user    ON workflow_tasks(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_wt_status  ON workflow_tasks(status);
CREATE INDEX IF NOT EXISTS idx_wt_created ON workflow_tasks(created_at DESC);

CREATE TRIGGER trg_workflow_tasks_updated_at
    BEFORE UPDATE ON workflow_tasks
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE IF NOT EXISTS sessions (
                                        id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL,
    title           VARCHAR(512),
    message_count   INTEGER     NOT NULL DEFAULT 0,
    last_message_at TIMESTAMPTZ,
    is_archived     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  sessions                IS '多轮对话会话表。一个会话对应前端左侧的一个对话记录，可包含多个 workflow_tasks';
COMMENT ON COLUMN sessions.id             IS '主键，UUID';
COMMENT ON COLUMN sessions.user_id        IS '会话所属用户ID';
COMMENT ON COLUMN sessions.title          IS '会话标题（第一条消息自动生成或用户手动命名）';
COMMENT ON COLUMN sessions.message_count  IS '消息总数';
COMMENT ON COLUMN sessions.last_message_at IS '最后一条消息的时间';
COMMENT ON COLUMN sessions.is_archived    IS '是否已归档（前端隐藏但不删除）';

CREATE INDEX IF NOT EXISTS idx_sessions_user    ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_created ON sessions(created_at DESC);

CREATE TRIGGER trg_sessions_updated_at
    BEFORE UPDATE ON sessions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE IF NOT EXISTS session_messages (
                                                id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          UUID        NOT NULL REFERENCES sessions(id),
    role                VARCHAR(16) NOT NULL,
    content             TEXT,
    workflow_task_id    UUID        REFERENCES workflow_tasks(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  session_messages                 IS '会话消息表。存储对话的每一条消息（用户输入和系统回复）';
COMMENT ON COLUMN session_messages.id              IS '主键，UUID';
COMMENT ON COLUMN session_messages.session_id      IS '关联 sessions.id';
COMMENT ON COLUMN session_messages.role            IS '消息角色：user（用户输入）| assistant（系统回复）';
COMMENT ON COLUMN session_messages.content         IS '消息文本内容';
COMMENT ON COLUMN session_messages.workflow_task_id IS '关联 workflow_tasks.id，assistant消息对应的分析任务';

CREATE INDEX IF NOT EXISTS idx_sm_session ON session_messages(session_id, created_at ASC);

CREATE TABLE IF NOT EXISTS identification_tasks (
                                                    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_task_id    UUID        REFERENCES workflow_tasks(id),
    trigger_type        VARCHAR(32),
    narrative_id        UUID        REFERENCES narratives(id),
    input_account_ids   UUID[],
    status              VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    accounts_analyzed   INTEGER     NOT NULL DEFAULT 0,
    targets_identified  INTEGER     NOT NULL DEFAULT 0,
    groups_detected     INTEGER     NOT NULL DEFAULT 0,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  identification_tasks                  IS 'T6目标识别任务表。每次触发T6识别对应一条任务记录';
COMMENT ON COLUMN identification_tasks.id               IS '主键，UUID';
COMMENT ON COLUMN identification_tasks.workflow_task_id IS '关联的分析线任务ID';
COMMENT ON COLUMN identification_tasks.trigger_type     IS '识别触发方式：narrative（以叙事为入口）| account_list（指定账号列表）| manual（手动触发）';
COMMENT ON COLUMN identification_tasks.narrative_id     IS '以叙事为入口时，关联的叙事ID';
COMMENT ON COLUMN identification_tasks.input_account_ids IS '直接指定账号列表时的账号ID数组';
COMMENT ON COLUMN identification_tasks.accounts_analyzed IS '本次分析的账号总数';
COMMENT ON COLUMN identification_tasks.targets_identified IS '识别出的重点目标数量';
COMMENT ON COLUMN identification_tasks.groups_detected  IS '检测到的协同群组数量';

CREATE INDEX IF NOT EXISTS idx_it_workflow  ON identification_tasks(workflow_task_id) WHERE workflow_task_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_it_narrative ON identification_tasks(narrative_id) WHERE narrative_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_it_status    ON identification_tasks(status);

CREATE TABLE IF NOT EXISTS identification_results (
                                                      id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id             UUID        NOT NULL REFERENCES identification_tasks(id),
    target_type         VARCHAR(16),
    target_entity_type  VARCHAR(32),
    target_entity_id    UUID        NOT NULL,
    confidence          NUMERIC(4,3),
    bend_distribution   JSONB,
    evidence_text       TEXT,
    evidence_content_ids UUID[],
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  identification_results                  IS 'T6目标识别结果表。每个识别出来的重点目标对应一条记录';
COMMENT ON COLUMN identification_results.id               IS '主键，UUID';
COMMENT ON COLUMN identification_results.task_id          IS '关联 identification_tasks.id';
COMMENT ON COLUMN identification_results.target_type      IS '目标类型：T00（正常用户）到T10（最高风险操控者），T??表示可疑待定';
COMMENT ON COLUMN identification_results.target_entity_type IS '目标实体类型：social_account | person | organization';
COMMENT ON COLUMN identification_results.target_entity_id IS '目标实体的ID（对应相应实体表的主键）';
COMMENT ON COLUMN identification_results.confidence       IS 'T6识别置信度0-1';
COMMENT ON COLUMN identification_results.bend_distribution IS 'BEND手法分布（JSONB），如 {"Distort":0.41,"Dismiss":0.28,"Amplify":0.19}';
COMMENT ON COLUMN identification_results.evidence_text    IS 'LLM生成的中文证据说明，供前端直接展示';
COMMENT ON COLUMN identification_results.evidence_content_ids IS '支撑识别结论的内容ID数组（media_contents.id）';

CREATE INDEX IF NOT EXISTS idx_ir_task   ON identification_results(task_id);
CREATE INDEX IF NOT EXISTS idx_ir_target ON identification_results(target_entity_type, target_entity_id);
CREATE INDEX IF NOT EXISTS idx_ir_type   ON identification_results(target_type);

-- ============================================================
-- 用户与系统管理
-- ============================================================

CREATE TABLE IF NOT EXISTS users (
                                     id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    username        VARCHAR(128) NOT NULL,
    password_hash   VARCHAR(256) NOT NULL,
    display_name    VARCHAR(256),
    role            VARCHAR(32) NOT NULL DEFAULT 'analyst',
    is_active       BOOLEAN     NOT NULL DEFAULT TRUE,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  users               IS '系统用户表。支持角色权限控制';
COMMENT ON COLUMN users.id            IS '主键，UUID';
COMMENT ON COLUMN users.username      IS '登录用户名，唯一';
COMMENT ON COLUMN users.password_hash IS '密码哈希（bcrypt），不存明文';
COMMENT ON COLUMN users.display_name  IS '前端显示名称';
COMMENT ON COLUMN users.role          IS '用户角色：admin（系统管理员）| analyst（分析员）| reviewer（复核员）| readonly（只读）';
COMMENT ON COLUMN users.is_active     IS '是否启用，false时禁止登录';
COMMENT ON COLUMN users.last_login_at IS '最后一次登录时间';

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_username ON users(username);

INSERT INTO users (id, username, password_hash, display_name, role, is_active, created_at, updated_at)
VALUES (
           gen_random_uuid(),
           'admin',
           '$2a$10$S7w5YjbP11RdLhfX8t21tOIPszlMGA87vm/Wg4o7/z5GBYiYAlX7e',
           '系统管理员',
           'admin',
           true,
           NOW(),
           NOW()
       ) ON CONFLICT (username) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    display_name = EXCLUDED.display_name,
    role = EXCLUDED.role,
    is_active = EXCLUDED.is_active,
    updated_at = NOW();

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE IF NOT EXISTS sub_agent_registry (
                                                  id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_code          VARCHAR(8)  NOT NULL,
    agent_name          VARCHAR(128) NOT NULL,
    description         TEXT,
    actions             JSONB       NOT NULL DEFAULT '[]',
    base_url            TEXT,
    mock_url            TEXT,
    active_url_type     VARCHAR(8)  NOT NULL DEFAULT 'mock',
    timeout_seconds     INTEGER     NOT NULL DEFAULT 30,
    max_retries         SMALLINT    NOT NULL DEFAULT 3,
    is_active           BOOLEAN     NOT NULL DEFAULT TRUE,
    health_status       VARCHAR(16) NOT NULL DEFAULT 'unknown',
    last_health_check   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  sub_agent_registry                  IS 'T1-T6子Agent注册表。存储各Agent的能力描述、接口地址和健康状态，支持Mock/真实地址一键切换';
COMMENT ON COLUMN sub_agent_registry.id               IS '主键，UUID';
COMMENT ON COLUMN sub_agent_registry.agent_code       IS 'Agent代号：T1 | T2 | T3 | T4 | T5 | T6';
COMMENT ON COLUMN sub_agent_registry.agent_name       IS 'Agent名称';
COMMENT ON COLUMN sub_agent_registry.description      IS 'Agent功能描述';
COMMENT ON COLUMN sub_agent_registry.actions          IS 'Agent能力列表（JSONB），含action名称、输入输出Schema，序列化后作为LLM的Function Calling定义';
COMMENT ON COLUMN sub_agent_registry.base_url         IS '算法服务真实地址（生产环境）';
COMMENT ON COLUMN sub_agent_registry.mock_url         IS 'Mock服务地址（开发环境）';
COMMENT ON COLUMN sub_agent_registry.active_url_type  IS '当前激活的地址类型：mock | real，改这个字段即可一键切换，无需修改代码';
COMMENT ON COLUMN sub_agent_registry.timeout_seconds  IS '单次HTTP调用超时时间（秒）';
COMMENT ON COLUMN sub_agent_registry.max_retries      IS '调用失败最大重试次数';
COMMENT ON COLUMN sub_agent_registry.is_active        IS '是否启用';
COMMENT ON COLUMN sub_agent_registry.health_status    IS '健康状态：healthy | degraded | down | unknown（定时心跳检测更新）';
COMMENT ON COLUMN sub_agent_registry.last_health_check IS '最近一次心跳检测时间';

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_code ON sub_agent_registry(agent_code);

CREATE TRIGGER trg_sub_agent_registry_updated_at
    BEFORE UPDATE ON sub_agent_registry
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE IF NOT EXISTS batch_import_tasks (
                                                  id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID        NOT NULL REFERENCES users(id),
    file_name           VARCHAR(512),
    file_format         VARCHAR(16),
    minio_key           TEXT,
    total_records       INTEGER,
    processed_records   INTEGER     NOT NULL DEFAULT 0,
    success_records     INTEGER     NOT NULL DEFAULT 0,
    failed_records      INTEGER     NOT NULL DEFAULT 0,
    duplicate_records   INTEGER     NOT NULL DEFAULT 0,
    status              VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

COMMENT ON TABLE  batch_import_tasks                   IS '批量文件导入任务表。用户通过前端上传JSON/JSONL/CSV/Excel/ZIP文件时创建';
COMMENT ON COLUMN batch_import_tasks.id                IS '主键，UUID';
COMMENT ON COLUMN batch_import_tasks.user_id           IS '发起导入的用户ID';
COMMENT ON COLUMN batch_import_tasks.file_name         IS '上传文件原始名称';
COMMENT ON COLUMN batch_import_tasks.file_format       IS '文件格式：json | jsonl | csv | excel | zip';
COMMENT ON COLUMN batch_import_tasks.minio_key         IS '文件在MinIO中的存储路径';
COMMENT ON COLUMN batch_import_tasks.total_records     IS '文件中的总记录数（解析完成后填入）';
COMMENT ON COLUMN batch_import_tasks.processed_records IS '已处理记录数';
COMMENT ON COLUMN batch_import_tasks.success_records   IS '成功入库记录数';
COMMENT ON COLUMN batch_import_tasks.failed_records    IS '处理失败记录数';
COMMENT ON COLUMN batch_import_tasks.duplicate_records IS '因重复被跳过的记录数';
COMMENT ON COLUMN batch_import_tasks.status            IS '任务状态：PENDING | RUNNING | DONE | FAILED | PARTIAL（部分成功）';

CREATE INDEX IF NOT EXISTS idx_bit_user   ON batch_import_tasks(user_id);
CREATE INDEX IF NOT EXISTS idx_bit_status ON batch_import_tasks(status);

-- ============================================================
-- 实体融合记录
-- ============================================================

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
    match_method         VARCHAR(50),
    match_score          DECIMAL(5,4),
    resolver_model       VARCHAR(100),
    is_auto_merged       BOOLEAN      DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    );
CREATE INDEX IF NOT EXISTS idx_fusion_records_entity_type ON entity_fusion_records(entity_type);
CREATE INDEX IF NOT EXISTS idx_fusion_records_survivor    ON entity_fusion_records(survivor_id);
CREATE INDEX IF NOT EXISTS idx_fusion_records_created_at  ON entity_fusion_records(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_fusion_records_job_run     ON entity_fusion_records(job_run_id);

-- ============================================================
-- 初始化数据
-- ============================================================

INSERT INTO sub_agent_registry (agent_code, agent_name, description, active_url_type) VALUES
                                                                                          ('T1', 'T1自动标注Agent',
                                                                                           '对媒体内容进行9维度自动标注：主题分类、情感倾向、立场判别、事件热度、AIGC检测、账号类型提示、影响力预估、传播潜力评估、实体提示',
                                                                                           'mock'),
                                                                                          ('T2', 'T2信息抽取Agent',
                                                                                           '从内容和传播链字段中抽取人物/组织/事件/叙事等实体及10类实体、6种关系类型，支持多语言抽取',
                                                                                           'mock'),
                                                                                          ('T3', 'T3信息融合Agent',
                                                                                           '跨语言实体归一和关系融合；跨批次归一由后台EntityDeduplicationJob处理',
                                                                                           'mock'),
                                                                                          ('T4', 'T4多模态检索Agent',
                                                                                           '生成文本/图像Embedding写入Milvus向量库，同步全文索引到Elasticsearch，支持三路融合语义检索',
                                                                                           'mock'),
                                                                                          ('T5', 'T5画像补全Agent',
                                                                                           '对高价值目标生成15+维度全息画像（政治倾向/情感/立场/行为/BEND手法/影响力/MBTI/协同网络等），直接覆盖写入画像表',
                                                                                           'mock'),
                                                                                          ('T6', 'T6目标识别Agent',
                                                                                           'BEND手法分类、协同群体检测、T00-T10目标类型识别，以叙事ID或账号列表为输入',
                                                                                           'mock')
    ON CONFLICT (agent_code) DO NOTHING;

UPDATE sub_agent_registry
SET agent_name = 'T3 信息融合',
    description = '跨语言实体归一和关系融合',
    mock_url = COALESCE(mock_url, 'http://localhost:8080/mock/t3'),
    active_url_type = COALESCE(active_url_type, 'mock'),
    timeout_seconds = 60,
    max_retries = 2,
    is_active = TRUE
WHERE agent_code = 'T3';

-- ============================================================
-- 验证建表结果
-- ============================================================
SELECT
    t.tablename                                              AS "表名",
    obj_description(c.oid, 'pg_class')                      AS "表注释",
    pg_size_pretty(pg_total_relation_size(c.oid))           AS "占用大小",
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_name = t.tablename
       AND table_schema = 'public')                         AS "字段数"
FROM pg_tables t
         JOIN pg_class c ON c.relname = t.tablename
WHERE t.schemaname = 'public'
ORDER BY t.tablename;

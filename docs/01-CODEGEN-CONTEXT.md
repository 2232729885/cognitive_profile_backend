# 课题四全息画像系统 · 后端代码生成上下文

> 这份文档专供 AI 代码助手生成代码使用，不含设计讨论过程。
> 技术栈：Spring Boot 3.5.13 + MyBatis-Plus 3.5.16 + PostgreSQL
> 完整建表 SQL 见同目录 `init_db.sql`，本文档不重复贴 DDL，只给字段速查表和处理逻辑。

---

## 0. 全局约定（所有 Entity 都遵守）

- 主键统一是 `UUID`，PostgreSQL 端用 `gen_random_uuid()` 默认生成。MyBatis-Plus 这边 `@TableId(type = IdType.ASSIGN_UUID)` 或留给数据库默认值用 `IdType.NONE` 配合 INSERT 不传 id 都可以，团队统一一种即可。
- 所有表都有 `created_at`，多数有 `updated_at`。`updated_at` 由数据库触发器 `update_updated_at()` 自动维护，**Entity 里不要手动 SET updated_at**，Mapper 的 UPDATE 语句也不需要带这个字段。
- JSONB 字段在 Java 侧用 `String`（存原始 JSON 文本）或自定义 `TypeHandler` 转 `Map<String,Object>` / 具体 DTO，团队按需选一种，本文档不强制。
- 数组字段（`TEXT[]`、`UUID[]`、`VARCHAR(16)[]`）需要自定义 `TypeHandler`，MyBatis-Plus 默认不支持 PG 数组类型，建表已有但 Mapper 层要注意映射。
- 所有表名、字段名是 snake_case，Entity 用 `@TableField` 显式映射或开启全局下划线转驼峰（`mybatis-plus.configuration.map-underscore-to-camel-case=true`，推荐后者，减少注解）。

---

## 1. 表清单与所属层次（21张表）

| 层次 | 表名 | 一句话说明 |
|---|---|---|
| L0 | `raw_records` | 原始消息落地表，所有数据的入口 |
| L1 | `media_contents` | 社交内容/新闻标准化表 |
| L1 | `social_accounts` | 账号最新状态表 |
| L1 | `social_account_snapshots` | 账号历史快照，追加写 |
| L1 | `account_relations` | 账号关系（关注/订阅/成员），先存字符串后回填UUID |
| L1 | `media_assets` | 媒体文件表 |
| L1 | `collection_tasks` | 采集任务元数据 |
| L2 | `persons` | 人物实体（精简字段） |
| L2 | `organizations` | 组织实体（精简字段） |
| L2 | `events` | 事件实体（精简字段） |
| L2 | `narratives` | 叙事实体（精简字段，T2自动识别） |
| L4 | `person_profiles` | 人物全息画像，15维度 |
| 任务 | `pipeline_tasks` | T1-T4 入库流水线调度表 |
| 任务 | `workflow_tasks` | 用户触发的分析任务 |
| 任务 | `sessions` / `session_messages` | 对话会话 |
| 任务 | `identification_tasks` / `identification_results` | T6目标识别 |
| 管理 | `users` | 系统用户 |
| 管理 | `sub_agent_registry` | T1-T6 Agent注册表，含mock/真实地址切换 |
| 管理 | `batch_import_tasks` | 批量文件导入任务 |

---

## 2. 各表字段速查（仅列字段名+类型+可空性，详细注释见 SQL 文件）

### raw_records
```
id UUID PK · schema_version VARCHAR(32) NOT NULL DEFAULT'kt3_to_kt4_v1' · record_type VARCHAR(32) NOT NULL
source_record_id VARCHAR(256) NOT NULL · crawl_task_id VARCHAR(256) · collected_at TIMESTAMPTZ
source_url TEXT · payload_hash VARCHAR(64) NOT NULL · source_type VARCHAR(32) NOT NULL DEFAULT'kafka'
source_topic VARCHAR(128) · platform VARCHAR(64) · language VARCHAR(16) · content_type VARCHAR(32)
raw_payload JSONB NOT NULL · raw_published_at TIMESTAMPTZ · normalized_at TIMESTAMPTZ
pipeline_status VARCHAR(32) NOT NULL DEFAULT'RECEIVED' · pipeline_task_id UUID · error_message TEXT
retry_count SMALLINT NOT NULL DEFAULT 0 · t1_output JSONB · t2_output JSONB · t3_output JSONB · t4_output JSONB
batch_import_id UUID · created_at · updated_at
```
**枚举值：**
- `record_type`: `social_content` | `social_account` | `account_relation` | `media_asset` | `news_article` | `collection_task`
- `pipeline_status`: `RECEIVED` → `NORMALIZED` → `T1_DONE` → `T2_DONE` → `T3_DONE` → `T4_INDEXED` | `FAILED`

### media_contents
```
id UUID PK · raw_record_id UUID NOT NULL FK→raw_records · platform VARCHAR(64) NOT NULL · content_type VARCHAR(32) NOT NULL
platform_content_id VARCHAR(256) · author_platform_user_id VARCHAR(256) · author_account_id UUID（可空，后回填）
title TEXT · body_text TEXT · language VARCHAR(16) · published_at TIMESTAMPTZ · url TEXT
parent_content_id VARCHAR(256) · root_content_id VARCHAR(256) · repost_of_content_id VARCHAR(256) · quoted_content_id VARCHAR(256)
hashtags TEXT[] · mentions TEXT[] · external_urls TEXT[]
like_count/comment_count/share_count/repost_count/quote_count/view_count/reaction_count BIGINT NOT NULL DEFAULT 0
media_asset_ids UUID[]
news_source_name VARCHAR(256) · news_domain VARCHAR(128) · news_author VARCHAR(256) · news_section VARCHAR(64) · news_tags TEXT[]
t1_annotated_at TIMESTAMPTZ · t1_model_version VARCHAR(64)
topic_category VARCHAR(64) · topic_subcategory VARCHAR(64) · event_heat_score NUMERIC(5,2)
sentiment_label VARCHAR(16) · sentiment_score NUMERIC(4,3) · stance_label VARCHAR(16) · stance_target VARCHAR(128)
aigc_score NUMERIC(4,3) · aigc_type VARCHAR(32) · entities_hint JSONB · narrative_hint TEXT
need_human_review BOOLEAN NOT NULL DEFAULT FALSE · human_review_status VARCHAR(32)
created_at · updated_at
```
**枚举值：**
- `content_type`: `post` | `channel_post` | `comment` | `reply` | `quote` | `repost` | `forward` | `forum_thread` | `article`
- `sentiment_label`: `positive` | `negative` | `neutral`
- `stance_label`: `support` | `oppose` | `neutral`
- `aigc_type`: `human` | `ai_text` | `ai_image` | `ai_mixed`

### social_accounts
```
id UUID PK · platform VARCHAR(64) NOT NULL · platform_user_id VARCHAR(256) NOT NULL（联合唯一: platform+platform_user_id）
account_entity_type VARCHAR(32) · platform_native_type VARCHAR(64)
handle VARCHAR(256) · display_name VARCHAR(512) · bio TEXT · avatar_url TEXT · profile_url TEXT
self_declared_location TEXT · verified BOOLEAN NOT NULL DEFAULT FALSE · verified_type VARCHAR(32)
is_suspended BOOLEAN NOT NULL DEFAULT FALSE · account_created_at TIMESTAMPTZ
followers_count/following_count/subscriber_count/member_count/post_count/view_count BIGINT NOT NULL DEFAULT 0
account_type VARCHAR(32) · account_type_confidence NUMERIC(4,3)
entity_person_id UUID（可空，后回填） · entity_org_id UUID（可空，后回填）
latest_snapshot_at TIMESTAMPTZ · first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW() · last_active_at TIMESTAMPTZ
created_at · updated_at
```
**枚举值：**
- `account_entity_type`: `user` | `channel` | `page` | `group` | `community` | `forum_board` | `news_source` | `unknown`
- `account_type`: `official` | `media` | `kol` | `normal` | `bot` | `cyborg`

### social_account_snapshots
```
id UUID PK · account_id UUID NOT NULL FK→social_accounts · raw_record_id UUID FK→raw_records · snapshot_at TIMESTAMPTZ NOT NULL
display_name VARCHAR(512) · bio TEXT · verified BOOLEAN · verified_type VARCHAR(32) · self_declared_location TEXT · is_suspended BOOLEAN
followers_count/following_count/subscriber_count/member_count/post_count/view_count BIGINT
snapshot_payload JSONB · created_at
```
**只 INSERT，不 UPDATE，不 DELETE。**

### account_relations
```
id UUID PK · raw_record_id UUID FK→raw_records
source_platform_user_id VARCHAR(256) NOT NULL · target_platform_user_id VARCHAR(256) NOT NULL · platform VARCHAR(64)
relation_type VARCHAR(32) NOT NULL · observed_at TIMESTAMPTZ NOT NULL · occurred_at TIMESTAMPTZ · source VARCHAR(64)
confidence NUMERIC(4,3) NOT NULL DEFAULT 1.0
from_account_id UUID FK→social_accounts（入库时NULL，回填批处理填充）
to_account_id UUID FK→social_accounts（入库时NULL，回填批处理填充）
created_at
```
**枚举值：** `relation_type`: `following` | `subscribe` | `member_of` | `owner_of` | `creator_of` | `admin_of` | `moderator_of`

### media_assets
```
id UUID PK · raw_record_id UUID FK→raw_records · content_id UUID FK→media_contents
source_asset_id VARCHAR(256)（唯一，可空） · asset_type VARCHAR(32) NOT NULL
source_url TEXT · storage_uri TEXT · mime_type VARCHAR(128) · sha256 VARCHAR(64)（唯一，可空）
file_size_bytes BIGINT · width INTEGER · height INTEGER · duration_seconds INTEGER
thumbnail_uri TEXT · ocr_text TEXT · asr_text TEXT · aigc_score NUMERIC(4,3)
minio_bucket VARCHAR(128) · minio_key TEXT · embedding_id VARCHAR(128)
created_at
```
**枚举值：** `asset_type`: `image` | `video` | `audio` | `thumbnail`
**去重：** INSERT 时用 `ON CONFLICT (sha256) DO NOTHING`

### collection_tasks
```
id UUID PK · raw_record_id UUID FK→raw_records · crawl_task_id VARCHAR(256) NOT NULL UNIQUE
collection_method VARCHAR(32) · seed_type VARCHAR(32) · seed_value TEXT · query_expression TEXT · platform VARCHAR(64)
time_window_start TIMESTAMPTZ · time_window_end TIMESTAMPTZ
target_languages VARCHAR(16)[] · target_regions VARCHAR(16)[] · collector_version VARCHAR(64) · records_collected INTEGER
raw_payload JSONB · created_at
```

### persons / organizations / events（三表结构高度相似，精简字段）
```
persons:        id · canonical_name VARCHAR(512) NOT NULL · importance_score NUMERIC(5,2) NOT NULL DEFAULT 0
                 is_high_value BOOLEAN NOT NULL DEFAULT FALSE · content_count INTEGER NOT NULL DEFAULT 0 · dedup_status
                 first_seen_at · last_seen_at · merge_history UUID[] · created_at · updated_at

organizations:  id · canonical_name · org_type VARCHAR(64) · country VARCHAR(64)
                 importance_score · is_high_value · content_count · dedup_status · merge_history · created_at · updated_at

events:         id · canonical_name · event_type VARCHAR(64) · occurred_at_start · occurred_at_end · country VARCHAR(64)
                 importance_score · content_count · dedup_status · merge_history · created_at · updated_at
```
**枚举值：**
- `org_type`: `government` | `media` | `ngo` | `political_party` | `military` | `company` | `other`
- `event_type`: `election` | `military` | `diplomatic` | `protest` | `disaster` | `other`

### narratives
```
id UUID PK · canonical_label VARCHAR(512) NOT NULL · frame_type VARCHAR(64)
lifecycle_state VARCHAR(32) NOT NULL DEFAULT'emerging' · content_count INTEGER NOT NULL DEFAULT 0 · account_count INTEGER NOT NULL DEFAULT 0
importance_score NUMERIC(5,2) NOT NULL DEFAULT 0 · is_active BOOLEAN NOT NULL DEFAULT TRUE · dedup_status
first_detected_at · peak_at · merge_history UUID[] · claim_atoms JSONB NOT NULL DEFAULT'[]'
created_at · updated_at
```
**枚举值：**
- `frame_type`: `attribution` | `threat` | `victim` | `conspiracy` | `economic` | `identity`
- `lifecycle_state`: `emerging` | `peak` | `declining` | `dormant`

### person_profiles（15维度画像，定时任务批量生成，不做差量补全）
```
id UUID PK · person_id UUID NOT NULL FK→persons · portrait_version INTEGER NOT NULL DEFAULT 1
status VARCHAR(32) NOT NULL DEFAULT'active'   -- 同一person_id只有一个status='active'

political_orientation VARCHAR(64) · political_score NUMERIC(5,2) · political_confidence NUMERIC(4,3)
emotion_profile JSONB                         -- 10种情感: {moral_outrage, resentment, fear_inducing, national_pride,
                                                  conspiracy_belief, victimhood_narrative, contempt, distrust, hope_appeal, helplessness}
stance_profile JSONB                          -- [{topic, stance, confidence}]
active_time_pattern JSONB · post_frequency_daily NUMERIC(7,2) · content_original_ratio NUMERIC(4,3)
bend_profile JSONB                            -- {Distort, Dismiss, Amplify, Narrativize, other}
influence_score NUMERIC(5,2) · reach_score NUMERIC(5,2) · virality_score NUMERIC(5,2)
mbti_type VARCHAR(8) · mbti_confidence NUMERIC(4,3)
decision_style VARCHAR(32) · language_style VARCHAR(32) · interest_domains VARCHAR(64)[]
coordination_network JSONB · preferred_narratives JSONB
target_type VARCHAR(16) · target_confidence NUMERIC(4,3) · target_evidence TEXT
hidden_relations JSONB
manipulation_risk VARCHAR(16) · manipulation_score NUMERIC(5,2)
model_versions JSONB · generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW() · reviewed_at TIMESTAMPTZ · reviewer_id UUID
created_at · updated_at
```
**枚举值：**
- `status`: `active` | `archived` | `draft`
- `decision_style`: `aggressive` | `cautious` | `opportunistic` | `ideological`
- `language_style`: `inciting` | `rational` | `emotional` | `neutral`
- `target_type`: `T00`~`T10`
- `manipulation_risk`: `critical` | `high` | `medium` | `low`

### pipeline_tasks
```
id UUID PK · raw_record_id UUID NOT NULL FK→raw_records · content_id UUID FK→media_contents
status VARCHAR(32) NOT NULL DEFAULT'PENDING'
t1_status/t2_status/t3_status/t4_status VARCHAR(16) NOT NULL DEFAULT'pending'
t1_started_at/t1_done_at TIMESTAMPTZ · t1_duration_ms INTEGER（t2/t3/t4 同结构）
retry_count SMALLINT NOT NULL DEFAULT 0 · max_retries SMALLINT NOT NULL DEFAULT 3
error_step VARCHAR(4) · error_message TEXT · created_at · updated_at
```
**枚举值：**
- `status`: `PENDING` | `RUNNING` | `DONE` | `FAILED`
- `tN_status`: `pending` | `running` | `done` | `failed`

### workflow_tasks / sessions / session_messages
```
workflow_tasks:   id · session_id UUID · user_id UUID · input_text TEXT · input_files JSONB
                   status VARCHAR(32) NOT NULL DEFAULT'PENDING' · intent_parsed JSONB · execution_plan JSONB
                   is_fallback BOOLEAN NOT NULL DEFAULT FALSE · fallback_reason TEXT · steps JSONB NOT NULL DEFAULT'[]'
                   result_summary TEXT · result_data JSONB · llm_tokens_used INTEGER · total_duration_ms INTEGER
                   error_message TEXT · started_at · completed_at · created_at · updated_at

sessions:         id · user_id UUID NOT NULL · title VARCHAR(512) · message_count INTEGER NOT NULL DEFAULT 0
                   last_message_at TIMESTAMPTZ · is_archived BOOLEAN NOT NULL DEFAULT FALSE · created_at · updated_at

session_messages: id · session_id UUID NOT NULL FK→sessions · role VARCHAR(16) NOT NULL · content TEXT
                   workflow_task_id UUID FK→workflow_tasks · created_at
```
**枚举值：** `session_messages.role`: `user` | `assistant`

### identification_tasks / identification_results
```
identification_tasks:   id · workflow_task_id UUID FK→workflow_tasks · trigger_type VARCHAR(32)
                         narrative_id UUID FK→narratives · input_account_ids UUID[]
                         status VARCHAR(32) NOT NULL DEFAULT'PENDING'
                         accounts_analyzed/targets_identified/groups_detected INTEGER NOT NULL DEFAULT 0
                         started_at · completed_at · created_at

identification_results: id · task_id UUID NOT NULL FK→identification_tasks
                         target_type VARCHAR(16) · target_entity_type VARCHAR(32) · target_entity_id UUID NOT NULL
                         confidence NUMERIC(4,3) · bend_distribution JSONB · evidence_text TEXT
                         evidence_content_ids UUID[] · created_at
```
**枚举值：** `trigger_type`: `narrative` | `account_list` | `manual`；`target_entity_type`: `social_account` | `person` | `organization`

### users / sub_agent_registry / batch_import_tasks
```
users:               id · username VARCHAR(128) NOT NULL UNIQUE · password_hash VARCHAR(256) NOT NULL
                      display_name VARCHAR(256) · role VARCHAR(32) NOT NULL DEFAULT'analyst'
                      is_active BOOLEAN NOT NULL DEFAULT TRUE · last_login_at · created_at · updated_at

sub_agent_registry:   id · agent_code VARCHAR(8) NOT NULL UNIQUE · agent_name VARCHAR(128) NOT NULL · description TEXT
                      actions JSONB NOT NULL DEFAULT'[]' · base_url TEXT · mock_url TEXT
                      active_url_type VARCHAR(8) NOT NULL DEFAULT'mock' · timeout_seconds INTEGER NOT NULL DEFAULT 30
                      max_retries SMALLINT NOT NULL DEFAULT 3 · is_active BOOLEAN NOT NULL DEFAULT TRUE
                      health_status VARCHAR(16) NOT NULL DEFAULT'unknown' · last_health_check · created_at · updated_at

batch_import_tasks:   id · user_id UUID NOT NULL FK→users · file_name VARCHAR(512) · file_format VARCHAR(16)
                      minio_key TEXT · total_records INTEGER
                      processed_records/success_records/failed_records/duplicate_records INTEGER NOT NULL DEFAULT 0
                      status VARCHAR(32) NOT NULL DEFAULT'PENDING' · started_at · completed_at · error_message · created_at
```
**枚举值：**
- `users.role`: `admin` | `analyst` | `reviewer` | `readonly`
- `sub_agent_registry.agent_code`: `T1`~`T6`；`active_url_type`: `mock` | `real`；`health_status`: `healthy` | `degraded` | `down` | `unknown`
- `batch_import_tasks.file_format`: `json` | `jsonl` | `csv` | `excel` | `zip`；`status`: `PENDING` | `RUNNING` | `DONE` | `FAILED` | `PARTIAL`

---

## 3. 处理流程伪代码（按 record_type 分类，供 Service 层实现参考）

### 3.1 social_content / news_article（走完整 T1→T2→T3→T4）

```
function handleSocialContentOrNewsArticle(kafkaMessage):
    // Step1 校验+去重
    if not isValidSchema(kafkaMessage): logErrorAndAck(); return
    if rawRecordMapper.existsBySourceRecordId(msg.sourceRecordId)
       or rawRecordMapper.existsByPayloadHash(msg.payloadHash):
        ack(); return  // 重复，跳过，不报错

    // Step2 写 raw_records（必须最先写，事务内）
    rawRecord = buildRawRecord(msg)  // pipeline_status='RECEIVED'
    rawRecordMapper.insert(rawRecord)

    // Step3 标准化映射（同一事务）
    mediaContent = mapToMediaContent(msg, rawRecord.id)
    if msg.recordType == 'news_article':
        mediaContent.contentType = 'article'
        mediaContent.platform = 'news'
        mediaContent.platformContentId = md5(msg.sourceUrl)
    mediaContentMapper.insert(mediaContent)

    socialAccount = upsertSocialAccount(msg.authorPlatformUserId, msg.platform)  // ON CONFLICT DO UPDATE

    if msg.data.media is not empty:
        for each media in msg.data.media:
            mediaAssetMapper.insertIgnoreOnConflict(buildMediaAsset(media, rawRecord.id, mediaContent.id))

    // Step4 创建流水线任务
    task = pipelineTaskMapper.insert(buildPipelineTask(rawRecord.id, mediaContent.id))  // status='PENDING'
    rawRecordMapper.update(rawRecord.id, {pipelineTaskId: task.id, pipelineStatus: 'NORMALIZED'})

    // 提交异步执行（线程池或发内部Kafka消息）
    pipelineExecutor.submit(task.id)


function executePipeline(taskId):
    task = pipelineTaskMapper.selectById(taskId)
    try:
        runT1(task)   // 调agent-proxy-service → T1 Agent
        runT2(task)   // 依赖T1结果
        // T3和T4并行
        parallel:
            runT3(task)
            runT4(task)
        pipelineTaskMapper.update(taskId, {status: 'DONE'})
    catch error:
        markFailedAndRetry(task, currentStep, error)


function runT1(task):
    pipelineTaskMapper.update(task.id, {t1Status:'running', t1StartedAt: now()})
    mc = mediaContentMapper.selectById(task.contentId)
    response = agentProxyClient.callT1(mc.bodyText, mc.rawPayload)  // HTTP调用
    mediaContentMapper.update(mc.id, {
        topicCategory: response.topicCategory, sentimentLabel: response.sentimentLabel,
        sentimentScore: response.sentimentScore, stanceLabel: response.stanceLabel,
        aigcScore: response.aigcScore, aigcType: response.aigcType,
        entitiesHint: response.entitiesHint, narrativeHint: response.narrativeHint,
        needHumanReview: response.aigcScore > 0.8,
        t1AnnotatedAt: now(), t1ModelVersion: response.modelVersion
    })
    rawRecordMapper.update(task.rawRecordId, {t1Output: response.raw, pipelineStatus: 'T1_DONE'})
    pipelineTaskMapper.update(task.id, {t1Status:'done', t1DoneAt: now(), t1DurationMs: elapsed})
    // 失败：t1Status='failed', retryCount+1，指数退避重试，超3次→pipelineStatus='FAILED'


function runT2(task):
    mc = mediaContentMapper.selectById(task.contentId)
    response = agentProxyClient.callT2(mc.bodyText, mc.entitiesHint, mc.narrativeHint,
                                         mc.hashtags, mc.mentions, mc.parentContentId, mc.repostOfContentId)
    for each entity in response.entities:
        insertEntity(entity)  // persons/organizations/events/narratives 精简字段直接 INSERT，dedup_status='pending'
    if mc.authorAccountId is null and response.resolvedAuthorAccountId:
        mediaContentMapper.update(mc.id, {authorAccountId: response.resolvedAuthorAccountId})
    rawRecordMapper.update(task.rawRecordId, {t2Output: response.raw, pipelineStatus: 'T2_DONE'})
    pipelineTaskMapper.update(task.id, {t2Status:'done', t2DurationMs: elapsed})


function runT3(task):
    response = agentProxyClient.callT3(t2Output实体列表)
    // T3 融合决策已改为后台定时任务（EntityDeduplicationJob）批量处理，不在写入时实时融合。
    // T3 仍然负责写 Neo4j 节点和关系，但不再负责 PG 实体记录的归一合并。
    neo4jClient.mergeNodesAndRelations(response.nodes, response.relations)
    rawRecordMapper.update(task.rawRecordId, {t3Output: response.raw, pipelineStatus: 'T3_DONE'})
    pipelineTaskMapper.update(task.id, {t3Status:'done', t3DurationMs: elapsed})


function runT4(task):
    mc = mediaContentMapper.selectById(task.contentId)
    embedding = agentProxyClient.callT4Embedding(mc.bodyText)
    milvusClient.insert('text_embeddings', {sourceId: mc.id, sourceType:'media_content',
                          platform: mc.platform, embedding: embedding})
    if mc has images:
        for each image: milvusClient.insert('image_embeddings', {...})
    esClient.index('media_contents_index', mc.id, buildEsDocument(mc))
    rawRecordMapper.update(task.rawRecordId, {t4Output: {...}, pipelineStatus: 'T4_INDEXED'})
    pipelineTaskMapper.update(task.id, {t4Status:'done', t4DoneAt: now()})
```

### 3.2 social_account（只标准化，不走T1-T4）

```
function handleSocialAccount(kafkaMessage):
    dedupCheck(msg)
    rawRecord = rawRecordMapper.insert(buildRawRecord(msg))  // record_type='social_account'

    account = socialAccountMapper.upsertByPlatformAndUserId(msg.data)
        // ON CONFLICT (platform, platform_user_id) DO UPDATE

    snapshot = buildSnapshot(msg.data, account.id, rawRecord.id)
    socialAccountSnapshotMapper.insert(snapshot)  // 只追加，不覆盖

    rawRecordMapper.update(rawRecord.id, {pipelineStatus: 'NORMALIZED'})
    // 终态，不创建pipeline_tasks
```

### 3.3 account_relation（先存字符串，独立批处理回填UUID）

```
function handleAccountRelation(kafkaMessage):
    dedupCheck(msg)  // 联合去重: source_platform_user_id+target_platform_user_id+relation_type+observed_at
    rawRecord = rawRecordMapper.insert(buildRawRecord(msg))

    relation = buildAccountRelation(msg.data, rawRecord.id)
    relation.fromAccountId = null  // 故意留空
    relation.toAccountId = null
    accountRelationMapper.insert(relation)

    rawRecordMapper.update(rawRecord.id, {pipelineStatus: 'NORMALIZED'})
    // 入库完成，UUID回填是独立的定时任务，不在这个流程里


// 独立定时任务，建议每5分钟跑一次，与上面的入库流程完全解耦
function backfillAccountRelationUuidJob():
    // 批量回填from_account_id
    accountRelationMapper.backfillFromAccountId()
        // SQL: UPDATE account_relations ar SET from_account_id = sa.id
        //      FROM social_accounts sa WHERE ar.from_account_id IS NULL
        //      AND sa.platform=ar.platform AND sa.platform_user_id=ar.source_platform_user_id

    accountRelationMapper.backfillToAccountId()  // 同理

    // 把刚回填完成、两端UUID都就位的记录写入Neo4j
    readyRelations = accountRelationMapper.selectReadyForNeo4jSync()
        // WHERE from_account_id IS NOT NULL AND to_account_id IS NOT NULL AND synced_to_neo4j = FALSE
    for each rel in readyRelations:
        neo4jRelationType = mapRelationType(rel.relationType)
            // following→FOLLOWS, subscribe→MEMBER_OF, member_of→MEMBER_OF, admin_of→ADMIN_OF, owner_of→OWNS
        neo4jClient.mergeRelation(rel.fromAccountId, rel.toAccountId, neo4jRelationType,
                                    {observedAt: rel.observedAt, confidence: 1.0, source: rel.source})
        accountRelationMapper.markSyncedToNeo4j(rel.id)

    // 监控：统计长期未回填的孤儿关系，超阈值告警
    orphanCount = accountRelationMapper.countOrphansOlderThan(days=7)
    if orphanCount > THRESHOLD: alertOps(orphanCount)
```

> **注：** 如果 `account_relations` 表里没有 `synced_to_neo4j` 字段，需要在实际开发时补一个 BOOLEAN 字段（DEFAULT FALSE），避免重复写入图谱。当前 SQL 文件未包含此字段，建表后请补充：
> `ALTER TABLE account_relations ADD COLUMN synced_to_neo4j BOOLEAN NOT NULL DEFAULT FALSE;`

### 3.4 media_asset（只标准化，T4异步补图像向量）

```
function handleMediaAsset(kafkaMessage):
    dedupCheck(msg)
    rawRecord = rawRecordMapper.insert(buildRawRecord(msg))

    asset = buildMediaAsset(msg.data, rawRecord.id)
    mediaAssetMapper.insertOnConflictDoNothing(asset)  // ON CONFLICT (sha256) DO NOTHING

    rawRecordMapper.update(rawRecord.id, {pipelineStatus: 'NORMALIZED'})


// 独立异步任务，扫描还没生成向量的图片/视频
function asyncImageEmbeddingJob():
    assets = mediaAssetMapper.selectWhereEmbeddingIdIsNullAndAssetTypeIn(['image','video'])
    for each asset in assets:
        embedding = agentProxyClient.callT4ImageEmbedding(asset.sourceUrl or asset.storageUri)
        vectorId = milvusClient.insert('image_embeddings', {assetId: asset.id, contentId: asset.contentId,
                                          aigcScore: asset.aigcScore, embedding: embedding})
        mediaAssetMapper.update(asset.id, {embeddingId: vectorId})
```

### 3.5 collection_task（最简单，只写两张表）

```
function handleCollectionTask(kafkaMessage):
    dedupCheck(msg)
    rawRecord = rawRecordMapper.insert(buildRawRecord(msg))
    collectionTaskMapper.insert(buildCollectionTask(msg.data, rawRecord.id))
    rawRecordMapper.update(rawRecord.id, {pipelineStatus: 'NORMALIZED'})
```

### 3.6 画像生成（定时任务，与入库流程完全独立，不是用户触发）

EntityDeduplicationJob：实体去重融合（batch/dedup/），扫描 dedup_status='pending' 的实体，提交融合服务判断，执行 PG 记录合并和 Neo4j 节点合并。当前为占位实现，融合算法待算法组提供后实现。

```
function generatePersonProfilesJob():
    candidates = personMapper.selectCandidatesForProfileGeneration(limit=100)
        // WHERE id NOT IN (SELECT person_id FROM person_profiles
        //                   WHERE status='active' AND generated_at > NOW()-INTERVAL'7 days')
        // ORDER BY importance_score DESC

    for each person in candidates:
        try:
            t5Response = agentProxyClient.callT5GenerateFullProfile(person.id)
                // 输入：该人物在Neo4j中的完整属性、关联内容、关系网络
                // 输出：15维度全量画像（一次性生成，不是差量）

            oldVersion = personProfileMapper.selectActiveByPersonId(person.id)
            newVersion = (oldVersion?.portraitVersion ?: 0) + 1

            newProfile = buildPersonProfile(person.id, newVersion, t5Response)
            newProfile.status = 'active'
            personProfileMapper.insert(newProfile)

            if oldVersion exists:
                personProfileMapper.update(oldVersion.id, {status: 'archived'})

        catch error:
            log("画像生成失败: personId=" + person.id)
            // 不中断，继续下一个，下一轮定时任务会重新尝试这个person
```

---

## 4. 失败重试统一处理

```
function markFailedAndRetry(task, failedStep, error):
    pipelineTaskMapper.update(task.id, {
        [failedStep+'Status']: 'failed',
        errorStep: failedStep,
        errorMessage: error.message,
        retryCount: task.retryCount + 1
    })
    if task.retryCount + 1 >= task.maxRetries:
        pipelineTaskMapper.update(task.id, {status: 'FAILED'})
        rawRecordMapper.update(task.rawRecordId, {pipelineStatus: 'FAILED', errorMessage: error.message})
        sendToDeadLetterQueue(task)
    else:
        scheduleRetryWithBackoff(task.id, delaySeconds = pow(2, task.retryCount))  // 1s/2s/4s指数退避
```

---

## 5. Mock/真实地址切换机制（agent-proxy-service 实现要点）

```
function callAgent(agentCode, action, params):
    agent = subAgentRegistryMapper.selectByAgentCode(agentCode)  // T1~T6
    url = (agent.activeUrlType == 'real') ? agent.baseUrl : agent.mockUrl
    response = httpClient.post(url + '/' + action, params, timeout = agent.timeoutSeconds)
    return response
    // 切换mock/real只需要 UPDATE sub_agent_registry SET active_url_type='real' WHERE agent_code='T1'
    // 代码不用改，不用重启
```

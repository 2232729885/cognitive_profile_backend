# 知识图谱融合接口规约 v1.1
# 课题四 × 算法组（T2/T3）接口对接文档

> 变更说明：v1.1 将 events 合并进 entities（type=event），relations 用 EVENT_INVOLVES_ENTITY 表达参与者关系，结构统一
> 生效范围：T2信息抽取、T3实体消歧、课题四后端编排入图
> 核心原则：T2/T3只做算法判断，不直接操作数据库；后端负责所有存储读写和流程编排

---

## 一、总体流程

```
Kafka消息
    │
    ▼
T1 标注（现有不变）
    │
    ▼
T2  POST /kg/extract          ← 算法组实现
    │
    ▼ 后端编排（T2ExtractionStep 改造）
    │
    ├─① 后端  POST /kg/entity/normalize/batch
    ├─② 后端  POST /kg/entity/candidates/batch   （ES + Milvus + Neo4j 三路召回）
    ├─③ T3    POST /kg/entity/resolve/batch       ← 算法组实现
    ├─④ 后端  POST /kg/entity/merge/batch（高置信≥0.9）
    │   后端  POST /kg/review/tasks（低置信0.6~0.9）
    │   后端  POST /kg/entity/create/batch（无候选或置信<0.6）
    ├─⑤ 后端  POST /kg/relation/normalize/batch
    ├─⑥ 后端  POST /kg/relation/exist/batch
    ├─⑦ 后端  POST /kg/relation/create/batch 或 /kg/evidence/upsert/batch
    └─⑧ 后端  POST /kg/conflict/check/batch
    │
    ▼
T4 向量化索引（现有不变）
```

---

## 二、算法组实现接口

### 2.1 T2 实体关系抽取

**POST /kg/extract**

**请求体：**
```json
{
  "docId": "mc_f480e382-a59b-43cd-b42d-17ad6901e4c9",
  "text": "美国中央司令部在霍尔木兹海峡附近展开军事演习，伊朗方面发表声明表示抗议。",
  "annotation": {
    "topics": ["military", "geopolitics"],
    "keywords": ["霍尔木兹海峡", "军事演习"],
    "entitiesHint": [
      {"text": "美国中央司令部", "typeHint": "organization"},
      {"text": "霍尔木兹海峡", "typeHint": "location"}
    ],
    "sentiment": {"label": "neutral", "score": 0.0},
    "summary": "美国中央司令部在霍尔木兹海峡附近展开军事演习"
  },
  "source": {
    "platformId": "x",
    "contentUrl": "https://x.example/status/xxx",
    "publishTime": "2026-07-07T10:00:00+08:00",
    "authorHandle": "ft_user_xxx",
    "hashtags": ["美伊冲突", "紧急"],
    "mentions": [],
    "parentContentId": null,
    "repostOfContentId": null,
    "quotedContentId": null
  },
  "language": "zh"
}
```

**响应体：**
```json
{
  "docId": "mc_f480e382-a59b-43cd-b42d-17ad6901e4c9",
  "entities": [
    {
      "mentionId": "m1",
      "name": "美国中央司令部",
      "normalizedName": "U.S. Central Command",
      "type": "organization",
      "aliases": ["CENTCOM", "美国中央指挥部"],
      "importanceScore": 90.0,
      "confidence": 0.97,
      "attributes": {}
    },
    {
      "mentionId": "m2",
      "name": "霍尔木兹海峡",
      "normalizedName": "Strait of Hormuz",
      "type": "location",
      "aliases": ["霍尔木兹"],
      "importanceScore": 85.0,
      "confidence": 0.95,
      "attributes": {}
    },
    {
      "mentionId": "m3",
      "name": "伊朗",
      "normalizedName": "Iran",
      "type": "organization",
      "aliases": ["伊斯兰共和国"],
      "importanceScore": 80.0,
      "confidence": 0.98,
      "attributes": {}
    },
    {
      "mentionId": "e1",
      "name": "霍尔木兹军事演习",
      "normalizedName": "Hormuz Military Exercise",
      "type": "event",
      "aliases": [],
      "importanceScore": 85.0,
      "confidence": 0.90,
      "attributes": {
        "eventType": "military",
        "eventTimeStart": "2026-07-07"
      }
    }
  ],
  "relations": [
    {
      "relationMentionId": "r1",
      "subjectMentionId": "m1",
      "predicate": "EVENT_OCCURRED_AT",
      "objectMentionId": "m2",
      "confidence": 0.92,
      "evidence": "美国中央司令部在霍尔木兹海峡附近展开军事演习"
    },
    {
      "relationMentionId": "r2",
      "subjectMentionId": "m3",
      "predicate": "OPPOSES",
      "objectMentionId": "m1",
      "confidence": 0.88,
      "evidence": "伊朗方面发表声明表示抗议"
    },
    {
      "relationMentionId": "r3",
      "subjectMentionId": "m1",
      "predicate": "EVENT_INVOLVES_ENTITY",
      "objectMentionId": "e1",
      "confidence": 0.90,
      "evidence": "美国中央司令部在霍尔木兹海峡附近展开军事演习"
    },
    {
      "relationMentionId": "r4",
      "subjectMentionId": "m2",
      "predicate": "EVENT_INVOLVES_ENTITY",
      "objectMentionId": "e1",
      "confidence": 0.90,
      "evidence": "美国中央司令部在霍尔木兹海峡附近展开军事演习"
    }
  ],
  "resolvedAuthorAccountId": null,
  "modelVersion": "t2-extract-v2.0"
}
```

**字段说明：**

| 字段 | 说明 |
|---|---|
| `mentionId` | 文档内唯一ID（`m1`/`m2`...），用于后续步骤关联，算法组自行生成 |
| `normalizedName` | 英文标准名（跨语言归一的关键字段，必填） |
| `name` | 原文出现的名称 |
| `type` | 实体类型，取值：`person`/`organization`/`event`/`location`/`narrative`。**event 类型直接放在 entities 数组里，不再单独设 events 字段；event 参与者关系用 `EVENT_INVOLVES_ENTITY` 表达** |
| `aliases` | 同一实体的其他表达方式 |
| `attributes` | 实体扩展属性，`event` 类型时包含 `eventType`（military/diplomatic/social等）、`eventTimeStart`（ISO8601时间）等字段；其他类型可为空对象 `{}` |
| `predicate` | 关系类型，必须在下方词表内取值 |

**关系类型词表（predicate 必须从以下选取）：**
```
SAME_AS, HAS_ACCOUNT, ALIAS_OF, MERGED_INTO,
AFFILIATED_WITH, PART_OF, CONTROLS, OWNS, MEMBER_OF, ADMIN_OF, PUBLISHED_IN,
AUTHORED, REPLY_TO, COMMENT_ON, REPOSTS, QUOTES, SHARES, REFERENCES_URL,
MENTIONS, HAS_MEDIA, DESCRIBES, REPORTS,
EVENT_OCCURRED_AT, EVENT_INVOLVES_ENTITY, LOCATED_IN, POSTS_FROM,
CONTENT_EXPRESSES_NARRATIVE, NARRATIVE_TARGETS_ENTITY, NARRATIVE_ABOUT_EVENT,
SUPPORTS, OPPOSES, HAS_EMOTION,
AMPLIFIES, BRIDGES_COMMUNITY, COORDINATES_WITH,
POTENTIAL_SUBORDINATE_TO, INFLUENCES,
ASSERTED_BY, DERIVED_FROM, CONFLICTS_WITH, REVIEWED_BY
```

---

### 2.2 T3 实体消歧判断

**POST /kg/entity/resolve/batch**

> T3 只做判断，不读写任何数据库。候选实体由后端查询后传入。

**请求体：**
```json
{
  "items": [
    {
      "mention": {
        "mentionId": "m1",
        "name": "美国中央司令部",
        "normalizedName": "U.S. Central Command",
        "type": "organization",
        "aliases": ["CENTCOM", "美国中央指挥部"],
        "attributes": {}
      },
      "candidates": [
        {
          "entityId": "3f8a2b1c-...",
          "canonicalName": "U.S. Central Command",
          "type": "organization",
          "aliases": ["CENTCOM", "美国中央司令部"],
          "importanceScore": 92.0,
          "attributes": {},
          "score": 0.94,
          "retrievalChannels": ["NAME_INDEX", "ALIAS_INDEX", "VECTOR_INDEX"]
        },
        {
          "entityId": "7c4d9e2f-...",
          "canonicalName": "United States Army",
          "type": "organization",
          "aliases": ["美国陆军"],
          "importanceScore": 85.0,
          "attributes": {},
          "score": 0.61,
          "retrievalChannels": ["VECTOR_INDEX"]
        }
      ],
      "context": {
        "docId": "mc_f480e382-...",
        "textWindow": "美国中央司令部在霍尔木兹海峡附近展开军事演习",
        "language": "zh"
      }
    }
  ],
  "strategy": {
    "autoMergeThreshold": 0.9,
    "reviewThreshold": 0.6
  }
}
```

**响应体：**
```json
{
  "results": [
    {
      "mentionId": "m1",
      "action": "MERGE",
      "matchedEntityId": "3f8a2b1c-...",
      "score": 0.96,
      "confidence": 0.96,
      "matchMethod": "exact_name_alias",
      "reason": "normalizedName与候选实体canonicalName完全一致，aliases高度重叠"
    }
  ],
  "modelVersion": "t3-resolve-v2.0"
}
```

**action 枚举说明：**

| action | 触发条件 | 后端处理 |
|---|---|---|
| `MERGE` | score ≥ autoMergeThreshold(0.9) | 调 /kg/entity/merge/batch |
| `CREATE` | 无候选 或 score < reviewThreshold(0.6) | 调 /kg/entity/create/batch |
| `REVIEW` | reviewThreshold(0.6) ≤ score < autoMergeThreshold(0.9) | 调 /kg/review/tasks |

---

### 2.3 T3 离线全局融合

**POST /kg/offline-resolution/jobs**

> 定期（每日/每周）由后端定时任务触发，或前端手动触发。
> T3 从后端提供的实体列表里找出跨批次、跨语言的重复实体并给出合并建议。

**请求体：**
```json
{
  "jobName": "daily_global_entity_resolution_20260707",
  "scope": {
    "entityTypes": ["person", "organization", "event", "narrative", "location"],
    "updatedAfter": "2026-07-06T00:00:00+08:00",
    "includeCanonical": true
  },
  "entities": [
    {
      "entityId": "3f8a2b1c-...",
      "canonicalName": "U.S. Central Command",
      "type": "organization",
      "aliases": ["CENTCOM", "美国中央司令部"],
      "importanceScore": 92.0
    },
    {
      "entityId": "9a1b3c2d-...",
      "canonicalName": "مرکز فرماندهی مرکزی آمریکا",
      "type": "organization",
      "aliases": [],
      "importanceScore": 75.0
    }
  ],
  "strategy": {
    "autoMergeThreshold": 0.95,
    "reviewThreshold": 0.7,
    "outputMode": "GENERATE_MERGE_PLAN"
  }
}
```

**响应体（异步，立即返回 jobId）：**
```json
{
  "jobId": "offline_er_job_20260707_001",
  "status": "SUBMITTED",
  "submittedAt": "2026-07-07T02:00:00+08:00"
}
```

**GET /kg/offline-resolution/jobs/{jobId}**

```json
{
  "jobId": "offline_er_job_20260707_001",
  "status": "COMPLETED",
  "statistics": {
    "scannedEntityCount": 1200,
    "candidatePairCount": 850,
    "suggestedMergeCount": 120,
    "highConfidenceMergeCount": 95,
    "reviewRequiredCount": 25
  },
  "mergePlan": [
    {
      "survivorEntityId": "3f8a2b1c-...",
      "mergeEntityIds": ["9a1b3c2d-..."],
      "confidence": 0.97,
      "matchMethod": "cross_language",
      "reason": "波斯语名称与英文名称语义等价"
    }
  ]
}
```

**POST /kg/merge-plans/{mergePlanId}/execute**

> 后端收到 mergePlan 后，按置信度阈值决定自动执行还是进审核队列，T3 不参与这一步。

---

## 三、后端实现接口（内部编排，不对外暴露）

以下接口由后端自己调用，T2/T3 算法组不需要关心。

### 3.1 实体标准化
**POST /kg/entity/normalize/batch**
- 输入：T2 返回的 entities 列表
- 处理：类型统一小写、名称 trim、aliases 去重
- 输出：标准化后的 mention 列表，加上 `normalizedName`

### 3.2 实体候选召回
**POST /kg/entity/candidates/batch**
- 三路召回：
  - ES：`canonicalName` + `aliases` 字段模糊匹配
  - Milvus `entity_embeddings`：`normalizedName` 向量 ANN TopK=20
  - Neo4j：aliases 属性精确匹配
- 结果合并去重，按综合分数排序，返回 TopK=10 候选

### 3.3 实体创建
**POST /kg/entity/create/batch**
- 写 PG 对应实体表（persons/organizations/events/narratives）
- 写 Neo4j 节点（stableUuid）
- 写 Milvus `entity_embeddings`（normalizedName 向量化）
- 写 ES entities 索引

### 3.4 实体融合
**POST /kg/entity/merge/batch**
- PG：winner 记录追加 aliases，loser 设 `dedup_status=deduplicated`
- Neo4j：`MERGE` 节点属性，建 `SAME_AS` 关系
- 更新 `entity_fusion_records` 表

### 3.5 关系规范化
**POST /kg/relation/normalize/batch**
- 把 T2 输出的自然语言 predicate 映射到 ALLOWED_RELATION_TYPES 词表
- 不在词表内的 predicate 记录 warn 日志并跳过

### 3.6 关系查重
**POST /kg/relation/exist/batch**
- 用融合后的 entityId（stableUuid）在 Neo4j 查关系是否已存在
- 返回：`exists: true/false`，已存在时返回 `relationId`

### 3.7 关系创建 / 证据追加
**POST /kg/relation/create/batch**
- 不存在：Neo4j `MERGE` 创建关系，写 `confidence`、`evidence`、`source` 属性

**POST /kg/evidence/upsert/batch**
- 已存在：追加证据，更新 `sourceCount`，取最高 `confidence`

### 3.8 冲突检测
**POST /kg/conflict/check/batch**
- 检测同一实体下属性冲突（如同一人有两个不同国籍）
- 冲突记录写 `kg_conflict_tasks` 表，前端人工审核页面处理

---

## 四、新增存储设计

### 4.1 Milvus entity_embeddings Collection

```
Collection: entity_embeddings
Schema:
  - id: VARCHAR(64) PK  → "entity_{entityType}_{stableUuid}"
  - entity_id: VARCHAR(64)   → stableUuid
  - entity_type: VARCHAR(32) → person/organization/event/narrative/location
  - embedding: FLOAT_VECTOR(dim=1024)  → normalizedName 向量
索引：HNSW，metric=COSINE
```

### 4.2 ES entities 索引

```
Index: entities_index
Fields:
  - entity_id: keyword
  - canonical_name: text (ik_max_word)
  - normalized_name: text (ik_max_word)
  - aliases: text[] (ik_max_word)
  - entity_type: keyword
  - importance_score: float
```

### 4.3 entity_fusion_records 表新增字段

```sql
ALTER TABLE entity_fusion_records
  ADD COLUMN IF NOT EXISTS match_method VARCHAR(50),
  ADD COLUMN IF NOT EXISTS match_score DECIMAL(5,4),
  ADD COLUMN IF NOT EXISTS resolver_model VARCHAR(100),
  ADD COLUMN IF NOT EXISTS is_auto_merged BOOLEAN DEFAULT TRUE;
```

---

## 五、置信度分层策略

```
score ≥ 0.90   → 自动融合（MERGE）  → /kg/entity/merge/batch
0.60 ≤ score < 0.90 → 人工审核（REVIEW）→ /kg/review/tasks
score < 0.60   → 新建实体（CREATE） → /kg/entity/create/batch
无候选          → 新建实体（CREATE） → /kg/entity/create/batch
```

---

## 六、接口联调顺序建议

1. T2 先实现 `/kg/extract`，后端用 Mock T3 联调①②④步骤
2. T3 实现 `/kg/entity/resolve/batch`，后端替换 Mock 联调③步骤
3. 后端实现完整编排链路，端到端测试
4. T3 实现 `/kg/offline-resolution/jobs`，后端对接离线融合定时任务

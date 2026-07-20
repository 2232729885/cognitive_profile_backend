# 知识图谱融合接口规约
# 课题四 × 算法组（T2/T3）接口对接文档

> **重要变更说明**：本文档相对旧版（v1.1）做了架构级重写。旧版里 T2 直接输出 `sourceName`/`targetName`/`relationType` 三元组、T3 走"离线全局融合"批处理的模式已经不再是当前架构。现在 T2 输出的是 **mention 级**结果（每个实体/关系都是一个"提及"，带 `mentionId`，还没有和图谱里已有实体建立对应关系），T3 负责判断每个 mention 应该合并到已有实体、还是新建、还是转人工审核。整个流程改成"实时候选召回 + T3 判断"，不再有旧版里的"离线全局融合定时任务"（T3 那部分，即 `/kg/offline-resolution/jobs`）这个概念。
>
> 关系类型词表（当前 16 个）单独维护在 `docs/关系词表与头尾实体类型说明.md`，本文档不重复列出，只说接口调用层面的东西。

---

## 一、总体流程

```
Kafka消息（社交内容）
    │
    ▼
T1 标注（annotate，见《T1标注接口规约》）
    │
    ▼
T2  action=extract_entities     ← 算法组实现
    │
    ▼ 后端编排（T2ExtractionStep + EntityResolutionService）
    │
    ├─① 候选召回（后端）：ES + Milvus 三路召回，针对每个mention查找图谱里可能匹配的已有实体
    ├─② T3  action=resolve_batch  ← 算法组实现，判断每个mention该MERGE/CREATE/REVIEW
    ├─③ 后端按判断结果：
    │     MERGE(score≥0.9)  → 合并进已有实体节点，写 entity_fusion_records
    │     REVIEW(0.6~0.9)   → 写 entity_fusion_records 待审核（前端 DedupView 人工审核）
    │     CREATE(<0.6或无候选) → 新建实体（PG + Neo4j + ES索引 + Milvus向量）
    └─④ 关系落图：predicate 校验（必须在16个关系词表内）→ 查重 → 创建/追加证据
    │
    ▼
T4 向量化索引（现有不变）
```

**另外一条独立流程——账号身份识别**（这是新增的，旧版文档完全没有）：

```
账号摄入（SocialAccountConsumer）
    │
    ▼
T1 annotate_account（判断账号类别）
    │
    ▼ 后端定时任务 SocialAccountIdentityJob（异步，不阻塞账号摄入）
    │
    ├─ 账号类别是"个人类"（ordinary_user/political_actor/academic_or_expert/influencer_kol）
    │     → 拿 displayName 当 mention，走候选召回 + T3 resolve_batch，
    │       匹配/新建一个 Person 实体，写 HAS_ACCOUNT 关系
    ├─ 账号类别是"机构类"（news_media/government_agency 等10类）
    │     → 同上，但匹配/新建 Organization 实体
    └─ 账号类别是"跳过类"（anonymous_account/suspected_bot_or_automated/unknown/other）
          → 不做身份识别
```

这条流程复用跟 T2 一样的"候选召回 + T3判断"逻辑（同一个后端服务 `EntityResolutionService`），算法组这边**不需要额外实现任何新接口**，T3 的 `resolve_batch` 接口是通用的，不区分调用方是内容抽取场景还是账号身份识别场景。

---

## 二、算法组实现接口

### 2.1 T2 实体关系抽取

**action=`extract_entities`**

**请求体：**
```json
{
  "title": "文章标题，社交内容通常为空",
  "text": "美国中央司令部在霍尔木兹海峡附近展开军事演习，伊朗方面发表声明表示抗议。",
  "t1Annotation": "T1 annotate_content 的完整业务响应体，包含 aigcDetection/annotations/evidenceClues，供参考，可能为null",
  "context": {
    "contentId": "内容ID",
    "platform": "x",
    "url": "内容原始链接",
    "publishedAt": "2026-07-07T10:00:00+08:00",
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
  "contentId": "跟请求里的contentId对应",
  "entities": [
    {
      "mentionId": "m1",
      "name": "美国中央司令部",
      "canonicalName": "U.S. Central Command",
      "type": "organization",
      "span": {"start": 0, "end": 8},
      "aliases": ["CENTCOM", "美国中央指挥部"],
      "importanceScore": 90.0,
      "confidence": 0.97,
      "attributes": {}
    },
    {
      "mentionId": "m2",
      "name": "霍尔木兹海峡",
      "canonicalName": "Strait of Hormuz",
      "type": "location",
      "span": {"start": 12, "end": 18},
      "aliases": ["霍尔木兹"],
      "importanceScore": 85.0,
      "confidence": 0.95,
      "attributes": {}
    },
    {
      "mentionId": "e1",
      "name": "军事演习",
      "canonicalName": "Hormuz Military Exercise",
      "type": "event",
      "span": {"start": 20, "end": 24},
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
      "subjectMentionId": "e1",
      "predicate": "EVENT_OCCURRED_AT",
      "objectMentionId": "m2",
      "confidence": 0.92,
      "evidence": "美国中央司令部在霍尔木兹海峡附近展开军事演习"
    },
    {
      "relationMentionId": "r2",
      "subjectMentionId": "e1",
      "predicate": "EVENT_INVOLVES_ENTITY",
      "objectMentionId": "m1",
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
| `mentionId` | 文档内唯一ID（`m1`/`m2`/`e1`...），算法组自行生成，用于 `relations` 里引用 |
| `canonicalName` | 标准名（跨语言归一，必填），这个名字会在实体新建时直接作为图谱里的正式名称 |
| `name` | 原文出现的名称（surface form） |
| `type` | 实体类型：`person`/`organization`/`event`/`location`。**event 类型直接放在 `entities` 数组里，不单独设 `events` 字段，event 参与者关系用 `EVENT_INVOLVES_ENTITY` 表达** |
| `span` | 这个实体在 `text` 里的字符位置，`{start, end}` 对象，不是数组 |
| `aliases` | 同一实体的其他表达方式 |
| `attributes` | 实体扩展属性，`event` 类型时包含 `eventType`/`eventTimeStart`，其他类型可为空对象 `{}` |
| `predicate` | 关系类型，必须在 `docs/关系词表与头尾实体类型说明.md` 定义的16个关系类型内取值，不在词表内的会被后端过滤掉，不会写入图谱 |

---

### 2.2 T3 实体消歧判断

**action=`resolve_batch`**

> T3 只做判断，不读写任何数据库。候选实体由后端查询后传入。这个接口同时服务内容抽取（T2之后）和账号身份识别两种场景，输入结构完全一样，算法组不需要区分调用方是谁。

**请求体：**
```json
{
  "items": [
    {
      "mention": {
        "mentionId": "m1",
        "name": "美国中央司令部",
        "canonicalName": "U.S. Central Command",
        "type": "organization",
        "span": {"start": 0, "end": 8},
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
          "retrievalChannels": ["NAME_INDEX", "VECTOR_INDEX"]
        }
      ],
      "context": {
        "contentId": "内容ID或账号ID（账号身份识别场景下装的是账号ID）",
        "platform": "x",
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
      "reason": "canonicalName与候选实体canonicalName完全一致，aliases高度重叠"
    }
  ],
  "modelVersion": "t3-resolve-v2.0"
}
```

**action 枚举说明：**

| action | 触发条件 | 后端处理 |
|---|---|---|
| `MERGE` | score ≥ autoMergeThreshold(0.9) | 合并进已有实体节点 |
| `REVIEW` | reviewThreshold(0.6) ≤ score < autoMergeThreshold(0.9) | 写待审核队列，人工确认 |
| `CREATE` | 无候选 或 score < reviewThreshold(0.6) | 新建实体 |

---

## 三、后端实现（内部编排，不对外暴露，算法组不需要实现）

- **候选召回**（`EntityCandidateRetrievalService`）：ES 名称/别名模糊匹配 + Milvus 向量ANN，合并去重返回 TopK。
- **实体解析编排**（`EntityResolutionService`）：调 T3、按 action 分层处理、写 PG/Neo4j/ES/Milvus，这套逻辑内容抽取场景和账号身份识别场景共用同一份实现。
- **关系落图**：`predicate` 校验（对照 `docs/关系词表与头尾实体类型说明.md` 的16个关系类型）→ Neo4j 查重（`relationExists`）→ 不存在则创建，已存在则追加证据。
- **`HAS_ACCOUNT` 关系**：由账号身份识别流程（`SocialAccountIdentityJob`）产出，写 `(Person|Organization)-[HAS_ACCOUNT]->(SocialAccount)`，同时把匹配/新建的实体ID写回 `social_accounts.entity_person_id`/`entity_org_id`。
- **内容与实体的关系**：T2 每条内容里成功解析（MERGE/REVIEW/CREATE 任意一种）出来的实体，后端会自动补一条内容指向该实体的关系，不需要算法组在 `relations[]` 里额外输出——`event` 类型走 `(MediaContent)-[DESCRIBES]->(Event)`，`person`/`organization`/`location` 类型走 `(MediaContent)-[MENTIONS]->(实体)`。这样图谱上才能查"这个事件被哪些内容描述过""这个人被哪些内容提到过"，算法组不用关心这一步，只要 `entities[]` 抽取完整、`canonicalName` 准确即可。

---

## 四、存储设计

### 4.1 Milvus `entity_embeddings` Collection

```
Collection: entity_embeddings
Schema:
  - id: VARCHAR(64) PK  → "entity_{entityType}_{stableUuid}"
  - entity_id: VARCHAR(64)   → stableUuid
  - entity_type: VARCHAR(32) → person/organization/event/location
  - embedding: FLOAT_VECTOR  → canonicalName 向量
索引：HNSW，metric=COSINE
```

### 4.2 ES `entities_index`

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

### 4.3 `entity_fusion_records` 表关键字段

```sql
match_method VARCHAR(50)       -- T3给出的匹配方法
match_score DECIMAL(5,4)       -- T3给出的匹配分数
resolver_model VARCHAR(100)    -- T3的modelVersion
is_auto_merged BOOLEAN         -- 是否自动合并（MERGE）还是待审核（REVIEW）
```

---

## 五、置信度分层策略

```
score ≥ 0.90         → 自动合并（MERGE）
0.60 ≤ score < 0.90  → 人工审核（REVIEW）
score < 0.60         → 新建实体（CREATE）
无候选                → 新建实体（CREATE）
```

这个阈值目前是写死在后端代码里的默认值（`T3ResolveBatchRequest.Strategy.autoMergeThreshold=0.9`/`reviewThreshold=0.6`），算法组的 `resolve_batch` 判断逻辑应该按请求里传的 `strategy` 阈值来，不要自己写死一套不同的阈值。

---

## 六、不再使用的旧接口/概念（提醒，避免误实现）

- ~~`/kg/entity/resolve/batch`~~ → 现在统一叫 `resolve_batch`（action名），且是唯一的T3接口，不再区分"实时" vs "离线全局融合"两套。
- ~~`/kg/offline-resolution/jobs`~~（离线全局融合任务）→ 已移除，不需要实现。
- ~~`sourceName`/`targetName`/`relationType` 三元组直出~~ → 改成 mention 级 + predicate。
- ~~`events` 独立数组~~ → event 现在混在 `entities` 里，用 `type=event` + `attributes` 区分。
- ~~`Narrative` 实体类型~~ → 已从图谱里去掉，不再是基础实体类，T2 不应该再输出 `type=narrative` 的实体。
- ~~`AMPLIFIES`/`HAS_MEDIA` 关系类型~~ → 已从16个关系词表里移除。

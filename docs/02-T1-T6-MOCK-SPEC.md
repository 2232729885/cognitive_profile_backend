# T1-T6 Agent 接口规范（Mock Server 实现依据）

> 用途：固定 Mock Server 和真实算法组接口对接的契约，两边可并行开发，互不阻塞。
> 实现方式：方案B，Spring Boot内置`@Profile("mock")`的Controller，与主项目同进程运行。
> 字段来源：与`docs/01-CODEGEN-CONTEXT.md`、`src/main/java/com/idata/profile/agentproxy/dto`下DTO严格一致，禁止偏离。

---

## 0. 调用约定（所有Agent共用）

**URL拼接规则**（见`AgentProxyClient.call()`第58行）：
```
完整请求URL = sub_agent_registry.mock_url（或base_url） + "/" + action
```

例如 `mock_url = http://localhost:8080/mock/t1`，`action = annotate_text`，
实际请求地址为 `http://localhost:8080/mock/t1/annotate_text`。

**因此 `sub_agent_registry` 表的 `mock_url` 字段填法是**（按agent_code各自配置）：

| agent_code | mock_url（建表后UPDATE这个字段） |
|---|---|
| T1 | `http://localhost:8080/mock/t1` |
| T2 | `http://localhost:8080/mock/t2` |
| T3 | `http://localhost:8080/mock/t3` |
| T4 | `http://localhost:8080/mock/t4` |
| T5 | `http://localhost:8080/mock/t5` |
| T6 | `http://localhost:8080/mock/t6` |

**请求方式**：统一POST，Content-Type: application/json。
**HTTP状态码**：Mock阶段统一返回200，不模拟4xx/5xx（错误场景由调用方的@Retryable和业务层重试机制覆盖，Mock不需要模拟失败）。

---

## 1. T1 自动标注

**Action**: `annotate_text`
**完整路径**: `POST /mock/t1/annotate_text`
**对应DTO**: `agentproxy.dto.t1.T1AnnotateRequest` / `T1AnnotateResponse`
**调用方**: `pipeline.step.T1AnnotationStep.run()`

### 请求体
```json
{
  "bodyText": "string，内容正文",
  "rawPayload": "string，JSONB原文",
  "language": "string，如 en",
  "platform": "string，如 x"
}
```

### 响应体
```json
{
  "topicCategory": "string，如 politics",
  "topicSubcategory": "string，如 election",
  "eventHeatScore": "number，0-100",
  "sentimentLabel": "string，positive|negative|neutral",
  "sentimentScore": "number，-1.0~1.0",
  "stanceLabel": "string，support|oppose|neutral",
  "stanceTarget": "string",
  "aigcScore": "number，0.0-1.0",
  "aigcType": "string，human|ai_text|ai_image|ai_mixed",
  "entitiesHint": "object，任意JSON结构，传给T2",
  "narrativeHint": "string",
  "modelVersion": "string，如 mock-t1-v1.0",
  "raw": "string，建议直接回传整个响应体的JSON字符串，存入raw_records.t1_output"
}
```

---

## 2. T2 信息抽取

**Action**: `extract_entities`
**完整路径**: `POST /mock/t2/extract_entities`
**对应DTO**: `agentproxy.dto.t2.T2ExtractRequest` / `T2ExtractResponse`
**调用方**: `pipeline.step.T2ExtractionStep.run()`

### 请求体
```json
{
  "bodyText": "string",
  "entitiesHint": "object，来自T1",
  "narrativeHint": "string，来自T1",
  "hashtags": ["string"],
  "mentions": ["string"],
  "parentContentId": "string，可为null",
  "repostOfContentId": "string，可为null",
  "quotedContentId": "string，可为null"
}
```

### 响应体
```json
{
  "entities": [
    {
      "type": "string，person|organization|event|narrative",
      "canonicalName": "string",
      "importanceScore": "number，0-100",
      "matchedAccountId": "string，UUID，仅type=person时可能有值，否则null"
    }
  ],
  "resolvedAuthorAccountId": "string，UUID，可为null",
  "raw": "string"
}
```

---

## 3. T3 信息融合

**Action**: `fuse_entities`
**完整路径**: `POST /mock/t3/fuse_entities`
**对应DTO**: `agentproxy.dto.t3.T3FuseRequest` / `T3FuseResponse`
**调用方**: `pipeline.step.T3FusionStep.run()`

### 请求体
```json
{
  "entities": [
    { "type": "string", "canonicalName": "string", "tempId": "string，T2阶段临时引用" }
  ]
}
```

### 响应体
```json
{
  "entityMerges": [
    { "survivorId": "string，UUID", "mergedIds": ["string，UUID"] }
  ],
  "nodes": [
    {
      "label": "string，Person|Organization|Event|Narrative|SocialAccount",
      "id": "string，UUID",
      "properties": "object，完整属性，新值直接覆盖Neo4j中的旧值（不审核不暂存）"
    }
  ],
  "relations": [
    {
      "fromId": "string，UUID",
      "toId": "string，UUID",
      "relationType": "string，如AFFILIATED_WITH",
      "properties": "object"
    }
  ],
  "raw": "string"
}
```

**重要提醒（避免Mock数据违反设计原则）**：
`entityMerges`记录的是实体归一（要保留追溯链，写PG的mergeHistory），
`nodes.properties`是字段覆盖（直接SET不留旧值）。Mock数据要分别覆盖测试这两种场景，
不要把两者混为一谈，否则验证不出代码是否正确区分处理了这两种情况。

---

## 4. T4 多模态检索 / 索引构建

T4有两个action，分别对应文本和图像向量化。

### 4a. 文本向量化

**Action**: `generate_text_embedding`
**完整路径**: `POST /mock/t4/generate_text_embedding`
**调用方**: `pipeline.step.T4IndexingStep.run()`

**请求体**：
```json
{ "text": "string", "imageUrl": null }
```

**响应体**（对应`T4EmbeddingResponse`）：
```json
{
  "embedding": [0.123, -0.456, "... 共4096个float"],
  "modelVersion": "string，如 mock-qwen3-vl-embedding-8b"
}
```

### 4b. 图像向量化

**Action**: `generate_image_embedding`
**完整路径**: `POST /mock/t4/generate_image_embedding`
**调用方**: `batch.asset.ImageEmbeddingJob.run()`

**请求体**：
```json
{ "text": null, "imageUrl": "string，图片URL或storageUri" }
```

**响应体**：与4a相同结构（`embedding`字段为4096维float数组）。

> **注意**：Mock阶段embedding数组建议用固定seed的伪随机数生成（如按文本/URL长度做简单hash映射到[-1,1]区间），
> 不需要真实语义，只需要维度对（4096维），因为这一步的目的是验证Milvus写入流程通不通，不验证检索准确率。

---

## 5. T5 画像补全

**Action**: `generate_full_profile`
**完整路径**: `POST /mock/t5/generate_full_profile`
**对应DTO**: `agentproxy.dto.t5.T5GenerateProfileRequest` / `T5GenerateProfileResponse`
**调用方**: `batch.profile.PersonProfileGenerationJob.generateForPerson()`

### 请求体
```json
{ "personId": "string，UUID" }
```

### 响应体（15维度全量画像，字段对应`PersonProfile`实体）
```json
{
  "politicalOrientation": "string，如pro_west",
  "politicalScore": "number，-100~100",
  "politicalConfidence": "number，0-1",
  "emotionProfile": {
    "moral_outrage": 0.0, "resentment": 0.0, "fear_inducing": 0.0,
    "national_pride": 0.0, "conspiracy_belief": 0.0, "victimhood_narrative": 0.0,
    "contempt": 0.0, "distrust": 0.0, "hope_appeal": 0.0, "helplessness": 0.0
  },
  "stanceProfile": [
    { "topic": "string", "stance": "string", "confidence": 0.0 }
  ],
  "activeTimePattern": { "0": 0.02, "1": 0.01, "...": "...23" },
  "postFrequencyDaily": "number",
  "contentOriginalRatio": "number，0-1",
  "bendProfile": { "Distort": 0.0, "Dismiss": 0.0, "Amplify": 0.0, "Narrativize": 0.0, "other": 0.0 },
  "influenceScore": "number，0-100",
  "reachScore": "number，0-100",
  "viralityScore": "number，0-100",
  "mbtiType": "string，如ENTJ",
  "mbtiConfidence": "number，0-1",
  "decisionStyle": "string，aggressive|cautious|opportunistic|ideological",
  "languageStyle": "string，inciting|rational|emotional|neutral",
  "interestDomains": ["string"],
  "coordinationNetwork": "object，任意结构",
  "preferredNarratives": "object，任意结构",
  "targetType": "string，T00~T10",
  "targetConfidence": "number，0-1",
  "targetEvidence": "string，中文证据说明",
  "hiddenRelations": "object，任意结构",
  "manipulationRisk": "string，critical|high|medium|low",
  "manipulationScore": "number，0-100",
  "modelVersions": "object，任意结构"
}
```

> **15个维度必须全部返回非null值**，因为设计原则是T5一次性生成全量画像，不做差量补全，
> Mock响应如果某字段返回null，会暴露调用方代码是否正确处理"全字段覆盖写入"的逻辑。

---

## 6. T6 重点目标识别

**Action**: `identify_targets`（约定值，骨架代码里identification包尚为占位，此action名为本次新定）
**完整路径**: `POST /mock/t6/identify_targets`
**对应DTO**: `agentproxy.dto.t6.T6IdentifyRequest` / `T6IdentifyResponse`
**调用方**: 待实现，见`identification.IdentificationTaskService`（当前为占位类）

### 请求体
```json
{
  "triggerType": "string，narrative|account_list|manual",
  "narrativeId": "string，UUID，triggerType=narrative时必填",
  "inputAccountIds": ["string，UUID"]
}
```

### 响应体
```json
{
  "targets": [
    {
      "targetType": "string，T00~T10",
      "targetEntityType": "string，social_account|person|organization",
      "targetEntityId": "string，UUID",
      "confidence": "number，0-1",
      "bendDistribution": { "Distort": 0.0, "Dismiss": 0.0, "Amplify": 0.0, "Narrativize": 0.0, "other": 0.0 },
      "evidenceText": "string，中文证据说明",
      "evidenceContentIds": ["string，UUID"]
    }
  ],
  "accountsAnalyzed": "number，整数",
  "groupsDetected": "number，整数"
}
```

---

## 7. Action名称速查表（写Mock Controller时对照）

| agent_code | action | HTTP方法 | 完整Mock路径 |
|---|---|---|---|
| T1 | annotate_text | POST | /mock/t1/annotate_text |
| T2 | extract_entities | POST | /mock/t2/extract_entities |
| T3 | fuse_entities | POST | /mock/t3/fuse_entities |
| T4 | generate_text_embedding | POST | /mock/t4/generate_text_embedding |
| T4 | generate_image_embedding | POST | /mock/t4/generate_image_embedding |
| T5 | generate_full_profile | POST | /mock/t5/generate_full_profile |
| T6 | identify_targets | POST | /mock/t6/identify_targets |


# T1_annotation_v0.5 README

## 1. 文档定位

本文档用于说明 `T1_annotation_v0.5` 的输出结构、字段含义、枚举值、使用边界和代码实现约束。

`T1_annotation_v0.5` 是课题四 T1 自动标注方向的结构化输出 schema，用于指导后续 T1 Agent、自动标注工具、提示词、模型输出解析、前端展示样例、接口适配和测试用例设计。

本 README 的用途包括：

1. 说明 T1 自动标注模块应输出哪些字段；
2. 说明每个字段的含义、取值范围和边界；
3. 说明哪些能力属于 T1，哪些能力不属于 T1；
4. 约束后续代码实现时的 JSON 输出格式；
5. 为负责人审核标签体系、研发人员实现功能、集成组对接接口提供统一依据。

---

## 2. T1 自动标注任务定位

T1 自动标注的核心任务是：

> 面向文本、图像、视频及图文混合等认知数据，自动生成结构化认知标签，包括 AIGC 检测、多维主观认知标注、基础客观标注、证据线索池和质量控制信息。

T1 不是单纯的情感分析模块，也不是完整的信息抽取系统。  
T1 关注的是“这条内容表现出什么认知属性”，例如：

- 是否疑似 AI 生成；
- 主要话题是什么；
- 内容总体立场是什么；
- 文中出现的实体之间是否存在明确立场指向；
- 民众态度如何；
- 情绪倾向如何；
- 事件热度如何；
- 账号类别如何；
- 表达方式是否激烈、讽刺、煽动或理性；
- 内容目的是什么；
- 是否存在风险；
- 哪些原始证据支撑这些判断。

T2 信息抽取更关注实体、关系、事件的正式结构化抽取。  
T1 中的 `entities_hint` 和 `entities_hint_stance` 只作为 T1 自动标注过程中的实体线索和立场线索，不替代 T2 的正式实体抽取、关系抽取或事件抽取结果。

---

## 3. 总体输出结构

`T1_annotation_v0.5` 顶层结构包括 9 个字段：

```json
{
  "schema_version": "t1_annotation_v0.5",
  "input_reference": {},
  "language": "string",
  "aigc_detection": {},
  "annotations": {},
  "evidence_clues": [],
  "quality_control": {},
  "overall_confidence": "number | null",
  "processed_at": "string"
}
```

| 顶层字段 | 类型 | 功能说明 |
|---|---|---|
| `schema_version` | string | 输出结构版本号，固定为 `t1_annotation_v0.5` |
| `input_reference` | object | 输入内容的基础引用信息 |
| `language` | string | 输入语言，通常透传上游语言字段 |
| `aigc_detection` | object | AIGC 检测结果，覆盖文本、图像、视频和多模态一致性 |
| `annotations` | object | 自动标注结果，包括高价值主观维度和基础客观维度 |
| `evidence_clues` | array | 原始证据池，被各字段通过 `evidence_ids` 引用 |
| `quality_control` | object | 质量控制、人工复核提示和模块失败记录 |
| `overall_confidence` | number \| null | T1 对整条输出结果的总体置信度 |
| `processed_at` | string | T1 处理完成时间，建议使用 ISO 8601 时间格式 |

---

## 4. 全局设计原则

### 4.1 AIGC 检测单独作为顶层模块

`aigc_detection` 不放入 `annotations`，而是作为顶层字段单独存在。

原因：

1. AIGC 检测是任务书明确要求的核心能力；
2. AIGC 检测涉及文本、图像、视频、多模态一致性等多个子模块；
3. AIGC 检测不只是一个普通标签，而是独立的内容真实性/生成性判断能力；
4. 单独放置便于后续接入文本检测器、图像鉴伪模型、视频 DeepFake 检测器和多模态一致性检测器。

### 4.2 `annotations` 分为两类

`annotations` 下分为：

```json
{
  "annotations": {
    "high_value_subjective": {},
    "basic_objective": {}
  }
}
```

其中：

| 一级字段 | 含义 |
|---|---|
| `high_value_subjective` | 高价值主观认知维度，用于表示立场、态度、情绪、热度、风险、表达方式和内容目的等认知判断 |
| `basic_objective` | 基础客观标注维度，用于表示话题、账号类别、实体线索、关键词、摘要、事件类型等基础信息 |

当前 `annotations` 共包含 15 个标注维度：

| 类别 | 维度数量 | 维度 |
|---|---:|---|
| `high_value_subjective` | 9 | `ideology`、`core_stance`、`entities_hint_stance`、`public_attitude`、`opinion_emotion`、`event_heat`、`language_style`、`content_purpose`、`risk_level` |
| `basic_objective` | 6 | `topic_tags`、`account_type`、`entities_hint`、`keywords`、`summary`、`event_type` |

### 4.3 证据必须来自原始输入

所有 `evidence_ids` 都必须引用 `evidence_clues` 中的原始证据。

允许作为证据的内容包括：

- 原始文本片段；
- OCR 文本；
- 视频字幕或转写文本；
- 原始图像区域；
- 原始视频时间段；
- 原始视频帧区域；
- 原始元数据；
- AIGC 检测模型的原始信号；
- 多模态一致性检测中的原始冲突信号。

不允许把模型生成的解释、推理过程、总结性描述作为证据。

不应把下面内容作为证据：

```text
这句话体现出明显反对立场。
```

应当保存原始输入片段，例如：

```text
The government is using security as an excuse to silence ordinary people.
```

### 4.4 `summary` 不需要 `evidence_ids`

`summary` 是对原始内容的客观摘要，字段中不设置 `evidence_ids`。

但代码实现时必须约束：

1. 摘要只能依据原始内容生成；
2. 摘要不能添加原文没有的背景、原因、影响或结论；
3. 摘要不能替代证据；
4. 摘要不能作为其他字段的判断依据。

### 4.5 `not_applicable` 的使用规则

`not_applicable` 表示“该模态不存在或该任务不适用”。

在 AIGC 检测中：

| 场景 | 输出规则 |
|---|---|
| 没有文本 | `text_aigc_label = not_applicable` |
| 没有图像 | `image_aigc_label = not_applicable` |
| 没有视频 | `video_aigc_label = not_applicable` |
| 不是多模态输入 | `multimodal_aigc_label = not_applicable` |

如果模态存在但模型无法判断，应输出 `unclear`，不能输出 `not_applicable`。

例如：

- 输入中有文本，但文本太短无法判断是否 AI 生成：输出 `unclear`；
- 输入中没有视频：输出 `not_applicable`。

### 4.6 `unclear` 的使用规则

`unclear` 表示“任务适用，但当前信息不足或模型无法可靠判断”。

典型场景包括：

- 文本太短；
- 上下文缺失；
- 证据冲突；
- 多模态信号不一致；
- 模型置信度过低；
- 现有规则无法覆盖；
- 输入内容表达隐晦或讽刺，难以判断。

### 4.7 `null` 和空数组的使用规则

| 类型 | 使用场景 |
|---|---|
| `null` | 字段理论上应该有值，但当前无法获得或无法计算 |
| `[]` | 字段是数组类型，但当前没有可输出项 |
| `unclear` | 枚举字段适用，但无法判断 |
| `not_applicable` | 枚举字段对应任务不适用 |

例如：

```json
{
  "overall_confidence": null,
  "evidence_ids": [],
  "event_type_label": "unclear",
  "video_aigc_label": "not_applicable"
}
```

### 4.8 置信度取值规则

所有 `confidence` 或 `score` 类型字段建议使用 0 到 1 的小数。

| 范围 | 建议含义 |
|---|---|
| `0.00 - 0.40` | 低置信度 |
| `0.40 - 0.70` | 中等置信度 |
| `0.70 - 1.00` | 高置信度 |

注意：

- `score` 表示某种倾向或程度；
- `confidence` 表示模型对判断结果的确信程度；
- 二者不能混用。

例如：

- `overall_aigc_score = 0.85` 表示内容很像 AI 生成；
- `aigc_detection_confidence = 0.60` 表示模型对这个判断只有中等把握。

### 4.9 暂不纳入的字段和能力

当前版本不设置以下字段：

- `content_modality`
- `auxiliary`
- `multimodal_clues`
- `social_psychology`
- `text_content`
- `media_assets`
- `missing_inputs`
- `warnings`

这些字段不是永久不能使用，而是当前阶段为了保持结构清晰、避免过度扩展，暂不纳入。

---

## 5. `input_reference`：输入引用信息

### 5.1 字段结构

```json
"input_reference": {
  "content_id": "string",
  "content_type": "text | image | video | text_image_mixed",
  "platform": "string | null",
  "url": "string | null",
  "author_id": "string | null",
  "created_at": "string | null"
}
```

### 5.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `content_id` | string | 原始记录 ID，应与上游数据库或输入记录中的内容 ID 保持一致 |
| `content_type` | enum | 内容类型 |
| `platform` | string \| null | 内容来源平台 |
| `url` | string \| null | 原始内容链接 |
| `author_id` | string \| null | 发布者账号 ID |
| `created_at` | string \| null | 原始内容发布时间 |

### 5.3 `content_type` 枚举值

| 枚举值 | 含义 |
|---|---|
| `text` | 纯文本内容 |
| `image` | 纯图像内容 |
| `video` | 视频内容 |
| `text_image_mixed` | 图文混合内容 |

### 5.4 边界说明

`input_reference` 只记录输入引用和基础元数据：

- 不存正文内容；
- 不存媒体资源列表；
- 不做账号类别判断；
- 不做 AIGC 检测；
- 不做证据判断；
- 不做标注结果存储。

---

## 6. `language`：语言信息

### 6.1 字段结构

```json
"language": "string"
```

### 6.2 字段说明

`language` 表示输入内容的语言。

建议取值包括：

| 取值 | 含义 |
|---|---|
| `zh` | 中文 |
| `en` | 英文 |
| `ja` | 日文 |
| `ko` | 韩文 |
| `vi` | 越南语 |
| `tl` | 菲律宾语 |
| `mixed` | 多语言混合 |
| `unknown` | 未知语言 |

### 6.3 边界说明

当前 `language` 主要作为上游输入字段保留。  
如果上游已经提供语言字段，T1 可以直接透传。  
如果后续需要 T1 自行检测语言，可以在代码实现中增加语言检测模块，但不改变当前字段结构。

---

## 7. `aigc_detection`：AIGC 检测结果

### 7.1 整体结构

```json
"aigc_detection": {
  "overall_aigc_label": "ai_generated | human_generated | mixed | suspicious | unclear",
  "overall_aigc_score": "number | null",
  "text_aigc_detection": {},
  "image_aigc_detection": {},
  "video_aigc_detection": {},
  "multimodal_aigc_detection": {},
  "aigc_detection_confidence": "number | null"
}
```

### 7.2 总体字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `overall_aigc_label` | enum | 整条内容的总体 AIGC 判断 |
| `overall_aigc_score` | number \| null | AIGC 倾向分数，建议 0 到 1，越接近 1 越像 AI 生成 |
| `text_aigc_detection` | object | 文本 AIGC 检测结果 |
| `image_aigc_detection` | object | 图像 AIGC 检测结果 |
| `video_aigc_detection` | object | 视频 AIGC 检测结果 |
| `multimodal_aigc_detection` | object | 多模态一致性与混合生成检测结果 |
| `aigc_detection_confidence` | number \| null | 对总体 AIGC 判断的置信度 |

### 7.3 `overall_aigc_label` 枚举值

| 枚举值 | 含义 |
|---|---|
| `ai_generated` | 整体上疑似 AI 生成 |
| `human_generated` | 整体上疑似人类生成 |
| `mixed` | 人工与 AI 混合生成 |
| `suspicious` | 存在可疑 AIGC 信号，但证据不足 |
| `unclear` | 无法判断 |

---

## 8. `text_aigc_detection`：文本 AIGC 检测

### 8.1 字段结构

```json
"text_aigc_detection": {
  "text_aigc_label": "ai_generated | human_generated | mixed | suspicious | unclear | not_applicable",
  "text_aigc_score": "number | null",
  "text_aigc_signal_labels": [],
  "text_aigc_confidence": "number | null",
  "evidence_ids": []
}
```

### 8.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `text_aigc_label` | enum | 文本部分的 AIGC 判断 |
| `text_aigc_score` | number \| null | 文本 AIGC 倾向分数 |
| `text_aigc_signal_labels` | array | 文本 AIGC 检测信号 |
| `text_aigc_confidence` | number \| null | 文本 AIGC 判断置信度 |
| `evidence_ids` | array | 支撑文本 AIGC 判断的证据 ID |

### 8.3 `text_aigc_label` 枚举值

| 枚举值 | 含义 |
|---|---|
| `ai_generated` | 文本疑似 AI 生成 |
| `human_generated` | 文本疑似人类生成 |
| `mixed` | 文本中存在人工与 AI 混合生成痕迹 |
| `suspicious` | 文本存在 AI 生成可疑信号，但证据不足 |
| `unclear` | 有文本，但无法判断 |
| `not_applicable` | 输入中没有文本 |

### 8.4 `text_aigc_signal_labels` 枚举值

| 枚举值 | 含义 |
|---|---|
| `ai_self_disclosure` | AI 自我暴露表达，例如 “As an AI language model” |
| `template_like_structure` | 模板化结构明显 |
| `generic_over_polished` | 表达过于泛化、平滑、缺少具体细节 |
| `repetitive_phrasing` | 重复句式或重复表达 |
| `unnatural_transition` | 机械化连接或转折 |
| `instruction_following_trace` | 指令跟随痕迹 |
| `mixed_style` | 前后文本风格明显混杂 |
| `none` | 无明显文本 AIGC 信号 |
| `unclear` | 信号不明确 |

### 8.5 边界说明

文本 AIGC 检测只判断文本部分。  
可使用的证据包括：原始正文、OCR 文本、视频字幕、视频转写文本、模型检测器输出的原始信号。

---

## 9. `image_aigc_detection`：图像 AIGC 检测

### 9.1 字段结构

```json
"image_aigc_detection": {
  "image_aigc_label": "ai_generated | human_generated | edited_or_manipulated | mixed | suspicious | unclear | not_applicable",
  "image_aigc_score": "number | null",
  "image_aigc_signal_labels": [],
  "image_aigc_confidence": "number | null",
  "evidence_ids": []
}
```

### 9.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `image_aigc_label` | enum | 图像部分的 AIGC 判断 |
| `image_aigc_score` | number \| null | 图像 AIGC 倾向分数 |
| `image_aigc_signal_labels` | array | 图像 AIGC 检测信号 |
| `image_aigc_confidence` | number \| null | 图像 AIGC 判断置信度 |
| `evidence_ids` | array | 支撑图像 AIGC 判断的证据 ID |

### 9.3 `image_aigc_label` 枚举值

| 枚举值 | 含义 |
|---|---|
| `ai_generated` | 图像疑似 AI 生成 |
| `human_generated` | 图像疑似真实拍摄或人工制作 |
| `edited_or_manipulated` | 图像疑似经过编辑或局部篡改 |
| `mixed` | 图像中存在 AI 生成与真实/人工编辑混合情况 |
| `suspicious` | 图像存在可疑信号，但证据不足 |
| `unclear` | 有图像，但无法判断 |
| `not_applicable` | 输入中没有图像 |

### 9.4 `image_aigc_signal_labels` 枚举值

| 枚举值 | 含义 |
|---|---|
| `visual_artifact` | 存在明显视觉伪影 |
| `face_inconsistency` | 人脸结构或纹理不一致 |
| `hand_or_body_anomaly` | 手部、肢体或身体结构异常 |
| `text_rendering_anomaly` | 图中文字渲染异常 |
| `lighting_shadow_inconsistency` | 光照或阴影不一致 |
| `background_distortion` | 背景扭曲或不自然 |
| `object_boundary_anomaly` | 物体边缘异常 |
| `metadata_anomaly` | 元数据异常 |
| `deepfake_signal` | 存在深度伪造信号 |
| `local_manipulation_signal` | 存在局部篡改信号 |
| `none` | 无明显图像 AIGC 信号 |
| `unclear` | 信号不明确 |

---

## 10. `video_aigc_detection`：视频 AIGC 检测

### 10.1 字段结构

```json
"video_aigc_detection": {
  "video_aigc_label": "ai_generated | human_generated | deepfake | edited_or_manipulated | mixed | suspicious | unclear | not_applicable",
  "video_aigc_score": "number | null",
  "video_aigc_signal_labels": [],
  "video_aigc_confidence": "number | null",
  "evidence_ids": []
}
```

### 10.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `video_aigc_label` | enum | 视频部分的 AIGC 判断 |
| `video_aigc_score` | number \| null | 视频 AIGC 倾向分数 |
| `video_aigc_signal_labels` | array | 视频 AIGC 检测信号 |
| `video_aigc_confidence` | number \| null | 视频 AIGC 判断置信度 |
| `evidence_ids` | array | 支撑视频 AIGC 判断的证据 ID |

### 10.3 `video_aigc_label` 枚举值

| 枚举值 | 含义 |
|---|---|
| `ai_generated` | 视频整体疑似 AI 生成 |
| `human_generated` | 视频整体疑似真实拍摄或人工制作 |
| `deepfake` | 视频中存在明显深度伪造迹象 |
| `edited_or_manipulated` | 视频疑似经过剪辑、篡改或局部编辑 |
| `mixed` | 视频中存在 AI 生成、真实内容、编辑内容混合情况 |
| `suspicious` | 视频存在可疑信号，但证据不足 |
| `unclear` | 有视频，但无法判断 |
| `not_applicable` | 输入中没有视频 |

### 10.4 `video_aigc_signal_labels` 枚举值

| 枚举值 | 含义 |
|---|---|
| `deepfake_signal` | 深度伪造信号 |
| `face_swap_signal` | 换脸信号 |
| `lip_sync_inconsistency` | 口型与语音不一致 |
| `audio_visual_mismatch` | 音画不一致 |
| `voice_synthesis_signal` | 合成语音信号 |
| `temporal_inconsistency` | 时间连续性异常 |
| `frame_artifact` | 帧级伪影 |
| `motion_anomaly` | 动作异常 |
| `lighting_shadow_inconsistency` | 光照或阴影不一致 |
| `background_distortion` | 背景异常或扭曲 |
| `scene_boundary_anomaly` | 场景边界异常 |
| `metadata_anomaly` | 元数据异常 |
| `local_manipulation_signal` | 局部篡改信号 |
| `none` | 无明显视频 AIGC 信号 |
| `unclear` | 信号不明确 |

---

## 11. `multimodal_aigc_detection`：多模态 AIGC 检测

### 11.1 字段结构

```json
"multimodal_aigc_detection": {
  "multimodal_aigc_label": "consistent | inconsistent | mixed_generated | suspicious | unclear | not_applicable",
  "modality_combination": "text_image | text_video | image_text_ocr | video_audio | video_subtitle | text_image_video | other | not_applicable",
  "multimodal_signal_labels": [],
  "multimodal_aigc_confidence": "number | null",
  "evidence_ids": []
}
```

### 11.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `multimodal_aigc_label` | enum | 多模态一致性或混合生成判断 |
| `modality_combination` | enum | 当前参与判断的模态组合 |
| `multimodal_signal_labels` | array | 多模态异常或一致性信号 |
| `multimodal_aigc_confidence` | number \| null | 多模态判断置信度 |
| `evidence_ids` | array | 支撑多模态判断的证据 ID |

### 11.3 `multimodal_aigc_label` 枚举值

| 枚举值 | 含义 |
|---|---|
| `consistent` | 多模态内容基本一致 |
| `inconsistent` | 多模态内容存在明显不一致 |
| `mixed_generated` | 不同模态疑似存在混合生成情况 |
| `suspicious` | 多模态存在可疑信号，但证据不足 |
| `unclear` | 多模态信息存在，但无法判断 |
| `not_applicable` | 输入不是多模态内容 |

### 11.4 `modality_combination` 枚举值

| 枚举值 | 含义 |
|---|---|
| `text_image` | 文本与图像组合 |
| `text_video` | 文本与视频组合 |
| `image_text_ocr` | 图像与 OCR 文本组合 |
| `video_audio` | 视频与音频组合 |
| `video_subtitle` | 视频与字幕组合 |
| `text_image_video` | 文本、图像、视频组合 |
| `other` | 其他多模态组合 |
| `not_applicable` | 不适用 |

### 11.5 `multimodal_signal_labels` 枚举值

| 枚举值 | 含义 |
|---|---|
| `text_image_mismatch` | 文本与图像内容不一致 |
| `text_video_mismatch` | 文本与视频内容不一致 |
| `image_ocr_mismatch` | 图像内容与 OCR 文本不一致 |
| `audio_visual_mismatch` | 音频与画面不一致 |
| `subtitle_visual_mismatch` | 字幕与画面不一致 |
| `caption_context_mismatch` | 配文与上下文不一致 |
| `cross_modal_source_mismatch` | 跨模态来源信息不一致 |
| `mixed_generation_signal` | 存在混合生成信号 |
| `none` | 无明显多模态异常信号 |
| `unclear` | 信号不明确 |

---

## 12. `annotations`：自动标注结果

### 12.1 整体结构

```json
"annotations": {
  "high_value_subjective": {},
  "basic_objective": {}
}
```

### 12.2 设计说明

`annotations` 是 T1 自动标注的核心输出。  
其中：

- `high_value_subjective` 用于承载主观认知判断；
- `basic_objective` 用于承载基础客观信息。

这一区分有利于后续：

1. 分别设计主观维度和客观维度的评测方法；
2. 区分高价值认知判断与基础辅助字段；
3. 与任务书中高价值主观维度和基础客观维度指标相对应；
4. 支持后续 T2、T3、T5 或集成组按需调用不同类别字段。

---

## 13. `high_value_subjective`：高价值主观认知维度

### 13.1 维度概览

```json
"high_value_subjective": {
  "ideology": {},
  "core_stance": {},
  "entities_hint_stance": [],
  "public_attitude": {},
  "opinion_emotion": {},
  "event_heat": {},
  "language_style": {},
  "content_purpose": {},
  "risk_level": {}
}
```

| 维度 | 功能 |
|---|---|
| `ideology` | 判断内容呈现出的意识形态倾向 |
| `core_stance` | 判断整条内容的总体立场 |
| `entities_hint_stance` | 标注实体/对象级立场指向 |
| `public_attitude` | 判断内容中体现的民众或公众群体态度 |
| `opinion_emotion` | 判断观点情绪和情感倾向 |
| `event_heat` | 判断事件热度 |
| `language_style` | 判断语言表达方式 |
| `content_purpose` | 判断内容发布或传播目的 |
| `risk_level` | 判断内容风险等级和风险类型 |

---

## 14. `ideology`：意识形态倾向

### 14.1 字段结构

```json
"ideology": {
  "ideology_label": "left_leaning | right_leaning | liberal | conservative | nationalist | populist | pro_government | anti_government | pro_western | anti_western | neutral | unclear | other",
  "target_entity_hint_ids": [],
  "ideology_confidence": "number | null",
  "evidence_ids": []
}
```

### 14.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `ideology_label` | enum | 意识形态倾向标签 |
| `target_entity_hint_ids` | array | 该意识形态判断关联的实体线索 ID |
| `ideology_confidence` | number \| null | 意识形态判断置信度 |
| `evidence_ids` | array | 支撑该判断的证据 ID |

### 14.3 `ideology_label` 枚举值

| 枚举值 | 含义 |
|---|---|
| `left_leaning` | 左倾 |
| `right_leaning` | 右倾 |
| `liberal` | 自由主义倾向 |
| `conservative` | 保守主义倾向 |
| `nationalist` | 民族主义倾向 |
| `populist` | 民粹主义倾向 |
| `pro_government` | 亲政府 |
| `anti_government` | 反政府 |
| `pro_western` | 亲西方 |
| `anti_western` | 反西方 |
| `neutral` | 中立或无明显意识形态倾向 |
| `unclear` | 无法判断 |
| `other` | 其他意识形态倾向 |

### 14.4 边界说明

`ideology` 只用于判断内容中体现出的意识形态倾向。  
它不用于判断作者长期政治属性，也不用于生成画像结论。

---

## 15. `core_stance`：内容级总体立场

### 15.1 字段结构

```json
"core_stance": {
  "stance_label": "support | oppose | neutral | mixed | unclear",
  "stance_strength": "weak | medium | strong | unclear",
  "core_stance_confidence": "number | null",
  "evidence_ids": []
}
```

### 15.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `stance_label` | enum | 整条内容的总体立场 |
| `stance_strength` | enum | 总体立场强度 |
| `core_stance_confidence` | number \| null | 总体立场判断置信度 |
| `evidence_ids` | array | 支撑总体立场判断的证据 ID |

### 15.3 `stance_label` 枚举值

| 枚举值 | 含义 |
|---|---|
| `support` | 总体支持 |
| `oppose` | 总体反对 |
| `neutral` | 总体中立 |
| `mixed` | 同时存在支持与反对，或多方立场混杂 |
| `unclear` | 无法判断总体立场 |

### 15.4 `stance_strength` 枚举值

| 枚举值 | 含义 |
|---|---|
| `weak` | 弱立场 |
| `medium` | 中等强度立场 |
| `strong` | 强立场 |
| `unclear` | 无法判断强度 |

### 15.5 边界说明

`core_stance` 表示整条内容的总体立场。  
如果一条内容中多个实体表达了不同立场，`core_stance` 可以为 `mixed`，具体实体/对象级立场由 `entities_hint_stance` 表达。

---

## 16. `entities_hint_stance`：实体/对象级立场标注

### 16.1 字段结构

```json
"entities_hint_stance": [
  {
    "stance_unit_id": "string",
    "stance_holder": {
      "stance_holder_id": "string | null",
      "text": "string | null"
    },
    "stance_target": {
      "stance_target_id": "string | null",
      "text": "string | null"
    },
    "stance_label": "support | oppose | neutral | mixed | unclear",
    "evidence_ids": []
  }
]
```

### 16.2 维度作用

`entities_hint_stance` 用于标注内容中出现的实体、人物、组织、账号、群体、事件、政策、议题等对象之间的立场指向。

它回答的问题是：

```text
谁 / 哪个实体 / 哪个说话人
对谁 / 哪个对象 / 哪个事件 / 哪个议题
表达了什么立场？
```

例如：

```text
人物 A 支持事件 X；
人物 B 反对政策 Y；
某组织批评某国家政府；
某账号对某事件态度中立。
```

### 16.3 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `stance_unit_id` | string | 单条实体/对象级立场标注的唯一 ID |
| `stance_holder` | object | 立场持有者 |
| `stance_holder.stance_holder_id` | string \| null | 立场持有者 ID，通常引用 `entities_hint.entity_hint_id` |
| `stance_holder.text` | string \| null | 立场持有者在原文中的表层文本 |
| `stance_target` | object | 立场指向对象 |
| `stance_target.stance_target_id` | string \| null | 立场对象 ID，通常引用 `entities_hint.entity_hint_id` |
| `stance_target.text` | string \| null | 立场对象在原文中的表层文本 |
| `stance_label` | enum | 实体/对象级立场 |
| `evidence_ids` | array | 支撑该条立场判断的证据 ID |

### 16.4 `stance_label` 枚举值

| 枚举值 | 含义 |
|---|---|
| `support` | 立场持有者支持立场对象 |
| `oppose` | 立场持有者反对立场对象 |
| `neutral` | 立场持有者对立场对象保持中立 |
| `mixed` | 立场表达复杂，同时存在支持和反对，或语义混杂 |
| `unclear` | 无法判断立场 |

### 16.5 ID 使用规则

`stance_holder_id` 和 `stance_target_id` 建议优先引用 `basic_objective.entities_hint[].entity_hint_id`。

示例：

```json
"entities_hint": [
  {
    "entity_hint_id": "ent_001",
    "text": "Person A",
    "type_hint": "persons",
    "span": [0, 8],
    "entity_hint_confidence": 0.88,
    "evidence_ids": ["ev_001"]
  },
  {
    "entity_hint_id": "ent_002",
    "text": "Policy X",
    "type_hint": "events",
    "span": [20, 28],
    "entity_hint_confidence": 0.82,
    "evidence_ids": ["ev_002"]
  }
]
```

则对应立场可以写为：

```json
"entities_hint_stance": [
  {
    "stance_unit_id": "stance_001",
    "stance_holder": {
      "stance_holder_id": "ent_001",
      "text": "Person A"
    },
    "stance_target": {
      "stance_target_id": "ent_002",
      "text": "Policy X"
    },
    "stance_label": "support",
    "evidence_ids": ["ev_003"]
  }
]
```

### 16.6 边界说明

`entities_hint_stance` 只做实体/对象级立场标注，不做以下任务：

- 不做实体级情绪标注；
- 不做实体级民众态度标注；
- 不做正式实体抽取；
- 不做正式关系抽取；
- 不做事件抽取；
- 不做实体归一；
- 不做跨平台实体对齐；
- 不做长期画像判断。

`stance_holder` 和 `stance_target` 都是 T1 标注所需的上下文线索。  
它们可以与 `entities_hint` 对齐，但不替代 T2 输出的正式实体或关系。

### 16.7 与 `core_stance` 的区别

| 字段 | 粒度 | 作用 |
|---|---|---|
| `core_stance` | 内容级 | 判断整条内容总体支持、反对、中立、混合或不明确 |
| `entities_hint_stance` | 实体/对象级 | 判断某个实体或说话人对某个对象的具体立场 |

示例：

```text
A 支持政策 X，但 B 反对政策 X。
```

可以输出：

```json
"core_stance": {
  "stance_label": "mixed"
}
```

同时输出：

```json
"entities_hint_stance": [
  {
    "stance_holder": {"text": "A"},
    "stance_target": {"text": "政策 X"},
    "stance_label": "support"
  },
  {
    "stance_holder": {"text": "B"},
    "stance_target": {"text": "政策 X"},
    "stance_label": "oppose"
  }
]
```

---

## 17. `public_attitude`：民众态度

### 17.1 字段结构

```json
"public_attitude": {
  "public_group": "general_public | netizens | local_residents | protesters | supporters | opponents | consumers | voters | community_members | unclear | not_applicable",
  "attitude_label": "supportive | approving | critical | distrustful | hostile | sympathetic | concerned | dissatisfied | fearful | mocking | indifferent | mixed | unclear | not_applicable",
  "attitude_intensity": "low | medium | high | unclear | not_applicable",
  "public_attitude_confidence": "number | null",
  "evidence_ids": []
}
```

### 17.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `public_group` | enum | 民众或公众群体类型 |
| `attitude_label` | enum | 民众态度标签 |
| `attitude_intensity` | enum | 民众态度强度 |
| `public_attitude_confidence` | number \| null | 民众态度判断置信度 |
| `evidence_ids` | array | 支撑民众态度判断的证据 ID |

### 17.3 `public_group` 枚举值

| 枚举值 | 含义 |
|---|---|
| `general_public` | 泛指普通民众 |
| `netizens` | 网民 |
| `local_residents` | 当地居民 |
| `protesters` | 抗议者 |
| `supporters` | 支持者群体 |
| `opponents` | 反对者群体 |
| `consumers` | 消费者 |
| `voters` | 选民 |
| `community_members` | 社区成员 |
| `unclear` | 群体不明确 |
| `not_applicable` | 不涉及民众态度 |

### 17.4 `attitude_label` 枚举值

| 枚举值 | 含义 |
|---|---|
| `supportive` | 支持 |
| `approving` | 认可 |
| `critical` | 批评 |
| `distrustful` | 不信任 |
| `hostile` | 敌对 |
| `sympathetic` | 同情 |
| `concerned` | 担忧 |
| `dissatisfied` | 不满 |
| `fearful` | 恐惧 |
| `mocking` | 嘲讽 |
| `indifferent` | 冷漠 |
| `mixed` | 态度混合 |
| `unclear` | 无法判断 |
| `not_applicable` | 不适用 |

### 17.5 `attitude_intensity` 枚举值

| 枚举值 | 含义 |
|---|---|
| `low` | 弱态度 |
| `medium` | 中等强度态度 |
| `high` | 强态度 |
| `unclear` | 无法判断强度 |
| `not_applicable` | 不适用 |

### 17.6 边界说明

`public_attitude` 只用于判断内容中体现出的公众或民众群体态度。  
如果只是单个实体对另一个实体的支持或反对，应使用 `entities_hint_stance`。  
如果是整条内容的总体立场，应使用 `core_stance`。

---

## 18. `opinion_emotion`：观点情绪

### 18.1 字段结构

```json
"opinion_emotion": {
  "sentiment_polarity": "positive | negative | neutral | mixed | unclear",
  "emotion_labels": [
    "anger | fear | sadness | anxiety | disgust | contempt | joy | hope | sympathy | surprise | sarcasm | none | unclear"
  ],
  "emotion_intensity": "low | medium | high | unclear",
  "opinion_emotion_confidence": "number | null",
  "evidence_ids": []
}
```

### 18.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `sentiment_polarity` | enum | 整体情感极性 |
| `emotion_labels` | array | 情绪标签，可多选 |
| `emotion_intensity` | enum | 情绪强度 |
| `opinion_emotion_confidence` | number \| null | 情绪判断置信度 |
| `evidence_ids` | array | 支撑情绪判断的证据 ID |

### 18.3 `sentiment_polarity` 枚举值

| 枚举值 | 含义 |
|---|---|
| `positive` | 正向 |
| `negative` | 负向 |
| `neutral` | 中性 |
| `mixed` | 正负情感混合 |
| `unclear` | 无法判断 |

### 18.4 `emotion_labels` 枚举值

| 枚举值 | 含义 |
|---|---|
| `anger` | 愤怒 |
| `fear` | 恐惧 |
| `sadness` | 悲伤 |
| `anxiety` | 焦虑 |
| `disgust` | 厌恶 |
| `contempt` | 轻蔑 |
| `joy` | 喜悦 |
| `hope` | 希望 |
| `sympathy` | 同情 |
| `surprise` | 惊讶 |
| `sarcasm` | 讽刺 |
| `none` | 无明显情绪 |
| `unclear` | 无法判断 |

### 18.5 `emotion_intensity` 枚举值

| 枚举值 | 含义 |
|---|---|
| `low` | 弱情绪 |
| `medium` | 中等强度情绪 |
| `high` | 强情绪 |
| `unclear` | 无法判断强度 |

### 18.6 边界说明

`opinion_emotion` 表示内容级观点情绪。  
它不用于判断某个实体对某个对象的实体级情绪。

---

## 19. `event_heat`：事件热度

### 19.1 字段结构

```json
"event_heat": {
  "heat_level": "low | medium | high | explosive | unclear",
  "heat_score": "number | null",
  "heat_signal_types": [
    "textual_heat_signal | engagement_metrics | platform_trending_signal | media_coverage_signal | temporal_burst_signal | unclear"
  ],
  "event_heat_confidence": "number | null",
  "evidence_ids": []
}
```

### 19.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `heat_level` | enum | 事件热度等级 |
| `heat_score` | number \| null | 事件热度分数 |
| `heat_signal_types` | array | 热度判断所依据的信号类型 |
| `event_heat_confidence` | number \| null | 热度判断置信度 |
| `evidence_ids` | array | 支撑热度判断的证据 ID |

### 19.3 `heat_level` 枚举值

| 枚举值 | 含义 |
|---|---|
| `low` | 低热度 |
| `medium` | 中等热度 |
| `high` | 高热度 |
| `explosive` | 爆发式热度 |
| `unclear` | 无法判断 |

### 19.4 `heat_signal_types` 枚举值

| 枚举值 | 含义 |
|---|---|
| `textual_heat_signal` | 文本中出现“爆火”“刷屏”等热度表达 |
| `engagement_metrics` | 互动量、点赞、评论、转发等指标 |
| `platform_trending_signal` | 平台热搜、趋势榜等信号 |
| `media_coverage_signal` | 媒体报道频次信号 |
| `temporal_burst_signal` | 短时间集中爆发信号 |
| `unclear` | 信号不明确 |

### 19.5 边界说明

如果输入没有互动量、传播量或平台热度信息，可以基于文本热度表达进行初步判断，但应降低置信度。  
如果完全缺少相关依据，应输出 `unclear`。

---

## 20. `language_style`：语言表达方式

### 20.1 字段结构

```json
"language_style": {
  "style_labels": [
    "neutral | aggressive | sarcastic | mocking | alarmist | threatening | sensationalized | emotional | conspiratorial | accusatory | slogan_like | rhetorical_questioning | rational_analytical | unclear"
  ],
  "language_style_confidence": "number | null",
  "evidence_ids": []
}
```

### 20.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `style_labels` | array | 语言表达方式标签，可多选 |
| `language_style_confidence` | number \| null | 表达方式判断置信度 |
| `evidence_ids` | array | 支撑表达方式判断的证据 ID |

### 20.3 `style_labels` 枚举值

| 枚举值 | 含义 |
|---|---|
| `neutral` | 中性表达 |
| `aggressive` | 攻击性表达 |
| `sarcastic` | 讽刺 |
| `mocking` | 嘲弄 |
| `alarmist` | 制造恐慌式表达 |
| `threatening` | 威胁性表达 |
| `sensationalized` | 标题党或夸张渲染 |
| `emotional` | 情绪化表达 |
| `conspiratorial` | 阴谋论式表达 |
| `accusatory` | 指控式表达 |
| `slogan_like` | 口号式表达 |
| `rhetorical_questioning` | 反问式表达 |
| `rational_analytical` | 理性分析式表达 |
| `unclear` | 无法判断 |

### 20.4 边界说明

`language_style` 只描述表达方式，不直接判断内容目的。  
内容是否是宣传、动员、攻击、辟谣，应由 `content_purpose` 表达。

---

## 21. `content_purpose`：内容目的

### 21.1 字段结构

```json
"content_purpose": {
  "primary_purpose": "information_sharing | opinion_expression | persuasion | mobilization | propaganda | attack_or_smear | debunking | warning | attention_seeking | rumor_spreading | unclear",
  "secondary_purposes": [
    "information_sharing | opinion_expression | persuasion | mobilization | propaganda | attack_or_smear | debunking | warning | attention_seeking | rumor_spreading | unclear"
  ],
  "content_purpose_confidence": "number | null",
  "evidence_ids": []
}
```

### 21.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `primary_purpose` | enum | 内容主要目的 |
| `secondary_purposes` | array | 内容次要目的，可多选 |
| `content_purpose_confidence` | number \| null | 内容目的判断置信度 |
| `evidence_ids` | array | 支撑内容目的判断的证据 ID |

### 21.3 `primary_purpose` / `secondary_purposes` 枚举值

| 枚举值 | 含义 |
|---|---|
| `information_sharing` | 信息分享 |
| `opinion_expression` | 观点表达 |
| `persuasion` | 说服他人 |
| `mobilization` | 动员行动 |
| `propaganda` | 宣传 |
| `attack_or_smear` | 攻击或抹黑 |
| `debunking` | 辟谣或澄清 |
| `warning` | 警告或提醒 |
| `attention_seeking` | 吸引关注 |
| `rumor_spreading` | 传播谣言 |
| `unclear` | 无法判断 |

### 21.4 边界说明

`content_purpose` 判断内容“想要达成什么目的”。  
它不等同于 `language_style`。

---

## 22. `risk_level`：风险等级

### 22.1 字段结构

```json
"risk_level": {
  "risk_label": "none | low | medium | high | severe | unclear",
  "risk_types": [
    "misinformation | rumor | polarization | hostility | panic_amplification | mobilization_risk | reputation_attack | manipulation | aigc_deception | none | unclear"
  ],
  "risk_level_confidence": "number | null",
  "evidence_ids": []
}
```

### 22.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `risk_label` | enum | 风险等级 |
| `risk_types` | array | 风险类型，可多选 |
| `risk_level_confidence` | number \| null | 风险判断置信度 |
| `evidence_ids` | array | 支撑风险判断的证据 ID |

### 22.3 `risk_label` 枚举值

| 枚举值 | 含义 |
|---|---|
| `none` | 无明显风险 |
| `low` | 低风险 |
| `medium` | 中风险 |
| `high` | 高风险 |
| `severe` | 严重风险 |
| `unclear` | 无法判断 |

### 22.4 `risk_types` 枚举值

| 枚举值 | 含义 |
|---|---|
| `misinformation` | 错误信息 |
| `rumor` | 谣言 |
| `polarization` | 加剧对立 |
| `hostility` | 敌意或仇恨表达 |
| `panic_amplification` | 放大恐慌 |
| `mobilization_risk` | 动员风险 |
| `reputation_attack` | 声誉攻击 |
| `manipulation` | 操纵性内容 |
| `aigc_deception` | AIGC 欺骗风险 |
| `none` | 无明显风险 |
| `unclear` | 风险类型不明确 |

### 22.5 边界说明

`risk_level` 是 T1 的内容风险提示，不等同于最终安全处置结论。  
后续是否预警、拦截、上报或进入专家审核，应由系统规则或人工流程决定。

---

## 23. `basic_objective`：基础客观标注维度

### 23.1 维度概览

```json
"basic_objective": {
  "topic_tags": {},
  "account_type": {},
  "entities_hint": [],
  "keywords": [],
  "summary": {},
  "event_type": {}
}
```

| 维度 | 功能 |
|---|---|
| `topic_tags` | 话题标签 |
| `account_type` | 账号类别 |
| `entities_hint` | 实体线索 |
| `keywords` | 关键词 |
| `summary` | 摘要 |
| `event_type` | 事件类型 |

---

## 24. `topic_tags`：话题标签

### 24.1 字段结构

```json
"topic_tags": {
  "primary_domain": "politics | military | economy_finance | technology_cyber | public_health | social_livelihood | ethnic_religious | energy_environment | disaster_accident | crime_public_safety | culture_education | migration_refugee | other | unclear",
  "subtopic_tags": [],
  "topic_tags_confidence": "number | null",
  "evidence_ids": []
}
```

### 24.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `primary_domain` | enum | 一级话题领域 |
| `subtopic_tags` | array | 细分话题标签 |
| `topic_tags_confidence` | number \| null | 话题判断置信度 |
| `evidence_ids` | array | 支撑话题判断的证据 ID |

### 24.3 `primary_domain` 枚举值

| 枚举值 | 含义 |
|---|---|
| `politics` | 政治 |
| `military` | 军事 |
| `economy_finance` | 经济金融 |
| `technology_cyber` | 科技与网络安全 |
| `public_health` | 公共卫生 |
| `social_livelihood` | 社会民生 |
| `ethnic_religious` | 民族宗教 |
| `energy_environment` | 能源环境 |
| `disaster_accident` | 灾害事故 |
| `crime_public_safety` | 犯罪与公共安全 |
| `culture_education` | 文化教育 |
| `migration_refugee` | 移民与难民 |
| `other` | 其他 |
| `unclear` | 无法判断 |

### 24.4 `subtopic_tags` 使用说明

`subtopic_tags` 是细分话题标签列表。  
当前不强制固定枚举，后续可根据数据和任务需要维护受控词表。

示例：

```json
"subtopic_tags": ["election", "border_security", "public_protest"]
```

---

## 25. `account_type`：账号类别

### 25.1 字段结构

```json
"account_type": {
  "primary_account_category": "ordinary_user | news_media | state_affiliated_media | government_agency | political_actor | political_party_or_campaign | military_security_agency | international_organization | ngo_or_civil_society | academic_or_expert | commercial_brand | platform_official | influencer_kol | community_group | anonymous_account | suspected_bot_or_automated | unknown | other",
  "account_subtype_tags": [],
  "automation_suspicion": "none | low | medium | high | unclear",
  "account_type_confidence": "number | null",
  "evidence_ids": []
}
```

### 25.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `primary_account_category` | enum | 账号主类别 |
| `account_subtype_tags` | array | 账号细分类标签 |
| `automation_suspicion` | enum | 自动化或机器人嫌疑 |
| `account_type_confidence` | number \| null | 账号类别判断置信度 |
| `evidence_ids` | array | 支撑账号类别判断的证据 ID |

### 25.3 `primary_account_category` 枚举值

| 枚举值 | 含义 |
|---|---|
| `ordinary_user` | 普通用户 |
| `news_media` | 新闻媒体 |
| `state_affiliated_media` | 国家关联媒体 |
| `government_agency` | 政府机构 |
| `political_actor` | 政治人物或政治行动者 |
| `political_party_or_campaign` | 政党或竞选组织 |
| `military_security_agency` | 军事或安全机构 |
| `international_organization` | 国际组织 |
| `ngo_or_civil_society` | 非政府组织或民间组织 |
| `academic_or_expert` | 学者或专家 |
| `commercial_brand` | 商业品牌 |
| `platform_official` | 平台官方账号 |
| `influencer_kol` | 网红或意见领袖 |
| `community_group` | 社区或群组账号 |
| `anonymous_account` | 匿名账号 |
| `suspected_bot_or_automated` | 疑似机器人或自动化账号 |
| `unknown` | 未知账号类别 |
| `other` | 其他账号类别 |

### 25.4 `automation_suspicion` 枚举值

| 枚举值 | 含义 |
|---|---|
| `none` | 无自动化嫌疑 |
| `low` | 低自动化嫌疑 |
| `medium` | 中等自动化嫌疑 |
| `high` | 高自动化嫌疑 |
| `unclear` | 无法判断 |

### 25.5 `account_subtype_tags` 使用说明

`account_subtype_tags` 用于保存更细粒度的账号标签。  
当前不强制固定枚举，可根据平台元数据、账号简介、认证信息、发帖行为和人工规则扩展。

---

## 26. `entities_hint`：实体线索

### 26.1 字段结构

```json
"entities_hint": [
  {
    "entity_hint_id": "string",
    "text": "string",
    "type_hint": "persons | organizations | events | locations | media_contents | social_accounts | narratives | others | unknown",
    "span": [0, 10],
    "entity_hint_confidence": "number | null",
    "evidence_ids": []
  }
]
```

### 26.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `entity_hint_id` | string | 实体线索 ID |
| `text` | string | 实体在原文中的表层文本 |
| `type_hint` | enum | 实体类型线索 |
| `span` | array | 实体文本在原文中的字符位置 |
| `entity_hint_confidence` | number \| null | 实体线索识别置信度 |
| `evidence_ids` | array | 支撑实体线索的证据 ID |

### 26.3 `type_hint` 枚举值

| 枚举值 | 含义 |
|---|---|
| `persons` | 人物 |
| `organizations` | 组织或机构 |
| `events` | 事件 |
| `locations` | 地点 |
| `media_contents` | 媒体内容 |
| `social_accounts` | 社交账号 |
| `narratives` | 叙事线索或叙事对象类型占位 |
| `others` | 其他实体 |
| `unknown` | 未知类型 |

### 26.4 `span` 使用说明

`span` 表示实体文本在原始文本中的字符位置：

```json
"span": [start, end]
```

其中：

- `start` 为起始字符位置；
- `end` 为结束字符位置；
- 建议使用左闭右开区间；
- 如果无法定位，可以设置为 `null` 或空数组，具体由实现统一约定。

### 26.5 边界说明

`entities_hint` 是实体线索，不是正式实体抽取结果。  
它的作用是为 T1 的立场、话题、风险、证据定位等任务提供辅助。

正式实体规范化、实体消歧、关系抽取和事件抽取不由 `entities_hint` 完成。

---

## 27. `keywords`：关键词

### 27.1 字段结构

```json
"keywords": [
  {
    "keyword_text": "string",
    "source": "text | image_ocr | video_transcript | metadata | unknown",
    "span": [0, 10],
    "keyword_confidence": "number | null",
    "evidence_ids": []
  }
]
```

### 27.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `keyword_text` | string | 关键词文本 |
| `source` | enum | 关键词来源 |
| `span` | array | 关键词在原文中的字符位置 |
| `keyword_confidence` | number \| null | 关键词提取置信度 |
| `evidence_ids` | array | 支撑关键词的证据 ID |

### 27.3 `source` 枚举值

| 枚举值 | 含义 |
|---|---|
| `text` | 原始文本 |
| `image_ocr` | 图像 OCR 文本 |
| `video_transcript` | 视频转写文本 |
| `metadata` | 元数据 |
| `unknown` | 未知来源 |

### 27.4 边界说明

`keywords` 只记录对内容理解有帮助的关键词。  
不要求输出所有名词，也不替代实体抽取。

---

## 28. `summary`：摘要

### 28.1 字段结构

```json
"summary": {
  "summary_text": "string | null",
  "summary_confidence": "number | null"
}
```

### 28.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `summary_text` | string \| null | 内容摘要 |
| `summary_confidence` | number \| null | 摘要生成置信度 |

### 28.3 边界说明

`summary` 是客观摘要，不是观点扩展。  
它不能加入原文没有的信息。  
它不设置 `evidence_ids`，但生成时必须严格依据原始内容。

---

## 29. `event_type`：事件类型

### 29.1 字段结构

```json
"event_type": {
  "event_type_label": "military_conflict | diplomatic_dispute | policy_announcement | election_campaign | protest_demonstration | economic_sanction | cyber_incident | public_health_event | disaster_accident | crime_public_safety | social_livelihood_event | public_opinion_event | other | unclear | not_applicable",
  "event_type_confidence": "number | null",
  "evidence_ids": []
}
```

### 29.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `event_type_label` | enum | 事件类型 |
| `event_type_confidence` | number \| null | 事件类型判断置信度 |
| `evidence_ids` | array | 支撑事件类型判断的证据 ID |

### 29.3 `event_type_label` 枚举值

| 枚举值 | 含义 |
|---|---|
| `military_conflict` | 军事冲突 |
| `diplomatic_dispute` | 外交争端 |
| `policy_announcement` | 政策发布 |
| `election_campaign` | 选举或竞选 |
| `protest_demonstration` | 抗议或示威 |
| `economic_sanction` | 经济制裁 |
| `cyber_incident` | 网络安全事件 |
| `public_health_event` | 公共卫生事件 |
| `disaster_accident` | 灾害事故 |
| `crime_public_safety` | 犯罪或公共安全事件 |
| `social_livelihood_event` | 社会民生事件 |
| `public_opinion_event` | 舆情事件 |
| `other` | 其他事件 |
| `unclear` | 无法判断 |
| `not_applicable` | 不涉及事件 |

---

## 30. `evidence_clues`：原始证据池

### 30.1 字段结构

```json
"evidence_clues": [
  {
    "evidence_id": "string",
    "evidence_type": "text_span | image_region | video_segment | video_frame_region | metadata | model_signal",
    "source": "text | image | video | audio | ocr | subtitle | metadata | aigc_detector",
    "evidence_text": "string | null",
    "span": [0, 10],
    "media_id": "string | null",
    "region": {
      "x": "number",
      "y": "number",
      "width": "number",
      "height": "number"
    },
    "time_range": {
      "start": "number",
      "end": "number"
    },
    "metadata_snapshot": {},
    "model_signal": {}
  }
]
```

### 30.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `evidence_id` | string | 证据 ID |
| `evidence_type` | enum | 证据类型 |
| `source` | enum | 证据来源 |
| `evidence_text` | string \| null | 文本形式证据内容 |
| `span` | array | 文本证据字符位置 |
| `media_id` | string \| null | 媒体资源 ID |
| `region` | object | 图像或视频帧区域 |
| `time_range` | object | 视频或音频时间段 |
| `metadata_snapshot` | object | 元数据快照 |
| `model_signal` | object | 检测器原始信号 |

### 30.3 `evidence_type` 枚举值

| 枚举值 | 含义 |
|---|---|
| `text_span` | 文本片段 |
| `image_region` | 图像区域 |
| `video_segment` | 视频时间段 |
| `video_frame_region` | 视频帧区域 |
| `metadata` | 元数据 |
| `model_signal` | 模型或检测器原始信号 |

### 30.4 `source` 枚举值

| 枚举值 | 含义 |
|---|---|
| `text` | 原始文本 |
| `image` | 图像 |
| `video` | 视频 |
| `audio` | 音频 |
| `ocr` | OCR 文本 |
| `subtitle` | 字幕 |
| `metadata` | 元数据 |
| `aigc_detector` | AIGC 检测器输出 |

### 30.5 `region` 字段说明

```json
"region": {
  "x": "number",
  "y": "number",
  "width": "number",
  "height": "number"
}
```

| 字段 | 含义 |
|---|---|
| `x` | 区域左上角横坐标 |
| `y` | 区域左上角纵坐标 |
| `width` | 区域宽度 |
| `height` | 区域高度 |

### 30.6 `time_range` 字段说明

```json
"time_range": {
  "start": "number",
  "end": "number"
}
```

| 字段 | 含义 |
|---|---|
| `start` | 起始时间，单位建议为秒 |
| `end` | 结束时间，单位建议为秒 |

### 30.7 证据池使用规则

1. 所有证据必须来自原始输入或检测器原始输出；
2. 所有 `evidence_ids` 必须能在 `evidence_clues` 中找到对应 `evidence_id`；
3. 一个证据可以被多个字段复用；
4. 一个字段可以引用多个证据；
5. 不允许把模型自由生成的解释文本作为证据；
6. 摘要不强制挂证据；
7. AIGC 顶层不重复挂证据，子模块自行挂证据。

---

## 31. `quality_control`：质量控制

### 31.1 字段结构

```json
"quality_control": {
  "need_human_review": "boolean",
  "review_reasons": [
    "low_confidence | insufficient_context | conflicting_signals | multimodal_inconsistency | aigc_suspicious | high_risk_content | missing_metadata | module_failure | too_short_input | manual_policy_required | none | other"
  ],
  "failed_modules": [
    "text_aigc_detection | image_aigc_detection | video_aigc_detection | multimodal_aigc_detection | ideology | core_stance | entities_hint_stance | public_attitude | opinion_emotion | event_heat | language_style | content_purpose | risk_level | topic_tags | account_type | entities_hint | keywords | summary | event_type | none | other"
  ]
}
```

### 31.2 字段说明

| 字段 | 类型 | 含义 |
|---|---|---|
| `need_human_review` | boolean | 是否需要人工复核 |
| `review_reasons` | array | 需要人工复核的原因 |
| `failed_modules` | array | 执行失败或未正常返回的模块 |

### 31.3 `review_reasons` 枚举值

| 枚举值 | 含义 |
|---|---|
| `low_confidence` | 置信度过低 |
| `insufficient_context` | 上下文不足 |
| `conflicting_signals` | 多个判断信号冲突 |
| `multimodal_inconsistency` | 多模态信息不一致 |
| `aigc_suspicious` | AIGC 可疑 |
| `high_risk_content` | 内容风险较高 |
| `missing_metadata` | 缺少关键元数据 |
| `module_failure` | 模块执行失败 |
| `too_short_input` | 输入内容过短 |
| `manual_policy_required` | 需要人工规则或专家判断 |
| `none` | 无需复核 |
| `other` | 其他原因 |

### 31.4 `failed_modules` 枚举值

| 枚举值 | 含义 |
|---|---|
| `text_aigc_detection` | 文本 AIGC 检测模块失败 |
| `image_aigc_detection` | 图像 AIGC 检测模块失败 |
| `video_aigc_detection` | 视频 AIGC 检测模块失败 |
| `multimodal_aigc_detection` | 多模态 AIGC 检测模块失败 |
| `ideology` | 意识形态判断模块失败 |
| `core_stance` | 内容级总体立场模块失败 |
| `entities_hint_stance` | 实体/对象级立场标注模块失败 |
| `public_attitude` | 民众态度模块失败 |
| `opinion_emotion` | 观点情绪模块失败 |
| `event_heat` | 事件热度模块失败 |
| `language_style` | 语言表达方式模块失败 |
| `content_purpose` | 内容目的模块失败 |
| `risk_level` | 风险等级模块失败 |
| `topic_tags` | 话题标签模块失败 |
| `account_type` | 账号类别模块失败 |
| `entities_hint` | 实体线索模块失败 |
| `keywords` | 关键词模块失败 |
| `summary` | 摘要模块失败 |
| `event_type` | 事件类型模块失败 |
| `none` | 无失败模块 |
| `other` | 其他模块失败 |

### 31.5 边界说明

`quality_control` 只用于质量控制和人工复核提示。  
它不存放具体标注结果，不存放证据内容，不替代 `evidence_clues`。

---

## 32. `overall_confidence`：整体置信度

### 32.1 字段结构

```json
"overall_confidence": "number | null"
```

### 32.2 字段说明

`overall_confidence` 表示 T1 对整条输出结果的总体置信度。

它可以综合以下因素计算：

- 各模块置信度；
- 证据充分性；
- 模块是否失败；
- 是否存在多模态冲突；
- 是否触发人工复核；
- 输入是否过短；
- 元数据是否缺失。

### 32.3 使用规则

1. 建议取值范围为 0 到 1；
2. 如果暂时没有统一计算方法，可以设置为 `null`；
3. 不应简单等同于某一个字段的置信度；
4. 不应等同于 `overall_aigc_score`。

---

## 33. `processed_at`：处理时间

### 33.1 字段结构

```json
"processed_at": "string"
```

### 33.2 字段说明

`processed_at` 表示 T1 完成该条内容处理的时间。

建议格式：

```text
YYYY-MM-DDTHH:MM:SSZ
```

示例：

```json
"processed_at": "2026-06-18T10:30:00Z"
```

---

## 34. 代码实现约束

### 34.1 代码返回契约与集成框架适配说明

本文档主要约束 T1 自动标注模块的**业务输出字段契约**，即 T1 最终应稳定产出哪些字段、字段之间如何嵌套、字段枚举如何取值、证据如何引用、质量控制如何表达。

当前 README 中展示的 JSON 结构用于说明 `T1_annotation_v0.5` 的字段组织方式，便于标签体系审核、前端展示样例、接口讨论、测试样例编写和后续验收材料整理。它不等同于要求 T1 Agent 在集成组框架中必须直接返回 JSON 字符串，也不等同于要求当前代码阶段必须额外写出 `.json` 文件。

涉及集成组代码框架的内容，应以**最新 GitHub 仓库中的实际实现**为准，包括但不限于：

1. T1 Agent action 在当前框架中应返回的 Python 类型；
2. `BaseAgent`、`AgentManager`、orchestrator 或 API 层对返回值的校验方式；
3. 框架是否自动封装 `AgentResponse`；
4. T1 返回结果最终在接口响应中位于哪一层，例如是否进入 `data.output_data`；
5. 是否需要手动添加框架外层信封；
6. 是否需要为前端、离线测试或验收测试额外导出 JSON 文件；
7. 代码实现中 action、tool、skill、adapter 之间的具体分工。

这些内容不在本 README 中写死。后续进入代码适配、联调、PR 修改或验收测试准备阶段时，应让 Claude Code 结合用户本地最新 GitHub 仓库进行具体分析，重点检查集成组最新的 `base_agent.py`、`AgentManager`、接口规约、测试用例、T1 Agent 实现和前后端调用链路，再据此确定代码返回形式和适配方式。

因此，本 README 对代码实现层面的稳定要求是：

1. T1 输出的**业务字段结构**应符合本文档定义的 `T1_annotation_v0.5`；
2. `schema_version`、`input_reference`、`aigc_detection`、`annotations`、`evidence_clues`、`quality_control`、`overall_confidence`、`processed_at` 等字段契约应保持稳定；
3. 各字段枚举值、证据引用方式、置信度表达和质量控制规则应遵循本文档；
4. 集成组框架要求的外层封装、返回类型校验、接口响应层级和文件导出方式，后续由 Claude Code 基于最新仓库确认后实现；
5. 不应仅凭本 README 中的展示 JSON 推断框架层返回类型或 API 外层结构。

后续如果需要生成 `.json` 文件，应将其视为展示、调试、离线评测或验收测试的辅助产物；是否生成、何时生成、由 action 直接生成还是由额外脚本生成，应在代码实现阶段结合最新集成框架再确定。

### 34.2 所有顶层字段必须保留

即使某些模块当前未实现，也应保留字段结构，并使用：

- `null`
- `[]`
- `unclear`
- `not_applicable`
- `failed_modules`

来表达当前状态。

### 34.3 不允许随意新增顶层字段

后续代码实现时，不应随意新增顶层字段。  
如确需新增，应先修改 schema 和 README，再进入代码实现。

### 34.4 枚举值必须使用本文档定义

代码输出时应严格使用本文档列出的枚举值。  
不应输出中文枚举、大小写混用枚举、同义词枚举或未定义枚举。

不推荐：

```json
"stance_label": "支持"
```

推荐：

```json
"stance_label": "support"
```

### 34.5 证据 ID 必须可追溯

所有字段中的 `evidence_ids` 必须能在 `evidence_clues` 中找到对应证据。

例如：

```json
"core_stance": {
  "stance_label": "oppose",
  "evidence_ids": ["ev_001"]
}
```

则必须存在：

```json
"evidence_clues": [
  {
    "evidence_id": "ev_001",
    "evidence_type": "text_span",
    "source": "text",
    "evidence_text": "原始证据片段"
  }
]
```

### 34.6 模型输出与本地后处理分工

建议实现方式：

1. 模型负责判断标签、生成摘要、识别候选实体文本；
2. 本地代码负责 JSON 解析、字段补全、枚举校验、span 计算、evidence_id 绑定；
3. 本地代码负责失败降级；
4. 本地代码负责 `failed_modules` 和 `quality_control` 补全；
5. 本地代码负责最终 schema 校验。

### 34.7 LLM 失败时的降级策略

如果 LLM 调用失败、超时、解析失败或返回非法 JSON，应：

1. 保留顶层结构；
2. 对未完成模块填入 `unclear`、`null` 或空数组；
3. 在 `failed_modules` 中记录失败模块；
4. 将 `need_human_review` 设置为 `true`；
5. 在 `review_reasons` 中加入 `module_failure` 或 `low_confidence`。

---

## 35. 与 T2 / 集成组的边界关系

### 35.1 与 T2 的关系

T1 输出标注线索，T2 输出正式抽取结果。

| 模块 | 主要职责 |
|---|---|
| T1 | 标注话题、立场、态度、情绪、风险、AIGC、账号类别、实体线索和实体/对象级立场 |
| T2 | 抽取正式实体、关系、事件、叙事信号和结构化知识单元 |

`entities_hint` 和 `entities_hint_stance` 可以被 T2 参考或调用，但不要求 T2 必须依赖 T1 输出。  
T2 也可以调用 T1 的某个功能函数，例如实体/对象级立场检测函数。

### 35.2 与集成组的关系

集成组可使用本结构进行：

1. 后端接口适配；
2. 前端展示；
3. 数据库字段映射；
4. Agent 输出校验；
5. 示例数据构造；
6. 测试用例编写；
7. 模块联调。

如果集成组接口字段与本结构不完全一致，应优先通过适配器转换，而不是直接压缩 T1 标签体系。

---

## 36. 最小可用输出要求

后续代码实现时，至少应保证以下内容可稳定输出：

1. `schema_version`
2. `input_reference.content_id`
3. `language`
4. `aigc_detection`
5. `annotations.high_value_subjective.ideology`
6. `annotations.high_value_subjective.core_stance`
7. `annotations.high_value_subjective.entities_hint_stance`
8. `annotations.high_value_subjective.public_attitude`
9. `annotations.high_value_subjective.opinion_emotion`
10. `annotations.high_value_subjective.event_heat`
11. `annotations.basic_objective.topic_tags`
12. `annotations.basic_objective.account_type`
13. `annotations.basic_objective.entities_hint`
14. `annotations.basic_objective.summary`
15. `evidence_clues`
16. `quality_control`
17. `overall_confidence`
18. `processed_at`

如部分能力尚未实现，应显式标记为 `unclear`、`null`、空数组或写入 `failed_modules`，不能删除字段。

---

## 37. 完整输出结构示意

```json
{
  "schema_version": "t1_annotation_v0.5",
  "input_reference": {
    "content_id": "string",
    "content_type": "text | image | video | text_image_mixed",
    "platform": "string | null",
    "url": "string | null",
    "author_id": "string | null",
    "created_at": "string | null"
  },
  "language": "string",
  "aigc_detection": {
    "overall_aigc_label": "ai_generated | human_generated | mixed | suspicious | unclear",
    "overall_aigc_score": "number | null",
    "text_aigc_detection": {},
    "image_aigc_detection": {},
    "video_aigc_detection": {},
    "multimodal_aigc_detection": {},
    "aigc_detection_confidence": "number | null"
  },
  "annotations": {
    "high_value_subjective": {
      "ideology": {},
      "core_stance": {},
      "entities_hint_stance": [],
      "public_attitude": {},
      "opinion_emotion": {},
      "event_heat": {},
      "language_style": {},
      "content_purpose": {},
      "risk_level": {}
    },
    "basic_objective": {
      "topic_tags": {},
      "account_type": {},
      "entities_hint": [],
      "keywords": [],
      "summary": {},
      "event_type": {}
    }
  },
  "evidence_clues": [],
  "quality_control": {
    "need_human_review": "boolean",
    "review_reasons": [],
    "failed_modules": []
  },
  "overall_confidence": "number | null",
  "processed_at": "string"
}
```

---

## 38. 版本固定说明

本文档对应：

```text
schema_version = t1_annotation_v0.5
```

后续 T1 Agent、样例 JSON、README、测试用例、前端展示和接口适配应以本文档为准。  
如负责人或集成组后续确认需要调整字段，应先修改 README 和 schema，再进入代码实现。

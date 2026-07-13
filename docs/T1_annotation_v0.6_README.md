# T1_annotation_v0.6 README

## 1. 文档定位

`T1_annotation_v0.6` 是课题四 T1 自动标注方向当前统一的三接口标签体系。

| 接口 | 处理对象 | 核心输出 |
|---|---|---|
| `annotate` | 单条贴文、评论、回复或文章 | AIGC检测、6个高价值主观维度、5个基础客观维度 |
| `annotate_account` | 账户主页或账户快照 | 主要账户类别、账户细分类标签、自动化嫌疑 |
| `annotate_event_heat` | 已识别并聚合后的事件 | 事件热度等级、热度分数、热度信号 |

三个接口统一使用：

```json
"schemaVersion": "t1_annotation_v0.6"
```

三个接口同步升级版本，不分别维护独立版本号。

---

## 2. 文件说明

本目录包含：

```text
T1_annotation_v0.6.json
T1_annotation_v0.6_README.md
```

- JSON文件用于程序读取、字段核查和接口实现。
- README用于说明字段作用、固定枚举、使用边界和异常处理规则。

JSON文件顶层结构：

```text
T1_annotation_v0.6
├── interfaces
│   ├── annotate
│   ├── annotate_account
│   └── annotate_event_heat
└── commonRules
```

---

## 3. 通用规则

### 3.1 `score`与`confidence`

两者均使用`0.0—1.0`，但含义不同：

- `score`：目标属性的程度。
- `confidence`：模型对判断结果的确信程度。

例如：

```text
overallAigcScore = 内容有多像AI生成
heatScore = 事件当前有多热
overallConfidence = 对当前接口整套结果有多确定
```

无法计算或无法形成有效判断时使用`null`。

### 3.2 空数组`[]`

空数组表示模块正常执行，但没有相应结果。例如：

```json
"methodLabels": []
```

表示没有识别到明确的BEND手法。

```json
"reviewReasons": []
```

表示没有人工复核原因。

```json
"failedModules": []
```

表示没有模块执行失败。

### 3.3 `unclear`

`unclear`表示任务适用于当前输入，但由于信息不足、信号冲突或上下文不完整，无法可靠判断。

### 3.4 `not_applicable`

`not_applicable`表示当前输入不存在对应模态，或该字段对当前输入不适用。

例如纯文本输入中：

```json
"videoAigcLabel": "not_applicable"
```

这不表示视频检测模块失败。

### 3.5 时间格式

所有时间字段建议使用ISO 8601格式，例如：

```text
2026-07-12T10:30:45Z
```

### 3.6 证据引用

内容接口和账户接口采用：

```text
标注字段.evidenceIds
        ↓
顶层evidenceClues
```

每个`evidenceId`必须能够在同一响应的`evidenceClues`中找到。不同标签可以复用同一条证据。

---

# 4. `annotate`：内容标注接口

## 4.1 接口作用

`annotate`处理一条独立内容记录，输出：

```text
AIGC检测
+
6个高价值主观维度
+
5个基础客观维度
+
统一证据池
+
质量控制
```

接口不负责账户类别标注和事件级热度标注。

响应顶层结构：

```text
annotate
├── schemaVersion
├── inputReference
├── language
├── aigcDetection
├── annotations
│   ├── highValueSubjective
│   └── basicObjective
├── evidenceClues
├── qualityControl
├── overallConfidence
└── processedAt
```

---

## 4.2 `inputReference`

`inputReference`只保存原始内容引用和基础元数据，不复制完整原文或媒体文件。

| 字段 | 类型 | 作用 |
|---|---|---|
| `contentId` | `string` | 原始内容唯一标识 |
| `contentType` | 固定枚举 | 内容业务类型 |
| `modalityCombination` | 固定枚举 | 实际输入模态组合 |
| `platform` | `string \| null` | 来源平台 |
| `url` | `string \| null` | 原始内容URL |
| `authorId` | `string \| null` | 作者或发布账号标识 |
| `createdAt` | `string \| null` | 原始内容创建时间 |

### `contentType`

| 枚举值 | 含义 |
|---|---|
| `post` | 独立贴文或社交媒体主帖 |
| `comment` | 针对内容发表的评论 |
| `reply` | 针对评论或回复的进一步回复 |
| `article` | 新闻、博客或其他文章型内容 |

### `modalityCombination`

| 枚举值 | 含义 |
|---|---|
| `text` | 仅文本 |
| `image` | 仅图片 |
| `video` | 仅视频 |
| `text_image` | 文本与图片 |
| `text_video` | 文本与视频 |
| `image_video` | 图片与视频 |
| `text_image_video` | 文本、图片与视频 |

---

## 4.3 `language`

`language`表示内容的主要语言。

| 枚举值 | 含义 |
|---|---|
| `zh` | 中文 |
| `en` | 英文 |
| `ja` | 日文 |
| `ko` | 韩文 |
| `vi` | 越南文 |
| `tl` | 菲律宾语/他加禄语 |
| `mixed` | 两种或多种语言混合 |
| `unknown` | 无法确定语言 |

---

## 4.4 `aigcDetection`

AIGC检测为独立顶层模块，不属于风险类型。

```text
aigcDetection
├── overallAigcLabel
├── overallAigcScore
├── textAigcDetection
├── imageAigcDetection
├── videoAigcDetection
├── multimodalAigcDetection
└── aigcDetectionConfidence
```

### 4.4.1 `overallAigcLabel`

| 枚举值 | 含义 |
|---|---|
| `ai_generated` | 整体主要由AI生成 |
| `human_generated` | 整体主要由人工生成 |
| `mixed` | 人工内容与AI生成内容混合 |
| `suspicious` | 存在较强AI生成嫌疑，但证据不足以直接确认 |
| `unclear` | 现有信息不足或信号冲突，无法判断 |

- `overallAigcScore`：整条内容的AI生成倾向程度。
- `aigcDetectionConfidence`：对整个AIGC检测结果的总体确信程度。

### 4.4.2 文本AIGC检测

#### `textAigcLabel`

| 枚举值 | 含义 |
|---|---|
| `ai_generated` | 文本主要由AI生成 |
| `human_generated` | 文本主要由人工生成 |
| `mixed` | 文本包含人工与AI混合内容 |
| `suspicious` | 文本存在AI生成嫌疑 |
| `unclear` | 有文本，但无法可靠判断 |
| `not_applicable` | 输入中不存在可分析文本 |

#### `textAigcSignalLabels`

| 枚举值 | 含义 |
|---|---|
| `ai_self_disclosure` | 文本明确自述由AI生成或由AI参与 |
| `template_like_structure` | 结构高度模板化 |
| `generic_over_polished` | 表达泛化且过度工整 |
| `repetitive_phrasing` | 词句或表达模式重复 |
| `unnatural_transition` | 段落或语义衔接不自然 |
| `instruction_following_trace` | 出现指令执行痕迹 |
| `mixed_style` | 同一文本中风格显著混合 |
| `none` | 未发现明确文本AIGC信号 |
| `unclear` | 文本信号无法归类或相互冲突 |

`none`与`unclear`建议单独使用，不与其他具体信号并列。

### 4.4.3 图片AIGC检测

#### `imageAigcLabel`

| 枚举值 | 含义 |
|---|---|
| `ai_generated` | 图片主要由生成式模型生成 |
| `human_generated` | 图片主要由人工拍摄或制作 |
| `edited_or_manipulated` | 图片经过明显编辑、拼接或局部操纵 |
| `mixed` | 图片包含人工与AI生成或操纵部分 |
| `suspicious` | 存在AI生成或操纵嫌疑 |
| `unclear` | 有图片，但无法可靠判断 |
| `not_applicable` | 输入中不存在图片 |

#### `imageAigcSignalLabels`

| 枚举值 | 含义 |
|---|---|
| `visual_artifact` | 存在典型视觉伪影 |
| `face_inconsistency` | 人脸结构或身份表现不一致 |
| `hand_or_body_anomaly` | 手部、肢体或人体结构异常 |
| `text_rendering_anomaly` | 图片中文字生成异常 |
| `lighting_shadow_inconsistency` | 光照或阴影关系不一致 |
| `background_distortion` | 背景结构扭曲 |
| `object_boundary_anomaly` | 物体边界异常 |
| `metadata_anomaly` | 图片元数据异常 |
| `deepfake_signal` | 出现深度伪造相关信号 |
| `local_manipulation_signal` | 出现局部篡改或编辑信号 |
| `none` | 未发现明确图片AIGC信号 |
| `unclear` | 图片信号无法归类或相互冲突 |

多图输入时输出综合判断，具体图片由`evidenceIds`定位。

### 4.4.4 视频AIGC检测

#### `videoAigcLabel`

| 枚举值 | 含义 |
|---|---|
| `ai_generated` | 视频主要由生成式模型生成 |
| `human_generated` | 视频主要由人工拍摄或制作 |
| `deepfake` | 视频存在明确深度伪造 |
| `edited_or_manipulated` | 视频经过明显编辑或局部操纵 |
| `mixed` | 视频包含人工、AI生成或操纵部分 |
| `suspicious` | 存在AI生成或操纵嫌疑 |
| `unclear` | 有视频，但无法可靠判断 |
| `not_applicable` | 输入中不存在视频 |

#### `videoAigcSignalLabels`

| 枚举值 | 含义 |
|---|---|
| `deepfake_signal` | 出现深度伪造信号 |
| `face_swap_signal` | 出现换脸信号 |
| `lip_sync_inconsistency` | 口型与语音不一致 |
| `audio_visual_mismatch` | 音频与视觉内容不一致 |
| `voice_synthesis_signal` | 出现语音合成信号 |
| `temporal_inconsistency` | 前后帧时间连续性异常 |
| `frame_artifact` | 单帧或连续帧出现伪影 |
| `motion_anomaly` | 运动模式不自然 |
| `lighting_shadow_inconsistency` | 光照或阴影随时间不一致 |
| `background_distortion` | 背景结构异常 |
| `scene_boundary_anomaly` | 场景切换或边界异常 |
| `metadata_anomaly` | 视频元数据异常 |
| `local_manipulation_signal` | 出现局部篡改信号 |
| `none` | 未发现明确视频AIGC信号 |
| `unclear` | 视频信号无法归类或相互冲突 |

多视频输入时输出综合判断，具体视频和时间段由证据池定位。

### 4.4.5 多模态AIGC检测

#### `multimodalAigcLabel`

| 枚举值 | 含义 |
|---|---|
| `consistent` | 不同模态之间整体一致 |
| `inconsistent` | 不同模态之间存在明显不一致 |
| `mixed_generated` | 不同模态呈现人工与AI生成混合 |
| `suspicious` | 存在跨模态异常或混合生成嫌疑 |
| `unclear` | 存在可分析模态，但无法判断一致性 |
| `not_applicable` | 只有一个可分析模态，不适用跨模态判断 |

#### `checkedModalityPairs`

| 枚举值 | 含义 |
|---|---|
| `text_image` | 文本与图片 |
| `text_video` | 文本与视频 |
| `image_video` | 图片与视频 |
| `image_ocr` | 图片视觉内容与OCR文字 |
| `video_audio` | 视频画面与音频 |
| `video_subtitle` | 视频画面与字幕 |
| `text_image_video` | 文本、图片与视频联合检查 |
| `other` | 其他跨模态组合 |

#### `multimodalSignalLabels`

| 枚举值 | 含义 |
|---|---|
| `text_image_mismatch` | 文本与图片语义不一致 |
| `text_video_mismatch` | 文本与视频语义不一致 |
| `image_video_mismatch` | 图片与视频语义不一致 |
| `image_ocr_mismatch` | 图片视觉内容与OCR文字不一致 |
| `audio_visual_mismatch` | 音频与画面不一致 |
| `subtitle_visual_mismatch` | 字幕与画面不一致 |
| `caption_context_mismatch` | 标题或说明文字与上下文不一致 |
| `cross_modal_source_mismatch` | 不同模态的来源或出处不一致 |
| `mixed_generation_signal` | 不同模态呈现混合生成信号 |
| `none` | 未发现明确跨模态异常 |
| `unclear` | 跨模态信号无法归类或相互冲突 |

---

## 4.5 `annotations.highValueSubjective`

高价值主观维度共6个：

```text
ideology
coreStance
opinionEmotion
languageStyle
manipulationMethod
riskLevel
```

### 4.5.1 `ideology`

只判断当前内容表达出的意识形态，不推断作者长期身份。

| 枚举值 | 含义 |
|---|---|
| `left_leaning` | 整体偏左 |
| `right_leaning` | 整体偏右 |
| `liberal` | 自由主义倾向 |
| `conservative` | 保守主义倾向 |
| `nationalist` | 民族主义倾向 |
| `populist` | 民粹主义倾向 |
| `pro_government` | 明确支持政府或执政当局 |
| `anti_government` | 明确反对政府或执政当局 |
| `pro_western` | 整体亲西方 |
| `anti_western` | 整体反西方 |
| `neutral` | 明确保持中立或平衡 |
| `not_obvious` | 没有明显意识形态表达 |
| `mixed` | 存在多种明显意识形态倾向 |
| `unclear` | 存在相关信号但无法可靠判断 |
| `other` | 已能判断，但现有词表未覆盖 |

### 4.5.2 `coreStance`

`coreStance`针对一个主要对象输出内容级总体立场。

#### `stanceTarget.targetType`

| 枚举值 | 含义 |
|---|---|
| `event` | 事件 |
| `issue` | 议题 |
| `policy` | 政策 |
| `action` | 行动或行为 |
| `person` | 人物 |
| `organization` | 组织或机构 |
| `country_or_region` | 国家或地区 |
| `ideology_or_value` | 意识形态或价值观 |
| `other` | 其他对象 |
| `unclear` | 对象类型无法确定 |

#### `stanceLabel`

| 枚举值 | 含义 |
|---|---|
| `support` | 支持或赞同 |
| `oppose` | 反对或否定 |
| `neutral` | 明确中立 |
| `mixed` | 同时包含支持与反对 |
| `unclear` | 无法可靠判断 |

#### `stanceStrength`

| 枚举值 | 含义 |
|---|---|
| `weak` | 立场表达较弱 |
| `medium` | 立场表达中等 |
| `strong` | 立场表达强烈 |
| `unclear` | 强度无法判断 |

### 4.5.3 `opinionEmotion`

#### `sentimentPolarity`

| 枚举值 | 含义 |
|---|---|
| `positive` | 总体正向 |
| `negative` | 总体负向 |
| `neutral` | 总体中性 |
| `mixed` | 正负情感混合 |
| `unclear` | 无法判断总体极性 |

#### `emotionLabels`

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
| `none` | 没有明显具体情绪 |
| `unclear` | 情绪信号无法判断 |

`none`与`unclear`建议单独使用。

#### `emotionIntensity`

| 枚举值 | 含义 |
|---|---|
| `low` | 情绪强度较低 |
| `medium` | 情绪强度中等 |
| `high` | 情绪强度较高 |
| `unclear` | 强度无法判断 |
| `not_applicable` | 当前内容没有可分析的情绪表达 |

### 4.5.4 `languageStyle`

| 枚举值 | 含义 |
|---|---|
| `neutral` | 中性、平实 |
| `aggressive` | 攻击性强 |
| `sarcastic` | 讽刺 |
| `mocking` | 嘲讽或戏谑 |
| `alarmist` | 制造紧迫或危机感 |
| `threatening` | 威胁性表达 |
| `sensationalized` | 夸张、耸动 |
| `emotional` | 明显情绪化 |
| `conspiratorial` | 阴谋论式表达 |
| `accusatory` | 指责或归罪 |
| `slogan_like` | 口号化表达 |
| `rhetorical_questioning` | 大量使用反问或设问 |
| `rational_analytical` | 理性分析式表达 |
| `unclear` | 语言风格无法判断 |
| `not_applicable` | 当前输入没有可分析的文字、语音、字幕或OCR内容 |

该字段可多选。

### 4.5.5 `manipulationMethod`

该维度只使用BEND八类固定方法，可多选，也可以返回空数组。

| 枚举值 | 含义 |
|---|---|
| `engage` | 通过互动、号召或参与机制吸引受众 |
| `explain` | 通过解释和框架化叙述塑造理解 |
| `excite` | 激发兴奋、愤怒、恐惧等强烈反应 |
| `enhance` | 强化特定人物、组织、议题或叙事 |
| `dismiss` | 淡化、否定或贬低特定对象 |
| `distort` | 歪曲、误导或选择性呈现信息 |
| `dismay` | 制造失望、恐慌、沮丧或无力感 |
| `distract` | 转移注意力或改变讨论焦点 |

没有识别到明确方法时：

```json
"methodLabels": []
```

### 4.5.6 `riskLevel`

#### `riskLabel`

| 枚举值 | 含义 |
|---|---|
| `none` | 未发现明显风险 |
| `low` | 低风险 |
| `medium` | 中等风险 |
| `high` | 高风险 |
| `severe` | 严重风险 |
| `unclear` | 无法判断风险等级 |

#### `riskTypes`

| 枚举值 | 含义 |
|---|---|
| `misinformation` | 错误、失实或误导性信息 |
| `rumor` | 未经证实的传闻 |
| `polarization` | 加剧群体对立或极化 |
| `hostility` | 煽动敌意或仇恨 |
| `panic_amplification` | 放大恐慌 |
| `mobilization_risk` | 存在动员、号召或集结风险 |
| `reputation_attack` | 针对人物或组织的声誉攻击 |
| `manipulation` | 存在明显操纵性表达 |
| `none` | 未发现具体风险类型 |
| `unclear` | 风险类型无法判断 |

`none`与`unclear`建议单独使用。

---

## 4.6 `annotations.basicObjective`

基础客观维度共5个：

```text
topicTags
entitiesHint
keywords
summary
eventType
```

### 4.6.1 `topicTags.primaryDomain`

| 枚举值 | 含义 |
|---|---|
| `politics` | 政治 |
| `military` | 军事 |
| `economy_finance` | 经济与金融 |
| `technology_cyber` | 科技与网络安全 |
| `public_health` | 公共卫生 |
| `social_livelihood` | 社会与民生 |
| `ethnic_religious` | 民族与宗教 |
| `energy_environment` | 能源与环境 |
| `disaster_accident` | 灾害与事故 |
| `crime_public_safety` | 犯罪与公共安全 |
| `culture_education` | 文化与教育 |
| `migration_refugee` | 移民与难民 |
| `other` | 已能判断但现有词表未覆盖 |
| `unclear` | 无法判断主要领域 |

只输出一个主要领域。

### 4.6.2 `entitiesHint`

`entitiesHint`用于提供内容中的实体线索，不替代正式实体抽取。

#### `typeHint`

| 枚举值 | 含义 |
|---|---|
| `persons` | 人物 |
| `organizations` | 组织或机构 |
| `events` | 事件 |
| `locations` | 地点 |
| `media_contents` | 媒体内容 |
| `social_accounts` | 社交账号 |
| `narratives` | 叙事 |
| `others` | 其他实体 |
| `unknown` | 实体类型未知 |

每个实体线索包含：

```text
entityHintId
text
typeHint
entityHintConfidence
evidenceIds
```

实体位置由`evidenceIds`指向证据池。

### 4.6.3 `keywords`

每个关键词包含：

```text
keywordText
keywordConfidence
evidenceIds
```

关键词来源和位置由证据池表达。

### 4.6.4 `summary`

摘要结构为：

```text
summaryText
summaryConfidence
```

摘要依据整条多模态输入生成，不单独设置`evidenceIds`。

### 4.6.5 `eventType.eventTypeLabel`

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
| `disaster_accident` | 灾害或事故 |
| `crime_public_safety` | 犯罪或公共安全事件 |
| `social_livelihood_event` | 社会民生事件 |
| `public_opinion_event` | 舆情事件 |
| `other` | 明确属于事件，但词表未覆盖 |
| `unclear` | 可能涉及事件，但无法判断类型 |
| `not_applicable` | 内容不涉及具体事件 |

只输出一个主要事件类型。


---

## 4.7 `evidenceClues`

内容接口证据池结构：

```text
evidenceClues[]
├── evidenceId
├── evidenceType
├── source
├── evidenceText
├── span
├── mediaId
├── region
├── timeRange
├── metadataSnapshot
└── modelSignal
```

### `evidenceType`

| 枚举值 | 含义 |
|---|---|
| `text_span` | 原始文字区间 |
| `image_region` | 图片局部区域 |
| `video_segment` | 视频时间段 |
| `video_frame_region` | 视频帧局部区域 |
| `metadata` | 原始元数据 |
| `model_signal` | 检测器或模型原始信号 |

### `source`

| 枚举值 | 含义 |
|---|---|
| `text` | 正文文本 |
| `image` | 图片 |
| `video` | 视频 |
| `audio` | 音频 |
| `ocr` | 图片OCR文字 |
| `subtitle` | 视频字幕 |
| `metadata` | 元数据 |
| `aigc_detector` | AIGC检测器 |

### 使用规则

1. 证据必须来自原始输入、原始元数据或检测器原始输出。
2. 不把模型生成的解释文本作为原始证据。
3. `span`使用`[start, end]`字符区间。
4. `region`使用`x、y、width、height`，建议采用`0.0—1.0`归一化坐标。
5. `timeRange.start`和`timeRange.end`单位为秒。
6. `mediaId`用于定位具体图片或视频。
7. 不适用的定位字段使用`null`。
8. `metadataSnapshot`和`modelSignal`只保存与当前证据有关的最小结构。

---

## 4.8 `qualityControl`

### `needHumanReview`

布尔值，表示当前结果是否需要人工复核。

### `reviewReasons`

| 枚举值 | 含义 |
|---|---|
| `low_confidence` | 总体或关键维度置信度较低 |
| `insufficient_context` | 上下文不足 |
| `conflicting_signals` | 不同判断信号相互冲突 |
| `multimodal_inconsistency` | 不同模态之间存在不一致 |
| `aigc_suspicious` | 存在较强AIGC嫌疑 |
| `high_risk_content` | 内容风险较高，需要人工关注 |
| `missing_metadata` | 关键元数据缺失 |
| `module_failure` | 有模块执行失败 |
| `too_short_input` | 输入过短，难以形成可靠判断 |
| `manual_policy_required` | 需要人工规则或专家标准 |
| `other` | 其他复核原因 |

没有复核原因时返回：

```json
"reviewReasons": []
```

### `failedModules`

固定值为：

```text
aigcDetection
textAigcDetection
imageAigcDetection
videoAigcDetection
multimodalAigcDetection
ideology
coreStance
opinionEmotion
languageStyle
manipulationMethod
riskLevel
topicTags
entitiesHint
keywords
summary
eventType
other
```

规则：

- 模态不存在并使用`not_applicable`不属于模块失败。
- 输出`unclear`不自动等于模块失败。
- `failedModules`非空时，通常应设置`needHumanReview = true`。
- 有模块失败时，`reviewReasons`通常应包含`module_failure`。

---

## 4.9 `overallConfidence`

`overallConfidence`表示对内容接口整套输出的总体确信程度。

它不是各子模块置信度的简单算术平均，应综合考虑：

- 关键标签是否有可靠证据；
- 不同模态判断是否一致；
- 是否存在模块失败；
- 是否存在大量`unclear`；
- 输入是否完整；
- 质量控制是否触发人工复核。

---

## 4.10 `processedAt`

表示本次内容标注完成时间，建议使用ISO 8601格式。

---

# 5. `annotate_account`：账户类别标注接口

## 5.1 接口作用

该接口基于账户主页或账户快照，输出：

```text
主要账户类别
+
账户细分类标签
+
自动化账号嫌疑
```

该接口不对近期内容样本逐条生成完整内容标注，也不负责构建长期全息画像。

---

## 5.2 请求结构

```text
annotate_account请求
├── platform
├── platformUserId
├── accountEntityType
├── platformNativeType
├── handle
├── displayName
├── bio
├── selfDeclaredLocation
├── verified
├── verifiedType
├── isSuspended
├── accountCreatedAt
├── followersCount
├── followingCount
├── subscriberCount
├── memberCount
├── postCount
├── viewCount
└── recentPostSamples
```

### `accountEntityType`

| 枚举值 | 含义 |
|---|---|
| `user` | 普通用户账号 |
| `channel` | 频道 |
| `page` | 公共主页 |
| `group` | 群组 |
| `community` | 社区 |
| `forum_board` | 论坛版块 |
| `news_source` | 新闻来源对象 |

该字段表示平台对象形态，不等于最终账户类别。

### `verifiedType`

| 枚举值 | 含义 |
|---|---|
| `none` | 无认证 |
| `blue` | 普通蓝标或个人认证 |
| `org` | 组织认证 |
| `government` | 政府认证 |
| `media` | 媒体认证 |

当认证信息不存在时允许为`null`。

### 其他请求字段

| 字段 | 作用 |
|---|---|
| `platform` | 账号所属平台 |
| `platformUserId` | 平台内唯一账号标识 |
| `platformNativeType` | 平台原始账号类型 |
| `handle` | 账号公开用户名 |
| `displayName` | 显示名称 |
| `bio` | 账号简介 |
| `selfDeclaredLocation` | 自述位置 |
| `verified` | 是否认证 |
| `isSuspended` | 是否暂停或封禁 |
| `accountCreatedAt` | 账号创建时间 |
| `followersCount` | 粉丝数 |
| `followingCount` | 关注数 |
| `subscriberCount` | 订阅数 |
| `memberCount` | 成员数 |
| `postCount` | 发帖数 |
| `viewCount` | 浏览或播放数 |
| `recentPostSamples` | 近期内容样本 |

`recentPostSamples`为开放对象数组，保留上游实际能够提供的内容、时间、平台和互动字段。

---

## 5.3 响应结构

```text
annotate_account
├── schemaVersion
├── accountReference
├── accountType
│   ├── primaryAccountCategory
│   ├── accountSubtypeTags
│   └── automationSuspicion
├── evidenceClues
├── qualityControl
├── overallConfidence
└── processedAt
```

### `accountReference`

只保存：

```text
platform
platformUserId
accountEntityType
platformNativeType
handle
displayName
```

不复制完整账户主页数据。

---

## 5.4 `primaryAccountCategory`

只选择一个主要类别。

| 枚举值 | 含义 |
|---|---|
| `ordinary_user` | 普通个人用户 |
| `news_media` | 新闻媒体 |
| `state_affiliated_media` | 国家关联媒体 |
| `government_agency` | 政府机构 |
| `political_actor` | 政治人物、官员或候选人 |
| `political_party_or_campaign` | 政党、竞选组织或政治运动组织 |
| `military_security_agency` | 军事、国防、情报、警务或安全机构 |
| `international_organization` | 国际组织 |
| `ngo_or_civil_society` | 非政府组织或社会组织 |
| `academic_or_expert` | 大学、研究机构、学者或专家 |
| `commercial_brand` | 企业、公司或商业品牌 |
| `platform_official` | 平台官方账号 |
| `influencer_kol` | 网红、自媒体或意见领袖 |
| `community_group` | 社区、兴趣群体或群组 |
| `anonymous_account` | 以匿名方式运营的账号 |
| `suspected_bot_or_automated` | 主要表现为机器人或自动化账号 |
| `unknown` | 输入资料不足，无法判断 |
| `other` | 身份性质明确，但现有词表未覆盖 |

选择原则：

1. 优先依据主体身份，而不是粉丝量或传播规模。
2. 优先选择更具体的类别。
3. 账号名称、简介、认证和平台标签应相互印证。
4. 资料不足时使用`unknown`。
5. 身份明确但词表未覆盖时使用`other`。

---

## 5.5 `accountSubtypeTags`

`accountSubtypeTags`用于补充主要类别无法表达的细粒度身份。

每个标签结构：

```text
subtypeTag
evidenceIds
```

`subtypeTag`当前不是固定枚举，而是受控字符串。没有可靠细分类时返回：

```json
"accountSubtypeTags": []
```

---

## 5.6 `automationSuspicion`

| 枚举值 | 含义 |
|---|---|
| `none` | 没有发现明显自动化特征 |
| `low` | 存在少量弱自动化信号 |
| `medium` | 存在较明显自动化运行特征 |
| `high` | 有强证据表明账号主要由自动化系统控制 |
| `unclear` | 数据不足或信号冲突，无法判断 |

可能依据包括：

- 简介明确自述为机器人或自动播报账号；
- 平台明确标记为自动化账号；
- 全天候高频发布；
- 发布时间间隔高度规律；
- 内容结构长期高度重复；
- 大量机械化转发；
- 近期样本呈现明显模板化。

单纯发帖量大，不能直接判断为高自动化嫌疑。

---

## 5.7 账户证据池

账户接口证据结构：

```text
evidenceClues[]
├── evidenceId
├── evidenceType
├── sourceField
└── metadataSnapshot
```

账户证据中不设置`evidenceText`和`postSampleId`。所有原始值、文本、统计或近期样本都保存在`metadataSnapshot`。

### `evidenceType`

| 枚举值 | 含义 |
|---|---|
| `profile_text` | 显示名称、简介或自述位置 |
| `verification_info` | 认证状态或认证类型 |
| `account_metadata` | 平台对象类型、创建时间等基础元数据 |
| `activity_statistics` | 粉丝数、发帖数、浏览量等统计 |
| `recent_post_sample` | 近期内容样本 |
| `platform_label` | 平台明确提供的身份标签 |
| `other` | 其他有效账户证据 |

### `sourceField`

| 枚举值 | 含义 |
|---|---|
| `display_name` | 显示名称 |
| `bio` | 账号简介 |
| `self_declared_location` | 自述位置 |
| `verified` | 是否认证 |
| `verified_type` | 认证类型 |
| `account_entity_type` | 归一化平台对象类型 |
| `platform_native_type` | 平台原始对象类型 |
| `account_created_at` | 账号创建时间 |
| `followers_count` | 粉丝数 |
| `following_count` | 关注数 |
| `subscriber_count` | 订阅数 |
| `member_count` | 成员数 |
| `post_count` | 发帖数 |
| `view_count` | 浏览或播放数 |
| `recent_post_sample` | 近期内容样本 |
| `other` | 其他输入字段 |

### `metadataSnapshot`

只保存支撑当前判断的最小证据快照，不复制完整请求体。

简介证据示例：

```json
{
  "evidenceId": "ev_account_001",
  "evidenceType": "profile_text",
  "sourceField": "bio",
  "metadataSnapshot": {
    "value": "某国外交部官方账号"
  }
}
```

统计证据可以区分原始值与派生值：

```json
{
  "evidenceId": "ev_account_002",
  "evidenceType": "activity_statistics",
  "sourceField": "post_count",
  "metadataSnapshot": {
    "rawValues": {
      "postCount": 86000,
      "accountCreatedAt": "2025-01-01T00:00:00Z"
    },
    "derivedValues": {
      "averagePostsPerDay": 159.3
    }
  }
}
```

---

## 5.8 账户质量控制

### `reviewReasons`

| 枚举值 | 含义 |
|---|---|
| `low_confidence` | 整套账户标注置信度较低 |
| `insufficient_profile_information` | 显示名称、简介等资料不足 |
| `conflicting_identity_signals` | 多项身份信息相互冲突 |
| `ambiguous_account_category` | 存在多个难以区分的主要类别 |
| `verification_conflict` | 认证信息与账号资料不一致 |
| `insufficient_activity_data` | 缺少活动统计 |
| `insufficient_post_samples` | 近期内容样本不足 |
| `automation_judgment_uncertain` | 自动化嫌疑无法可靠判断 |
| `missing_metadata` | 关键账户元数据缺失 |
| `module_failure` | 账户标注模块执行失败 |
| `manual_policy_required` | 需要人工规则或专家标准 |
| `other` | 其他复核原因 |

没有复核原因时返回空数组。

### `failedModules`

固定值：

```text
primaryAccountCategory
accountSubtypeTags
automationSuspicion
evidenceExtraction
other
```

信息不足不等于模块失败。例如缺少近期内容样本时，可以输出：

```text
automationSuspicion.suspicionLevel = unclear
reviewReasons = [insufficient_post_samples, automation_judgment_uncertain]
failedModules = []
```

---

## 5.9 `overallConfidence`

账户接口不设置`accountTypeConfidence`或各子标签置信度。

顶层`overallConfidence`统一表示对以下结果的总体确信程度：

```text
primaryAccountCategory
+
accountSubtypeTags
+
automationSuspicion
```

---

## 5.10 `processedAt`

表示本次账户标注完成时间，建议使用ISO 8601格式。


---

# 6. `annotate_event_heat`：事件热度接口

## 6.1 接口作用

该接口处理已经识别和聚合后的事件，依据：

```text
事件基础信息
+
一跳关联实体和抽样内容
+
后端聚合统计
```

输出事件当前综合热度。

接口不负责事件识别、实体抽取、内容关联和统计聚合。

---

## 6.2 请求结构

```text
annotate_event_heat请求
├── event
├── relatedEntities
└── aggregateStats
```

### `event`

| 字段 | 作用 |
|---|---|
| `eventId` | 事件唯一标识 |
| `canonicalName` | 事件标准名称 |
| `eventType` | 事件粗粒度类型 |
| `occurredAtStart` | 事件开始时间 |
| `occurredAtEnd` | 事件结束时间 |
| `country` | 主要关联国家或地区 |

#### `eventType`

| 枚举值 | 含义 |
|---|---|
| `election` | 选举类事件 |
| `military` | 军事类事件 |
| `diplomatic` | 外交类事件 |
| `protest` | 抗议或示威类事件 |
| `disaster` | 灾害类事件 |
| `other` | 其他事件 |

### `relatedEntities`

每个关联实体包含：

```text
entityId
entityType
name
publishedAt
platform
likeCount
commentCount
shareCount
repostCount
viewCount
```

#### `entityType`

| 枚举值 | 含义 |
|---|---|
| `person` | 人物 |
| `organization` | 组织或机构 |
| `location` | 地点 |
| `media_content` | 关联贴文、新闻或其他媒体内容 |
| `social_account` | 社交账号 |

人物、组织、地点和账号通常使用`entityId`、`entityType`和`name`。  
`media_content`可以进一步携带发布时间、平台和互动量。

### `aggregateStats`

| 字段 | 作用 |
|---|---|
| `totalRelatedContentCount` | 与事件关联的全部内容数量 |
| `totalEngagement` | 全部关联内容的总互动量 |
| `distinctPlatformCount` | 事件覆盖的平台数量 |
| `earliestContentAt` | 最早关联内容时间 |
| `latestContentAt` | 最新关联内容时间 |

`relatedEntities`中的媒体内容属于辅助样本，事件内容总规模应以`totalRelatedContentCount`为准。

事件自身的发生时间与关联内容发布时间不同：

```text
occurredAtStart / occurredAtEnd
= 事件本身何时发生

earliestContentAt / latestContentAt
= 系统关联到的内容何时发布
```

---

## 6.3 响应结构

```text
annotate_event_heat
├── schemaVersion
├── eventHeat
│   ├── heatLevel
│   ├── heatScore
│   ├── heatSignalTypes
│   └── reasoning
├── overallConfidence
└── processedAt
```

响应不重复返回事件引用对象。调用方通过请求上下文中的`event.eventId`关联结果。

---

## 6.4 `heatLevel`

| 枚举值 | 含义 |
|---|---|
| `low` | 关联内容少、互动有限、传播范围小 |
| `medium` | 已经形成一定讨论规模，但没有明显爆发 |
| `high` | 内容量、互动量或平台覆盖明显，并持续受到关注 |
| `explosive` | 短时间内快速增长、互动激增或跨平台扩散 |
| `unclear` | 数据不足、统计口径不清或信号冲突 |

使用说明：

- `low`：相关内容少、互动低、覆盖范围有限。
- `medium`：已经形成稳定讨论，但没有明显爆发。
- `high`：多项热度信号明显，事件持续受到关注。
- `explosive`：必须体现短时间内的快速增长或互动激增。
- `unclear`：无法形成可靠判断。

`explosive`不能仅依据历史累计总量较高。

---

## 6.5 `heatScore`

`heatScore`取值为`0.0—1.0`，表示事件当前有多热。

```text
heatScore ≠ overallConfidence
```

- `heatScore`：热度程度。
- `overallConfidence`：对整套事件热度结果的确信程度。

无法计算有效热度分数时使用`null`。

---

## 6.6 `heatSignalTypes`

| 枚举值 | 含义 |
|---|---|
| `content_volume` | 关联内容规模较大 |
| `engagement_surge` | 互动量在当前时间窗口内明显激增 |
| `rapid_growth` | 单位时间新增内容量快速增长 |
| `wide_platform_spread` | 事件在多个平台广泛传播 |
| `sustained_attention` | 事件在较长时间内持续受到关注 |
| `declining` | 当前新增内容或互动量正在下降 |
| `insufficient_data` | 数据不足，无法形成可靠判断 |
| `unclear` | 数据冲突或信号性质无法确定 |

使用规则：

1. 具体热度信号可以多选。
2. `insufficient_data`建议单独使用。
3. `unclear`建议单独使用。
4. `content_volume`主要依据全量关联内容数。
5. `wide_platform_spread`主要依据平台覆盖情况。
6. `sustained_attention`需要结合时间跨度和持续新增情况。
7. 只有累计总量时，不应直接输出`engagement_surge`、`rapid_growth`或`declining`。
8. `engagement_surge`、`rapid_growth`和`declining`应有不同时间窗口的对比数据支撑。

没有任何关联内容时，应输出：

```json
{
  "heatLevel": "unclear",
  "heatScore": null,
  "heatSignalTypes": ["insufficient_data"],
  "reasoning": "当前事件尚未关联到有效内容，无法形成可靠的事件热度判断。"
}
```

---

## 6.7 `reasoning`

`reasoning`是面向系统展示和人工复核的简短依据摘要，不记录模型内部推理过程。

正常示例：

```text
事件关联内容数量较多，覆盖多个平台，且近期仍持续产生相关内容，因此判断为高热度。
```

数据不足示例：

```text
当前缺少互动量和时间窗口统计，无法形成可靠的事件热度判断。
```

模块失败或无法生成说明时允许为`null`。

---

## 6.8 `overallConfidence`

事件热度接口不设置`eventHeat.confidence`。

顶层`overallConfidence`统一表示对以下结果的总体确信程度：

```text
heatLevel
+
heatScore
+
heatSignalTypes
+
reasoning
```

影响置信度的因素包括：

- 关联内容数量是否可靠；
- 互动量统计是否完整；
- 平台覆盖统计是否清楚；
- 时间数据是否充分；
- 多项热度信号是否一致；
- 聚合数据是否足够新。

---

## 6.9 `processedAt`

表示本次事件热度计算完成时间，建议使用ISO 8601格式。

事件热度可以周期性重新计算，每次重算后更新`processedAt`。

---

# 7. 三接口字段边界

| 内容 | 所属接口 |
|---|---|
| 单条内容的意识形态、核心立场、情绪和语言风格 | `annotate` |
| 单条内容的BEND方法和风险等级 | `annotate` |
| 单条内容的AIGC检测 | `annotate` |
| 账户的主要类别和细分类标签 | `annotate_account` |
| 账户的自动化嫌疑 | `annotate_account` |
| 已聚合事件的整体热度 | `annotate_event_heat` |

边界原则：

1. 不使用单条贴文代替账户主页快照进行账户类别判断。
2. 不在单条内容首次进入系统时直接输出事件级热度。
3. `entitiesHint`只提供实体线索，不替代正式实体抽取。
4. AIGC检测与内容风险判断相互独立。
5. 事件热度基于聚合数据，不基于单条内容的互动量直接判断。

---

# 8. 最小输出与异常处理

## 8.1 正常但未命中

模块正常执行但未识别到标签时使用空数组。例如：

```json
"methodLabels": []
```

## 8.2 信息不足

任务适用但信息不足时使用`unclear`，并降低相应置信度。

## 8.3 模态不存在

使用`not_applicable`，不记为模块失败。

## 8.4 模块失败

内容或账户接口发生模块失败时，应：

```text
failedModules记录失败模块
needHumanReview = true
reviewReasons包含module_failure
```

## 8.5 无有效总体置信度

顶层`overallConfidence`允许为`null`。

## 8.6 `none`、`unclear`和空数组的区别

```text
none
= 已完成判断，确认没有相应属性或风险

unclear
= 任务适用，但无法可靠判断

[]
= 数组字段中没有可输出项，或没有异常项
```

## 8.7 `other`与`unknown`

```text
other
= 已能判断对象性质，但固定词表没有对应类别

unknown
= 信息不足，无法判断对象类别
```

---

# 9. 实现一致性要求

1. 所有接口返回的`schemaVersion`必须为`t1_annotation_v0.6`。
2. 所有置信度和程度分数必须位于`0.0—1.0`。
3. 所有枚举字段只能使用本文和JSON文件列出的固定值。
4. 非固定字段，如`accountSubtypeTags[].subtypeTag`，应使用项目维护的受控字符串。
5. 内容接口和账户接口的`evidenceIds`必须可以解析到当前响应顶层证据池。
6. 时间字段统一使用ISO 8601。
7. 不应在响应中增加未定义的同义置信度字段。
8. 不应把`not_applicable`写入`failedModules`。
9. 不应把模型生成的解释当作原始证据。
10. 账户证据的实际值统一保存在`metadataSnapshot`。

---

# 10. 版本说明

当前版本：

```text
t1_annotation_v0.6
```

本版本包含三个接口：

```text
annotate
annotate_account
annotate_event_heat
```

三个接口作为同一套T1自动标注能力统一维护和升级。

package com.idata.profile.llmagent;

import com.idata.profile.agentproxy.dto.t1.T1AnnotateRequest;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateResponse;
import com.idata.profile.agentproxy.dto.t2.T2ExtractRequest;
import com.idata.profile.agentproxy.dto.t2.T2ExtractResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/llm")
@RequiredArgsConstructor
public class LlmAgentController {

    private static final String MODEL_VERSION = "qwen3-vl-32b";

    private static final String T1_SYSTEM_PROMPT = """
            你是一个专业的信息标注系统。对输入的社交媒体文本进行多维度分析，严格按照 JSON 格式输出，不要输出任何其他内容，不要有 markdown 代码块，不要有解释说明。

            输出 JSON 结构如下：
            {
              "annotations": {
                "topics": ["主题1", "主题2"],
                "keywords": ["关键词1", "关键词2"],
                "summary": "一句话摘要（原文语言）",
                "languageStyle": {
                  "formality": "formal|informal|mixed|unknown",
                  "emotionalIntensity": "none|low|medium|high|unclear"
                },
                "sentiment": {
                  "label": "positive|negative|neutral",
                  "score": 0.0（-1.0到1.0）
                },
                "eventType": "事件类型或null",
                "contentPurpose": "目的（如criticism/news/propaganda/opinion）",
                "aigcSuspicion": "none|low|medium|high",
                "entitiesHint": [
                  {
                    "text": "实体文本",
                    "typeHint": "person|organization|event|location|narrative|other",
                    "stance": "support|oppose|neutral",
                    "emotionExpression": "情感表达或null",
                    "emotionIntensity": "none|low|medium|high"
                  }
                ]
              },
              "qualityControl": {
                "autoLabelStatus": "success",
                "needHumanReview": false,
                "modelVersion": "qwen3-vl-32b"
              },
              "confidence": 0.85
            }

            要求：
            1. topics 字段用英文小写，如 politics/military/economics/social/technology/culture
            2. keywords 保留原文语言
            3. summary 用原文语言写一句话（不超过100字）
            4. entitiesHint 只列出文本中明确提到的实体，不推断
            5. aigcSuspicion 基于语言风格、重复模式、语义连贯性判断
            """;

    private static final String T2_SYSTEM_PROMPT = """
            你是一个专业的信息抽取系统。从社交媒体文本中抽取实体、关系和事件，严格按照 JSON 格式输出，不要输出任何其他内容，不要有 markdown 代码块，不要有解释说明。

            输出 JSON 结构如下：
            {
              "entities": [
                {
                  "type": "person|organization|event|narrative|location|social_account",
                  "canonicalName": "标准化名称（英文实体保留英文，中文保留中文）",
                  "aliases": ["别名1", "别名2"],
                  "importanceScore": 85.0（0-100，在文本中的重要程度）
                }
              ],
              "relationships": [
                {
                  "sourceName": "来源实体名称",
                  "sourceType": "实体类型",
                  "targetName": "目标实体名称",
                  "targetType": "实体类型",
                  "relationType": "关系类型（见下方词表）",
                  "role": "角色说明或null",
                  "confidence": 0.85
                }
              ],
              "events": [
                {
                  "eventType": "military|diplomatic|protest|cyber|election|disaster|other",
                  "canonicalName": "事件标准名称",
                  "eventTimeStart": "ISO-8601时间或null",
                  "confidence": 0.90,
                  "participants": [
                    {"name": "参与方名称", "role": "actor|target|location|related"}
                  ]
                }
              ]
            }

            关系类型词表（relationType 必须从以下选取，不允许自定义）：
            身份归一类：SAME_AS, HAS_ACCOUNT, ALIAS_OF, MERGED_INTO
            组织社群类：AFFILIATED_WITH, PART_OF, CONTROLS, OWNS, MEMBER_OF, ADMIN_OF, PUBLISHED_IN
            内容链路类：AUTHORED, REPLY_TO, COMMENT_ON, REPOSTS, QUOTES, SHARES, REFERENCES_URL, MENTIONS, HAS_MEDIA
            事件地点类：DESCRIBES, REPORTS, EVENT_OCCURRED_AT, EVENT_INVOLVES_ENTITY, LOCATED_IN, POSTS_FROM
            叙事认知类：CONTENT_EXPRESSES_NARRATIVE, NARRATIVE_TARGETS_ENTITY, NARRATIVE_ABOUT_EVENT, SUPPORTS, OPPOSES, HAS_EMOTION
            传播协同类：AMPLIFIES, BRIDGES_COMMUNITY, COORDINATES_WITH, POTENTIAL_SUBORDINATE_TO, INFLUENCES
            证据治理类：ASSERTED_BY, DERIVED_FROM, CONFLICTS_WITH, REVIEWED_BY

            要求：
            1. 只抽取文本中明确提到的实体，不推断隐含实体
            2. canonicalName 使用文本中出现的语言，不做翻译
            3. importanceScore：核心实体 80-100，次要实体 50-79，背景实体 20-49
            4. 没有实体/关系/事件时对应字段返回空数组 []，不返回 null
            5. entities 数量控制在 10 个以内，relationships 控制在 15 个以内
            """;

    private static final Set<String> ALLOWED_RELATION_TYPES = Set.of(
            "SAME_AS", "HAS_ACCOUNT", "ALIAS_OF", "MERGED_INTO",
            "AFFILIATED_WITH", "PART_OF", "CONTROLS", "OWNS", "MEMBER_OF", "ADMIN_OF", "PUBLISHED_IN",
            "AUTHORED", "REPLY_TO", "COMMENT_ON", "REPOSTS", "QUOTES", "SHARES", "REFERENCES_URL", "MENTIONS", "HAS_MEDIA",
            "DESCRIBES", "REPORTS", "EVENT_OCCURRED_AT", "EVENT_INVOLVES_ENTITY", "LOCATED_IN", "POSTS_FROM",
            "CONTENT_EXPRESSES_NARRATIVE", "NARRATIVE_TARGETS_ENTITY", "NARRATIVE_ABOUT_EVENT",
            "SUPPORTS", "OPPOSES", "HAS_EMOTION",
            "AMPLIFIES", "BRIDGES_COMMUNITY", "COORDINATES_WITH", "POTENTIAL_SUBORDINATE_TO", "INFLUENCES",
            "ASSERTED_BY", "DERIVED_FROM", "CONFLICTS_WITH", "REVIEWED_BY");

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    @PostMapping("/t1/annotate_text")
    public T1AnnotateResponse annotateText(@RequestBody T1AnnotateRequest request) {
        log.info("[LLM-T1] annotate_text, textLength={}",
                request.getText() != null ? request.getText().length() : 0);

        String userPrompt = buildT1UserPrompt(request);

        try {
            String raw = chatClient.prompt()
                    .system(T1_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();

            T1AnnotateResponse response = parseT1Response(raw);
            response.setProcessedAt(java.time.OffsetDateTime.now().toString());
            response.setLanguage(request.getLanguage());
            response.setRaw(raw);
            return response;

        } catch (Exception e) {
            logLlmFailure("[LLM-T1] annotate_text失败，返回fallback", e);
            T1AnnotateResponse response = buildFallbackT1Response(request.getText());
            response.setLanguage(request.getLanguage());
            return response;
        }
    }

    @PostMapping("/t2/extract_entities")
    public T2ExtractResponse extractEntities(@RequestBody T2ExtractRequest request) {
        log.info("[LLM-T2] extract_entities, textLength={}",
                request.getText() != null ? request.getText().length() : 0);

        String userPrompt = buildT2UserPrompt(request);

        try {
            String raw = chatClient.prompt()
                    .system(T2_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();

            T2ExtractResponse response = parseT2Response(raw);
            filterUnsupportedRelationships(response);
            response.setRaw(raw);
            return response;

        } catch (Exception e) {
            logLlmFailure("[LLM-T2] extract_entities失败，返回fallback", e);
            return buildFallbackT2Response();
        }
    }

    private String buildT1UserPrompt(T1AnnotateRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("请标注以下文本：\n\n");
        sb.append("文本语言：").append(request.getLanguage() != null ? request.getLanguage() : "未知").append("\n");
        sb.append("平台来源：社交媒体\n\n");
        sb.append("文本内容：\n").append(request.getText());
        return sb.toString();
    }

    private T1AnnotateResponse parseT1Response(String raw) throws Exception {
        return objectMapper.readValue(cleanJson(raw), T1AnnotateResponse.class);
    }

    private T1AnnotateResponse buildFallbackT1Response(String text) {
        T1AnnotateResponse resp = new T1AnnotateResponse();
        T1AnnotateResponse.Annotations ann = new T1AnnotateResponse.Annotations();
        ann.setTopics(List.of("unknown"));
        ann.setKeywords(List.of());
        ann.setSummary(text != null && !text.isBlank() ? text.substring(0, Math.min(text.length(), 100)) : null);
        ann.setAigcSuspicion("low");

        T1AnnotateResponse.Annotations.LanguageStyle languageStyle =
                new T1AnnotateResponse.Annotations.LanguageStyle();
        languageStyle.setFormality("unknown");
        languageStyle.setEmotionalIntensity("unclear");
        ann.setLanguageStyle(languageStyle);

        T1AnnotateResponse.Annotations.Sentiment sentiment =
                new T1AnnotateResponse.Annotations.Sentiment();
        sentiment.setLabel("neutral");
        sentiment.setScore(0.0);
        ann.setSentiment(sentiment);
        ann.setEntitiesHint(List.of());

        T1AnnotateResponse.QualityControl qc = new T1AnnotateResponse.QualityControl();
        qc.setAutoLabelStatus("failed");
        qc.setNeedHumanReview(true);
        qc.setModelVersion(MODEL_VERSION);

        resp.setAnnotations(ann);
        resp.setEvidenceClues(List.of());
        resp.setQualityControl(qc);
        resp.setConfidence(0.0);
        resp.setProcessedAt(java.time.OffsetDateTime.now().toString());
        return resp;
    }

    private String buildT2UserPrompt(T2ExtractRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("请从以下文本中抽取实体、关系和事件：\n\n");

        if (request.getAnnotation() != null) {
            sb.append("T1标注参考（仅供参考，以文本为准）：")
                    .append(request.getAnnotation()).append("\n\n");
        }

        if (request.getHashtags() != null && request.getHashtags().length > 0) {
            sb.append("话题标签：").append(String.join(", ", request.getHashtags())).append("\n");
        }
        if (request.getSourceInfo() != null) {
            sb.append("平台：").append(request.getSourceInfo().getPlatformId()).append("\n");
        }

        sb.append("\n文本内容：\n").append(request.getText());
        return sb.toString();
    }

    private T2ExtractResponse parseT2Response(String raw) throws Exception {
        T2ExtractResponse response = objectMapper.readValue(cleanJson(raw), T2ExtractResponse.class);
        normalizeT2Response(response);
        return response;
    }

    private void normalizeT2Response(T2ExtractResponse response) {
        if (response.getEntities() == null) {
            response.setEntities(List.of());
        }
        if (response.getRelationships() == null) {
            response.setRelationships(List.of());
        }
        if (response.getEvents() == null) {
            response.setEvents(List.of());
        }
    }

    private void filterUnsupportedRelationships(T2ExtractResponse response) {
        if (response == null || response.getRelationships() == null) {
            return;
        }
        response.setRelationships(response.getRelationships().stream()
                .filter(relation -> relation != null
                        && relation.getRelationType() != null
                        && ALLOWED_RELATION_TYPES.contains(relation.getRelationType()))
                .toList());
    }

    private T2ExtractResponse buildFallbackT2Response() {
        T2ExtractResponse resp = new T2ExtractResponse();
        resp.setEntities(List.of());
        resp.setRelationships(List.of());
        resp.setEvents(List.of());
        return resp;
    }

    private String cleanJson(String raw) {
        String json = raw.trim();
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```[\\w-]*\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }
        return json;
    }

    private void logLlmFailure(String message, Exception e) {
        Throwable timeoutCause = findTimeoutCause(e);
        if (timeoutCause != null) {
            log.warn("{}, reason={}: {}", message,
                    timeoutCause.getClass().getSimpleName(), timeoutCause.getMessage());
            return;
        }
        log.error(message, e);
    }

    private Throwable findTimeoutCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof InterruptedIOException) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }
}

package com.idata.profile.llmagent;

import com.idata.profile.agentproxy.dto.t1.T1AnnotateAccountRequest;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateAccountResponse;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateEventHeatRequest;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateEventHeatResponse;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateRequest;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateResponse;
import com.idata.profile.agentproxy.dto.t2.T2ExtractRequest;
import com.idata.profile.agentproxy.dto.t2.T2ExtractResponse;
import com.idata.profile.agentproxy.dto.t3.T3ResolveBatchRequest;
import com.idata.profile.agentproxy.dto.t3.T3ResolveBatchResponse;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingRequest;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingResponse;
import com.idata.profile.agentproxy.dto.t5.T5GenerateProfileRequest;
import com.idata.profile.agentproxy.dto.t5.T5GenerateProfileResponse;
import com.idata.profile.agentproxy.dto.t6.T6IdentifyRequest;
import com.idata.profile.agentproxy.dto.t6.T6IdentifyResponse;
import com.idata.profile.common.constant.AllowedRelationTypes;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.graph.Person;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.mapper.graph.PersonMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/llm")
@RequiredArgsConstructor
public class LlmAgentController {

    private static final String MODEL_VERSION = "qwen3-vl-32b";

    private static final String T1_SYSTEM_PROMPT = """
            You are a professional content annotation system. Analyze the input text and return only
            one valid JSON object matching the T1_annotation_v0.6 schema. Do not output markdown code
            fences, <think> tags, or any explanation.

            Required JSON shape:
            {
              "schemaVersion": "t1_annotation_v0.6",
              "language": "same as input language",
              "aigcDetection": {
                "overallAigcLabel": "ai_generated|human_generated|mixed|suspicious|unclear",
                "overallAigcScore": 0.0,
                "textAigcDetection": {
                  "textAigcLabel": "ai_generated|human_generated|mixed|suspicious|unclear|not_applicable",
                  "textAigcScore": 0.0,
                  "textAigcSignalLabels": ["ai_self_disclosure|template_like_structure|generic_over_polished|repetitive_phrasing|unnatural_transition|instruction_following_trace|mixed_style|none|unclear"],
                  "textAigcConfidence": 0.0,
                  "evidenceIds": ["ev_001"]
                },
                "imageAigcDetection": {"imageAigcLabel": "ai_generated|human_generated|edited_or_manipulated|mixed|suspicious|unclear|not_applicable", "imageAigcSignalLabels": ["visual_artifact|face_inconsistency|hand_or_body_anomaly|text_rendering_anomaly|lighting_shadow_inconsistency|background_distortion|object_boundary_anomaly|metadata_anomaly|deepfake_signal|local_manipulation_signal|none|unclear"], "evidenceIds": []},
                "videoAigcDetection": {"videoAigcLabel": "ai_generated|human_generated|deepfake|edited_or_manipulated|mixed|suspicious|unclear|not_applicable", "videoAigcSignalLabels": ["deepfake_signal|face_swap_signal|lip_sync_inconsistency|audio_visual_mismatch|voice_synthesis_signal|temporal_inconsistency|frame_artifact|motion_anomaly|lighting_shadow_inconsistency|background_distortion|scene_boundary_anomaly|metadata_anomaly|local_manipulation_signal|none|unclear"], "evidenceIds": []},
                "multimodalAigcDetection": {"multimodalAigcLabel": "consistent|inconsistent|mixed_generated|suspicious|unclear|not_applicable", "checkedModalityPairs": ["text_image|text_video|image_video|image_ocr|video_audio|video_subtitle|text_image_video|other"], "multimodalSignalLabels": ["text_image_mismatch|text_video_mismatch|image_video_mismatch|image_ocr_mismatch|audio_visual_mismatch|subtitle_visual_mismatch|caption_context_mismatch|cross_modal_source_mismatch|mixed_generation_signal|none|unclear"], "evidenceIds": []},
                "aigcDetectionConfidence": 0.0
              },
              "annotations": {
                "highValueSubjective": {
                  "ideology": {"ideologyLabel": "left_leaning|right_leaning|liberal|conservative|nationalist|populist|pro_government|anti_government|pro_western|anti_western|neutral|not_obvious|mixed|unclear|other", "ideologyConfidence": 0.0, "evidenceIds": []},
                  "coreStance": {"stanceTarget": {"targetType": "event|issue|policy|action|person|organization|country_or_region|ideology_or_value|other|unclear", "targetText": "..."}, "stanceLabel": "support|oppose|neutral|mixed|unclear", "stanceStrength": "weak|medium|strong|unclear", "coreStanceConfidence": 0.0, "evidenceIds": []},
                  "opinionEmotion": {"sentimentPolarity": "positive|negative|neutral|mixed|unclear", "emotionLabels": ["anger|fear|sadness|anxiety|disgust|contempt|joy|hope|sympathy|surprise|none|unclear"], "emotionIntensity": "low|medium|high|unclear|not_applicable", "opinionEmotionConfidence": 0.0, "evidenceIds": []},
                  "languageStyle": {"styleLabels": ["neutral|aggressive|sarcastic|mocking|alarmist|threatening|sensationalized|emotional|conspiratorial|accusatory|slogan_like|rhetorical_questioning|rational_analytical|unclear|not_applicable"], "languageStyleConfidence": 0.0, "evidenceIds": []},
                  "manipulationMethod": {"methodLabels": ["engage|explain|excite|enhance|dismiss|distort|dismay|distract"], "manipulationMethodConfidence": 0.0, "evidenceIds": []},
                  "riskLevel": {"riskLabel": "none|low|medium|high|severe|unclear", "riskTypes": ["misinformation|rumor|polarization|hostility|panic_amplification|mobilization_risk|reputation_attack|manipulation|none|unclear"], "riskLevelConfidence": 0.0, "evidenceIds": []}
                },
                "basicObjective": {
                  "topicTags": {"primaryDomain": "politics|military|economy_finance|technology_cyber|public_health|social_livelihood|ethnic_religious|energy_environment|disaster_accident|crime_public_safety|culture_education|migration_refugee|other|unclear", "topicTagsConfidence": 0.0, "evidenceIds": []},
                  "entitiesHint": [{"entityHintId": "ent_001", "text": "...", "typeHint": "persons|organizations|events|locations|media_contents|social_accounts|narratives|others|unknown", "entityHintConfidence": 0.0, "evidenceIds": []}],
                  "keywords": [{"keywordText": "...", "keywordConfidence": 0.0, "evidenceIds": []}],
                  "summary": {"summaryText": "...", "summaryConfidence": 0.0},
                  "topicType": {"topicTypeLabel": "military_conflict|diplomatic_dispute|policy_announcement|election_campaign|protest_demonstration|economic_sanction|cyber_incident|public_health_event|disaster_accident|crime_public_safety|social_livelihood_event|public_opinion_event|other|unclear|not_applicable", "topicTypeConfidence": 0.0, "evidenceIds": []}
                }
              },
              "evidenceClues": [{"evidenceId": "ev_001", "evidenceType": "text_span", "source": "text", "evidenceText": "...", "span": [0,10]}],
              "qualityControl": {"needHumanReview": false, "reviewReasons": [], "failedModules": []},
              "overallConfidence": 0.0
            }

            Rules:
            1. Use "not_applicable" and evidenceIds: [] for modalities that are not present in the input.
               When text and at least one image/video are present, make a real multimodal judgment.
            2. There is no accountType or contentPurpose dimension in this schema anymore - do not add them.
            3. Every evidenceId referenced anywhere in the output must have a matching entry in evidenceClues.
            4. Return empty arrays, not null, when a list has no items.
            5. Use "unclear"/"not_applicable" per the field's own enum when signal is insufficient - never guess
               or invent a confident label without support.
            6. entitiesHint / keywords / evidenceClues: keep to at most 10 items each.
            7. manipulationMethod.methodLabels: only include tactics with clear textual evidence; return an
               empty array if none apply. Use lowercase values (engage/explain/excite/...), not capitalized.
            8. coreStance.stanceTarget describes what the content's stance is generically directed at
               (an event/policy/person/etc as a category + a short text description), not a resolved graph entity.
            """;

    private static final String T1_ACCOUNT_SYSTEM_PROMPT = """
            You are an account classification system. Given a social media account profile, return only
            one valid JSON object. Do not output markdown code fences or explanation.

            Required JSON shape:
            {
              "schemaVersion": "t1_annotation_v0.6",
              "accountType": {
                "primaryAccountCategory": {
                  "categoryLabel": "ordinary_user|news_media|state_affiliated_media|government_agency|political_actor|political_party_or_campaign|military_security_agency|international_organization|ngo_or_civil_society|academic_or_expert|commercial_brand|platform_official|influencer_kol|community_group|anonymous_account|suspected_bot_or_automated|unknown|other",
                  "evidenceIds": ["ev_account_001"]
                },
                "accountSubtypeTags": [{"subtypeTag": "free-form finer-grained label, not a fixed enum", "evidenceIds": []}],
                "automationSuspicion": {
                  "suspicionLevel": "none|low|medium|high|unclear",
                  "evidenceIds": []
                }
              },
              "evidenceClues": [{"evidenceId": "ev_account_001", "evidenceType": "profile_text|verification_info|account_metadata|activity_statistics|recent_post_sample|platform_label|other", "sourceField": "display_name|bio|self_declared_location|verified|verified_type|account_entity_type|platform_native_type|account_created_at|followers_count|following_count|subscriber_count|member_count|post_count|view_count|recent_post_sample|other", "metadataSnapshot": {}}],
              "qualityControl": {"needHumanReview": false, "reviewReasons": [], "failedModules": []},
              "overallConfidence": 0.0
            }

            Rules:
            1. Base your judgment on bio, verified status, verifiedType, follower/following/post counts,
               platform, and any recent post samples provided. If the profile is too sparse to judge
               confidently, use categoryLabel="unknown" and suspicionLevel="unclear" rather than guessing.
            2. Every evidenceId referenced must have a matching entry in evidenceClues.
            3. There is no accountTypeConfidence field in this schema - use the top-level overallConfidence
               to express confidence in the whole primaryAccountCategory+accountSubtypeTags+automationSuspicion result.
            4. Account evidence has no evidenceText field - put the actual raw value inside metadataSnapshot instead,
               e.g. {"value": "..."} or {"rawValues": {...}, "derivedValues": {...}}.
            """;

    private static final String T1_EVENT_HEAT_SYSTEM_PROMPT = """
            You are an event heat annotation system. Return only one valid JSON object.
            Estimate heat from event metadata, one-hop related entities, sampled media content,
            and aggregate engagement statistics. Do not output markdown code fences, <think> tags,
            or any explanation outside the JSON.

            Required JSON shape:
            {
              "schemaVersion": "t1_annotation_v0.6",
              "eventHeat": {
                "heatLevel": "low|medium|high|explosive|unclear",
                "heatScore": 0.0,
                "heatSignalTypes": ["content_volume|engagement_surge|rapid_growth|wide_platform_spread|sustained_attention|declining|insufficient_data|unclear"],
                "reasoning": "brief reason"
              },
              "overallConfidence": 0.0
            }

            Rules:
            1. Use unclear with insufficient_data when there are no related media_content samples.
            2. heatScore must be between 0.0 and 1.0 (not 0-100).
            3. Use aggregateStats.totalRelatedContentCount as the true content count, not the sample size.
            4. Consider total engagement, platform spread, and temporal spread when selecting heatSignalTypes.
            5. explosive must reflect rapid growth within a short time window, not just a high cumulative total.
            6. There is no eventId field and no eventHeat.confidence field in this schema - do not add them,
               use the top-level overallConfidence only.
            """;

    private static final String T2_SYSTEM_PROMPT_V11 = """
            You are an information extraction system. Return only one valid JSON object.
            Extract mention-level entities and relation mentions from the input text.

            Required JSON shape:
            {
              "contentId": "same contentId from request or null",
              "entities": [
                {
                  "mentionId": "m1",
                  "name": "surface form in text",
                  "canonicalName": "canonical cross-lingual name",
                  "type": "person|organization|event|location",
                  "span": {"start": 0, "end": 10},
                  "aliases": [],
                  "importanceScore": 0.0,
                  "confidence": 0.0,
                  "attributes": {}
                }
              ],
              "relations": [
                {
                  "relationMentionId": "r1",
                  "subjectMentionId": "m1",
                  "predicate": "SUPPORTS",
                  "objectMentionId": "m2",
                  "confidence": 0.0,
                  "evidence": "short evidence span"
                }
              ],
              "resolvedAuthorAccountId": null,
              "modelVersion": "qwen3-vl-32b"
            }

            Rules:
            - relation subjectMentionId and objectMentionId must refer to entities[].mentionId.
            - predicate must be one of these valid values: HAS_ACCOUNT, BELONGS_TO, PART_OF, PUBLISHED_BY, REPLY_TO,
              REPOSTS, MENTIONS, DESCRIBES, EVENT_OCCURRED_AT, EVENT_INVOLVES_ENTITY, LOCATED_IN, SUPPORTS,
              OPPOSES, QUESTIONS, INCITES, DE_ESCALATES.
            - Events are entities with type="event"; put eventType/eventTimeStart in attributes.
            - Return empty arrays when no entity or relation exists.
            """;

    private static final String T3_SYSTEM_PROMPT = """
            你是一个实体消歧专家，专门判断不同语言表述的实体是否指向同一个真实世界对象。
            严格按照 JSON 格式输出，不要有 markdown 代码块，不要有解释说明。

            输出 JSON 结构：
            {
              "mergeGroups": [
                {
                  "survivorId": "保留的实体ID（importanceScore最高的那个）",
                  "mergedIds": ["被合并的实体ID列表"],
                  "confidence": 0.92,
                  "matchMethod": "cross_language|alias_match|semantic_similarity|identifier_match"
                }
              ],
              "disjointPairs": [],
              "uncertain": ["无法判断的实体ID列表"]
            }

            判断规则：
            1. survivorId 必须是输入列表中某个实体的 id，不能凭空创造
            2. confidence >= 0.8 才放入 mergeGroups，否则放入 uncertain
            3. 同一平台相同 sourceIdentifiers 的实体必须合并（identifier_match，confidence=0.98）
            4. 跨语言同名（如"拜登"和"Biden"）放入 mergeGroups（cross_language，confidence=0.92）
            5. 明确不同的实体放入 disjointPairs
            6. 没有重复实体时 mergeGroups 返回空数组
            """;

    private static final String T5_SYSTEM_PROMPT = """
            你是一个专业的认知操控目标分析系统。根据提供的人物信息和内容数据，生成该人物的全息画像。
            严格按照 JSON 格式输出，不要有 markdown 代码块，不要有解释说明。

            输出 JSON 结构（所有字段必须有值，不能返回 null）：
            {
              "politicalOrientation": "pro_west|anti_west|neutral|complex",
              "politicalScore": 0.0（-100亲西方 到 100反西方）,
              "politicalConfidence": 0.85,
              "emotionProfile": {"moral_outrage": 0.5, "resentment": 0.3, "fear_inducing": 0.2, "national_pride": 0.4, "conspiracy_belief": 0.3, "victimhood_narrative": 0.2, "contempt": 0.1, "distrust": 0.4, "hope_appeal": 0.2, "helplessness": 0.1},
              "stanceProfile": [{"topic": "议题", "stance": "strongly_support|support|neutral|oppose|strongly_oppose", "confidence": 0.85}],
              "activeTimePattern": {"0": 0.01, "14": 0.15},
              "postFrequencyDaily": 3.5,
              "contentOriginalRatio": 0.4,
              "bendProfile": {"Distort": 0.3, "Dismiss": 0.2, "Amplify": 0.3, "Narrativize": 0.1, "other": 0.1},
              "influenceScore": 65.0,
              "reachScore": 55.0,
              "viralityScore": 45.0,
              "mbtiType": "ENTJ",
              "mbtiConfidence": 0.6,
              "decisionStyle": "aggressive|cautious|opportunistic|ideological",
              "languageStyle": "inciting|rational|emotional|neutral",
              "interestDomains": ["geopolitics", "military"],
              "coordinationNetwork": [],
              "preferredNarratives": [],
              "targetType": "T00|T01|T02|T03|T04|T05|T06|T07|T08|T09|T10",
              "targetConfidence": 0.8,
              "targetEvidence": "中文证据说明",
              "hiddenRelations": [],
              "manipulationRisk": "critical|high|medium|low",
              "manipulationScore": 50.0,
              "modelVersions": {"t5_model": "qwen3-vl-32b"}
            }

            targetType 定义：T00=正常用户，T01-T04=内容操控，T05-T07=协调操控，T08-T10=高级威胁
            bendProfile 五项之和必须等于 1.0
            emotionProfile 每项 0-1 之间
            """;

    private static final String T6_SYSTEM_PROMPT = """
            你是一个信息操控目标识别专家，专门分析社交媒体账号的操控行为模式。
            严格按照 JSON 格式输出，不要有 markdown 代码块，不要有解释说明。

            输出 JSON 结构：
            {
              "accountIdentifyResult": [
                {
                  "accountId": "账号ID（必须是输入列表中的ID）",
                  "targetType": "T00|T01-T10|T??",
                  "targetTypeName": "中文名称",
                  "confidence": 0.85,
                  "csiScore": 0.72,
                  "evidence": {
                    "matchedTacticId": "T08",
                    "distributionSimilarity": 0.91,
                    "sequenceSimilarity": 0.83,
                    "combinedScore": 0.87,
                    "evidence": "中文证据说明（具体说明判断依据）"
                  },
                  "filterReason": null
                }
              ],
              "entityIdentifyResult": [],
              "groupIdentifyResult": [],
              "summary": {
                "narrativeId": "叙事ID或null",
                "totalAccounts": 10,
                "t00Count": 7,
                "suspectCount": 0,
                "identifiedCount": 3,
                "unknownCount": 0,
                "groupCount": 0,
                "processingTimeMs": 0
              }
            }

            targetType 定义：T00=正常用户，T01=内容扭曲者，T02=信息压制者，T03=叙事放大者，
            T04=叙事构建者，T05=协调分发者，T06=情感操控者，T07=虚假信源，T08=协调网络核心节点，
            T09=跨平台协调者，T10=高级持续威胁，T??=可疑但无法确定

            判断要点：
            1. 每个输入账号都必须在 accountIdentifyResult 里有对应结果
            2. accountId 必须是输入列表里的原始 accountId，不能修改
            3. 关注发帖频率、内容重复度、与其他账号的协同程度、注册时间
            4. 正常用户（T00）的 filterReason 说明原因：entropy_high/csi_low/authority_low/all_three
            5. summary 里的计数必须和 accountIdentifyResult 里的实际数量一致
            """;

    private static final List<String> BEND_KEYS = List.of("Distort", "Dismiss", "Amplify", "Narrativize", "other");

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final PersonMapper personMapper;
    private final MediaContentMapper mediaContentMapper;
    private final Neo4jGraphService neo4jGraphService;
    private final RestClient embeddingRestClient = RestClient.create();

    @Value("${llm.embedding.base-url}")
    private String embeddingBaseUrl;

    @Value("${llm.embedding.api-key}")
    private String embeddingApiKey;

    @Value("${llm.embedding.model}")
    private String embeddingModel;

    @PostMapping("/t1/annotate_content")
    public T1AnnotateResponse annotate(@RequestBody T1AnnotateRequest request) {
        boolean hasText = request.getText() != null && !request.getText().isBlank();
        boolean hasImages = request.getMedias() != null
                && request.getMedias().stream().anyMatch(m -> "image".equals(m.getMediaType()));
        boolean hasVideos = request.getMedias() != null
                && request.getMedias().stream().anyMatch(m -> "video".equals(m.getMediaType()));

        log.info("[LLM-T1] annotate_content, hasText={}, hasImages={}, hasVideos={}", hasText, hasImages, hasVideos);

        String userPrompt = buildT1UserPrompt(request, hasText, hasImages, hasVideos);

        try {
            String raw = callJsonLlm(T1_SYSTEM_PROMPT, userPrompt);

            T1AnnotateResponse response = parseT1Response(raw, request, hasText, hasImages, hasVideos);
            response.setProcessedAt(java.time.OffsetDateTime.now().toString());
            response.setLanguage(request.getLanguage());
            return response;

        } catch (Exception e) {
            logLlmFailure("[LLM-T1] annotate_content failed, returning fallback", e);
            T1AnnotateResponse response = buildFallbackT1Response(request, hasText, hasImages, hasVideos);
            response.setLanguage(request.getLanguage());
            return response;
        }
    }

    @PostMapping("/t1/annotate_account_type")
    public T1AnnotateAccountResponse annotateAccount(@RequestBody T1AnnotateAccountRequest request) {
        log.info("[LLM-T1] annotate_account_type, platform={}, handle={}", request.getPlatform(), request.getHandle());

        String userPrompt = buildT1AccountUserPrompt(request);

        try {
            String raw = callJsonLlm(T1_ACCOUNT_SYSTEM_PROMPT, userPrompt);
            T1AnnotateAccountResponse response = objectMapper.readValue(cleanJson(raw), T1AnnotateAccountResponse.class);
            if (response.getSchemaVersion() == null) {
                response.setSchemaVersion("t1_annotation_v0.6");
            }
            response.setProcessedAt(java.time.OffsetDateTime.now().toString());
            return response;
        } catch (Exception e) {
            logLlmFailure("[LLM-T1] annotate_account_type failed, returning fallback", e);
            return buildFallbackT1AccountResponse();
        }
    }

    @PostMapping("/t1/annotate_event_heat")
    public T1AnnotateEventHeatResponse annotateEventHeat(@RequestBody T1AnnotateEventHeatRequest request) {
        log.info("[LLM-T1] annotate_event_heat, hasEvent={}, relatedEntities={}",
                request.getEvent() != null,
                request.getRelatedEntities() != null ? request.getRelatedEntities().size() : 0);

        String userPrompt = buildT1EventHeatUserPrompt(request);
        try {
            String raw = callJsonLlm(T1_EVENT_HEAT_SYSTEM_PROMPT, userPrompt);
            T1AnnotateEventHeatResponse response =
                    objectMapper.readValue(cleanJson(raw), T1AnnotateEventHeatResponse.class);
            if (response.getSchemaVersion() == null) {
                response.setSchemaVersion("t1_annotation_v0.6");
            }
            response.setProcessedAt(java.time.OffsetDateTime.now().toString());
            return response;
        } catch (Exception e) {
            logLlmFailure("[LLM-T1] annotate_event_heat failed, returning fallback", e);
            return buildFallbackT1EventHeatResponse(request);
        }
    }

    @PostMapping("/t2/extract_entities")
    public T2ExtractResponse extractEntities(@RequestBody T2ExtractRequest request) {
        log.info("[LLM-T2] extract_entities, textLength={}",
                request.getText() != null ? request.getText().length() : 0);

        String userPrompt = buildT2UserPrompt(request);

        try {
            String raw = callJsonLlm(T2_SYSTEM_PROMPT_V11, userPrompt);

            T2ExtractResponse response = parseT2Response(raw);
            filterUnsupportedRelationships(response);
            return response;

        } catch (Exception e) {
            logLlmFailure("[LLM-T2] extract_entities失败，返回fallback", e);
            return buildFallbackT2Response();
        }
    }

    @PostMapping("/t3/resolve_batch")
    public T3ResolveBatchResponse resolveBatch(@RequestBody T3ResolveBatchRequest request) {
        int itemCount = request.getItems() != null ? request.getItems().size() : 0;
        log.info("[LLM-T3] resolve_batch, itemCount={}", itemCount);

        String userPrompt = buildT3BatchUserPrompt(request);
        try {
            String raw = callJsonLlm(T3_SYSTEM_PROMPT, userPrompt);
            return parseT3BatchResponse(raw, request);
        } catch (Exception e) {
            logLlmFailure("[LLM-T3] resolve_batch失败，返回fallback", e);
            return buildFallbackT3BatchResponse(request);
        }
    }

    @PostMapping("/t4/generate_text_embedding")
    public T4EmbeddingResponse generateTextEmbedding(@RequestBody T4EmbeddingRequest request) {
        log.info("[LLM-T4] generate_text_embedding, textLength={}",
                request.getText() != null ? request.getText().length() : 0);
        return callEmbeddingApi(request.getText(), null);
    }

    @PostMapping("/t4/generate_image_embedding")
    public T4EmbeddingResponse generateImageEmbedding(@RequestBody T4EmbeddingRequest request) {
        log.info("[LLM-T4] generate_image_embedding, imageUrl={}", request.getImageUrl());
        return callEmbeddingApi(null, request.getImageUrl());
    }

    @PostMapping("/t5/complete_profile")
    public T5GenerateProfileResponse completeProfile(@RequestBody T5GenerateProfileRequest request) {
        log.info("[LLM-T5] complete_profile, targetId={}, targetType={}",
                request.getTargetId(), request.getTargetType());

        String context = buildPersonContext(request.getTargetId());
        try {
            String raw = callJsonLlm(T5_SYSTEM_PROMPT, "请根据以下上下文生成人物画像：\n\n" + context);

            return parseT5Response(raw);
        } catch (Exception e) {
            logLlmFailure("[LLM-T5] complete_profile失败，返回fallback", e);
            return buildFallbackT5Response(context);
        }
    }

    @PostMapping("/t6/identify_targets")
    public T6IdentifyResponse identifyTargets(@RequestBody T6IdentifyRequest request) {
        long startedAt = System.nanoTime();
        log.info("[LLM-T6] identify_targets, narrativeId={}, accountCount={}, contentCount={}",
                request.getNarrativeId(),
                request.getSocialAccounts() != null ? request.getSocialAccounts().size() : 0,
                request.getMediaContents() != null ? request.getMediaContents().size() : 0);

        String userPrompt = buildT6UserPrompt(request);
        try {
            String raw = callJsonLlm(T6_SYSTEM_PROMPT, userPrompt, 8192);

            T6IdentifyResponse response = parseT6Response(raw, request);
            normalizeT6Summary(response, request, startedAt);
            return response;
        } catch (Exception e) {
            logLlmFailure("[LLM-T6] identify_targets失败，返回fallback", e);
            T6IdentifyResponse response = buildFallbackT6Response(request);
            normalizeT6Summary(response, request, startedAt);
            return response;
        }
    }

    private String buildT1UserPrompt(T1AnnotateRequest request, boolean hasText, boolean hasImages, boolean hasVideos) {
        StringBuilder sb = new StringBuilder();
        sb.append("Annotate the following content.\n\n");
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            sb.append("Title: ").append(request.getTitle()).append("\n");
        }
        sb.append("Language: ").append(request.getLanguage() != null ? request.getLanguage() : "unknown").append("\n");
        if (request.getContext() != null) {
            T1AnnotateRequest.Context context = request.getContext();
            if (context.getPlatform() != null) {
                sb.append("Platform: ").append(context.getPlatform()).append("\n");
            }
            if (context.getHashtags() != null && !context.getHashtags().isEmpty()) {
                sb.append("Hashtags: ").append(String.join(", ", context.getHashtags())).append("\n");
            }
            if (context.getLikeCount() != null) {
                sb.append("Engagement: likes=").append(context.getLikeCount())
                        .append(", comments=").append(context.getCommentCount())
                        .append(", shares=").append(context.getShareCount())
                        .append(", reposts=").append(context.getRepostCount())
                        .append(", views=").append(context.getViewCount()).append("\n");
            }
        }
        if (hasText) {
            sb.append("\nText content:\n").append(request.getText()).append("\n");
        }
        if (hasImages) {
            sb.append("\nImages (with mediaId for evidence_clues.media_id attribution):\n");
            request.getMedias().stream().filter(m -> "image".equals(m.getMediaType()))
                    .forEach(m -> sb.append("- mediaId=").append(m.getId()).append(", url=").append(m.getUrl()).append("\n"));
        }
        if (hasVideos) {
            sb.append("\nVideos (with mediaId for evidence_clues.media_id attribution):\n");
            request.getMedias().stream().filter(m -> "video".equals(m.getMediaType()))
                    .forEach(m -> sb.append("- mediaId=").append(m.getId()).append(", url=").append(m.getUrl()).append("\n"));
        }
        sb.append("\nOnly fill in AIGC detection / annotation fields for modalities that are actually present above; ")
                .append("use not_applicable for missing modalities. If both text and at least one image/video are ")
                .append("present, attempt a genuine multimodalAigcDetection judgment comparing them, not just ")
                .append("not_applicable.");
        return sb.toString();
    }

    private String buildT1AccountUserPrompt(T1AnnotateAccountRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Classify the following account:\n\n");
        sb.append("Platform: ").append(request.getPlatform()).append("\n");
        sb.append("Handle: ").append(request.getHandle()).append("\n");
        sb.append("Display name: ").append(request.getDisplayName()).append("\n");
        sb.append("Bio: ").append(request.getBio() != null ? request.getBio() : "(none)").append("\n");
        sb.append("Verified: ").append(request.getVerified()).append(", type: ").append(request.getVerifiedType()).append("\n");
        sb.append("Followers: ").append(request.getFollowersCount())
                .append(", Following: ").append(request.getFollowingCount())
                .append(", Posts: ").append(request.getPostCount()).append("\n");
        if (request.getRecentPostSamples() != null && !request.getRecentPostSamples().isEmpty()) {
            sb.append("Recent posts:\n");
            for (String sample : request.getRecentPostSamples()) {
                sb.append("- ").append(sample).append("\n");
            }
        }
        return sb.toString();
    }

    private String buildT1EventHeatUserPrompt(T1AnnotateEventHeatRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Annotate event heat for the following event.\n\n");
        if (request.getEvent() != null) {
            T1AnnotateEventHeatRequest.EventInfo event = request.getEvent();
            sb.append("Canonical name: ").append(event.getCanonicalName()).append("\n");
            sb.append("Event type: ").append(event.getEventType()).append("\n");
            sb.append("Occurred at: ").append(event.getOccurredAtStart())
                    .append(" to ").append(event.getOccurredAtEnd()).append("\n");
            sb.append("Country: ").append(event.getCountry()).append("\n");
        }
        if (request.getAggregateStats() != null) {
            T1AnnotateEventHeatRequest.AggregateStats stats = request.getAggregateStats();
            sb.append("\nAggregate stats:\n");
            sb.append("Total related content count: ").append(stats.getTotalRelatedContentCount()).append("\n");
            sb.append("Total engagement: ").append(stats.getTotalEngagement()).append("\n");
            sb.append("Distinct platform count: ").append(stats.getDistinctPlatformCount()).append("\n");
            sb.append("Earliest content at: ").append(stats.getEarliestContentAt()).append("\n");
            sb.append("Latest content at: ").append(stats.getLatestContentAt()).append("\n");
        }
        if (request.getRelatedEntities() != null && !request.getRelatedEntities().isEmpty()) {
            sb.append("\nRelated entities and sampled content:\n");
            for (T1AnnotateEventHeatRequest.RelatedEntity entity : request.getRelatedEntities()) {
                sb.append("- type=").append(entity.getEntityType())
                        .append(", id=").append(entity.getEntityId())
                        .append(", name=").append(entity.getName());
                if ("media_content".equals(entity.getEntityType())) {
                    sb.append(", platform=").append(entity.getPlatform())
                            .append(", publishedAt=").append(entity.getPublishedAt())
                            .append(", likes=").append(entity.getLikeCount())
                            .append(", comments=").append(entity.getCommentCount())
                            .append(", shares=").append(entity.getShareCount())
                            .append(", reposts=").append(entity.getRepostCount())
                            .append(", views=").append(entity.getViewCount());
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private T1AnnotateResponse parseT1Response(String raw, T1AnnotateRequest request,
                                               boolean hasText, boolean hasImages, boolean hasVideos)
            throws Exception {
        T1AnnotateResponse response = objectMapper.readValue(cleanJson(raw), T1AnnotateResponse.class);
        if (response.getSchemaVersion() == null) {
            response.setSchemaVersion("t1_annotation_v0.6");
        }
        if (response.getInputReference() == null) {
            response.setInputReference(buildT1InputReference(request, hasText, hasImages, hasVideos));
        }
        return response;
    }

    private T1AnnotateResponse.InputReference buildT1InputReference(
            T1AnnotateRequest request, boolean hasText, boolean hasImages, boolean hasVideos) {
        T1AnnotateResponse.InputReference ref = new T1AnnotateResponse.InputReference();
        T1AnnotateRequest.Context context = request.getContext();
        ref.setContentId(context != null ? context.getContentId() : null);
        ref.setContentType(context != null ? context.getContentType() : null);
        ref.setModalityCombination(resolveT1ModalityCombination(hasText, hasImages, hasVideos));
        ref.setPlatform(context != null ? context.getPlatform() : null);
        ref.setUrl(context != null ? context.getUrl() : null);
        ref.setAuthorId(context != null ? context.getAuthorHandle() : null);
        ref.setCreatedAt(context != null ? context.getPublishedAt() : null);
        return ref;
    }

    private String resolveT1ModalityCombination(boolean hasText, boolean hasImages, boolean hasVideos) {
        if (hasText && hasImages && hasVideos) {
            return "text_image_video";
        }
        if (hasText && hasImages) {
            return "text_image";
        }
        if (hasText && hasVideos) {
            return "text_video";
        }
        if (hasImages && hasVideos) {
            return "image_video";
        }
        if (hasVideos) {
            return "video";
        }
        if (hasImages) {
            return "image";
        }
        return "text";
    }

    private T1AnnotateResponse buildFallbackT1Response(
        T1AnnotateRequest request, boolean hasText, boolean hasImages, boolean hasVideos) {
        T1AnnotateResponse resp = new T1AnnotateResponse();
        resp.setSchemaVersion("t1_annotation_v0.6");
        resp.setInputReference(buildT1InputReference(request, hasText, hasImages, hasVideos));
        resp.setEvidenceClues(List.of());

        T1AnnotateResponse.QualityControl qc = new T1AnnotateResponse.QualityControl();
        qc.setNeedHumanReview(true);
        qc.setReviewReasons(List.of("module_failure"));
        qc.setFailedModules(List.of(
                "textAigcDetection", "ideology", "coreStance", "opinionEmotion", "languageStyle",
                "manipulationMethod", "riskLevel", "topicTags", "entitiesHint", "keywords",
                "summary", "topicType"));
        resp.setQualityControl(qc);

        resp.setOverallConfidence(0.0);
        resp.setProcessedAt(java.time.OffsetDateTime.now().toString());
        return resp;
    }

    private T1AnnotateAccountResponse buildFallbackT1AccountResponse() {
        T1AnnotateAccountResponse.AccountType.PrimaryAccountCategory primaryAccountCategory =
                new T1AnnotateAccountResponse.AccountType.PrimaryAccountCategory();
        primaryAccountCategory.setCategoryLabel("unknown");
        primaryAccountCategory.setEvidenceIds(List.of());

        T1AnnotateAccountResponse.AccountType.AutomationSuspicion automationSuspicion =
                new T1AnnotateAccountResponse.AccountType.AutomationSuspicion();
        automationSuspicion.setSuspicionLevel("unclear");
        automationSuspicion.setEvidenceIds(List.of());

        T1AnnotateAccountResponse.AccountType accountType = new T1AnnotateAccountResponse.AccountType();
        accountType.setPrimaryAccountCategory(primaryAccountCategory);
        accountType.setAccountSubtypeTags(List.of());
        accountType.setAutomationSuspicion(automationSuspicion);

        T1AnnotateAccountResponse resp = new T1AnnotateAccountResponse();
        resp.setSchemaVersion("t1_annotation_v0.6");
        resp.setAccountType(accountType);
        resp.setEvidenceClues(List.of());

        T1AnnotateResponse.QualityControl qc = new T1AnnotateResponse.QualityControl();
        qc.setNeedHumanReview(true);
        qc.setReviewReasons(List.of("module_failure"));
        qc.setFailedModules(List.of("primaryAccountCategory", "accountSubtypeTags", "automationSuspicion"));
        resp.setQualityControl(qc);

        resp.setOverallConfidence(0.0);
        resp.setProcessedAt(java.time.OffsetDateTime.now().toString());
        return resp;
    }

    private T1AnnotateEventHeatResponse buildFallbackT1EventHeatResponse(T1AnnotateEventHeatRequest request) {
        T1AnnotateEventHeatResponse.EventHeat heat = new T1AnnotateEventHeatResponse.EventHeat();
        heat.setHeatLevel("unclear");
        heat.setHeatScore(null);
        heat.setHeatSignalTypes(List.of("insufficient_data"));
        heat.setReasoning("Event heat annotation failed or did not return a valid result.");

        T1AnnotateEventHeatResponse resp = new T1AnnotateEventHeatResponse();
        resp.setSchemaVersion("t1_annotation_v0.6");
        resp.setEventHeat(heat);
        resp.setOverallConfidence(0.0);
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

        if (request.getContext() != null && request.getContext().getHashtags() != null
                && request.getContext().getHashtags().length > 0) {
            sb.append("话题标签：").append(String.join(", ", request.getContext().getHashtags())).append("\n");
        }
        if (request.getContext() != null) {
            sb.append("平台：").append(request.getContext().getPlatform()).append("\n");
        }
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            sb.append("Title: ").append(request.getTitle()).append("\n");
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
                        && AllowedRelationTypes.VALUES.contains(relation.getRelationType()))
                .toList());
    }

    private T2ExtractResponse buildFallbackT2Response() {
        T2ExtractResponse resp = new T2ExtractResponse();
        resp.setEntities(List.of());
        resp.setRelationships(List.of());
        resp.setEvents(List.of());
        return resp;
    }

    private String callJsonLlm(String systemPrompt, String userPrompt) {
        return callJsonLlm(systemPrompt, userPrompt, null);
    }

    private String callJsonLlm(String systemPrompt, String userPrompt, Integer maxTokens) {
        return chatClient.prompt()
                .options(jsonOnlyOptions(maxTokens))
                .system(systemPrompt + """

                        硬性输出约束：
                        - 只输出一个合法 JSON 对象。
                        - 不要输出“首先”“下面”“分析”“解释”等自然语言前缀。
                        - 不要输出 <think>、推理过程、markdown、代码块。
                        - 如果信息不足，也必须按指定 JSON 结构填默认值或空数组。
                        """)
                .user(userPrompt)
                .call()
                .content();
    }

    private OpenAiChatOptions.Builder jsonOnlyOptions() {
        return jsonOnlyOptions(null);
    }

    private OpenAiChatOptions.Builder jsonOnlyOptions(Integer maxTokens) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .temperature(0.1)
                .responseFormat(OpenAiChatModel.ResponseFormat.builder()
                        .type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
                        .build())
                .extraBody(Map.of(
                        "enable_thinking", false,
                        "chat_template_kwargs", Map.of("enable_thinking", false)
                ));
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }
        return builder;
    }

    private String buildT3BatchUserPrompt(T3ResolveBatchRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Resolve each mention against its candidate list.\n");
        sb.append("Return JSON with results[].mentionId, action(MERGE/CREATE/REVIEW), matchedEntityId, ")
                .append("score, confidence, matchMethod, reason and modelVersion.\n\n");
        if (request.getItems() != null) {
            for (T3ResolveBatchRequest.ResolveItem item : request.getItems()) {
                T3ResolveBatchRequest.Mention mention = item.getMention();
                sb.append("Mention: ")
                        .append(mention != null ? mention.getMentionId() : null)
                        .append(" | type=").append(mention != null ? mention.getType() : null)
                        .append(" | name=").append(mention != null ? mention.getCanonicalName() : null)
                        .append("\nCandidates: ").append(item.getCandidates())
                        .append("\nContext: ").append(item.getContext())
                        .append("\n\n");
            }
        }
        return sb.toString();
    }

    private T3ResolveBatchResponse parseT3BatchResponse(String raw, T3ResolveBatchRequest request) throws Exception {
        T3ResolveBatchResponse response = objectMapper.readValue(cleanJson(raw), T3ResolveBatchResponse.class);
        normalizeT3BatchResponse(response, request);
        return response;
    }

    private void normalizeT3BatchResponse(T3ResolveBatchResponse response, T3ResolveBatchRequest request) {
        Set<String> mentionIds = request.getItems() == null ? Set.of() : request.getItems().stream()
                .map(T3ResolveBatchRequest.ResolveItem::getMention)
                .filter(Objects::nonNull)
                .map(T3ResolveBatchRequest.Mention::getMentionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (response.getResults() == null) {
            response.setResults(List.of());
        }
        response.setResults(response.getResults().stream()
                .filter(item -> item != null && mentionIds.contains(item.getMentionId()))
                .peek(item -> {
                    if (item.getAction() == null) {
                        item.setAction("CREATE");
                    }
                })
                .toList());
        if (response.getModelVersion() == null) {
            response.setModelVersion(MODEL_VERSION);
        }
    }

    private T3ResolveBatchResponse buildFallbackT3BatchResponse(T3ResolveBatchRequest request) {
        T3ResolveBatchResponse resp = new T3ResolveBatchResponse();
        resp.setResults(request.getItems() == null ? List.of() : request.getItems().stream()
                .map(item -> {
                    T3ResolveBatchResponse.ResolveResult result = new T3ResolveBatchResponse.ResolveResult();
                    T3ResolveBatchRequest.Mention mention = item.getMention();
                    result.setMentionId(mention != null ? mention.getMentionId() : null);
                    result.setAction("CREATE");
                    result.setScore(0D);
                    result.setConfidence(0D);
                    result.setMatchMethod("fallback");
                    result.setReason("T3 batch fallback");
                    return result;
                })
                .toList());
        resp.setModelVersion(MODEL_VERSION);
        return resp;
    }

    private T4EmbeddingResponse callEmbeddingApi(String text, String imageUrl) {
        String input = hasText(text) ? text : imageUrl;
        if (!hasText(input)) {
            return null;
        }
        try {
            EmbeddingApiResponse apiResponse = embeddingRestClient.post()
                    .uri(normalizeBaseUrl(embeddingBaseUrl) + "/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + embeddingApiKey)
                    .body(Map.of("model", embeddingModel, "input", input))
                    .retrieve()
                    .body(EmbeddingApiResponse.class);

            if (apiResponse == null || apiResponse.getData() == null || apiResponse.getData().isEmpty()
                    || apiResponse.getData().get(0).getEmbedding() == null) {
                return null;
            }

            List<Double> doubles = apiResponse.getData().get(0).getEmbedding();
            float[] embedding = new float[doubles.size()];
            for (int i = 0; i < doubles.size(); i++) {
                embedding[i] = doubles.get(i).floatValue();
            }

            T4EmbeddingResponse response = new T4EmbeddingResponse();
            response.setEmbedding(embedding);
            response.setModelVersion(embeddingModel);
            return response;
        } catch (Exception e) {
            logLlmFailure("[LLM-T4] embedding接口调用失败，返回null", e);
            return null;
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String buildPersonContext(String targetId) {
        UUID personId;
        try {
            personId = UUID.fromString(targetId);
        } catch (Exception e) {
            return "人物ID格式无效：" + targetId;
        }

        Person person = personMapper.selectById(personId);
        if (person == null) {
            return "未找到该人物信息";
        }

        StringBuilder ctx = new StringBuilder();
        ctx.append("人物基本信息：\n");
        ctx.append("  姓名：").append(person.getCanonicalName()).append("\n");
        ctx.append("  重要性评分：").append(person.getImportanceScore()).append("\n");
        ctx.append("  首次出现：").append(person.getFirstSeenAt()).append("\n");
        ctx.append("  内容出现次数：").append(person.getContentCount()).append("\n");

        List<UUID> accountIds = neo4jGraphService.findSocialAccountIdsByPerson(personId.toString());
        if (!accountIds.isEmpty()) {
            ctx.append("  关联账号数：").append(accountIds.size()).append("\n");
        }

        if (!accountIds.isEmpty()) {
            List<MediaContent> contents = mediaContentMapper.selectByAuthorAccountIds(accountIds, 50);
            if (!contents.isEmpty()) {
                ctx.append("\n最近发布的内容（最多50条）：\n");
                for (MediaContent mc : contents) {
                    ctx.append("  [").append(mc.getPlatform()).append("] ");
                    ctx.append(mc.getPublishedAt()).append(": ");
                    if (hasText(mc.getBodyText())) {
                        ctx.append(mc.getBodyText(), 0, Math.min(mc.getBodyText().length(), 200));
                    }
                    ctx.append("\n");
                }
            }
        }

        return ctx.toString();
    }

    private T5GenerateProfileResponse parseT5Response(String raw) throws Exception {
        T5GenerateProfileResponse response = objectMapper.readValue(cleanJson(raw), T5GenerateProfileResponse.class);
        normalizeT5Response(response);
        return response;
    }

    private void normalizeT5Response(T5GenerateProfileResponse response) {
        if (!hasText(response.getPoliticalOrientation())) {
            response.setPoliticalOrientation("neutral");
        }
        response.setPoliticalScore(defaultBigDecimal(response.getPoliticalScore(), "0"));
        response.setPoliticalConfidence(defaultBigDecimal(response.getPoliticalConfidence(), "0.5"));
        if (response.getEmotionProfile() == null) {
            response.setEmotionProfile(defaultEmotionProfile());
        }
        if (response.getStanceProfile() == null) {
            response.setStanceProfile(List.of());
        }
        if (response.getActiveTimePattern() == null) {
            response.setActiveTimePattern(Map.of("0", 0.0));
        }
        response.setPostFrequencyDaily(defaultBigDecimal(response.getPostFrequencyDaily(), "0"));
        response.setContentOriginalRatio(defaultBigDecimal(response.getContentOriginalRatio(), "0"));
        response.setBendProfile(normalizedBendProfile(response.getBendProfile()));
        response.setInfluenceScore(defaultBigDecimal(response.getInfluenceScore(), "0"));
        response.setReachScore(defaultBigDecimal(response.getReachScore(), "0"));
        response.setViralityScore(defaultBigDecimal(response.getViralityScore(), "0"));
        if (!hasText(response.getMbtiType())) {
            response.setMbtiType("UNKNOWN");
        }
        response.setMbtiConfidence(defaultBigDecimal(response.getMbtiConfidence(), "0"));
        if (!hasText(response.getDecisionStyle())) {
            response.setDecisionStyle("cautious");
        }
        if (!hasText(response.getLanguageStyle())) {
            response.setLanguageStyle("neutral");
        }
        if (response.getInterestDomains() == null) {
            response.setInterestDomains(new String[0]);
        }
        if (response.getCoordinationNetwork() == null) {
            response.setCoordinationNetwork(List.of());
        }
        if (response.getPreferredNarratives() == null) {
            response.setPreferredNarratives(List.of());
        }
        if (!hasText(response.getTargetType())) {
            response.setTargetType("T00");
        }
        response.setTargetConfidence(defaultBigDecimal(response.getTargetConfidence(), "0.5"));
        if (!hasText(response.getTargetEvidence())) {
            response.setTargetEvidence("证据不足，使用兜底画像。");
        }
        if (response.getHiddenRelations() == null) {
            response.setHiddenRelations(List.of());
        }
        if (!hasText(response.getManipulationRisk())) {
            response.setManipulationRisk("low");
        }
        response.setManipulationScore(defaultBigDecimal(response.getManipulationScore(), "0"));
        if (response.getModelVersions() == null) {
            response.setModelVersions(Map.of("t5_model", MODEL_VERSION));
        }
    }

    private T5GenerateProfileResponse buildFallbackT5Response(String context) {
        T5GenerateProfileResponse response = new T5GenerateProfileResponse();
        response.setPoliticalOrientation("neutral");
        response.setPoliticalScore(BigDecimal.ZERO);
        response.setPoliticalConfidence(new BigDecimal("0.3"));
        response.setEmotionProfile(defaultEmotionProfile());
        response.setStanceProfile(List.of());
        response.setActiveTimePattern(Map.of("0", 0.0));
        response.setPostFrequencyDaily(BigDecimal.ZERO);
        response.setContentOriginalRatio(BigDecimal.ZERO);
        response.setBendProfile(defaultBendProfile());
        response.setInfluenceScore(BigDecimal.ZERO);
        response.setReachScore(BigDecimal.ZERO);
        response.setViralityScore(BigDecimal.ZERO);
        response.setMbtiType("UNKNOWN");
        response.setMbtiConfidence(BigDecimal.ZERO);
        response.setDecisionStyle("cautious");
        response.setLanguageStyle("neutral");
        response.setInterestDomains(new String[0]);
        response.setCoordinationNetwork(List.of());
        response.setPreferredNarratives(List.of());
        response.setTargetType("T00");
        response.setTargetConfidence(new BigDecimal("0.3"));
        response.setTargetEvidence("LLM画像生成失败或数据不足，使用兜底画像。上下文摘要：" + abbreviate(context, 200));
        response.setHiddenRelations(List.of());
        response.setManipulationRisk("low");
        response.setManipulationScore(BigDecimal.ZERO);
        response.setModelVersions(Map.of("t5_model", MODEL_VERSION));
        return response;
    }

    private Map<String, Double> normalizedBendProfile(Object value) {
        Map<String, Double> raw = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (String key : BEND_KEYS) {
                raw.put(key, toDouble(map.get(key), 0D));
            }
        } else {
            raw.putAll(defaultBendProfile());
        }

        double total = raw.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0D) {
            return defaultBendProfile();
        }

        Map<String, Double> normalized = new LinkedHashMap<>();
        for (String key : BEND_KEYS) {
            normalized.put(key, BigDecimal.valueOf(raw.getOrDefault(key, 0D) / total)
                    .setScale(6, RoundingMode.HALF_UP)
                    .doubleValue());
        }
        return normalized;
    }

    private Map<String, Double> defaultBendProfile() {
        Map<String, Double> bend = new LinkedHashMap<>();
        bend.put("Distort", 0.2);
        bend.put("Dismiss", 0.2);
        bend.put("Amplify", 0.2);
        bend.put("Narrativize", 0.2);
        bend.put("other", 0.2);
        return bend;
    }

    private Map<String, Double> defaultEmotionProfile() {
        Map<String, Double> emotion = new LinkedHashMap<>();
        for (String key : List.of("moral_outrage", "resentment", "fear_inducing", "national_pride",
                "conspiracy_belief", "victimhood_narrative", "contempt", "distrust", "hope_appeal", "helplessness")) {
            emotion.put(key, 0D);
        }
        return emotion;
    }

    private String buildT6UserPrompt(T6IdentifyRequest request) {
        StringBuilder sb = new StringBuilder();

        if (request.getNarrativeId() != null) {
            sb.append("分析场景：叙事ID ").append(request.getNarrativeId()).append(" 的参与账号\n\n");
        }

        sb.append("待分析账号列表（共").append(
                request.getSocialAccounts() != null ? request.getSocialAccounts().size() : 0
        ).append("个）：\n");
        if (request.getSocialAccounts() != null) {
            for (T6IdentifyRequest.SocialAccountRef sa : request.getSocialAccounts()) {
                sb.append("  账号ID: ").append(sa.getAccountId())
                        .append(" | 平台: ").append(sa.getSourcePlatformId())
                        .append(" | handle: ").append(sa.getHandle());
                if (sa.getAccountMetrics() != null) {
                    sb.append(" | 粉丝: ").append(sa.getAccountMetrics().getFollowers())
                            .append(" | 发帖: ").append(sa.getAccountMetrics().getPosts());
                }
                if (sa.getRegisterTime() != null) {
                    sb.append(" | 注册时间: ").append(sa.getRegisterTime());
                }
                sb.append("\n");
            }
        }

        sb.append("\n最近发布内容（最多20条）：\n");
        if (request.getMediaContents() != null) {
            request.getMediaContents().stream().limit(20).forEach(mc -> {
                sb.append("  [").append(abbreviate(mc.getSourceAccountId(), 8)).append("...] ");
                sb.append(mc.getPublishedAt()).append(" | ").append(mc.getMessageType()).append(": ");
                if (mc.getBodyText() != null) {
                    sb.append(mc.getBodyText(), 0, Math.min(mc.getBodyText().length(), 150));
                }
                sb.append("\n");
            });
        }

        return sb.toString();
    }

    private T6IdentifyResponse parseT6Response(String raw, T6IdentifyRequest request) throws Exception {
        T6IdentifyResponse response = objectMapper.readValue(cleanJson(raw), T6IdentifyResponse.class);
        ensureAllT6Accounts(response, request);
        return response;
    }

    private void ensureAllT6Accounts(T6IdentifyResponse response, T6IdentifyRequest request) {
        if (response.getAccountIdentifyResult() == null) {
            response.setAccountIdentifyResult(new ArrayList<>());
        } else {
            response.setAccountIdentifyResult(new ArrayList<>(response.getAccountIdentifyResult()));
        }

        Set<String> inputIds = request.getSocialAccounts() == null ? Set.of() : request.getSocialAccounts().stream()
                .map(T6IdentifyRequest.SocialAccountRef::getAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        response.setAccountIdentifyResult(response.getAccountIdentifyResult().stream()
                .filter(result -> result != null && inputIds.contains(result.getAccountId()))
                .collect(Collectors.toCollection(ArrayList::new)));

        Set<String> returnedIds = response.getAccountIdentifyResult().stream()
                .map(T6IdentifyResponse.AccountIdentifyResult::getAccountId)
                .collect(Collectors.toSet());
        for (String accountId : inputIds) {
            if (!returnedIds.contains(accountId)) {
                response.getAccountIdentifyResult().add(buildFallbackT6AccountResult(accountId));
            }
        }

        for (T6IdentifyResponse.AccountIdentifyResult result : response.getAccountIdentifyResult()) {
            normalizeT6AccountResult(result);
        }
        if (response.getEntityIdentifyResult() == null) {
            response.setEntityIdentifyResult(List.of());
        }
        if (response.getGroupIdentifyResult() == null) {
            response.setGroupIdentifyResult(List.of());
        }
    }

    private void normalizeT6Summary(T6IdentifyResponse response, T6IdentifyRequest request, long startedAt) {
        if (response.getSummary() == null) {
            response.setSummary(new T6IdentifyResponse.Summary());
        }
        List<T6IdentifyResponse.AccountIdentifyResult> results = response.getAccountIdentifyResult() != null
                ? response.getAccountIdentifyResult() : List.of();
        int total = request.getSocialAccounts() != null ? request.getSocialAccounts().size() : 0;
        int t00 = (int) results.stream().filter(result -> "T00".equals(result.getTargetType())).count();
        int unknown = (int) results.stream().filter(result -> "T??".equals(result.getTargetType())).count();
        int identified = (int) results.stream()
                .filter(result -> result.getTargetType() != null
                        && !"T00".equals(result.getTargetType())
                        && !"T??".equals(result.getTargetType()))
                .count();

        T6IdentifyResponse.Summary summary = response.getSummary();
        summary.setNarrativeId(request.getNarrativeId());
        summary.setTotalAccounts(total);
        summary.setT00Count(t00);
        summary.setSuspectCount(unknown);
        summary.setIdentifiedCount(identified);
        summary.setUnknownCount(unknown);
        summary.setGroupCount(response.getGroupIdentifyResult() != null ? response.getGroupIdentifyResult().size() : 0);
        summary.setProcessingTimeMs(Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    private T6IdentifyResponse buildFallbackT6Response(T6IdentifyRequest request) {
        T6IdentifyResponse response = new T6IdentifyResponse();
        response.setAccountIdentifyResult(request.getSocialAccounts() == null ? List.of() : request.getSocialAccounts().stream()
                .map(T6IdentifyRequest.SocialAccountRef::getAccountId)
                .filter(Objects::nonNull)
                .map(this::buildFallbackT6AccountResult)
                .toList());
        response.setEntityIdentifyResult(List.of());
        response.setGroupIdentifyResult(List.of());
        return response;
    }

    private T6IdentifyResponse.AccountIdentifyResult buildFallbackT6AccountResult(String accountId) {
        T6IdentifyResponse.AccountIdentifyResult result = new T6IdentifyResponse.AccountIdentifyResult();
        result.setAccountId(accountId);
        result.setTargetType("T??");
        result.setTargetTypeName("可疑但无法确定");
        result.setConfidence(new BigDecimal("0.3"));
        result.setCsiScore(BigDecimal.ZERO);
        result.setEvidence(buildFallbackT6Evidence());
        result.setFilterReason(null);
        return result;
    }

    private T6IdentifyResponse.MatchEvidence buildFallbackT6Evidence() {
        T6IdentifyResponse.MatchEvidence evidence = new T6IdentifyResponse.MatchEvidence();
        evidence.setMatchedTacticId("T??");
        evidence.setDistributionSimilarity(0D);
        evidence.setSequenceSimilarity(0D);
        evidence.setCombinedScore(0D);
        evidence.setEvidence("LLM识别失败或结果缺失，使用兜底识别结果。");
        return evidence;
    }

    private void normalizeT6AccountResult(T6IdentifyResponse.AccountIdentifyResult result) {
        if (!hasText(result.getTargetType())) {
            result.setTargetType("T??");
        }
        if (!hasText(result.getTargetTypeName())) {
            result.setTargetTypeName(targetTypeName(result.getTargetType()));
        }
        result.setConfidence(defaultBigDecimal(result.getConfidence(), "0.5"));
        result.setCsiScore(defaultBigDecimal(result.getCsiScore(), "0"));
        if (result.getEvidence() == null) {
            result.setEvidence(buildFallbackT6Evidence());
        }
        if ("T00".equals(result.getTargetType()) && !hasText(result.getFilterReason())) {
            result.setFilterReason("csi_low");
        }
    }

    private String targetTypeName(String targetType) {
        return switch (targetType) {
            case "T00" -> "正常用户";
            case "T01" -> "内容扭曲者";
            case "T02" -> "信息压制者";
            case "T03" -> "叙事放大者";
            case "T04" -> "叙事构建者";
            case "T05" -> "协调分发者";
            case "T06" -> "情感操控者";
            case "T07" -> "虚假信源";
            case "T08" -> "协调网络核心节点";
            case "T09" -> "跨平台协调者";
            case "T10" -> "高级持续威胁";
            default -> "可疑但无法确定";
        };
    }

    private String cleanJson(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim()
                .replaceFirst("(?s)^\\s*<think>.*?</think>\\s*", "")
                .trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[\\w-]*\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }

        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        int start;
        char open;
        char close;
        if (objectStart >= 0 && (arrayStart < 0 || objectStart < arrayStart)) {
            start = objectStart;
            open = '{';
            close = '}';
        } else if (arrayStart >= 0) {
            start = arrayStart;
            open = '[';
            close = ']';
        } else {
            return text;
        }

        int end = findJsonEnd(text, start, open, close);
        return end >= start ? text.substring(start, end + 1).trim() : text.substring(start).trim();
    }

    private int findJsonEnd(String text, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = inString;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == open) {
                depth++;
            } else if (ch == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.substring(0, Math.min(value.length(), maxLength));
    }

    private BigDecimal defaultBigDecimal(BigDecimal value, String fallback) {
        return value != null ? value : new BigDecimal(fallback);
    }

    private double toDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void logLlmFailure(String message, Exception e) {
        Throwable timeoutCause = findTimeoutCause(e);
        if (timeoutCause != null) {
            log.warn("{}, reason={}: {}", message,
                    timeoutCause.getClass().getSimpleName(), timeoutCause.getMessage());
            return;
        }
        log.warn("{}, reason={}: {}", message, e.getClass().getSimpleName(), e.getMessage());
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

    @Data
    private static class EmbeddingApiResponse {
        private List<EmbeddingData> data;

        @Data
        private static class EmbeddingData {
            private List<Double> embedding;
        }
    }
}

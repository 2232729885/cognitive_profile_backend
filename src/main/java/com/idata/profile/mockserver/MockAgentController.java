package com.idata.profile.mockserver;

import com.idata.profile.agentproxy.dto.t1.T1AnnotateRequest;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateResponse;
import com.idata.profile.agentproxy.dto.t2.T2ExtractRequest;
import com.idata.profile.agentproxy.dto.t2.T2ExtractResponse;
import com.idata.profile.agentproxy.dto.t3.T3FuseRequest;
import com.idata.profile.agentproxy.dto.t3.T3FuseResponse;
import com.idata.profile.agentproxy.dto.t3.T3ResolveRequest;
import com.idata.profile.agentproxy.dto.t3.T3ResolveResponse;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingRequest;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingResponse;
import com.idata.profile.agentproxy.dto.t5.T5GenerateProfileRequest;
import com.idata.profile.agentproxy.dto.t5.T5GenerateProfileResponse;
import com.idata.profile.agentproxy.dto.t6.T6IdentifyRequest;
import com.idata.profile.agentproxy.dto.t6.T6IdentifyResponse;
import com.idata.profile.common.util.StableUuidUtil;
import com.idata.profile.mapper.graph.EventMapper;
import com.idata.profile.mapper.graph.NarrativeMapper;
import com.idata.profile.mapper.graph.OrganizationMapper;
import com.idata.profile.mapper.graph.PersonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@Profile("mock")
@RequiredArgsConstructor
public class MockAgentController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final PersonMapper personMapper;
    private final OrganizationMapper organizationMapper;
    private final EventMapper eventMapper;
    private final NarrativeMapper narrativeMapper;

    @PostMapping("/mock/t1/annotate_text")
    public T1AnnotateResponse annotateText(@RequestBody T1AnnotateRequest request) {
        log.info("[MOCK-T1] annotate_text, textLength={}",
                request.getText() != null ? request.getText().length() : 0);

        T1AnnotateResponse.Annotations.Sentiment sentiment =
                new T1AnnotateResponse.Annotations.Sentiment();
        sentiment.setLabel("negative");
        sentiment.setScore(-0.680);

        T1AnnotateResponse.Annotations.LanguageStyle languageStyle =
                new T1AnnotateResponse.Annotations.LanguageStyle();
        languageStyle.setFormality("formal");
        languageStyle.setEmotionalIntensity("medium");

        T1AnnotateResponse.Annotations.EntityHint.Span span =
                new T1AnnotateResponse.Annotations.EntityHint.Span();
        span.setStart(0);
        span.setEnd(12);

        T1AnnotateResponse.Annotations.EntityHint entityHint =
                new T1AnnotateResponse.Annotations.EntityHint();
        entityHint.setText("Leila Farzan");
        entityHint.setTypeHint("person");
        entityHint.setSpan(span);
        entityHint.setStance("oppose");
        entityHint.setEvidenceIds(List.of("ev_001"));

        T1AnnotateResponse.Annotations annotations = new T1AnnotateResponse.Annotations();
        annotations.setTopics(List.of("politics", "gulf_security"));
        annotations.setKeywords(List.of("霍尔木兹海峡", "军事对峙", "叙事操控"));
        annotations.setSummary("该内容围绕霍尔木兹海峡军事对峙议题，对外部势力行为表达质疑和反对。");
        annotations.setEventType("military_confrontation");
        annotations.setContentPurpose("criticism");
        annotations.setAigcSuspicion("low");
        annotations.setSentiment(sentiment);
        annotations.setLanguageStyle(languageStyle);
        annotations.setEntitiesHint(List.of(entityHint));

        T1AnnotateResponse.QualityControl qualityControl = new T1AnnotateResponse.QualityControl();
        qualityControl.setAutoLabelStatus("success");
        qualityControl.setNeedHumanReview(false);
        qualityControl.setSchemaVersion("t1_annotation_v0.3");
        qualityControl.setModelVersion("mock-t1-v1.0");

        T1AnnotateResponse resp = new T1AnnotateResponse();
        resp.setLanguage(request.getLanguage() != null ? request.getLanguage() : "zh");
        resp.setAnnotations(annotations);
        resp.setEvidenceClues(List.of());
        resp.setQualityControl(qualityControl);
        resp.setConfidence(0.82);
        resp.setProcessedAt(java.time.OffsetDateTime.now().toString());
        resp.setRaw(toJson(resp));
        return resp;
    }

    @PostMapping("/mock/t2/extract_entities")
    public T2ExtractResponse extractEntities(@RequestBody T2ExtractRequest request) {
        log.info("[MOCK-T2] extract_entities, textLength={}, hasAnnotation={}",
                request.getText() != null ? request.getText().length() : 0,
                request.getAnnotation() != null);

        T2ExtractResponse.ExtractedEntity person = new T2ExtractResponse.ExtractedEntity();
        person.setType("person");
        person.setCanonicalName("Leila Farzan");
        person.setImportanceScore(new BigDecimal("88.00"));
        person.setMatchedAccountId(null);

        T2ExtractResponse.ExtractedEntity narrative = new T2ExtractResponse.ExtractedEntity();
        narrative.setType("narrative");
        narrative.setCanonicalName("Hormuz Strait escalation narrative");
        narrative.setImportanceScore(new BigDecimal("76.00"));

        T2ExtractResponse.ExtractedEntity organization = new T2ExtractResponse.ExtractedEntity();
        organization.setType("organization");
        organization.setCanonicalName("U.S. Central Command");
        organization.setImportanceScore(new BigDecimal("84.00"));

        T2ExtractResponse.ExtractedEntity location = new T2ExtractResponse.ExtractedEntity();
        location.setType("location");
        location.setCanonicalName("Strait of Hormuz");
        location.setImportanceScore(new BigDecimal("85.00"));

        T2ExtractResponse.ExtractedEntity event = new T2ExtractResponse.ExtractedEntity();
        event.setType("event");
        event.setCanonicalName("2026 Persian Gulf Military Standoff");
        event.setImportanceScore(new BigDecimal("90.00"));

        T2ExtractResponse.ExtractedRelation rel1 = new T2ExtractResponse.ExtractedRelation();
        rel1.setSourceName("Leila Farzan");
        rel1.setSourceType("person");
        rel1.setTargetName("U.S. Central Command");
        rel1.setTargetType("organization");
        rel1.setRelationType("AFFILIATED_WITH");
        rel1.setRole("analyst");
        rel1.setConfidence(0.78);

        T2ExtractResponse.ExtractedRelation rel2 = new T2ExtractResponse.ExtractedRelation();
        rel2.setSourceName("U.S. Central Command");
        rel2.setSourceType("organization");
        rel2.setTargetName("Strait of Hormuz");
        rel2.setTargetType("location");
        rel2.setRelationType("LOCATED_IN");
        rel2.setConfidence(0.90);

        T2ExtractResponse.ExtractedRelation rel3 = new T2ExtractResponse.ExtractedRelation();
        rel3.setSourceName("2026 Persian Gulf Military Standoff");
        rel3.setSourceType("event");
        rel3.setTargetName("Strait of Hormuz");
        rel3.setTargetType("location");
        rel3.setRelationType("EVENT_OCCURRED_AT");
        rel3.setConfidence(0.95);

        T2ExtractResponse.ExtractedRelation rel4 = new T2ExtractResponse.ExtractedRelation();
        rel4.setSourceName("Hormuz Strait escalation narrative");
        rel4.setSourceType("narrative");
        rel4.setTargetName("2026 Persian Gulf Military Standoff");
        rel4.setTargetType("event");
        rel4.setRelationType("NARRATIVE_ABOUT_EVENT");
        rel4.setConfidence(0.85);

        T2ExtractResponse.ExtractedEvent extractedEvent = new T2ExtractResponse.ExtractedEvent();
        extractedEvent.setEventType("military");
        extractedEvent.setCanonicalName("2026 Persian Gulf Military Standoff");
        extractedEvent.setEventTimeStart("2026-06-01T00:00:00Z");
        extractedEvent.setConfidence(0.90);

        T2ExtractResponse.ExtractedEvent.EventParticipant p1 =
                new T2ExtractResponse.ExtractedEvent.EventParticipant();
        p1.setName("U.S. Central Command");
        p1.setRole("actor");

        T2ExtractResponse.ExtractedEvent.EventParticipant p2 =
                new T2ExtractResponse.ExtractedEvent.EventParticipant();
        p2.setName("Strait of Hormuz");
        p2.setRole("location");

        extractedEvent.setParticipants(List.of(p1, p2));

        T2ExtractResponse resp = new T2ExtractResponse();
        resp.setEntities(List.of(person, organization, narrative, location, event));
        resp.setRelationships(List.of(rel1, rel2, rel3, rel4));
        resp.setEvents(List.of(extractedEvent));
        resp.setResolvedAuthorAccountId(null);
        resp.setRaw(toJson(resp));
        return resp;
    }

    @PostMapping("/mock/t3/fuse_entities")
    public T3FuseResponse fuseEntities(@RequestBody T3FuseRequest request) {
        // All relation types must come from the RZDK relationships.py RelationType enum.
        // Real T3 integrations must use the same vocabulary.
        log.info("[MOCK-T3] fuse_entities, entityRefs={}",
                request.getEntities() != null ? request.getEntities().size() : 0);

        T3FuseRequest.T2EntityRef personRef = findEntity(request, "person", "Leila Farzan");
        T3FuseRequest.T2EntityRef organizationRef = findEntity(request, "organization", "U.S. Central Command");
        T3FuseRequest.T2EntityRef narrativeRef = findEntity(request, "narrative", "Hormuz Strait escalation narrative");
        T3FuseRequest.T2EntityRef locationRef = findEntity(request, "location", "Strait of Hormuz");
        T3FuseRequest.T2EntityRef eventRef = findEntity(request, "event", "2026 Persian Gulf Military Standoff");

        String personId = entityId(personRef);
        String organizationId = entityId(organizationRef);
        String narrativeId = entityId(narrativeRef);
        String locationId = entityId(locationRef);
        String eventId = entityId(eventRef);

        T3FuseResponse.EntityMerge merge = new T3FuseResponse.EntityMerge();
        merge.setSurvivorId(personId);
        merge.setMergedIds(List.of(stableUuid(personId + ":merged-alias")));

        T3FuseResponse.Neo4jNode personNode = new T3FuseResponse.Neo4jNode();
        personNode.setLabel("Person");
        personNode.setId(personId);
        personNode.setProperties(Map.of(
                "canonicalName", personRef.getCanonicalName(),
                "nationality", "IR-US",
                "profession", "security analyst",
                "importanceScore", 88.0
        ));

        T3FuseResponse.Neo4jNode organizationNode = new T3FuseResponse.Neo4jNode();
        organizationNode.setLabel("Organization");
        organizationNode.setId(organizationId);
        organizationNode.setProperties(Map.of(
                "canonicalName", organizationRef.getCanonicalName(),
                "orgType", "military_command",
                "country", "US",
                "importanceScore", 84.0
        ));

        T3FuseResponse.Neo4jNode narrativeNode = new T3FuseResponse.Neo4jNode();
        narrativeNode.setLabel("Narrative");
        narrativeNode.setId(narrativeId);
        narrativeNode.setProperties(Map.of(
                "canonicalLabel", narrativeRef.getCanonicalName(),
                "importanceScore", 76.0,
                "frameType", "threat_escalation",
                "region", "Persian Gulf",
                "source", "mock-t3"
        ));

        T3FuseResponse.Neo4jNode locationNode = new T3FuseResponse.Neo4jNode();
        locationNode.setLabel("Location");
        locationNode.setId(locationId);
        locationNode.setProperties(Map.of(
                "canonicalName", "Strait of Hormuz",
                "placeType", "water_body",
                "country", "IR",
                "importanceScore", 85.0,
                "source", "mock-t3"
        ));

        T3FuseResponse.Neo4jNode eventNode = new T3FuseResponse.Neo4jNode();
        eventNode.setLabel("Event");
        eventNode.setId(eventId);
        eventNode.setProperties(Map.of(
                "canonicalName", "2026 Persian Gulf Military Standoff",
                "eventType", "military",
                "country", "IR",
                "importanceScore", 90.0,
                "source", "mock-t3"
        ));

        T3FuseResponse.Neo4jRelation participates = new T3FuseResponse.Neo4jRelation();
        participates.setFromId(personId);
        participates.setToId(narrativeId);
        participates.setRelationType("AMPLIFIES");
        participates.setProperties(Map.of("frequency", 12, "confidence", 0.85, "source", "mock-t3"));

        T3FuseResponse.Neo4jRelation affiliated = new T3FuseResponse.Neo4jRelation();
        affiliated.setFromId(personId);
        affiliated.setToId(organizationId);
        affiliated.setRelationType("AFFILIATED_WITH");
        affiliated.setProperties(Map.of("confidence", 0.78, "source", "mock-t3"));

        T3FuseResponse.Neo4jRelation promotes = new T3FuseResponse.Neo4jRelation();
        promotes.setFromId(organizationId);
        promotes.setToId(narrativeId);
        promotes.setRelationType("SUPPORTS");
        promotes.setProperties(Map.of("confidence", 0.82, "source", "mock-t3"));

        T3FuseResponse.Neo4jRelation eventAtLocation = new T3FuseResponse.Neo4jRelation();
        eventAtLocation.setFromId(eventId);
        eventAtLocation.setToId(locationId);
        eventAtLocation.setRelationType("EVENT_OCCURRED_AT");
        eventAtLocation.setProperties(Map.of("confidence", 0.95, "source", "mock-t3"));

        T3FuseResponse.Neo4jRelation eventInvolvesOrg = new T3FuseResponse.Neo4jRelation();
        eventInvolvesOrg.setFromId(eventId);
        eventInvolvesOrg.setToId(organizationId);
        eventInvolvesOrg.setRelationType("EVENT_INVOLVES_ENTITY");
        eventInvolvesOrg.setProperties(Map.of("role", "actor", "confidence", 0.88, "source", "mock-t3"));

        T3FuseResponse.Neo4jRelation narrativeAboutEvent = new T3FuseResponse.Neo4jRelation();
        narrativeAboutEvent.setFromId(narrativeId);
        narrativeAboutEvent.setToId(eventId);
        narrativeAboutEvent.setRelationType("NARRATIVE_ABOUT_EVENT");
        narrativeAboutEvent.setProperties(Map.of("confidence", 0.92, "source", "mock-t3"));

        T3FuseResponse.Neo4jRelation orgLocatedIn = new T3FuseResponse.Neo4jRelation();
        orgLocatedIn.setFromId(organizationId);
        orgLocatedIn.setToId(locationId);
        orgLocatedIn.setRelationType("LOCATED_IN");
        orgLocatedIn.setProperties(Map.of("confidence", 0.80, "source", "mock-t3"));

        T3FuseResponse resp = new T3FuseResponse();
        resp.setEntityMerges(List.of(merge));
        resp.setNodes(List.of(personNode, organizationNode, narrativeNode, locationNode, eventNode));
        resp.setRelations(List.of(participates, affiliated, promotes,
                eventAtLocation, eventInvolvesOrg, narrativeAboutEvent, orgLocatedIn));
        resp.setRaw(toJson(resp));
        return resp;
    }

    @PostMapping("/mock/t3/resolve_entities")
    public T3ResolveResponse resolveEntities(@RequestBody T3ResolveRequest request) {
        log.info("[MOCK-T3] resolve_entities, candidateCount={}",
                request.getEntities() != null ? request.getEntities().size() : 0);

        /*
         * 算法组实现 resolve_entities 时，输出格式约定（选项B：直接输出聚合好的分组）：
         *
         * mergeGroups：T3 认为应该合并的实体分组列表。
         *   每组 survivorId 是归一后保留的实体 ID（应选 importanceScore 最高的那个），
         *   mergedIds 是被合并的实体 ID 列表，confidence 是归一置信度（后端只执行 >= 0.8 的）,
         *   matchMethod 标注归一依据：
         *     - exact_name：精确名称匹配（后端精确匹配已覆盖，T3 通常不需要再返回这类）
         *     - alias_match：别名匹配（如 "Biden" 匹配到 aliases 里有 "Biden" 的实体）
         *     - cross_language：跨语言归一（如 "拜登" 和 "Biden" 归一）
         *     - semantic_similarity：语义相似度归一（向量余弦，用于叙事/事件等长文本实体）
         *     - identifier_match：平台标识符匹配（如 twitter uid 相同，置信度最高）
         *
         * disjointPairs：T3 明确判断不是同一实体的 ID 对（否定证据，后端记录备用，暂不处理）。
         *
         * uncertain：T3 无法判断的实体 ID 列表（置信度低于阈值或证据不足）。
         *
         * Mock 实现：全部标记为 uncertain，mergeGroups 为空。
         * 接入真实 T3 后，mergeGroups 里应包含跨语言归一的结果。
         */
        T3ResolveResponse resp = new T3ResolveResponse();
        resp.setMergeGroups(List.of());
        resp.setDisjointPairs(List.of());
        resp.setUncertain(request.getEntities() == null ? List.of() :
                request.getEntities().stream()
                        .map(T3ResolveRequest.EntityCandidate::getId)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList()));
        return resp;
    }

    @PostMapping("/mock/t4/generate_text_embedding")
    public T4EmbeddingResponse generateTextEmbedding(@RequestBody T4EmbeddingRequest request) {
        log.info("[MOCK-T4] generate_text_embedding, textLength={}",
                request.getText() != null ? request.getText().length() : 0);
        return buildEmbeddingResponse(request.getText());
    }

    @PostMapping("/mock/t4/generate_image_embedding")
    public T4EmbeddingResponse generateImageEmbedding(@RequestBody T4EmbeddingRequest request) {
        log.info("[MOCK-T4] generate_image_embedding, imageUrl={}", request.getImageUrl());
        return buildEmbeddingResponse(request.getImageUrl());
    }

    @PostMapping("/mock/t5/complete_profile")
    public T5GenerateProfileResponse generateFullProfile(@RequestBody T5GenerateProfileRequest request) {
        log.info("[MOCK-T5] complete_profile, targetId={}, targetType={}",
                request.getTargetId(), request.getTargetType());

        T5GenerateProfileResponse resp = new T5GenerateProfileResponse();
        resp.setPoliticalOrientation("anti_west");
        resp.setPoliticalScore(new BigDecimal("65.00"));
        resp.setPoliticalConfidence(new BigDecimal("0.820"));
        resp.setEmotionProfile(Map.of(
                "moral_outrage", 0.89,
                "resentment", 0.76,
                "fear_inducing", 0.71
        ));
        resp.setStanceProfile(List.of(Map.of("topic", "mock_topic", "stance", "strongly_oppose", "confidence", 0.91)));
        resp.setActiveTimePattern(Map.of("14", 0.15, "20", 0.22));
        resp.setPostFrequencyDaily(new BigDecimal("4.20"));
        resp.setContentOriginalRatio(new BigDecimal("0.350"));
        resp.setBendProfile(Map.of("Distort", 0.41, "Dismiss", 0.28, "Amplify", 0.19));
        resp.setInfluenceScore(new BigDecimal("78.00"));
        resp.setReachScore(new BigDecimal("65.00"));
        resp.setViralityScore(new BigDecimal("58.00"));
        resp.setMbtiType("ENTJ");
        resp.setMbtiConfidence(new BigDecimal("0.670"));
        resp.setDecisionStyle("aggressive");
        resp.setLanguageStyle("inciting");
        resp.setInterestDomains(new String[]{"geopolitics", "military", "economics"});
        resp.setCoordinationNetwork(List.of(Map.of("entityId", UUID.randomUUID().toString(), "confidence", 0.87)));
        resp.setPreferredNarratives(List.of(Map.of("narrativeId", UUID.randomUUID().toString(), "frequency", 23)));
        resp.setTargetType("T07");
        resp.setTargetConfidence(new BigDecimal("0.880"));
        resp.setTargetEvidence("mock evidence");
        resp.setHiddenRelations(List.of(Map.of(
                "targetId", UUID.randomUUID().toString(),
                "relationType", "POTENTIAL_SUBORDINATE_TO",
                "confidence", 0.63
        )));
        resp.setManipulationRisk("high");
        resp.setManipulationScore(new BigDecimal("82.00"));
        resp.setModelVersions(Map.of("t5_model", "mock-t5-v1.0"));
        return resp;
    }

    @PostMapping("/mock/t6/identify_targets")
    public T6IdentifyResponse identifyTargets(@RequestBody T6IdentifyRequest request) {
        log.info("[MOCK-T6] identify_targets, narrativeId={}, accountCount={}, contentCount={}",
                request.getNarrativeId(),
                request.getSocialAccounts() != null ? request.getSocialAccounts().size() : 0,
                request.getMediaContents() != null ? request.getMediaContents().size() : 0);

        String firstAccountId = request.getSocialAccounts() != null && !request.getSocialAccounts().isEmpty()
                ? request.getSocialAccounts().get(0).getAccountId()
                : UUID.randomUUID().toString();

        T6IdentifyResponse.AccountIdentifyResult accountResult =
                new T6IdentifyResponse.AccountIdentifyResult();
        accountResult.setAccountId(firstAccountId);
        accountResult.setTargetType("T08");
        accountResult.setTargetTypeName("协调网络核心节点");
        accountResult.setConfidence(new BigDecimal("0.87"));
        accountResult.setCsiScore(new BigDecimal("0.72"));

        T6IdentifyResponse.MatchEvidence evidence = new T6IdentifyResponse.MatchEvidence();
        evidence.setMatchedTacticId("T08");
        evidence.setDistributionSimilarity(0.91);
        evidence.setSequenceSimilarity(0.83);
        evidence.setCombinedScore(0.87);
        evidence.setEvidence("mock evidence");
        accountResult.setEvidence(evidence);

        int accountCount = request.getSocialAccounts() != null ? request.getSocialAccounts().size() : 0;
        T6IdentifyResponse.Summary summary = new T6IdentifyResponse.Summary();
        summary.setNarrativeId(request.getNarrativeId());
        summary.setTotalAccounts(accountCount);
        summary.setT00Count(Math.max(0, accountCount - 1));
        summary.setSuspectCount(0);
        summary.setIdentifiedCount(Math.min(1, accountCount));
        summary.setUnknownCount(0);
        summary.setGroupCount(0);
        summary.setProcessingTimeMs(453L);

        T6IdentifyResponse resp = new T6IdentifyResponse();
        resp.setAccountIdentifyResult(List.of(accountResult));
        resp.setEntityIdentifyResult(List.of());
        resp.setGroupIdentifyResult(List.of());
        resp.setSummary(summary);
        return resp;
    }

    private T4EmbeddingResponse buildEmbeddingResponse(String seed) {
        int dim = 4096;
        float[] embedding = new float[dim];
        long seedHash = seed != null ? seed.hashCode() : 0;
        java.util.Random random = new java.util.Random(seedHash);
        for (int i = 0; i < dim; i++) {
            embedding[i] = (random.nextFloat() * 2) - 1;
        }
        T4EmbeddingResponse resp = new T4EmbeddingResponse();
        resp.setEmbedding(embedding);
        resp.setModelVersion("mock-qwen3-vl-embedding-8b");
        return resp;
    }

    private T3FuseRequest.T2EntityRef findEntity(T3FuseRequest request, String type, String fallbackName) {
        if (request.getEntities() != null) {
            for (T3FuseRequest.T2EntityRef entity : request.getEntities()) {
                if (type.equals(entity.getType())) {
                    return entity;
                }
            }
        }
        T3FuseRequest.T2EntityRef fallback = new T3FuseRequest.T2EntityRef();
        fallback.setType(type);
        fallback.setCanonicalName(fallbackName);
        fallback.setTempId(stableUuid(type + ":" + fallbackName));
        return fallback;
    }

    private String entityId(T3FuseRequest.T2EntityRef ref) {
        if (ref == null) {
            return stableUuid("unknown:unknown");
        }

        String canonicalName = ref.getCanonicalName();
        String type = ref.getType();
        UUID realId = switch (type != null ? type : "") {
            case "person" -> personMapper.selectCanonicalIdByName(canonicalName);
            case "organization" -> organizationMapper.selectCanonicalIdByName(canonicalName);
            case "event" -> eventMapper.selectCanonicalIdByName(canonicalName);
            case "narrative" -> narrativeMapper.selectCanonicalIdByLabel(canonicalName);
            default -> null;
        };
        if (realId != null) {
            return realId.toString();
        }

        return stableUuid((type != null ? type : "unknown") + ":" + canonicalName);
    }

    private String stableUuid(String value) {
        return StableUuidUtil.fromSeed(value);
    }

    private String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JacksonException e) {
            return "{\"serializationError\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }
}

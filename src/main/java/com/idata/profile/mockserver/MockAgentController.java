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
        log.info("[MOCK-T1] annotate_text, bodyTextLength={}",
                request.getBodyText() != null ? request.getBodyText().length() : 0);

        T1AnnotateResponse resp = new T1AnnotateResponse();
        resp.setTopicCategory("politics");
        resp.setTopicSubcategory("gulf_security");
        resp.setEventHeatScore(new BigDecimal("72.50"));
        resp.setSentimentLabel("negative");
        resp.setSentimentScore(new BigDecimal("-0.680"));
        resp.setStanceLabel("oppose");
        resp.setStanceTarget("gulf_escalation");
        resp.setAigcScore(new BigDecimal("0.150"));
        resp.setAigcType("human");
        resp.setEntitiesHint(Map.of(
                "person", "Leila Farzan",
                "organization", "U.S. Central Command",
                "narrative", "Hormuz Strait escalation narrative"
        ));
        resp.setNarrativeHint("Hormuz Strait escalation narrative");
        resp.setModelVersion("mock-t1-v1.0");
        resp.setRaw(toJson(resp));
        return resp;
    }

    @PostMapping("/mock/t2/extract_entities")
    public T2ExtractResponse extractEntities(@RequestBody T2ExtractRequest request) {
        log.info("[MOCK-T2] extract_entities, hashtags={}", (Object) request.getHashtags());

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

        T3ResolveResponse resp = new T3ResolveResponse();
        resp.setMergeGroups(List.of());
        resp.setDisjointPairs(List.of());
        resp.setUncertain(request.getEntities() == null ? List.of() :
                request.getEntities().stream()
                        .map(T3ResolveRequest.EntityCandidate::getId)
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

    @PostMapping("/mock/t5/generate_full_profile")
    public T5GenerateProfileResponse generateFullProfile(@RequestBody T5GenerateProfileRequest request) {
        log.info("[MOCK-T5] generate_full_profile, personId={}", request.getPersonId());

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
        log.info("[MOCK-T6] identify_targets, triggerType={}, narrativeId={}",
                request.getTriggerType(), request.getNarrativeId());

        T6IdentifyResponse.IdentifiedTarget target = new T6IdentifyResponse.IdentifiedTarget();
        target.setTargetType("T08");
        target.setTargetEntityType("social_account");
        target.setTargetEntityId(UUID.randomUUID().toString());
        target.setConfidence(new BigDecimal("0.870"));
        target.setBendDistribution(Map.of("Distort", 0.41, "Dismiss", 0.28, "Amplify", 0.19));
        target.setEvidenceText("mock evidence");
        target.setEvidenceContentIds(List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()));

        T6IdentifyResponse resp = new T6IdentifyResponse();
        resp.setTargets(List.of(target));
        resp.setAccountsAnalyzed(47);
        resp.setGroupsDetected(1);
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

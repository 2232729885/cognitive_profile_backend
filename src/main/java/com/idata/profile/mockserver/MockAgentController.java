package com.idata.profile.mockserver;

import com.idata.profile.agentproxy.dto.t1.T1AnnotateRequest;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateResponse;
import com.idata.profile.agentproxy.dto.t2.T2ExtractRequest;
import com.idata.profile.agentproxy.dto.t2.T2ExtractResponse;
import com.idata.profile.agentproxy.dto.t3.T3FuseRequest;
import com.idata.profile.agentproxy.dto.t3.T3FuseResponse;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingRequest;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingResponse;
import com.idata.profile.agentproxy.dto.t5.T5GenerateProfileRequest;
import com.idata.profile.agentproxy.dto.t5.T5GenerateProfileResponse;
import com.idata.profile.agentproxy.dto.t6.T6IdentifyRequest;
import com.idata.profile.agentproxy.dto.t6.T6IdentifyResponse;
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

@Slf4j
@RestController
@Profile("mock")
public class MockAgentController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @PostMapping("/mock/t1/annotate_text")
    public T1AnnotateResponse annotateText(@RequestBody T1AnnotateRequest request) {
        log.info("[MOCK-T1] annotate_text, bodyTextLength={}",
                request.getBodyText() != null ? request.getBodyText().length() : 0);

        T1AnnotateResponse resp = new T1AnnotateResponse();
        resp.setTopicCategory("politics");
        resp.setTopicSubcategory("election");
        resp.setEventHeatScore(new BigDecimal("72.50"));
        resp.setSentimentLabel("negative");
        resp.setSentimentScore(new BigDecimal("-0.680"));
        resp.setStanceLabel("oppose");
        resp.setStanceTarget("mock_government");
        resp.setAigcScore(new BigDecimal("0.150"));
        resp.setAigcType("human");
        resp.setEntitiesHint(Map.of("hint", "mock entity hint"));
        resp.setNarrativeHint("mock election interference narrative");
        resp.setModelVersion("mock-t1-v1.0");
        resp.setRaw(toJson(resp));
        return resp;
    }

    @PostMapping("/mock/t2/extract_entities")
    public T2ExtractResponse extractEntities(@RequestBody T2ExtractRequest request) {
        log.info("[MOCK-T2] extract_entities, hashtags={}", (Object) request.getHashtags());

        T2ExtractResponse.ExtractedEntity person = new T2ExtractResponse.ExtractedEntity();
        person.setType("person");
        person.setCanonicalName("Mock Person");
        person.setImportanceScore(new BigDecimal("88.00"));
        person.setMatchedAccountId(null);

        T2ExtractResponse.ExtractedEntity narrative = new T2ExtractResponse.ExtractedEntity();
        narrative.setType("narrative");
        narrative.setCanonicalName("Mock election interference narrative");
        narrative.setImportanceScore(new BigDecimal("76.00"));

        T2ExtractResponse resp = new T2ExtractResponse();
        resp.setEntities(List.of(person, narrative));
        resp.setResolvedAuthorAccountId(null);
        resp.setRaw(toJson(resp));
        return resp;
    }

    @PostMapping("/mock/t3/fuse_entities")
    public T3FuseResponse fuseEntities(@RequestBody T3FuseRequest request) {
        log.info("[MOCK-T3] fuse_entities, entityRefs={}",
                request.getEntities() != null ? request.getEntities().size() : 0);

        String personId = UUID.randomUUID().toString();
        String narrativeId = UUID.randomUUID().toString();

        T3FuseResponse.EntityMerge merge = new T3FuseResponse.EntityMerge();
        merge.setSurvivorId(personId);
        merge.setMergedIds(List.of(UUID.randomUUID().toString()));

        T3FuseResponse.Neo4jNode personNode = new T3FuseResponse.Neo4jNode();
        personNode.setLabel("Person");
        personNode.setId(personId);
        personNode.setProperties(Map.of(
                "canonicalName", "Mock Person",
                "nationality", "US",
                "importanceScore", 88.0
        ));

        T3FuseResponse.Neo4jNode narrativeNode = new T3FuseResponse.Neo4jNode();
        narrativeNode.setLabel("Narrative");
        narrativeNode.setId(narrativeId);
        narrativeNode.setProperties(Map.of(
                "canonicalLabel", "Mock election interference narrative",
                "importanceScore", 76.0,
                "source", "mock-t3"
        ));

        T3FuseResponse.Neo4jRelation relation = new T3FuseResponse.Neo4jRelation();
        relation.setFromId(personId);
        relation.setToId(narrativeId);
        relation.setRelationType("PARTICIPATES_IN_NARRATIVE");
        relation.setProperties(Map.of("frequency", 12, "confidence", 0.85, "source", "mock-t3"));

        T3FuseResponse resp = new T3FuseResponse();
        resp.setEntityMerges(List.of(merge));
        resp.setNodes(List.of(personNode, narrativeNode));
        resp.setRelations(List.of(relation));
        resp.setRaw(toJson(resp));
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
                "relationType", "latent_subordination",
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

    private String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JacksonException e) {
            return "{\"serializationError\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }
}

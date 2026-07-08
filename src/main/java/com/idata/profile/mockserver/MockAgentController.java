package com.idata.profile.mockserver;

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

@Slf4j
@RestController
@Profile("mock")
@RequiredArgsConstructor
public class MockAgentController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @PostMapping("/mock/t1/annotate_text")
    public T1AnnotateResponse annotateText(@RequestBody T1AnnotateRequest request) {
        log.info("[MOCK-T1] annotate_text, textLength={}",
                request.getText() != null ? request.getText().length() : 0);
        return placeholderT1Response(request.getLanguage());
    }
    @PostMapping("/mock/t1/annotate_image")
    public T1AnnotateResponse annotateImage(@RequestBody T1AnnotateRequest request) {
        log.info("[MOCK-T1] annotate_image, imageUrl={}, hasImageData={}",
                request.getImageUrl(), request.getImageData() != null);
        return placeholderT1Response(null);
    }
    @PostMapping("/mock/t2/extract_entities")
    public T2ExtractResponse extractEntities(@RequestBody T2ExtractRequest request) {
        log.info("[MOCK-T2] extract_entities, textLength={}, hasAnnotation={}",
                request.getText() != null ? request.getText().length() : 0,
                request.getAnnotation() != null);

        T2ExtractResponse.ExtractedMention person = new T2ExtractResponse.ExtractedMention();
        person.setMentionId("m1");
        person.setName("Leila Farzan");
        person.setType("person");
        person.setNormalizedName("Leila Farzan");
        person.setImportanceScore(88.0);
        person.setConfidence(0.92);
        person.setAliases(List.of("L. Farzan", "莱拉·法尔赞"));
        person.setAttributes(Map.of());

        T2ExtractResponse.ExtractedMention organization = new T2ExtractResponse.ExtractedMention();
        organization.setMentionId("m3");
        organization.setName("U.S. Central Command");
        organization.setType("organization");
        organization.setNormalizedName("U.S. Central Command");
        organization.setImportanceScore(84.0);
        organization.setConfidence(0.95);
        organization.setAliases(List.of("CENTCOM"));
        organization.setAttributes(Map.of());

        T2ExtractResponse.ExtractedMention location = new T2ExtractResponse.ExtractedMention();
        location.setMentionId("m4");
        location.setName("Strait of Hormuz");
        location.setType("location");
        location.setNormalizedName("Strait of Hormuz");
        location.setImportanceScore(85.0);
        location.setConfidence(0.95);
        location.setAliases(List.of("霍尔木兹海峡"));
        location.setAttributes(Map.of());

        T2ExtractResponse.ExtractedMention event = new T2ExtractResponse.ExtractedMention();
        event.setMentionId("e1");
        event.setName("2026 Persian Gulf Military Standoff");
        event.setType("event");
        event.setNormalizedName("2026 Persian Gulf Military Standoff");
        event.setImportanceScore(90.0);
        event.setConfidence(0.90);
        event.setAliases(List.of());
        event.setAttributes(Map.of(
                "eventType", "military",
                "eventTimeStart", "2026-06-01T00:00:00Z"));

        T2ExtractResponse.ExtractedRelationMention rel1 = new T2ExtractResponse.ExtractedRelationMention();
        rel1.setRelationMentionId("r1");
        rel1.setSubjectMentionId("m1");
        rel1.setPredicate("BELONGS_TO");
        rel1.setObjectMentionId("m3");
        rel1.setConfidence(0.78);
        rel1.setEvidence("Leila Farzan is described as an analyst related to U.S. Central Command");

        T2ExtractResponse.ExtractedRelationMention rel2 = new T2ExtractResponse.ExtractedRelationMention();
        rel2.setRelationMentionId("r2");
        rel2.setSubjectMentionId("e1");
        rel2.setPredicate("EVENT_OCCURRED_AT");
        rel2.setObjectMentionId("m4");
        rel2.setConfidence(0.90);
        rel2.setEvidence("The military standoff is located near the Strait of Hormuz");

        T2ExtractResponse.ExtractedRelationMention rel3 = new T2ExtractResponse.ExtractedRelationMention();
        rel3.setRelationMentionId("r3");
        rel3.setSubjectMentionId("e1");
        rel3.setPredicate("EVENT_INVOLVES_ENTITY");
        rel3.setObjectMentionId("m3");
        rel3.setConfidence(0.95);
        rel3.setEvidence("U.S. Central Command is involved in the standoff");

        T2ExtractResponse resp = new T2ExtractResponse();
        resp.setDocId(request.getDocId());
        resp.setEntities(List.of(person, organization, location, event));
        resp.setRelations(List.of(rel1, rel2, rel3));
        resp.setResolvedAuthorAccountId(null);
        resp.setModelVersion("mock-t2-v2.0");
        resp.setRaw(toJson(resp));
        return resp;
    }

    @PostMapping("/mock/t3/resolve_batch")
    public T3ResolveBatchResponse resolveBatch(@RequestBody T3ResolveBatchRequest request) {
        int itemCount = request.getItems() != null ? request.getItems().size() : 0;
        log.info("[MOCK-T3] resolve_batch, itemCount={}", itemCount);

        List<T3ResolveBatchResponse.ResolveResult> results =
                request.getItems() == null ? List.of() : request.getItems().stream()
                        .map(item -> {
                            T3ResolveBatchResponse.ResolveResult result =
                                    new T3ResolveBatchResponse.ResolveResult();
                            String mentionId = item.getMention() != null ? item.getMention().getMentionId() : null;
                            result.setMentionId(mentionId);

                            double autoMergeThreshold = request.getStrategy() != null
                                    && request.getStrategy().getAutoMergeThreshold() != null
                                    ? request.getStrategy().getAutoMergeThreshold() : 0.9D;
                            double reviewThreshold = request.getStrategy() != null
                                    && request.getStrategy().getReviewThreshold() != null
                                    ? request.getStrategy().getReviewThreshold() : 0.6D;

                            T3ResolveBatchRequest.Candidate top = item.getCandidates() != null
                                    && !item.getCandidates().isEmpty()
                                    ? item.getCandidates().get(0) : null;
                            double topScore = top != null && top.getScore() != null ? top.getScore() : 0D;

                            if (top != null && topScore >= autoMergeThreshold) {
                                result.setAction("MERGE");
                                result.setMatchedEntityId(top.getEntityId());
                                result.setMatchMethod("mock_candidate_top1");
                                result.setReason("mock resolver selected the highest ranked candidate");
                            } else if (top != null && topScore >= reviewThreshold) {
                                result.setAction("REVIEW");
                                result.setMatchedEntityId(top.getEntityId());
                                result.setMatchMethod("mock_candidate_top1");
                                result.setReason("mock resolver selected the highest ranked candidate");
                            } else {
                                result.setAction("CREATE");
                                result.setMatchMethod(top != null ? "low_score_candidate" : "no_candidate");
                                result.setReason(top != null
                                        ? "top candidate score below review threshold"
                                        : "no candidate returned by backend retrieval");
                            }
                            result.setScore(topScore);
                            result.setConfidence(topScore);
                            return result;
                        })
                        .toList();

        T3ResolveBatchResponse resp = new T3ResolveBatchResponse();
        resp.setResults(results);
        resp.setModelVersion("mock-t3-resolve-v2.0");
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

    private T1AnnotateResponse placeholderT1Response(String language) {
        T1AnnotateResponse resp = new T1AnnotateResponse();
        resp.setSchemaVersion("t1_annotation_v0.5");
        resp.setLanguage(language != null ? language : "zh");
        resp.setEvidenceClues(List.of());
        T1AnnotateResponse.QualityControl qualityControl = new T1AnnotateResponse.QualityControl();
        qualityControl.setNeedHumanReview(false);
        qualityControl.setReviewReasons(List.of("none"));
        qualityControl.setFailedModules(List.of("none"));
        resp.setQualityControl(qualityControl);
        resp.setOverallConfidence(0.0D);
        resp.setProcessedAt(java.time.OffsetDateTime.now().toString());
        return resp;
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

    private String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JacksonException e) {
            return "{\"serializationError\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }
}

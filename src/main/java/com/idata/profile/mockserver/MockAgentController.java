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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T1-T6 Mock Server。仅在 spring.profiles.active 包含 "mock" 时加载。
 *
 * 路径规则严格对应 docs/02-T1-T6-MOCK-SPEC.md 第7节速查表，
 * 与 AgentProxyClient.call() 的 URL拼接规则（baseUrl + "/" + action）对齐：
 *   sub_agent_registry.mock_url 应配置为本类@RequestMapping的前缀，
 *   如 T1 配置 mock_url = http://localhost:8080/mock/t1
 *
 * 启动方式：在 application.yml 或启动参数加 spring.profiles.active=mock，
 * 或者IDEA Run Configuration里加 VM options: -Dspring.profiles.active=mock
 *
 * 重要：本类只负责"让调用链能跑通"，不模拟真实算法效果，
 * 所有返回值是固定/伪随机的占位数据，仅用于验证字段映射和写表逻辑是否正确。
 */
@Slf4j
@RestController
@Profile("mock")
public class MockAgentController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ============================================================
    // T1 自动标注
    // ============================================================
    @PostMapping("/mock/t1/annotate_text")
    public T1AnnotateResponse annotateText(@RequestBody T1AnnotateRequest request) {
        log.info("[MOCK-T1] annotate_text 收到请求, bodyText长度={}",
                request.getBodyText() != null ? request.getBodyText().length() : 0);

        T1AnnotateResponse resp = new T1AnnotateResponse();
        resp.setTopicCategory("politics");
        resp.setTopicSubcategory("election");
        resp.setEventHeatScore(new BigDecimal("72.50"));
        resp.setSentimentLabel("negative");
        resp.setSentimentScore(new BigDecimal("-0.680"));
        resp.setStanceLabel("oppose");
        resp.setStanceTarget("某国政府");
        resp.setAigcScore(new BigDecimal("0.150"));
        resp.setAigcType("human");
        resp.setEntitiesHint(Map.of("hint", "mock实体提示，T2会用到这个字段"));
        resp.setNarrativeHint("外部势力干预选举叙事（mock）");
        resp.setModelVersion("mock-t1-v1.0");
        resp.setRaw(toJson(resp));
        return resp;
    }

    // ============================================================
    // T2 信息抽取
    // ============================================================
    @PostMapping("/mock/t2/extract_entities")
    public T2ExtractResponse extractEntities(@RequestBody T2ExtractRequest request) {
        log.info("[MOCK-T2] extract_entities 收到请求, hashtags={}", (Object) request.getHashtags());

        T2ExtractResponse resp = new T2ExtractResponse();

        T2ExtractResponse.ExtractedEntity person = new T2ExtractResponse.ExtractedEntity();
        person.setType("person");
        person.setCanonicalName("拜登（mock）");
        person.setImportanceScore(new BigDecimal("88.00"));
        person.setMatchedAccountId(null);  // mock阶段不强行关联账号，验证null分支

        T2ExtractResponse.ExtractedEntity narrative = new T2ExtractResponse.ExtractedEntity();
        narrative.setType("narrative");
        narrative.setCanonicalName("外部干预选举叙事（mock）");
        narrative.setImportanceScore(new BigDecimal("76.00"));

        resp.setEntities(List.of(person, narrative));
        resp.setResolvedAuthorAccountId(null);
        resp.setRaw(toJson(resp));
        return resp;
    }

    // ============================================================
    // T3 信息融合
    // ============================================================
    @PostMapping("/mock/t3/fuse_entities")
    public T3FuseResponse fuseEntities(@RequestBody T3FuseRequest request) {
        log.info("[MOCK-T3] fuse_entities 收到 {} 个实体引用",
                request.getEntities() != null ? request.getEntities().size() : 0);

        T3FuseResponse resp = new T3FuseResponse();

        // 场景一：实体归一（要保留追溯链，写PG的mergeHistory）
        T3FuseResponse.EntityMerge merge = new T3FuseResponse.EntityMerge();
        merge.setSurvivorId(UUID.randomUUID().toString());
        merge.setMergedIds(List.of(UUID.randomUUID().toString()));

        // 场景二：字段覆盖写Neo4j（新值直接覆盖旧值，不暂存不审核）
        T3FuseResponse.Neo4jNode node = new T3FuseResponse.Neo4jNode();
        node.setLabel("Person");
        node.setId(merge.getSurvivorId());
        node.setProperties(Map.of(
                "canonicalName", "拜登（mock，T3覆盖后）",
                "nationality", "美国",   // mock场景：演示新值覆盖旧值
                "importanceScore", 88.0
        ));

        T3FuseResponse.Neo4jRelation relation = new T3FuseResponse.Neo4jRelation();
        relation.setFromId(merge.getSurvivorId());
        relation.setToId(UUID.randomUUID().toString());
        relation.setRelationType("PROMOTES_NARRATIVE");
        relation.setProperties(Map.of("frequency", 12, "confidence", 0.85));

        resp.setEntityMerges(List.of(merge));
        resp.setNodes(List.of(node));
        resp.setRelations(List.of(relation));
        resp.setRaw(toJson(resp));
        return resp;
    }

    // ============================================================
    // T4 多模态检索 / 索引构建（两个action）
    // ============================================================
    @PostMapping("/mock/t4/generate_text_embedding")
    public T4EmbeddingResponse generateTextEmbedding(@RequestBody T4EmbeddingRequest request) {
        log.info("[MOCK-T4] generate_text_embedding, text长度={}",
                request.getText() != null ? request.getText().length() : 0);
        return buildEmbeddingResponse(request.getText());
    }

    @PostMapping("/mock/t4/generate_image_embedding")
    public T4EmbeddingResponse generateImageEmbedding(@RequestBody T4EmbeddingRequest request) {
        log.info("[MOCK-T4] generate_image_embedding, imageUrl={}", request.getImageUrl());
        return buildEmbeddingResponse(request.getImageUrl());
    }

    /** 生成4096维伪随机向量，仅用于验证Milvus写入流程，不追求语义准确性 */
    private T4EmbeddingResponse buildEmbeddingResponse(String seed) {
        int dim = 4096;
        float[] embedding = new float[dim];
        long seedHash = (seed != null ? seed.hashCode() : 0);
        java.util.Random random = new java.util.Random(seedHash);
        for (int i = 0; i < dim; i++) {
            embedding[i] = (random.nextFloat() * 2) - 1;  // [-1, 1]区间
        }
        T4EmbeddingResponse resp = new T4EmbeddingResponse();
        resp.setEmbedding(embedding);
        resp.setModelVersion("mock-qwen3-vl-embedding-8b");
        return resp;
    }

    // ============================================================
    // T5 画像补全：15维度全部返回非null值
    // ============================================================
    @PostMapping("/mock/t5/generate_full_profile")
    public T5GenerateProfileResponse generateFullProfile(@RequestBody T5GenerateProfileRequest request) {
        log.info("[MOCK-T5] generate_full_profile, personId={}", request.getPersonId());

        T5GenerateProfileResponse resp = new T5GenerateProfileResponse();
        resp.setPoliticalOrientation("anti_west");
        resp.setPoliticalScore(new BigDecimal("65.00"));
        resp.setPoliticalConfidence(new BigDecimal("0.820"));
        resp.setEmotionProfile(Map.of(
                "moral_outrage", 0.89, "resentment", 0.76, "fear_inducing", 0.71,
                "national_pride", 0.68, "conspiracy_belief", 0.62, "victimhood_narrative", 0.58,
                "contempt", 0.54, "distrust", 0.48, "hope_appeal", 0.31, "helplessness", 0.22
        ));
        resp.setStanceProfile(List.of(
                Map.of("topic", "美伊核协议", "stance", "strongly_oppose", "confidence", 0.91)
        ));
        resp.setActiveTimePattern(Map.of("14", 0.15, "20", 0.22));
        resp.setPostFrequencyDaily(new BigDecimal("4.20"));
        resp.setContentOriginalRatio(new BigDecimal("0.350"));
        resp.setBendProfile(Map.of(
                "Distort", 0.41, "Dismiss", 0.28, "Amplify", 0.19, "Narrativize", 0.07, "other", 0.05
        ));
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
        resp.setTargetEvidence("该账号高频转发特定叙事内容，发帖时段集中在目标地区活跃时段（mock证据说明）");
        resp.setHiddenRelations(List.of(Map.of(
                "targetId", UUID.randomUUID().toString(), "relationType", "潜在从属", "confidence", 0.63)));
        resp.setManipulationRisk("high");
        resp.setManipulationScore(new BigDecimal("82.00"));
        resp.setModelVersions(Map.of("t5_model", "mock-t5-v1.0"));
        return resp;
    }

    // ============================================================
    // T6 重点目标识别
    // ============================================================
    @PostMapping("/mock/t6/identify_targets")
    public T6IdentifyResponse identifyTargets(@RequestBody T6IdentifyRequest request) {
        log.info("[MOCK-T6] identify_targets, triggerType={}, narrativeId={}",
                request.getTriggerType(), request.getNarrativeId());

        T6IdentifyResponse.IdentifiedTarget target = new T6IdentifyResponse.IdentifiedTarget();
        target.setTargetType("T08");
        target.setTargetEntityType("social_account");
        target.setTargetEntityId(UUID.randomUUID().toString());
        target.setConfidence(new BigDecimal("0.870"));
        target.setBendDistribution(Map.of(
                "Distort", 0.41, "Dismiss", 0.28, "Amplify", 0.19, "Narrativize", 0.07, "other", 0.05
        ));
        target.setEvidenceText("该账号与另外3个账号在24小时内发布高度相似内容，时间同步度0.92（mock证据说明）");
        target.setEvidenceContentIds(List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()));

        T6IdentifyResponse resp = new T6IdentifyResponse();
        resp.setTargets(List.of(target));
        resp.setAccountsAnalyzed(47);
        resp.setGroupsDetected(1);
        return resp;
    }

    // ============================================================
    // 工具方法
    // ============================================================
    private String toJson(Object obj) {
        // mock阶段简化处理：把对象toString()当作raw字段值即可，
        // 不引入额外的Jackson依赖配置，因为这个字段只是落库追溯用，不参与业务逻辑解析
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JacksonException e) {
            return "{\"serializationError\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }
}

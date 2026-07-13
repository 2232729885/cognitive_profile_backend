package com.idata.profile.service;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t2.T2ExtractResponse;
import com.idata.profile.agentproxy.dto.t3.T3ResolveBatchRequest;
import com.idata.profile.agentproxy.dto.t3.T3ResolveBatchResponse;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingRequest;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingResponse;
import com.idata.profile.common.util.StableUuidUtil;
import com.idata.profile.entity.dedup.EntityFusionRecord;
import com.idata.profile.infra.elasticsearch.EntityEsService;
import com.idata.profile.infra.milvus.MilvusVectorService;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.dedup.EntityFusionRecordMapper;
import com.idata.profile.mapper.graph.EventMapper;
import com.idata.profile.mapper.graph.OrganizationMapper;
import com.idata.profile.mapper.graph.PersonMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实体候选召回后的解析/合并/新建逻辑，供 T2ExtractionStep（内容侧）和
 * SocialAccountIdentityJob（账号侧）共用，避免各写一份。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntityResolutionService {

    private static final double AUTO_MERGE_THRESHOLD = 0.9D;
    private static final double REVIEW_THRESHOLD = 0.6D;

    private final AgentProxyClient agentProxyClient;
    private final PersonMapper personMapper;
    private final OrganizationMapper organizationMapper;
    private final EventMapper eventMapper;
    private final Neo4jGraphService neo4jGraphService;
    private final EntityEsService entityEsService;
    private final MilvusVectorService milvusVectorService;
    private final EntityCandidateRetrievalService candidateRetrievalService;
    private final EntityFusionRecordMapper entityFusionRecordMapper;

    public Map<String, ResolvedMention> resolveMentions(
            List<T2ExtractResponse.ExtractedMention> rawMentions,
            String contentId, String platform, String language) {
        Map<String, ResolvedMention> resolved = new LinkedHashMap<>();
        if (rawMentions == null || rawMentions.isEmpty()) {
            return resolved;
        }

        List<T2ExtractResponse.ExtractedMention> mentions = rawMentions.stream()
                .map(this::normalizeMention)
                .filter(m -> hasText(m.getMentionId()) && hasText(m.getType()) && hasText(entityName(m)))
                .toList();
        Map<String, T3ResolveBatchResponse.ResolveResult> results =
                resolveWithT3(mentions, contentId, platform, language);

        for (T2ExtractResponse.ExtractedMention mention : mentions) {
            T3ResolveBatchResponse.ResolveResult result = results.get(mention.getMentionId());
            ResolvedMention resolvedMention = applyResolution(mention, result);
            resolved.put(mention.getMentionId(), resolvedMention);
        }
        return resolved;
    }

    private T2ExtractResponse.ExtractedMention normalizeMention(T2ExtractResponse.ExtractedMention mention) {
        if (mention == null) {
            return null;
        }
        mention.setType(hasText(mention.getType()) ? mention.getType().trim().toLowerCase() : null);
        mention.setName(trimToNull(mention.getName()));
        mention.setCanonicalName(hasText(mention.getCanonicalName())
                ? mention.getCanonicalName().trim()
                : mention.getName());
        mention.setAliases(distinctText(mention.getAliases()));
        if (mention.getAttributes() == null) {
            mention.setAttributes(Map.of());
        }
        return mention;
    }

    private Map<String, T3ResolveBatchResponse.ResolveResult> resolveWithT3(
            List<T2ExtractResponse.ExtractedMention> mentions,
            String contentId, String platform, String language) {
        T3ResolveBatchRequest request = new T3ResolveBatchRequest();
        request.setItems(mentions.stream()
                .map(mention -> toResolveItem(mention, contentId, platform, language))
                .filter(item -> item.getCandidates() != null && !item.getCandidates().isEmpty())
                .toList());
        T3ResolveBatchRequest.Strategy strategy = new T3ResolveBatchRequest.Strategy();
        strategy.setAutoMergeThreshold(AUTO_MERGE_THRESHOLD);
        strategy.setReviewThreshold(REVIEW_THRESHOLD);
        request.setStrategy(strategy);

        if (request.getItems().isEmpty()) {
            return Map.of();
        }

        try {
            T3ResolveBatchResponse response = agentProxyClient.call(
                    "T3", "resolve_batch", request, T3ResolveBatchResponse.class);
            if (response == null || response.getResults() == null) {
                return Map.of();
            }
            Map<String, T3ResolveBatchResponse.ResolveResult> result = new HashMap<>();
            for (T3ResolveBatchResponse.ResolveResult item : response.getResults()) {
                if (item != null && hasText(item.getMentionId())) {
                    item.setModelVersion(response.getModelVersion());
                    result.put(item.getMentionId(), item);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[EntityResolutionService] T3 resolve_batch failed, contentId={}", contentId, e);
            return Map.of();
        }
    }

    private T3ResolveBatchRequest.ResolveItem toResolveItem(
            T2ExtractResponse.ExtractedMention mention, String contentId, String platform, String language) {
        T3ResolveBatchRequest.ResolveItem item = new T3ResolveBatchRequest.ResolveItem();
        item.setMention(toT3Mention(mention));
        item.setCandidates(candidateRetrievalService.retrieveCandidates(entityName(mention), mention.getType(), 10));
        T3ResolveBatchRequest.Context context = new T3ResolveBatchRequest.Context();
        context.setContentId(contentId);
        context.setPlatform(platform);
        context.setTextWindow(entityName(mention));
        context.setLanguage(language);
        item.setContext(context);
        return item;
    }

    private T3ResolveBatchRequest.Mention toT3Mention(T2ExtractResponse.ExtractedMention mention) {
        T3ResolveBatchRequest.Mention result = new T3ResolveBatchRequest.Mention();
        result.setMentionId(mention.getMentionId());
        result.setName(mention.getName());
        result.setCanonicalName(mention.getCanonicalName());
        if (mention.getSpan() != null) {
            T3ResolveBatchRequest.Mention.Span span = new T3ResolveBatchRequest.Mention.Span();
            span.setStart(mention.getSpan().getStart());
            span.setEnd(mention.getSpan().getEnd());
            result.setSpan(span);
        }
        result.setType(mention.getType());
        result.setAliases(mention.getAliases());
        result.setAttributes(mention.getAttributes());
        return result;
    }

    private ResolvedMention applyResolution(T2ExtractResponse.ExtractedMention mention,
                                            T3ResolveBatchResponse.ResolveResult result) {
        String action = result != null && hasText(result.getAction()) ? result.getAction() : "CREATE";
        double confidence = boundedScore(result != null ? result.getConfidence() : null, 0D);
        if ("MERGE".equalsIgnoreCase(action) && confidence >= AUTO_MERGE_THRESHOLD
                && hasText(result.getMatchedEntityId())) {
            writeEntityNode(result.getMatchedEntityId(), mention, "t2_t3_merge");
            insertFusionRecord(mention, result, true);
            return resolved(mention, result.getMatchedEntityId(), "MERGE");
        }
        if ("REVIEW".equalsIgnoreCase(action) && confidence >= REVIEW_THRESHOLD
                && hasText(result.getMatchedEntityId())) {
            writeEntityNode(result.getMatchedEntityId(), mention, "t2_t3_review");
            insertFusionRecord(mention, result, false);
            return resolved(mention, result.getMatchedEntityId(), "REVIEW");
        }

        String nodeId = stableUuid(mention.getType() + ":" + entityName(mention));
        insertEntity(mention);
        writeEntityNode(nodeId, mention, "t2_create");
        indexEntity(nodeId, mention);
        syncEntityVectorToMilvus(nodeId, mention);
        return resolved(mention, nodeId, "CREATE");
    }

    private ResolvedMention resolved(T2ExtractResponse.ExtractedMention mention, String nodeId, String action) {
        ResolvedMention result = new ResolvedMention();
        result.setMentionId(mention.getMentionId());
        result.setNodeId(nodeId);
        result.setLabel(toNeo4jLabel(mention.getType()));
        result.setEntityType(mention.getType());
        result.setAction(action);
        result.setName(entityName(mention));
        return result;
    }

    private void insertEntity(T2ExtractResponse.ExtractedMention entity) {
        String entityType = entity.getType();
        String canonicalName = entityName(entity);
        BigDecimal importanceScore = entity.getImportanceScore() != null
                ? BigDecimal.valueOf(entity.getImportanceScore()) : BigDecimal.ZERO;

        switch (entityType) {
            case "person" -> personMapper.insertEntity(canonicalName, importanceScore);
            case "organization" -> organizationMapper.insertEntity(canonicalName, importanceScore);
            case "event" -> eventMapper.insertEntity(canonicalName, importanceScore);
            case "location" -> log.debug("Location mention is stored in Neo4j only, name={}", canonicalName);
            default -> log.warn("Unknown extracted entity type: {}", entity.getType());
        }
    }

    private void writeEntityNode(String nodeId, T2ExtractResponse.ExtractedMention entity, String source) {
        String label = toNeo4jLabel(entity.getType());
        if (!hasText(label)) {
            return;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("canonicalName", entityName(entity));
        if (entity.getAliases() != null && !entity.getAliases().isEmpty()) {
            props.put("aliases", entity.getAliases().toArray(new String[0]));
        }
        if (entity.getImportanceScore() != null) {
            props.put("importanceScore", entity.getImportanceScore());
        }
        if ("event".equals(entity.getType())) {
            putIfHasText(props, "eventType", stringValue(entity.getAttributes().get("eventType")));
            putIfHasText(props, "eventTimeStart", stringValue(entity.getAttributes().get("eventTimeStart")));
        }
        props.put("source", source);
        neo4jGraphService.mergeNode(label, nodeId, props);
    }

    private void indexEntity(String nodeId, T2ExtractResponse.ExtractedMention entity) {
        entityEsService.indexEntity(
                nodeId,
                entityName(entity),
                entity.getCanonicalName(),
                entity.getAliases(),
                entity.getType(),
                entity.getImportanceScore() != null ? entity.getImportanceScore() : 0D);
    }

    private void syncEntityVectorToMilvus(String stableId, T2ExtractResponse.ExtractedMention entity) {
        try {
            T4EmbeddingRequest req = new T4EmbeddingRequest();
            req.setText(entityName(entity));
            T4EmbeddingResponse resp = agentProxyClient.call(
                    "T4", "generate_text_embedding", req, T4EmbeddingResponse.class);
            if (resp == null || resp.getEmbedding() == null) {
                return;
            }
            milvusVectorService.insertEntityEmbedding(
                    stableId, entity.getType(), entityName(entity), resp.getEmbedding());
            log.debug("[EntityResolutionService] entity vector indexed, entityId={}, name={}",
                    stableId, entityName(entity));
        } catch (Exception e) {
            log.warn("[EntityResolutionService] entity vector indexing failed, entityId={}, name={}",
                    stableId, entityName(entity), e);
        }
    }

    private void insertFusionRecord(T2ExtractResponse.ExtractedMention mention,
                                    T3ResolveBatchResponse.ResolveResult result,
                                    boolean autoMerged) {
        try {
            EntityFusionRecord record = new EntityFusionRecord();
            record.setId(UUID.randomUUID());
            record.setEntityType(mention.getType());
            record.setSurvivorId(parseUuid(result.getMatchedEntityId()));
            record.setSurvivorName(result.getMatchedEntityId());
            record.setMergedIds(new UUID[0]);
            record.setMergedNames(new String[]{entityName(mention)});
            record.setMergedCount(0);
            record.setFusionMethod(autoMerged ? "t3_realtime_merge" : "t3_pending_review");
            record.setNeo4jMerged(autoMerged);
            record.setJobRunId(UUID.randomUUID());
            record.setMatchMethod(result.getMatchMethod());
            record.setMatchScore(toMatchScore(result));
            record.setResolverModel(result.getModelVersion());
            record.setIsAutoMerged(autoMerged);
            entityFusionRecordMapper.insert(record);
        } catch (Exception e) {
            log.warn("[EntityResolutionService] failed to insert realtime fusion record, mentionId={}, matchedEntityId={}",
                    mention.getMentionId(), result.getMatchedEntityId(), e);
        }
    }

    private String toNeo4jLabel(String type) {
        return switch (type != null ? type : "") {
            case "person" -> "Person";
            case "organization" -> "Organization";
            case "event" -> "Event";
            case "location" -> "Location";
            case "social_account" -> "SocialAccount";
            default -> null;
        };
    }

    private String stableUuid(String seed) {
        return StableUuidUtil.fromSeed(seed);
    }

    private String entityName(T2ExtractResponse.ExtractedMention mention) {
        return hasText(mention.getCanonicalName()) ? mention.getCanonicalName() : mention.getName();
    }

    private BigDecimal toMatchScore(T3ResolveBatchResponse.ResolveResult result) {
        if (result == null) {
            return null;
        }
        Double score = result.getConfidence() != null ? result.getConfidence() : result.getScore();
        if (score == null || !Double.isFinite(score)) {
            return null;
        }
        return BigDecimal.valueOf(boundedScore(score, 0D)).setScale(4, RoundingMode.HALF_UP);
    }

    private double boundedScore(Double value, double fallback) {
        if (value == null || !Double.isFinite(value)) {
            return fallback;
        }
        if (value < 0D) {
            return 0D;
        }
        if (value > 1D) {
            return 1D;
        }
        return value;
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private List<String> distinctText(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(this::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value);
        }
    }

    private UUID parseUuid(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @Data
    public static class ResolvedMention {
        private String mentionId;
        private String nodeId;
        private String label;
        private String entityType;
        private String action;
        private String name;
    }
}

package com.idata.profile.pipeline.step;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t2.T2ExtractRequest;
import com.idata.profile.agentproxy.dto.t2.T2ExtractResponse;
import com.idata.profile.agentproxy.dto.t3.T3ResolveBatchRequest;
import com.idata.profile.agentproxy.dto.t3.T3ResolveBatchResponse;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingRequest;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingResponse;
import com.idata.profile.common.constant.AllowedRelationTypes;
import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.common.util.StableUuidUtil;
import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.dedup.EntityFusionRecord;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.infra.elasticsearch.EntityEsService;
import com.idata.profile.infra.milvus.MilvusVectorService;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.account.SocialAccountMapper;
import com.idata.profile.mapper.content.MediaAssetMapper;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.mapper.dedup.EntityFusionRecordMapper;
import com.idata.profile.mapper.graph.EventMapper;
import com.idata.profile.mapper.graph.NarrativeMapper;
import com.idata.profile.mapper.graph.OrganizationMapper;
import com.idata.profile.mapper.graph.PersonMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import com.idata.profile.service.EntityCandidateRetrievalService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class T2ExtractionStep {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final double AUTO_MERGE_THRESHOLD = 0.9D;
    private static final double REVIEW_THRESHOLD = 0.6D;

    private final AgentProxyClient agentProxyClient;
    private final MediaContentMapper mediaContentMapper;
    private final RawRecordMapper rawRecordMapper;
    private final PipelineTaskMapper pipelineTaskMapper;
    private final PersonMapper personMapper;
    private final OrganizationMapper organizationMapper;
    private final EventMapper eventMapper;
    private final NarrativeMapper narrativeMapper;
    private final Neo4jGraphService neo4jGraphService;
    private final SocialAccountMapper socialAccountMapper;
    private final MediaAssetMapper mediaAssetMapper;
    private final EntityEsService entityEsService;
    private final MilvusVectorService milvusVectorService;
    private final EntityCandidateRetrievalService candidateRetrievalService;
    private final EntityFusionRecordMapper entityFusionRecordMapper;

    public void run(PipelineTask task) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        task.setT2Status("running");
        task.setT2StartedAt(startedAt);
        pipelineTaskMapper.updateById(task);

        MediaContent mc = mediaContentMapper.selectById(task.getContentId());
        T2ExtractResponse response = agentProxyClient.call(
                "T2", "extract_entities", buildRequest(mc), T2ExtractResponse.class);

        Map<String, ResolvedMention> resolvedMentions = processMentions(response, mc);

        if (mc.getAuthorAccountId() == null && hasText(response.getResolvedAuthorAccountId())) {
            mc.setAuthorAccountId(UUID.fromString(response.getResolvedAuthorAccountId()));
            mediaContentMapper.updateById(mc);
        }

        writeToNeo4j(task, response, mc, resolvedMentions);

        RawRecord rawRecord = rawRecordMapper.selectById(task.getRawRecordId());
        rawRecord.setT2Output(response.getRaw());
        rawRecord.setPipelineStatus(PipelineStatus.T2_DONE.name());
        rawRecordMapper.updateById(rawRecord);

        task.setT2Status("done");
        task.setT2DoneAt(OffsetDateTime.now());
        task.setT2DurationMs((int) java.time.Duration.between(startedAt, OffsetDateTime.now()).toMillis());
        pipelineTaskMapper.updateById(task);
    }

    private T2ExtractRequest buildRequest(MediaContent mc) {
        T2ExtractRequest request = new T2ExtractRequest();
        request.setDocId(mc.getId().toString());
        request.setText(mc.getBodyText());
        request.setLanguage(mc.getLanguage());
        if (hasText(mc.getNarrativeHint())) {
            try {
                request.setAnnotation(OBJECT_MAPPER.readValue(mc.getNarrativeHint(), Object.class));
            } catch (Exception e) {
                log.warn("Failed to parse T1 entities hint for T2, contentId={}", mc.getId(), e);
            }
        }

        T2ExtractRequest.SourceInfo source = new T2ExtractRequest.SourceInfo();
        source.setPlatformId(mc.getPlatform());
        source.setContentUrl(mc.getUrl());
        source.setPublishTime(mc.getPublishedAt() != null ? mc.getPublishedAt().toString() : null);
        source.setAuthorHandle(mc.getAuthorPlatformUserId());
        source.setHashtags(mc.getHashtags());
        source.setMentions(mc.getMentions());
        source.setParentContentId(mc.getParentContentId());
        source.setRepostOfContentId(mc.getRepostOfContentId());
        source.setQuotedContentId(mc.getQuotedContentId());
        request.setSource(source);
        return request;
    }

    private Map<String, ResolvedMention> processMentions(T2ExtractResponse response, MediaContent content) {
        Map<String, ResolvedMention> resolved = new LinkedHashMap<>();
        if (response == null || response.getEntities() == null || response.getEntities().isEmpty()) {
            return resolved;
        }

        List<T2ExtractResponse.ExtractedMention> mentions = response.getEntities().stream()
                .map(this::normalizeMention)
                .filter(m -> hasText(m.getMentionId()) && hasText(m.getType()) && hasText(entityName(m)))
                .toList();
        Map<String, T3ResolveBatchResponse.ResolveResult> results = resolveWithT3(mentions, content);

        for (T2ExtractResponse.ExtractedMention mention : mentions) {
            T3ResolveBatchResponse.ResolveResult result = results.get(mention.getMentionId());
            ResolvedMention resolvedMention = applyResolution(mention, result, response.getModelVersion());
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
        mention.setNormalizedName(hasText(mention.getNormalizedName())
                ? mention.getNormalizedName().trim()
                : mention.getName());
        mention.setAliases(distinctText(mention.getAliases()));
        if (mention.getAttributes() == null) {
            mention.setAttributes(Map.of());
        }
        return mention;
    }

    private Map<String, T3ResolveBatchResponse.ResolveResult> resolveWithT3(
            List<T2ExtractResponse.ExtractedMention> mentions, MediaContent content) {
        T3ResolveBatchRequest request = new T3ResolveBatchRequest();
        request.setItems(mentions.stream()
                .map(mention -> toResolveItem(mention, content))
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
            log.warn("[T2] T3 resolve_batch failed, contentId={}", content.getId(), e);
            return Map.of();
        }
    }

    private T3ResolveBatchRequest.ResolveItem toResolveItem(
            T2ExtractResponse.ExtractedMention mention, MediaContent content) {
        T3ResolveBatchRequest.ResolveItem item = new T3ResolveBatchRequest.ResolveItem();
        item.setMention(toT3Mention(mention));
        item.setCandidates(candidateRetrievalService.retrieveCandidates(entityName(mention), mention.getType(), 10));
        T3ResolveBatchRequest.Context context = new T3ResolveBatchRequest.Context();
        context.setDocId(content.getId().toString());
        context.setTextWindow(entityName(mention));
        context.setLanguage(content.getLanguage());
        item.setContext(context);
        return item;
    }

    private T3ResolveBatchRequest.Mention toT3Mention(T2ExtractResponse.ExtractedMention mention) {
        T3ResolveBatchRequest.Mention result = new T3ResolveBatchRequest.Mention();
        result.setMentionId(mention.getMentionId());
        result.setName(mention.getName());
        result.setNormalizedName(mention.getNormalizedName());
        result.setType(mention.getType());
        result.setAliases(mention.getAliases());
        result.setAttributes(mention.getAttributes());
        return result;
    }

    private ResolvedMention applyResolution(T2ExtractResponse.ExtractedMention mention,
                                            T3ResolveBatchResponse.ResolveResult result,
                                            String modelVersion) {
        String action = result != null && hasText(result.getAction()) ? result.getAction() : "CREATE";
        double confidence = result != null && result.getConfidence() != null ? result.getConfidence() : 0D;
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
            case "narrative" -> narrativeMapper.insertEntity(
                    canonicalName, importanceScore, buildClaimAtoms(canonicalName, importanceScore));
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
        String nameKey = "narrative".equals(entity.getType()) ? "canonicalLabel" : "canonicalName";
        props.put(nameKey, entityName(entity));
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
                entity.getNormalizedName(),
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
            log.debug("[T2] entity vector indexed, entityId={}, name={}", stableId, entityName(entity));
        } catch (Exception e) {
            log.warn("[T2] entity vector indexing failed, entityId={}, name={}", stableId, entityName(entity), e);
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
            record.setMatchScore(result.getScore() != null ? BigDecimal.valueOf(result.getScore()) : null);
            record.setResolverModel(result.getModelVersion());
            record.setIsAutoMerged(autoMerged);
            entityFusionRecordMapper.insert(record);
        } catch (Exception e) {
            log.warn("[T2] failed to insert realtime fusion record, mentionId={}, matchedEntityId={}",
                    mention.getMentionId(), result.getMatchedEntityId(), e);
        }
    }

    private void writeToNeo4j(PipelineTask task, T2ExtractResponse response, MediaContent mc,
                              Map<String, ResolvedMention> resolvedMentions) {
        try {
            writeRelations(response, resolvedMentions);
            linkAuthorAccount(resolvedMentions, mc);
            writeMediaContentToNeo4j(task, mc);
        } catch (Exception e) {
            log.error("T2 Neo4j write failed, taskId={}", task.getId(), e);
        }
    }

    private void writeRelations(T2ExtractResponse response, Map<String, ResolvedMention> resolvedMentions) {
        if (response == null || response.getRelations() == null) {
            return;
        }
        for (T2ExtractResponse.ExtractedRelationMention rel : response.getRelations()) {
            if (rel == null || !hasText(rel.getSubjectMentionId()) || !hasText(rel.getObjectMentionId())
                    || !hasText(rel.getPredicate())) {
                continue;
            }
            if (!AllowedRelationTypes.VALUES.contains(rel.getPredicate())) {
                log.warn("Skip T2 relation with unsupported predicate={}, subjectMentionId={}, objectMentionId={}",
                        rel.getPredicate(), rel.getSubjectMentionId(), rel.getObjectMentionId());
                continue;
            }
            ResolvedMention from = resolvedMentions.get(rel.getSubjectMentionId());
            ResolvedMention to = resolvedMentions.get(rel.getObjectMentionId());
            if (from == null || to == null || !hasText(from.getLabel()) || !hasText(to.getLabel())) {
                continue;
            }
            try {
                Map<String, Object> props = new HashMap<>();
                props.put("source", "t2_realtime_resolution");
                props.put("extraction_method", "t2_relation_mention");
                props.put("relationMentionId", rel.getRelationMentionId());
                if (rel.getConfidence() != null) {
                    props.put("confidence", rel.getConfidence());
                }
                putIfHasText(props, "evidence", rel.getEvidence());
                boolean existed = neo4jGraphService.relationExists(from.getNodeId(), to.getNodeId(), rel.getPredicate());
                props.put("evidenceUpsert", existed);
                neo4jGraphService.mergeRelation(
                        from.getLabel(), from.getNodeId(),
                        to.getLabel(), to.getNodeId(),
                        rel.getPredicate(), props);
            } catch (Exception e) {
                log.warn("T2 relation write failed, predicate={}, subjectMentionId={}, objectMentionId={}",
                        rel.getPredicate(), rel.getSubjectMentionId(), rel.getObjectMentionId(), e);
            }
        }
    }

    private void linkAuthorAccount(Map<String, ResolvedMention> resolvedMentions, MediaContent content) {
        if (content == null || content.getAuthorAccountId() == null) {
            return;
        }
        ResolvedMention person = firstResolvedByType(resolvedMentions, "person");
        if (person == null) {
            return;
        }

        SocialAccount account = socialAccountMapper.selectById(content.getAuthorAccountId());
        if (account == null) {
            return;
        }

        Map<String, Object> accountProps = new HashMap<>();
        putIfHasText(accountProps, "platform", account.getPlatform());
        putIfHasText(accountProps, "platformUserId", account.getPlatformUserId());
        putIfHasText(accountProps, "handle", account.getHandle());
        putIfHasText(accountProps, "displayName", account.getDisplayName());
        accountProps.put("source", "t2_author_link");

        neo4jGraphService.mergeNode("SocialAccount", account.getId().toString(), accountProps);
        neo4jGraphService.mergeRelation(
                "Person", person.getNodeId(),
                "SocialAccount", account.getId().toString(),
                "HAS_ACCOUNT",
                Map.of("confidence", 0.95D, "source", "t2_author_link",
                        "extraction_method", "author_field_lookup"));
    }

    private ResolvedMention firstResolvedByType(Map<String, ResolvedMention> resolvedMentions, String type) {
        for (ResolvedMention mention : resolvedMentions.values()) {
            if (type.equals(mention.getEntityType())) {
                return mention;
            }
        }
        return null;
    }

    private void writeMediaContentToNeo4j(PipelineTask task, MediaContent content) {
        try {
            if (content == null) {
                log.debug("Skip structural MediaContent Neo4j write because content was not found, taskId={}",
                        task.getId());
                return;
            }

            mergeMediaContentNode(content);

            if (content.getAuthorAccountId() != null) {
                mergeSocialAccountNode(content.getAuthorAccountId());
                neo4jGraphService.mergeRelation(
                        "MediaContent", content.getId().toString(),
                        "SocialAccount", content.getAuthorAccountId().toString(),
                        "PUBLISHED_BY",
                        Map.of("source", "backend_structural",
                                "extraction_method", "author_field_lookup"));
            }

            boolean allSynced = true;
            allSynced &= writePropagationRelation(content, content.getParentContentId(), "REPLY_TO");
            allSynced &= writePropagationRelation(content, content.getRepostOfContentId(), "REPOSTS");
            if (!sameContentId(content.getRootContentId(), content.getPlatformContentId())) {
                allSynced &= writePropagationRelation(content, content.getRootContentId(), "REPLY_TO");
            }
            if (allSynced) {
                mediaContentMapper.markPropagationSyncedToNeo4j(content.getId());
            }

            writeMediaAssetNodes(content);
        } catch (Exception e) {
            log.warn("Structural MediaContent Neo4j write failed, taskId={}, contentId={}",
                    task.getId(), task.getContentId(), e);
        }
    }

    private void writeMediaAssetNodes(MediaContent content) {
        UUID[] assetIds = content.getMediaAssetIds();
        if (assetIds == null || assetIds.length == 0) {
            return;
        }

        for (UUID assetId : assetIds) {
            try {
                MediaAsset asset = mediaAssetMapper.selectById(assetId);
                if (asset == null || "thumbnail".equals(asset.getAssetType())) {
                    continue;
                }

                Map<String, Object> props = new HashMap<>();
                putIfHasText(props, "assetType", asset.getAssetType());
                putIfHasText(props, "mimeType", asset.getMimeType());
                putIfHasText(props, "sourceUrl", asset.getSourceUrl());
                putIfHasText(props, "minioBucket", asset.getMinioBucket());
                putIfHasText(props, "minioKey", asset.getMinioKey());
                putIfNotNull(props, "fileSizeBytes", asset.getFileSizeBytes());
                putIfNotNull(props, "width", asset.getWidth());
                putIfNotNull(props, "height", asset.getHeight());
                putIfNotNull(props, "durationSeconds", asset.getDurationSeconds());
                putIfHasText(props, "ocrText", asset.getOcrText());
                putIfHasText(props, "asrText", asset.getAsrText());
                putIfHasText(props, "sceneLabel", asset.getSceneLabel());
                putIfHasText(props, "objectAnnotations", asset.getObjectAnnotations());
                putIfNotNull(props, "aigcScore",
                        asset.getAigcScore() != null ? asset.getAigcScore().doubleValue() : null);
                props.put("source", "backend_structural");

                neo4jGraphService.mergeNode("MediaAsset", asset.getId().toString(), props);
                Map<String, Object> relationProps = new HashMap<>();
                putIfHasText(relationProps, "assetType", asset.getAssetType());
                relationProps.put("source", "backend_structural");
                neo4jGraphService.mergeRelation(
                        "MediaContent", content.getId().toString(),
                        "MediaAsset", asset.getId().toString(),
                        "HAS_MEDIA",
                        relationProps);
            } catch (Exception e) {
                log.warn("Failed to write MediaAsset to Neo4j, assetId={}", assetId, e);
            }
        }
    }

    private boolean writePropagationRelation(MediaContent content, String targetPlatformContentId, String relationType) {
        if (!hasText(targetPlatformContentId)) {
            return true;
        }
        if (!hasText(content.getPlatform())) {
            return false;
        }

        try {
            MediaContent target = mediaContentMapper.selectByPlatformAndContentId(
                    content.getPlatform(), targetPlatformContentId.trim());
            if (target == null) {
                return false;
            }
            mergeMediaContentNode(target);
            neo4jGraphService.mergeRelation(
                    "MediaContent", content.getId().toString(),
                    "MediaContent", target.getId().toString(),
                    relationType,
                    Map.of("source", "backend_structural",
                            "extraction_method", "propagation_chain_field"));
            return true;
        } catch (Exception e) {
            log.warn("Failed to write propagation relation to Neo4j, contentId={}, relationType={}, "
                            + "targetPlatformContentId={}",
                    content.getId(), relationType, targetPlatformContentId, e);
            return false;
        }
    }

    private void mergeMediaContentNode(MediaContent content) {
        Map<String, Object> contentProps = new HashMap<>();
        putIfHasText(contentProps, "platform", content.getPlatform());
        putIfHasText(contentProps, "contentType", content.getContentType());
        putIfHasText(contentProps, "language", content.getLanguage());
        putIfHasText(contentProps, "platformContentId", content.getPlatformContentId());
        putIfNotNull(contentProps, "publishedAt",
                content.getPublishedAt() != null ? content.getPublishedAt().toString() : null);
        putIfHasText(contentProps, "url", content.getUrl());
        putIfHasText(contentProps, "topicCategory", content.getTopicCategory());
        putIfHasText(contentProps, "sentimentLabel", content.getSentimentLabel());
        putIfNotNull(contentProps, "aigcScore",
                content.getAigcScore() != null ? content.getAigcScore().doubleValue() : null);
        contentProps.put("source", "backend_structural");
        neo4jGraphService.mergeNode("MediaContent", content.getId().toString(), contentProps);
    }

    private void mergeSocialAccountNode(UUID accountId) {
        SocialAccount account = socialAccountMapper.selectById(accountId);
        Map<String, Object> accountProps = new HashMap<>();
        if (account != null) {
            putIfHasText(accountProps, "platform", account.getPlatform());
            putIfHasText(accountProps, "platformUserId", account.getPlatformUserId());
            putIfHasText(accountProps, "handle", account.getHandle());
            putIfHasText(accountProps, "displayName", account.getDisplayName());
        }
        accountProps.put("source", "backend_structural");
        neo4jGraphService.mergeNode("SocialAccount", accountId.toString(), accountProps);
    }

    private String toNeo4jLabel(String type) {
        return switch (type != null ? type : "") {
            case "person" -> "Person";
            case "organization" -> "Organization";
            case "event" -> "Event";
            case "narrative" -> "Narrative";
            case "location" -> "Location";
            case "social_account" -> "SocialAccount";
            default -> null;
        };
    }

    private String stableUuid(String seed) {
        return StableUuidUtil.fromSeed(seed);
    }

    private String entityName(T2ExtractResponse.ExtractedMention mention) {
        return hasText(mention.getNormalizedName()) ? mention.getNormalizedName() : mention.getName();
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

    private boolean sameContentId(String left, String right) {
        return hasText(left) && hasText(right) && left.trim().equals(right.trim());
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value);
        }
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String buildClaimAtoms(String canonicalName, BigDecimal importanceScore) {
        try {
            BigDecimal confidence = importanceScore
                    .divide(new BigDecimal("100"), 3, java.math.RoundingMode.HALF_UP)
                    .min(BigDecimal.ONE)
                    .max(BigDecimal.ZERO);
            return OBJECT_MAPPER.writeValueAsString(List.of(Map.of(
                    "claim", canonicalName,
                    "confidence", confidence
            )));
        } catch (JacksonException e) {
            log.warn("Failed to serialize narrative claim atom, canonicalName={}", canonicalName, e);
            return "[]";
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
    private static class ResolvedMention {
        private String mentionId;
        private String nodeId;
        private String label;
        private String entityType;
        private String action;
        private String name;
    }
}

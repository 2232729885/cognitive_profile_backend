package com.idata.profile.pipeline.step;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t2.T2ExtractRequest;
import com.idata.profile.agentproxy.dto.t2.T2ExtractResponse;
import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.common.util.StableUuidUtil;
import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.account.SocialAccountMapper;
import com.idata.profile.mapper.content.MediaAssetMapper;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.mapper.graph.EventMapper;
import com.idata.profile.mapper.graph.NarrativeMapper;
import com.idata.profile.mapper.graph.OrganizationMapper;
import com.idata.profile.mapper.graph.PersonMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class T2ExtractionStep {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_RELATION_TYPES = Set.of(
            "SAME_AS", "HAS_ACCOUNT", "ALIAS_OF", "MERGED_INTO",
            "AFFILIATED_WITH", "PART_OF", "CONTROLS", "OWNS", "MEMBER_OF", "ADMIN_OF", "PUBLISHED_IN",
            "AUTHORED", "REPLY_TO", "COMMENT_ON", "REPOSTS", "QUOTES", "SHARES", "REFERENCES_URL", "MENTIONS", "HAS_MEDIA",
            "DESCRIBES", "REPORTS", "EVENT_OCCURRED_AT", "EVENT_INVOLVES_ENTITY", "LOCATED_IN", "POSTS_FROM",
            "CONTENT_EXPRESSES_NARRATIVE", "NARRATIVE_TARGETS_ENTITY", "NARRATIVE_ABOUT_EVENT",
            "SUPPORTS", "OPPOSES", "HAS_EMOTION",
            "AMPLIFIES", "BRIDGES_COMMUNITY", "COORDINATES_WITH", "POTENTIAL_SUBORDINATE_TO", "INFLUENCES",
            "ASSERTED_BY", "DERIVED_FROM", "CONFLICTS_WITH", "REVIEWED_BY");

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

    public void run(PipelineTask task) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        task.setT2Status("running");
        task.setT2StartedAt(startedAt);
        pipelineTaskMapper.updateById(task);

        MediaContent mc = mediaContentMapper.selectById(task.getContentId());

        T2ExtractRequest request = new T2ExtractRequest();
        request.setText(mc.getBodyText());
        if (hasText(mc.getNarrativeHint())) {
            try {
                request.setAnnotation(OBJECT_MAPPER.readValue(mc.getNarrativeHint(), Object.class));
            } catch (Exception e) {
                log.warn("Failed to parse T1 entities hint for T2, contentId={}", mc.getId(), e);
            }
        }
        T2ExtractRequest.SourceInfo sourceInfo = new T2ExtractRequest.SourceInfo();
        sourceInfo.setPlatformId(mc.getPlatform());
        sourceInfo.setContentUrl(mc.getUrl());
        sourceInfo.setPublishTime(mc.getPublishedAt() != null ? mc.getPublishedAt().toString() : null);
        sourceInfo.setAuthorHandle(mc.getAuthorPlatformUserId());
        request.setSourceInfo(sourceInfo);
        request.setHashtags(mc.getHashtags());
        request.setMentions(mc.getMentions());
        request.setParentContentId(mc.getParentContentId());
        request.setRepostOfContentId(mc.getRepostOfContentId());
        request.setQuotedContentId(mc.getQuotedContentId());

        T2ExtractResponse response = agentProxyClient.call("T2", "extract_entities", request, T2ExtractResponse.class);

        if (response.getEntities() != null) {
            for (T2ExtractResponse.ExtractedEntity entity : response.getEntities()) {
                insertEntity(entity);
            }
        }
        if (response.getEvents() != null) {
            for (T2ExtractResponse.ExtractedEvent event : response.getEvents()) {
                insertEvent(event);
            }
        }

        if (mc.getAuthorAccountId() == null && response.getResolvedAuthorAccountId() != null) {
            mc.setAuthorAccountId(UUID.fromString(response.getResolvedAuthorAccountId()));
            mediaContentMapper.updateById(mc);
        }

        writeToNeo4j(task, response, mc);

        RawRecord rawRecord = rawRecordMapper.selectById(task.getRawRecordId());
        rawRecord.setT2Output(response.getRaw());
        rawRecord.setPipelineStatus(PipelineStatus.T2_DONE.name());
        rawRecordMapper.updateById(rawRecord);

        task.setT2Status("done");
        task.setT2DoneAt(OffsetDateTime.now());
        task.setT2DurationMs((int) java.time.Duration.between(startedAt, OffsetDateTime.now()).toMillis());
        pipelineTaskMapper.updateById(task);
    }

    private void insertEntity(T2ExtractResponse.ExtractedEntity entity) {
        if (entity == null || !hasText(entity.getType()) || !hasText(entity.getCanonicalName())) {
            log.warn("Skip invalid T2 extracted entity: {}", entity);
            return;
        }

        String entityType = entity.getType().toLowerCase();
        String canonicalName = entity.getCanonicalName().trim();
        BigDecimal importanceScore = entity.getImportanceScore() != null
                ? entity.getImportanceScore() : BigDecimal.ZERO;

        switch (entityType) {
            case "person" -> personMapper.insertEntity(canonicalName, importanceScore);
            case "organization" -> organizationMapper.insertEntity(canonicalName, importanceScore);
            case "event" -> eventMapper.insertEntity(canonicalName, importanceScore);
            case "narrative" -> narrativeMapper.insertEntity(
                    canonicalName, importanceScore, buildClaimAtoms(canonicalName, importanceScore));
            default -> log.warn("Unknown extracted entity type: {}", entity.getType());
        }
    }

    private void insertEvent(T2ExtractResponse.ExtractedEvent event) {
        if (event == null || !hasText(event.getCanonicalName())) {
            return;
        }

        double confidence = event.getConfidence() != null ? event.getConfidence() : 0.5D;
        BigDecimal importanceScore = BigDecimal.valueOf(confidence * 100D);
        eventMapper.insertEntity(event.getCanonicalName().trim(), importanceScore);
    }

    private void writeToNeo4j(PipelineTask task, T2ExtractResponse response, MediaContent mc) {
        try {
            writeEntityNodes(response);
            writeRelations(response);
            linkAuthorAccount(response, mc);
            writeMediaContentToNeo4j(task, mc);
        } catch (Exception e) {
            log.error("T2后Neo4j写入失败, taskId={}", task.getId(), e);
        }
    }

    private void writeEntityNodes(T2ExtractResponse response) {
        if (response == null) {
            return;
        }
        if (response.getEntities() != null) {
            for (T2ExtractResponse.ExtractedEntity entity : response.getEntities()) {
                if (entity == null || !hasText(entity.getType()) || !hasText(entity.getCanonicalName())) {
                    continue;
                }
                try {
                    String entityType = entity.getType().toLowerCase();
                    String label = toNeo4jLabel(entityType);
                    if (!hasText(label)) {
                        continue;
                    }
                    String nodeId = stableUuid(entityType + ":" + entity.getCanonicalName());
                    Map<String, Object> props = new HashMap<>();
                    String nameKey = "narrative".equals(entityType) ? "canonicalLabel" : "canonicalName";
                    props.put(nameKey, entity.getCanonicalName());
                    if (entity.getImportanceScore() != null) {
                        props.put("importanceScore", entity.getImportanceScore().doubleValue());
                    }
                    props.put("source", "t2_extraction");
                    neo4jGraphService.mergeNode(label, nodeId, props);
                } catch (Exception e) {
                    log.warn("T2实体写Neo4j失败, type={}, name={}",
                            entity.getType(), entity.getCanonicalName(), e);
                }
            }
        }

        if (response.getEvents() != null) {
            for (T2ExtractResponse.ExtractedEvent event : response.getEvents()) {
                if (event == null || !hasText(event.getCanonicalName())) {
                    continue;
                }
                try {
                    String nodeId = stableUuid("event:" + event.getCanonicalName());
                    Map<String, Object> props = new HashMap<>();
                    props.put("canonicalName", event.getCanonicalName());
                    putIfHasText(props, "eventType", event.getEventType());
                    putIfHasText(props, "eventTimeStart", event.getEventTimeStart());
                    if (event.getConfidence() != null) {
                        props.put("confidence", event.getConfidence());
                        props.put("importanceScore", event.getConfidence() * 100D);
                    }
                    props.put("source", "t2_extraction");
                    neo4jGraphService.mergeNode("Event", nodeId, props);
                } catch (Exception e) {
                    log.warn("T2事件写Neo4j失败, name={}", event.getCanonicalName(), e);
                }
            }
        }
    }

    private void writeRelations(T2ExtractResponse response) {
        if (response == null || response.getRelationships() == null) {
            return;
        }
        for (T2ExtractResponse.ExtractedRelation rel : response.getRelationships()) {
            if (rel == null || !hasText(rel.getSourceName()) || !hasText(rel.getTargetName())
                    || !hasText(rel.getRelationType())) {
                continue;
            }
            if (!ALLOWED_RELATION_TYPES.contains(rel.getRelationType())) {
                log.warn("Skip T2 relation with unsupported relationType={}, source={}, target={}",
                        rel.getRelationType(), rel.getSourceName(), rel.getTargetName());
                continue;
            }
            try {
                String fromLabel = toNeo4jLabel(rel.getSourceType());
                String toLabel = toNeo4jLabel(rel.getTargetType());
                if (!hasText(fromLabel) || !hasText(toLabel)) {
                    continue;
                }
                String fromId = stableUuid(rel.getSourceType() + ":" + rel.getSourceName());
                String toId = stableUuid(rel.getTargetType() + ":" + rel.getTargetName());

                ensureRelationEndpoint(fromLabel, fromId, rel.getSourceType(), rel.getSourceName());
                ensureRelationEndpoint(toLabel, toId, rel.getTargetType(), rel.getTargetName());

                Map<String, Object> props = new HashMap<>();
                props.put("source", "t2_extraction");
                props.put("extraction_method", "t2_relation");
                if (rel.getConfidence() != null) {
                    props.put("confidence", rel.getConfidence());
                }
                if (hasText(rel.getRole())) {
                    props.put("role", rel.getRole());
                }

                neo4jGraphService.mergeRelation(fromLabel, fromId, toLabel, toId, rel.getRelationType(), props);
            } catch (Exception e) {
                log.warn("T2关系写Neo4j失败, relationType={}, source={}, target={}",
                        rel.getRelationType(), rel.getSourceName(), rel.getTargetName(), e);
            }
        }
    }

    private void ensureRelationEndpoint(String label, String nodeId, String type, String name) {
        Map<String, Object> props = new HashMap<>();
        String nameKey = "narrative".equals(type) ? "canonicalLabel" : "canonicalName";
        props.put(nameKey, name);
        props.put("source", "t2_extraction");
        neo4jGraphService.mergeNode(label, nodeId, props);
    }

    private void linkAuthorAccount(T2ExtractResponse response, MediaContent content) {
        if (content == null || content.getAuthorAccountId() == null || response == null || response.getEntities() == null) {
            return;
        }

        String personName = firstEntityName(response, "person");
        if (!hasText(personName)) {
            return;
        }
        String personId = stableUuid("person:" + personName);
        String narrativeName = firstEntityName(response, "narrative");
        String narrativeId = hasText(narrativeName) ? stableUuid("narrative:" + narrativeName) : null;

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
                "Person", personId,
                "SocialAccount", account.getId().toString(),
                "HAS_ACCOUNT",
                Map.of("confidence", 0.95D, "source", "t2_author_link",
                        "extraction_method", "author_field_lookup"));

        if (hasText(narrativeId)) {
            neo4jGraphService.mergeRelation(
                    "SocialAccount", account.getId().toString(),
                    "Narrative", narrativeId,
                    "AMPLIFIES",
                    Map.of("frequency", 1, "confidence", 0.80D, "source", "t2_author_link",
                            "extraction_method", "author_field_lookup"));
        }
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
                        "SocialAccount", content.getAuthorAccountId().toString(),
                        "MediaContent", content.getId().toString(),
                        "AUTHORED",
                        Map.of("source", "backend_structural",
                                "extraction_method", "author_field_lookup"));
            }

            boolean allSynced = true;
            allSynced &= writePropagationRelation(content, content.getParentContentId(), "REPLY_TO");
            allSynced &= writePropagationRelation(content, content.getRepostOfContentId(), "REPOSTS");
            allSynced &= writePropagationRelation(content, content.getQuotedContentId(), "QUOTES");
            if (!sameContentId(content.getRootContentId(), content.getPlatformContentId())) {
                allSynced &= writePropagationRelation(content, content.getRootContentId(), "REPLY_TO");
            }
            if (allSynced) {
                mediaContentMapper.markPropagationSyncedToNeo4j(content.getId());
            }

            writeHashtagNarratives(content);
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
                if (asset == null) {
                    log.debug("MediaAsset not yet available, will be written later, assetId={}", assetId);
                    continue;
                }
                if ("thumbnail".equals(asset.getAssetType())) {
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
            log.debug("Skip {} relation because platform is empty, contentId={}, targetPlatformContentId={}",
                    relationType, content.getId(), targetPlatformContentId);
            return false;
        }

        try {
            MediaContent target = mediaContentMapper.selectByPlatformAndContentId(
                    content.getPlatform(), targetPlatformContentId.trim());
            if (target == null) {
                log.debug("Skip {} relation because target content was not found, contentId={}, platform={}, "
                                + "targetPlatformContentId={}",
                        relationType, content.getId(), content.getPlatform(), targetPlatformContentId);
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

    private void writeHashtagNarratives(MediaContent content) {
        String[] hashtags = content.getHashtags();
        if (hashtags == null || hashtags.length == 0) {
            return;
        }

        for (String rawHashtag : hashtags) {
            if (!hasText(rawHashtag)) {
                continue;
            }
            String hashtag = rawHashtag.trim();
            try {
                String narrativeId = stableUuid("hashtag:" + hashtag);
                neo4jGraphService.mergeNode("Narrative", narrativeId,
                        Map.of("canonicalLabel", hashtag,
                                "source", "backend_structural",
                                "frameType", "hashtag"));
                neo4jGraphService.mergeRelation(
                        "MediaContent", content.getId().toString(),
                        "Narrative", narrativeId,
                        "AMPLIFIES",
                        Map.of("source", "backend_structural",
                                "extraction_method", "hashtag_field"));
            } catch (Exception e) {
                log.warn("Failed to write hashtag narrative to Neo4j, contentId={}, hashtag={}",
                        content.getId(), hashtag, e);
            }
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

    private String firstEntityName(T2ExtractResponse response, String type) {
        for (T2ExtractResponse.ExtractedEntity entity : response.getEntities()) {
            if (entity != null && type.equals(entity.getType()) && hasText(entity.getCanonicalName())) {
                return entity.getCanonicalName();
            }
        }
        return null;
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

package com.idata.profile.pipeline.step;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t2.T2ExtractRequest;
import com.idata.profile.agentproxy.dto.t2.T2ExtractResponse;
import com.idata.profile.common.constant.AllowedRelationTypes;
import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.common.util.T1AnnotationView;
import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.account.SocialAccountMapper;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import com.idata.profile.service.EntityResolutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class T2ExtractionStep {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentProxyClient agentProxyClient;
    private final MediaContentMapper mediaContentMapper;
    private final RawRecordMapper rawRecordMapper;
    private final PipelineTaskMapper pipelineTaskMapper;
    private final Neo4jGraphService neo4jGraphService;
    private final SocialAccountMapper socialAccountMapper;
    private final EntityResolutionService entityResolutionService;

    public void run(PipelineTask task) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        task.setT2Status("running");
        task.setT2StartedAt(startedAt);
        pipelineTaskMapper.updateById(task);

        MediaContent mc = mediaContentMapper.selectById(task.getContentId());
        T2ExtractResponse response = agentProxyClient.call(
                "T2", "extract_entities", buildRequest(mc), T2ExtractResponse.class);

        EntityResolutionService.ResolutionResult resolutionResult = entityResolutionService.resolveMentions(
                response != null ? response.getEntities() : null,
                mc.getId().toString(),
                mc.getPlatform(),
                mc.getLanguage());
        Map<String, EntityResolutionService.ResolvedMention> resolvedMentions =
                resolutionResult.getResolvedMentions();

        if (mc.getAuthorAccountId() == null && hasText(response.getResolvedAuthorAccountId())) {
            mc.setAuthorAccountId(UUID.fromString(response.getResolvedAuthorAccountId()));
            mediaContentMapper.updateById(mc);
        }

        writeToNeo4j(task, response, mc, resolvedMentions);

        RawRecord rawRecord = rawRecordMapper.selectById(task.getRawRecordId());
        rawRecord.setT2Output(toJson(response));
        rawRecord.setT3Output(resolutionResult.getT3RawResponse());
        rawRecord.setPipelineStatus(PipelineStatus.T2_DONE.name());
        rawRecordMapper.updateById(rawRecord);

        task.setT2Status("done");
        task.setT2DoneAt(OffsetDateTime.now());
        task.setT2DurationMs((int) java.time.Duration.between(startedAt, OffsetDateTime.now()).toMillis());
        pipelineTaskMapper.updateById(task);
    }

    private T2ExtractRequest buildRequest(MediaContent mc) {
        T2ExtractRequest request = new T2ExtractRequest();
        request.setTitle(mc.getTitle());
        request.setText(mc.getBodyText());
        request.setLanguage(mc.getLanguage());
        Object t1EntitiesHint = extractT1EntitiesHint(mc);
        if (t1EntitiesHint != null) {
            request.setAnnotation(t1EntitiesHint);
        }

        T2ExtractRequest.Context context = new T2ExtractRequest.Context();
        context.setContentId(mc.getId().toString());
        context.setPlatform(mc.getPlatform());
        context.setUrl(mc.getUrl());
        context.setPublishedAt(mc.getPublishedAt() != null ? mc.getPublishedAt().toString() : null);
        context.setAuthorHandle(mc.getAuthorPlatformUserId());
        context.setHashtags(mc.getHashtags());
        context.setMentions(mc.getMentions());
        context.setParentContentId(mc.getParentContentId());
        context.setRepostOfContentId(mc.getRepostOfContentId());
        context.setQuotedContentId(mc.getQuotedContentId());
        request.setContext(context);
        return request;
    }

    private Object extractT1EntitiesHint(MediaContent mc) {
        String entitiesHintJson = T1AnnotationView.parse(mc.getT1Annotation()).entitiesHintJson();
        if (entitiesHintJson == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(entitiesHintJson, Object.class);
        } catch (Exception e) {
            log.warn("Failed to parse T1 entities hint for T2, contentId={}", mc.getId(), e);
            return null;
        }
    }

    private void writeToNeo4j(PipelineTask task, T2ExtractResponse response, MediaContent mc,
                              Map<String, EntityResolutionService.ResolvedMention> resolvedMentions) {
        try {
            writeRelations(response, resolvedMentions);
            writeMediaContentToNeo4j(task, mc);
            writeContentEntityRelations(mc, resolvedMentions);
        } catch (Exception e) {
            log.error("T2 Neo4j write failed, taskId={}", task.getId(), e);
        }
    }

    private void writeRelations(T2ExtractResponse response,
                                Map<String, EntityResolutionService.ResolvedMention> resolvedMentions) {
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
            EntityResolutionService.ResolvedMention from = resolvedMentions.get(rel.getSubjectMentionId());
            EntityResolutionService.ResolvedMention to = resolvedMentions.get(rel.getObjectMentionId());
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

    private void writeContentEntityRelations(MediaContent mc,
                                             Map<String, EntityResolutionService.ResolvedMention> resolvedMentions) {
        if (mc == null || resolvedMentions == null || resolvedMentions.isEmpty()) {
            return;
        }
        String contentNodeId = mc.getId().toString();
        for (EntityResolutionService.ResolvedMention resolved : resolvedMentions.values()) {
            if (resolved == null || !hasText(resolved.getNodeId()) || !hasText(resolved.getLabel())) {
                continue;
            }
            String predicate = "event".equals(resolved.getEntityType()) ? "DESCRIBES" : "MENTIONS";
            try {
                Map<String, Object> props = new HashMap<>();
                props.put("source", "t2_content_entity_link");
                boolean existed = neo4jGraphService.relationExists(contentNodeId, resolved.getNodeId(), predicate);
                props.put("evidenceUpsert", existed);
                neo4jGraphService.mergeRelation(
                        "MediaContent", contentNodeId,
                        resolved.getLabel(), resolved.getNodeId(),
                        predicate, props);
            } catch (Exception e) {
                log.warn("T2 content-entity relation write failed, contentId={}, entityId={}, predicate={}",
                        contentNodeId, resolved.getNodeId(), predicate, e);
            }
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

        } catch (Exception e) {
            log.warn("Structural MediaContent Neo4j write failed, taskId={}, contentId={}",
                    task.getId(), task.getContentId(), e);
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
        putIfHasText(contentProps, "title", content.getTitle());
        putIfHasText(contentProps, "platform", content.getPlatform());
        putIfHasText(contentProps, "contentType", content.getContentType());
        putIfHasText(contentProps, "language", content.getLanguage());
        putIfHasText(contentProps, "platformContentId", content.getPlatformContentId());
        putIfNotNull(contentProps, "publishedAt",
                content.getPublishedAt() != null ? content.getPublishedAt().toString() : null);
        putIfHasText(contentProps, "url", content.getUrl());
        putIfHasText(contentProps, "t1Annotation", content.getT1Annotation());
        putIfNotNull(contentProps, "likeCount", content.getLikeCount());
        putIfNotNull(contentProps, "commentCount", content.getCommentCount());
        putIfNotNull(contentProps, "shareCount", content.getShareCount());
        putIfNotNull(contentProps, "repostCount", content.getRepostCount());
        putIfNotNull(contentProps, "quoteCount", content.getQuoteCount());
        putIfNotNull(contentProps, "viewCount", content.getViewCount());
        putIfNotNull(contentProps, "reactionCount", content.getReactionCount());
        if (content.getMediaAssetIds() != null && content.getMediaAssetIds().length > 0) {
            String[] mediaAssetIdStrings = new String[content.getMediaAssetIds().length];
            for (int i = 0; i < content.getMediaAssetIds().length; i++) {
                mediaAssetIdStrings[i] = content.getMediaAssetIds()[i].toString();
            }
            contentProps.put("mediaAssetIds", mediaAssetIdStrings);
        }
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String toJson(T2ExtractResponse response) {
        if (response == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(response);
        } catch (Exception e) {
            log.warn("Failed to serialize T2 response");
            return null;
        }
    }
}

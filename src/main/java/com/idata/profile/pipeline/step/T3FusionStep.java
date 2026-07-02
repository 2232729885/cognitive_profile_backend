package com.idata.profile.pipeline.step;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t2.T2ExtractResponse;
import com.idata.profile.agentproxy.dto.t3.T3FuseRequest;
import com.idata.profile.agentproxy.dto.t3.T3FuseResponse;
import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.account.SocialAccountMapper;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.mapper.graph.EventMapper;
import com.idata.profile.mapper.graph.NarrativeMapper;
import com.idata.profile.mapper.graph.OrganizationMapper;
import com.idata.profile.mapper.graph.PersonMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class T3FusionStep {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_RELATION_NODE_LABEL = "Person";

    private final AgentProxyClient agentProxyClient;
    private final Neo4jGraphService neo4jGraphService;
    private final RawRecordMapper rawRecordMapper;
    private final PipelineTaskMapper pipelineTaskMapper;
    private final MediaContentMapper mediaContentMapper;
    private final SocialAccountMapper socialAccountMapper;
    private final PersonMapper personMapper;
    private final OrganizationMapper organizationMapper;
    private final EventMapper eventMapper;
    private final NarrativeMapper narrativeMapper;

    public void run(PipelineTask task) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        task.setT3Status("running");
        task.setT3StartedAt(startedAt);
        pipelineTaskMapper.updateById(task);

        RawRecord rawRecord = rawRecordMapper.selectById(task.getRawRecordId());
        T3FuseRequest request = buildFuseRequest(rawRecord);
        T3FuseResponse response = agentProxyClient.call("T3", "fuse_entities", request, T3FuseResponse.class);

        Map<String, String> labelsByNodeId = buildLabelsByNodeId(response.getNodes());
        mergeNodes(response.getNodes());
        mergeRelations(response.getRelations(), labelsByNodeId);
        linkAuthorAccount(task, response);
        appendMergeHistory(response.getEntityMerges());

        rawRecord.setT3Output(response.getRaw());
        if (!PipelineStatus.T4_INDEXED.name().equals(rawRecord.getPipelineStatus())) {
            rawRecord.setPipelineStatus(PipelineStatus.T3_DONE.name());
        }
        rawRecordMapper.updateById(rawRecord);

        task.setT3Status("done");
        task.setT3DoneAt(OffsetDateTime.now());
        task.setT3DurationMs((int) java.time.Duration.between(startedAt, OffsetDateTime.now()).toMillis());
        pipelineTaskMapper.updateById(task);
    }

    private T3FuseRequest buildFuseRequest(RawRecord rawRecord) {
        T3FuseRequest request = new T3FuseRequest();
        if (rawRecord == null || !hasText(rawRecord.getT2Output())) {
            request.setEntities(Collections.emptyList());
            return request;
        }

        try {
            T2ExtractResponse t2Output = OBJECT_MAPPER.readValue(rawRecord.getT2Output(), T2ExtractResponse.class);
            List<T3FuseRequest.T2EntityRef> refs = new ArrayList<>();
            if (t2Output.getEntities() != null) {
                for (T2ExtractResponse.ExtractedEntity entity : t2Output.getEntities()) {
                    T3FuseRequest.T2EntityRef ref = new T3FuseRequest.T2EntityRef();
                    ref.setType(entity.getType());
                    ref.setCanonicalName(entity.getCanonicalName());
                    UUID entityId = resolveEntityId(entity);
                    if (entityId != null) {
                        ref.setTempId(entityId.toString());
                    }
                    refs.add(ref);
                }
            }
            request.setEntities(refs);
        } catch (JacksonException e) {
            log.warn("Failed to parse T2 output for T3 request, rawRecordId={}", rawRecord.getId(), e);
            request.setEntities(Collections.emptyList());
        }
        return request;
    }

    private UUID resolveEntityId(T2ExtractResponse.ExtractedEntity entity) {
        if (entity == null || !hasText(entity.getType()) || !hasText(entity.getCanonicalName())) {
            return null;
        }
        String canonicalName = entity.getCanonicalName().trim();
        return switch (entity.getType()) {
            case "person" -> personMapper.selectIdByCanonicalName(canonicalName);
            case "organization" -> organizationMapper.selectIdByCanonicalName(canonicalName);
            case "event" -> eventMapper.selectIdByCanonicalName(canonicalName);
            case "narrative" -> narrativeMapper.selectIdByCanonicalLabel(canonicalName);
            default -> null;
        };
    }

    private void mergeNodes(List<T3FuseResponse.Neo4jNode> nodes) {
        if (nodes == null) {
            return;
        }

        for (T3FuseResponse.Neo4jNode node : nodes) {
            if (!hasText(node.getLabel()) || !hasText(node.getId())) {
                log.warn("Skip invalid T3 Neo4j node: {}", node);
                continue;
            }
            neo4jGraphService.mergeNode(node.getLabel(), node.getId(), toProperties(node.getProperties()));
        }
    }

    private void mergeRelations(List<T3FuseResponse.Neo4jRelation> relations,
                                Map<String, String> labelsByNodeId) {
        if (relations == null) {
            return;
        }

        for (T3FuseResponse.Neo4jRelation relation : relations) {
            if (!hasText(relation.getFromId())
                    || !hasText(relation.getToId())
                    || !hasText(relation.getRelationType())) {
                log.warn("Skip invalid T3 Neo4j relation: {}", relation);
                continue;
            }

            String fromLabel = resolveNodeLabel(relation.getFromId(), labelsByNodeId);
            String toLabel = resolveNodeLabel(relation.getToId(), labelsByNodeId);
            neo4jGraphService.mergeRelation(
                    fromLabel,
                    relation.getFromId(),
                    toLabel,
                    relation.getToId(),
                    relation.getRelationType(),
                    toProperties(relation.getProperties()));
        }
    }

    private void appendMergeHistory(List<T3FuseResponse.EntityMerge> entityMerges) {
        if (entityMerges == null) {
            return;
        }

        for (T3FuseResponse.EntityMerge merge : entityMerges) {
            UUID survivorId = parseUuid(merge.getSurvivorId());
            UUID[] mergedIds = parseUuidArray(merge.getMergedIds());
            if (survivorId == null || mergedIds.length == 0) {
                log.warn("Skip invalid T3 entity merge: {}", merge);
                continue;
            }

            if (personMapper.existsById(survivorId)) {
                personMapper.appendMergeHistory(survivorId, mergedIds);
            } else if (organizationMapper.existsById(survivorId)) {
                organizationMapper.appendMergeHistory(survivorId, mergedIds);
            } else if (eventMapper.existsById(survivorId)) {
                eventMapper.appendMergeHistory(survivorId, mergedIds);
            } else if (narrativeMapper.existsById(survivorId)) {
                narrativeMapper.appendMergeHistory(survivorId, mergedIds);
            } else {
                log.warn("Skip T3 entity merge because survivorId was not found in PG entity tables: {}",
                        survivorId);
            }
        }
    }

    private void linkAuthorAccount(PipelineTask task, T3FuseResponse response) {
        MediaContent content = mediaContentMapper.selectById(task.getContentId());
        if (content == null || content.getAuthorAccountId() == null) {
            return;
        }

        String personId = firstNodeId(response.getNodes(), "Person");
        if (!hasText(personId)) {
            return;
        }
        String narrativeId = firstNodeId(response.getNodes(), "Narrative");
        SocialAccount account = socialAccountMapper.selectById(content.getAuthorAccountId());
        if (account == null) {
            return;
        }

        Map<String, Object> accountProps = new HashMap<>();
        accountProps.put("platform", account.getPlatform());
        accountProps.put("platformUserId", account.getPlatformUserId());
        accountProps.put("handle", account.getHandle());
        accountProps.put("displayName", account.getDisplayName());
        accountProps.put("source", "t3_author_link");

        neo4jGraphService.mergeNode("SocialAccount", account.getId().toString(), accountProps);
        neo4jGraphService.mergeRelation(
                "SocialAccount", account.getId().toString(),
                "Person", personId,
                "OPERATED_BY",
                Map.of("confidence", 0.95D, "source", "t3_author_link"));

        if (hasText(narrativeId)) {
            neo4jGraphService.mergeRelation(
                    "SocialAccount", account.getId().toString(),
                    "Narrative", narrativeId,
                    "PARTICIPATES_IN_NARRATIVE",
                    Map.of("frequency", 1, "confidence", 0.80D, "source", "t3_author_link"));
        }
    }

    private String firstNodeId(List<T3FuseResponse.Neo4jNode> nodes, String label) {
        if (nodes == null) {
            return null;
        }
        for (T3FuseResponse.Neo4jNode node : nodes) {
            if (label.equals(node.getLabel()) && hasText(node.getId())) {
                return node.getId();
            }
        }
        return null;
    }

    private Map<String, String> buildLabelsByNodeId(List<T3FuseResponse.Neo4jNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> labels = new HashMap<>();
        for (T3FuseResponse.Neo4jNode node : nodes) {
            if (hasText(node.getId()) && hasText(node.getLabel())) {
                labels.put(node.getId(), node.getLabel());
            }
        }
        return labels;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toProperties(Object properties) {
        if (properties == null) {
            return Collections.emptyMap();
        }
        return OBJECT_MAPPER.convertValue(properties, Map.class);
    }

    private String resolveNodeLabel(String nodeId, Map<String, String> labelsByNodeId) {
        String label = labelsByNodeId.get(nodeId);
        if (hasText(label)) {
            return label;
        }
        log.warn("T3 relation endpoint node label not found for nodeId={}, fallback to {}. "
                        + "This is temporary because Neo4jRelation does not carry fromLabel/toLabel.",
                nodeId, DEFAULT_RELATION_NODE_LABEL);
        return DEFAULT_RELATION_NODE_LABEL;
    }

    private UUID[] parseUuidArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new UUID[0];
        }

        return values.stream()
                .map(this::parseUuid)
                .filter(java.util.Objects::nonNull)
                .toArray(UUID[]::new);
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

package com.idata.profile.pipeline.step;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t3.T3FuseRequest;
import com.idata.profile.agentproxy.dto.t3.T3FuseResponse;
import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.graph.EventMapper;
import com.idata.profile.mapper.graph.NarrativeMapper;
import com.idata.profile.mapper.graph.OrganizationMapper;
import com.idata.profile.mapper.graph.PersonMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
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
    private final PersonMapper personMapper;
    private final OrganizationMapper organizationMapper;
    private final EventMapper eventMapper;
    private final NarrativeMapper narrativeMapper;

    public void run(PipelineTask task) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        task.setT3Status("running");
        task.setT3StartedAt(startedAt);
        pipelineTaskMapper.updateById(task);

        T3FuseRequest request = new T3FuseRequest();
        T3FuseResponse response = agentProxyClient.call("T3", "fuse_entities", request, T3FuseResponse.class);

        Map<String, String> labelsByNodeId = buildLabelsByNodeId(response.getNodes());
        mergeNodes(response.getNodes());
        mergeRelations(response.getRelations(), labelsByNodeId);
        appendMergeHistory(response.getEntityMerges());

        RawRecord rawRecord = rawRecordMapper.selectById(task.getRawRecordId());
        rawRecord.setT3Output(response.getRaw());
        rawRecord.setPipelineStatus(PipelineStatus.T3_DONE.name());
        rawRecordMapper.updateById(rawRecord);

        task.setT3Status("done");
        task.setT3DoneAt(OffsetDateTime.now());
        task.setT3DurationMs((int) java.time.Duration.between(startedAt, OffsetDateTime.now()).toMillis());
        pipelineTaskMapper.updateById(task);
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

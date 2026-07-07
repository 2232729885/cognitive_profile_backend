package com.idata.profile.analysis.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.idata.profile.analysis.orchestrator.CoordinatorAgentService;
import com.idata.profile.analysis.workflow.WorkflowTaskService;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Component("queryGraphTool")
@RequiredArgsConstructor
public class QueryGraphTool implements Function<QueryGraphTool.Request, QueryGraphTool.Response> {

    private final Neo4jGraphService neo4jGraphService;
    private final ApplicationContext applicationContext;

    public record Request(
            @JsonProperty(required = true)
            @JsonPropertyDescription("节点ID（UUID格式）")
            String nodeId,

            @JsonProperty(required = true)
            @JsonPropertyDescription("节点类型：Person/Organization/Narrative/SocialAccount")
            String nodeLabel
    ) {
    }

    public record Response(
            int nodeCount,
            int relationCount,
            List<String> relatedEntityNames,
            String summary
    ) {
    }

    @Override
    public Response apply(Request request) {
        return applyWithTaskId(request, currentTaskId());
    }

    public Response applyWithTaskId(Request request, String taskId) {
        long startedAt = System.currentTimeMillis();
        sendStarted(taskId, "query_graph", request, startedAt);

        try {
            Map<String, Object> graph = neo4jGraphService.findTwoHopGraph(request.nodeId(), request.nodeLabel());
            List<Map<String, Object>> nodes = listOfMaps(graph.get("nodes"));
            List<Map<String, Object>> relations = listOfMaps(graph.get("relations"));
            List<String> relatedNames = relatedEntityNames(nodes);
            Response response = new Response(
                    nodes.size(),
                    relations.size(),
                    relatedNames,
                    buildSummary(nodes, relations)
            );
            sendDone(taskId, "query_graph", request, response, startedAt);
            return response;
        } catch (RuntimeException e) {
            sendFailed(taskId, "query_graph", request, e, startedAt);
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<String> relatedEntityNames(List<Map<String, Object>> nodes) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            Object propertiesValue = node.get("properties");
            if (!(propertiesValue instanceof Map<?, ?> rawProperties)) {
                continue;
            }
            Map<String, Object> properties = (Map<String, Object>) rawProperties;
            String name = firstText(properties, "canonicalName", "canonicalLabel", "name", "displayName", "handle", "username");
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names.stream().limit(20).toList();
    }

    private String buildSummary(List<Map<String, Object>> nodes, List<Map<String, Object>> relations) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String label = stringValue(node.get("label"));
            if (label != null && !label.isBlank()) {
                counts.merge(label, 1, Integer::sum);
            }
        }
        return "\u56fe\u8c31\u5171\u5305\u542b" + nodes.size() + "\u4e2a\u8282\u70b9\u3001"
                + relations.size() + "\u6761\u5173\u7cfb\uff0c\u8282\u70b9\u5206\u5e03\uff1a" + counts;
    }

    private String firstText(Map<String, Object> properties, String... keys) {
        for (String key : keys) {
            Object value = properties.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }

    private String currentTaskId() {
        return applicationContext.getBean(CoordinatorAgentService.class).currentTaskId();
    }

    private void sendStarted(String taskId, String stepName, Object input, long startedAt) {
        if (taskId == null) {
            return;
        }
        coordinator().sendSseEvent(taskId, "tool_started", Map.of("stepName", stepName, "input", input));
        workflow().appendStep(UUID.fromString(taskId),
                new WorkflowTaskService.StepRecord(stepName, "running", startedAt, null, input, null, null));
    }

    private void sendDone(String taskId, String stepName, Object input, Object output, long startedAt) {
        if (taskId == null) {
            return;
        }
        long doneAt = System.currentTimeMillis();
        coordinator().sendSseEvent(taskId, "tool_completed",
                Map.of("stepName", stepName, "output", output, "durationMs", doneAt - startedAt));
        workflow().appendStep(UUID.fromString(taskId),
                new WorkflowTaskService.StepRecord(stepName, "done", startedAt, doneAt, input, output, null));
    }

    private void sendFailed(String taskId, String stepName, Object input, Exception e, long startedAt) {
        if (taskId == null) {
            return;
        }
        long doneAt = System.currentTimeMillis();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepName", stepName);
        data.put("error", e.getMessage());
        coordinator().sendSseEvent(taskId, "tool_failed", data);
        workflow().appendStep(UUID.fromString(taskId),
                new WorkflowTaskService.StepRecord(stepName, "failed", startedAt, doneAt, input, null, e.getMessage()));
    }

    private CoordinatorAgentService coordinator() {
        return applicationContext.getBean(CoordinatorAgentService.class);
    }

    private WorkflowTaskService workflow() {
        return applicationContext.getBean(WorkflowTaskService.class);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}

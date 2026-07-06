package com.idata.profile.infra.neo4j;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class Neo4jGraphService {

    private static final int MAX_WRITE_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 150L;

    private final Neo4jClient neo4jClient;
    private final Object graphWriteLock = new Object();

    public void mergeNode(String label, String id, Map<String, Object> properties) {
        String cypher = String.format("MERGE (n:%s {id: $id}) SET n += $properties", label);
        runWrite("mergeNode " + label + "/" + id, () ->
                neo4jClient.query(cypher)
                        .bind(id).to("id")
                        .bind(properties).to("properties")
                        .run());
    }

    public void mergeRelation(String fromLabel, String fromId,
                              String toLabel, String toId,
                              String relationType, Map<String, Object> properties) {
        String cypher = String.format(
                "MATCH (a:%s {id: $fromId}), (b:%s {id: $toId}) " +
                        "MERGE (a)-[r:%s]->(b) SET r += $properties",
                fromLabel, toLabel, relationType);
        runWrite("mergeRelation " + fromId + "-" + relationType + "->" + toId, () ->
                neo4jClient.query(cypher)
                        .bind(fromId).to("fromId")
                        .bind(toId).to("toId")
                        .bind(properties).to("properties")
                        .run());
    }

    public void mergeRelationWithNodes(String fromLabel, String fromId, Map<String, Object> fromProperties,
                                       String toLabel, String toId, Map<String, Object> toProperties,
                                       String relationType, Map<String, Object> relationProperties) {
        String cypher = String.format("""
                MERGE (a:%s {id: $fromId})
                SET a += $fromProperties
                MERGE (b:%s {id: $toId})
                SET b += $toProperties
                MERGE (a)-[r:%s]->(b)
                SET r += $relationProperties
                """, fromLabel, toLabel, relationType);
        runWrite("mergeRelationWithNodes " + fromId + "-" + relationType + "->" + toId, () ->
                neo4jClient.query(cypher)
                        .bind(fromId).to("fromId")
                        .bind(fromProperties).to("fromProperties")
                        .bind(toId).to("toId")
                        .bind(toProperties).to("toProperties")
                        .bind(relationProperties).to("relationProperties")
                        .run());
    }

    /**
     * 把 sourceId 节点的所有关系重新指向 targetId 节点，然后删除 sourceId 节点。
     * 用于 EntityDeduplicationJob 做实体融合；全程使用普通 Cypher + Java 层重建关系，不依赖 APOC。
     */
    public void mergeNodes(String sourceId, String targetId, String label) {
        if (!hasText(sourceId) || !hasText(targetId) || !hasText(label) || sourceId.equals(targetId)) {
            return;
        }

        String outCypher = """
                MATCH (s {id: $sourceId})-[r]->(t)
                RETURN type(r) AS relType, properties(r) AS props,
                       t.id AS targetNodeId, labels(t)[0] AS targetLabel
                """;
        List<Map<String, Object>> outgoing = neo4jClient.query(outCypher)
                .bind(sourceId).to("sourceId")
                .fetch()
                .all()
                .stream()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        String inCypher = """
                MATCH (s)-[r]->(t {id: $sourceId})
                RETURN type(r) AS relType, properties(r) AS props,
                       s.id AS sourceNodeId, labels(s)[0] AS sourceLabel
                """;
        List<Map<String, Object>> incoming = neo4jClient.query(inCypher)
                .bind(sourceId).to("sourceId")
                .fetch()
                .all()
                .stream()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        for (Map<String, Object> row : outgoing) {
            String relType = stringValue(row.get("relType"));
            String targetNodeId = stringValue(row.get("targetNodeId"));
            String targetLabel = stringValue(row.get("targetLabel"));
            if (!hasText(relType) || !hasText(targetNodeId) || !hasText(targetLabel) || targetId.equals(targetNodeId)) {
                continue;
            }
            mergeRelation(label, targetId, targetLabel, targetNodeId, relType, relationProperties(row.get("props")));
        }

        for (Map<String, Object> row : incoming) {
            String relType = stringValue(row.get("relType"));
            String sourceNodeId = stringValue(row.get("sourceNodeId"));
            String sourceLabel = stringValue(row.get("sourceLabel"));
            if (!hasText(relType) || !hasText(sourceNodeId) || !hasText(sourceLabel) || targetId.equals(sourceNodeId)) {
                continue;
            }
            mergeRelation(sourceLabel, sourceNodeId, label, targetId, relType, relationProperties(row.get("props")));
        }

        runWrite("delete merged node " + label + "/" + sourceId, () ->
                neo4jClient.query("MATCH (n {id: $sourceId}) DETACH DELETE n")
                        .bind(sourceId).to("sourceId")
                        .run());
    }

    public List<UUID> findSocialAccountIdsByNarrative(UUID narrativeId) {
        String cypher = """
                MATCH (a:SocialAccount)-[:AMPLIFIES]->(n:Narrative {id: $narrativeId})
                RETURN a.id AS accountId
                """;
        return neo4jClient.query(cypher)
                .bind(narrativeId.toString()).to("narrativeId")
                .fetch()
                .all()
                .stream()
                .map(row -> row.get("accountId"))
                .map(this::toUuid)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /**
     * 查询节点的2跳关系图，用于前端知识图谱可视化
     * 返回格式：{"nodes": [...], "relations": [...]}
     * 每个 node：{"id": "...", "label": "...", "properties": {...}}
     * 每个 relation：{"fromId": "...", "toId": "...", "type": "...", "properties": {...}}
     */
    public Map<String, Object> findTwoHopGraph(String nodeId, String nodeLabel) {
        String cypher = """
                MATCH (n {id: $nodeId})-[r1]-(m)-[r2]-(k)
                WHERE labels(n)[0] = $nodeLabel
                RETURN n, r1, m, r2, k LIMIT 100
                """;
        List<Map<String, Object>> rows = neo4jClient.query(cypher)
                .bind(nodeId).to("nodeId")
                .bind(nodeLabel).to("nodeLabel")
                .fetch()
                .all()
                .stream()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        List<Map<String, Object>> relations = new ArrayList<>();
        Set<String> relationKeys = new LinkedHashSet<>();

        for (Map<String, Object> row : rows) {
            Node n = asNode(row.get("n"));
            Node m = asNode(row.get("m"));
            Node k = asNode(row.get("k"));
            addNode(nodes, n);
            addNode(nodes, m);
            addNode(nodes, k);

            addRelation(relations, relationKeys, asRelationship(row.get("r1")), nodes);
            addRelation(relations, relationKeys, asRelationship(row.get("r2")), nodes);
        }
        return graphResult(nodes, relations);
    }

    /**
     * 查两节点间的最短路径，用于发现隐性关联
     * 返回路径上的节点和关系列表，最大深度5跳
     * 找不到路径时返回空 Map
     */
    public Map<String, Object> findShortestPath(String fromNodeId, String toNodeId) {
        String cypher = """
                MATCH path = shortestPath(
                  (a {id: $fromId})-[*..5]-(b {id: $toId})
                )
                RETURN path
                """;
        List<Map<String, Object>> rows = neo4jClient.query(cypher)
                .bind(fromNodeId).to("fromId")
                .bind(toNodeId).to("toId")
                .fetch()
                .all()
                .stream()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (rows.isEmpty()) {
            return Collections.emptyMap();
        }

        Path path = asPath(rows.get(0).get("path"));
        if (path == null) {
            return Collections.emptyMap();
        }

        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        for (Node node : path.nodes()) {
            addNode(nodes, node);
        }

        List<Map<String, Object>> relations = new ArrayList<>();
        Set<String> relationKeys = new LinkedHashSet<>();
        for (Relationship relationship : path.relationships()) {
            addRelation(relations, relationKeys, relationship, nodes);
        }
        return graphResult(nodes, relations);
    }

    /**
     * 按实体名称模糊搜索 Neo4j 节点，支持 Person/Organization/Event/Narrative 四种标签
     * 返回匹配的节点列表，每个元素含 id、label、canonicalName、importanceScore
     */
    public List<Map<String, Object>> searchNodesByName(String keyword, String label, int limit) {
        if (!hasText(keyword) || limit <= 0) {
            return List.of();
        }

        List<String> labels = resolveSearchLabels(label);
        if (labels.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (String currentLabel : labels) {
            result.addAll(searchNodesBySingleLabel(keyword, currentLabel, limit));
        }
        result.sort(Comparator.comparingDouble(this::importanceScore).reversed());
        return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
    }

    public Map<String, Object> getGraphStats() {
        List<Map<String, Object>> nodeRows = neo4jClient.query(
                        "MATCH (n) RETURN labels(n)[0] AS label, count(n) AS cnt")
                .fetch()
                .all()
                .stream()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<Map<String, Object>> relationRows = neo4jClient.query(
                        "MATCH ()-[r]->() RETURN type(r) AS relType, count(r) AS cnt")
                .fetch()
                .all()
                .stream()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        Map<String, Object> nodesByLabel = new LinkedHashMap<>();
        long nodeCount = 0L;
        for (Map<String, Object> row : nodeRows) {
            String label = stringValue(row.get("label"));
            long count = longValue(row.get("cnt"));
            nodesByLabel.put(label, count);
            nodeCount += count;
        }

        Map<String, Object> relationsByType = new LinkedHashMap<>();
        long relationCount = 0L;
        for (Map<String, Object> row : relationRows) {
            String relType = stringValue(row.get("relType"));
            long count = longValue(row.get("cnt"));
            relationsByType.put(relType, count);
            relationCount += count;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeCount", nodeCount);
        result.put("relationCount", relationCount);
        result.put("nodesByLabel", nodesByLabel);
        result.put("relationsByType", relationsByType);
        return result;
    }

    private void runWrite(String operation, Runnable write) {
        synchronized (graphWriteLock) {
            RuntimeException lastError = null;
            for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
                try {
                    write.run();
                    return;
                } catch (RuntimeException e) {
                    lastError = e;
                    if (!isTransientNeo4jError(e) || attempt == MAX_WRITE_ATTEMPTS) {
                        throw e;
                    }
                    long delay = RETRY_DELAY_MS * attempt;
                    log.warn("Transient Neo4j write error, operation={}, attempt={}/{}, retryDelayMs={}",
                            operation, attempt, MAX_WRITE_ATTEMPTS, delay, e);
                    sleep(delay);
                }
            }
            throw lastError;
        }
    }

    private List<Map<String, Object>> searchNodesBySingleLabel(String keyword, String label, int limit) {
        String nameExpression = nameExpression(label);
        String cypher = String.format("""
                MATCH (n:%s)
                WITH n, %s AS name
                WHERE toLower(coalesce(toString(name), '')) CONTAINS toLower($keyword)
                RETURN n.id AS id, '%s' AS label, name,
                       coalesce(n.importanceScore, 0) AS importanceScore
                ORDER BY importanceScore DESC LIMIT $limit
                """, label, nameExpression, label);
        return neo4jClient.query(cypher)
                .bind(keyword).to("keyword")
                .bind(limit).to("limit")
                .fetch()
                .all()
                .stream()
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", stringValue(row.get("id")));
                    item.put("label", stringValue(row.get("label")));
                    item.put("canonicalName", row.get("name"));
                    item.put("importanceScore", row.get("importanceScore"));
                    return item;
                })
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private List<String> resolveSearchLabels(String label) {
        List<String> allowed = List.of(
                "Person", "Organization", "Event", "Location",
                "Narrative", "SocialAccount", "MediaContent", "MediaAsset");
        if (!hasText(label)) {
            return allowed;
        }
        return allowed.contains(label) ? List.of(label) : List.of();
    }

    private String nameExpression(String label) {
        return switch (label) {
            case "Narrative" -> "n.canonicalLabel";
            case "SocialAccount" -> "coalesce(n.displayName, n.handle)";
            case "MediaContent" -> "n.platformContentId";
            case "MediaAsset" -> "coalesce(n.sourceUrl, n.minioKey, n.id)";
            default -> "n.canonicalName";
        };
    }

    private Map<String, Object> graphResult(Map<String, Map<String, Object>> nodes,
                                            List<Map<String, Object>> relations) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", new ArrayList<>(nodes.values()));
        result.put("relations", relations);
        return result;
    }

    private void addNode(Map<String, Map<String, Object>> nodes, Node node) {
        if (node == null) {
            return;
        }
        Map<String, Object> properties = new LinkedHashMap<>(node.asMap());
        String id = stringValue(properties.get("id"));
        if (!hasText(id)) {
            id = node.elementId();
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("label", firstLabel(node));
        item.put("properties", properties);
        nodes.put(node.elementId(), item);
    }

    private void addRelation(List<Map<String, Object>> relations, Set<String> relationKeys,
                             Relationship relationship, Map<String, Map<String, Object>> nodes) {
        if (relationship == null || !relationKeys.add(relationship.elementId())) {
            return;
        }
        Map<String, Object> fromNode = nodes.get(relationship.startNodeElementId());
        Map<String, Object> toNode = nodes.get(relationship.endNodeElementId());
        if (fromNode == null || toNode == null) {
            return;
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("fromId", fromNode.get("id"));
        item.put("toId", toNode.get("id"));
        item.put("type", relationship.type());
        item.put("properties", new LinkedHashMap<>(relationship.asMap()));
        relations.add(item);
    }

    private String firstLabel(Node node) {
        if (node == null || node.labels() == null) {
            return "";
        }
        for (String label : node.labels()) {
            return label;
        }
        return "";
    }

    private Node asNode(Object value) {
        return value instanceof Node node ? node : null;
    }

    private Relationship asRelationship(Object value) {
        return value instanceof Relationship relationship ? relationship : null;
    }

    private Path asPath(Object value) {
        return value instanceof Path path ? path : null;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private double importanceScore(Map<String, Object> item) {
        Object value = item.get("importanceScore");
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0D;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0D;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> relationProperties(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(entry.getKey().toString(), entry.getValue());
                }
            }
            return result;
        }
        return Collections.emptyMap();
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isTransientNeo4jError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.contains("Transient")
                    || contains(message, "Neo.TransientError")
                    || contains(message, "DeadlockDetected")
                    || contains(message, "ForsetiClient")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean contains(String value, String token) {
        return value != null && value.contains(token);
    }

    private void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying Neo4j write", e);
        }
    }

    private UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid UUID returned from Neo4j: {}", value);
            return null;
        }
    }
}

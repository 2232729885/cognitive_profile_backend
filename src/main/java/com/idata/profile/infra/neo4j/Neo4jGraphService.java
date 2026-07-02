package com.idata.profile.infra.neo4j;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    public List<UUID> findSocialAccountIdsByNarrative(UUID narrativeId) {
        String cypher = """
                MATCH (a:SocialAccount)-[:PARTICIPATES_IN_NARRATIVE]->(n:Narrative {id: $narrativeId})
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

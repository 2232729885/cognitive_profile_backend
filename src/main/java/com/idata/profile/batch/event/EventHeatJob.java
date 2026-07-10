package com.idata.profile.batch.event;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateEventHeatRequest;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateEventHeatResponse;
import com.idata.profile.entity.graph.Event;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.graph.EventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventHeatJob {

    private static final int BATCH_LIMIT = 50;
    private static final int MAX_SAMPLED_CONTENT = 20;

    private final EventMapper eventMapper;
    private final Neo4jGraphService neo4jGraphService;
    private final AgentProxyClient agentProxyClient;

    @Scheduled(fixedDelay = 30 * 60 * 1000)
    public void run() {
        List<Event> events = eventMapper.selectReadyForHeatComputation(BATCH_LIMIT);
        if (events.isEmpty()) {
            return;
        }
        log.info("[EventHeatJob] computing event heat, count={}", events.size());
        int success = 0;
        for (Event event : events) {
            try {
                processEvent(event);
                success++;
            } catch (Exception e) {
                log.warn("[EventHeatJob] failed, eventId={}", event.getId(), e);
            }
        }
        log.info("[EventHeatJob] completed, success={}/{}", success, events.size());
    }

    @SuppressWarnings("unchecked")
    private void processEvent(Event event) {
        Map<String, Object> graph = neo4jGraphService.findHopGraph(event.getId().toString(), "Event", 1);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.getOrDefault("nodes", List.of());

        List<Map<String, Object>> contentNodes = new ArrayList<>();
        List<T1AnnotateEventHeatRequest.RelatedEntity> nonContentEntities = new ArrayList<>();

        for (Map<String, Object> node : nodes) {
            String label = stringValue(node.get("label"));
            String id = stringValue(node.get("id"));
            if (event.getId().toString().equals(id)) {
                continue;
            }
            if ("MediaContent".equals(label)) {
                contentNodes.add(node);
            } else {
                Map<String, Object> props = properties(node);
                T1AnnotateEventHeatRequest.RelatedEntity entity =
                        new T1AnnotateEventHeatRequest.RelatedEntity();
                entity.setEntityId(id);
                entity.setEntityType(entityType(label));
                entity.setName(displayName(props));
                nonContentEntities.add(entity);
            }
        }

        long totalEngagement = contentNodes.stream().mapToLong(this::totalEngagement).sum();

        List<Map<String, Object>> sampledContent = contentNodes.stream()
                .sorted(Comparator.comparingLong(this::totalEngagement).reversed())
                .limit(MAX_SAMPLED_CONTENT)
                .toList();

        List<T1AnnotateEventHeatRequest.RelatedEntity> entities = new ArrayList<>(nonContentEntities);
        for (Map<String, Object> node : sampledContent) {
            Map<String, Object> props = properties(node);
            T1AnnotateEventHeatRequest.RelatedEntity entity = new T1AnnotateEventHeatRequest.RelatedEntity();
            entity.setEntityId(stringValue(node.get("id")));
            entity.setEntityType("media_content");
            entity.setPublishedAt(stringValue(props.get("publishedAt")));
            entity.setPlatform(stringValue(props.get("platform")));
            entity.setLikeCount(longObject(props.get("likeCount")));
            entity.setCommentCount(longObject(props.get("commentCount")));
            entity.setShareCount(longObject(props.get("shareCount")));
            entity.setRepostCount(longObject(props.get("repostCount")));
            entity.setViewCount(longObject(props.get("viewCount")));
            entities.add(entity);
        }

        T1AnnotateEventHeatRequest request = new T1AnnotateEventHeatRequest();
        request.setEvent(buildEventInfo(event));
        request.setRelatedEntities(entities);
        request.setAggregateStats(buildAggregateStats(contentNodes, totalEngagement));

        T1AnnotateEventHeatResponse response = agentProxyClient.call(
                "T1", "annotate_event_heat", request, T1AnnotateEventHeatResponse.class);

        if (response != null && response.getEventHeat() != null) {
            T1AnnotateEventHeatResponse.EventHeat heat = response.getEventHeat();
            eventMapper.updateEventHeat(
                    event.getId(),
                    heat.getHeatLevel(),
                    heat.getHeatScore() != null ? BigDecimal.valueOf(heat.getHeatScore()) : null,
                    heat.getConfidence() != null ? BigDecimal.valueOf(heat.getConfidence()) : null,
                    contentNodes.size());

            neo4jGraphService.mergeNode("Event", event.getId().toString(), Map.of(
                    "eventHeatLevel", heat.getHeatLevel() != null ? heat.getHeatLevel() : "unclear",
                    "eventHeatScore", heat.getHeatScore() != null ? heat.getHeatScore() : 0.0));
        } else {
            eventMapper.updateEventHeat(event.getId(), "unclear", null, null, contentNodes.size());
        }
    }

    private T1AnnotateEventHeatRequest.EventInfo buildEventInfo(Event event) {
        T1AnnotateEventHeatRequest.EventInfo eventInfo = new T1AnnotateEventHeatRequest.EventInfo();
        eventInfo.setEventId(event.getId().toString());
        eventInfo.setCanonicalName(event.getCanonicalName());
        eventInfo.setEventType(event.getEventType());
        eventInfo.setOccurredAtStart(
                event.getOccurredAtStart() != null ? event.getOccurredAtStart().toString() : null);
        eventInfo.setOccurredAtEnd(
                event.getOccurredAtEnd() != null ? event.getOccurredAtEnd().toString() : null);
        eventInfo.setCountry(event.getCountry());
        return eventInfo;
    }

    private T1AnnotateEventHeatRequest.AggregateStats buildAggregateStats(
            List<Map<String, Object>> contentNodes, long totalEngagement) {
        T1AnnotateEventHeatRequest.AggregateStats stats =
                new T1AnnotateEventHeatRequest.AggregateStats();
        stats.setTotalRelatedContentCount(contentNodes.size());
        stats.setTotalEngagement(totalEngagement);
        stats.setDistinctPlatformCount((int) contentNodes.stream()
                .map(node -> stringValue(properties(node).get("platform")))
                .filter(Objects::nonNull)
                .distinct()
                .count());
        contentNodes.stream()
                .map(node -> stringValue(properties(node).get("publishedAt")))
                .filter(Objects::nonNull)
                .min(String::compareTo)
                .ifPresent(stats::setEarliestContentAt);
        contentNodes.stream()
                .map(node -> stringValue(properties(node).get("publishedAt")))
                .filter(Objects::nonNull)
                .max(String::compareTo)
                .ifPresent(stats::setLatestContentAt);
        return stats;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(Map<String, Object> node) {
        Object props = node.get("properties");
        return props instanceof Map<?, ?> ? (Map<String, Object>) props : Map.of();
    }

    private long totalEngagement(Map<String, Object> node) {
        Map<String, Object> props = properties(node);
        return longValue(props.get("likeCount")) + longValue(props.get("commentCount"))
                + longValue(props.get("shareCount")) + longValue(props.get("repostCount"));
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

    private Long longObject(Object value) {
        return value == null ? null : longValue(value);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String displayName(Map<String, Object> props) {
        Object name = props.get("canonicalName");
        if (name == null) {
            name = props.get("canonicalLabel");
        }
        if (name == null) {
            name = props.get("displayName");
        }
        if (name == null) {
            name = props.get("handle");
        }
        return stringValue(name);
    }

    private String entityType(String label) {
        if (label == null) {
            return "unknown";
        }
        return switch (label) {
            case "Person" -> "person";
            case "Organization" -> "organization";
            case "Location" -> "location";
            case "SocialAccount" -> "social_account";
            case "MediaContent" -> "media_content";
            default -> label.toLowerCase();
        };
    }
}

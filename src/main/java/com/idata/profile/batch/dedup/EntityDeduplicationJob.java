package com.idata.profile.batch.dedup;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.idata.profile.entity.dedup.EntityFusionRecord;
import com.idata.profile.entity.graph.Event;
import com.idata.profile.entity.graph.Narrative;
import com.idata.profile.entity.graph.Organization;
import com.idata.profile.entity.graph.Person;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.dedup.EntityFusionRecordMapper;
import com.idata.profile.mapper.graph.EventMapper;
import com.idata.profile.mapper.graph.NarrativeMapper;
import com.idata.profile.mapper.graph.OrganizationMapper;
import com.idata.profile.mapper.graph.PersonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class EntityDeduplicationJob {

    private static final int BATCH_LIMIT = 200;

    private final PersonMapper personMapper;
    private final OrganizationMapper organizationMapper;
    private final EventMapper eventMapper;
    private final NarrativeMapper narrativeMapper;
    private final EntityFusionRecordMapper entityFusionRecordMapper;
    private final Neo4jGraphService neo4jGraphService;
    private final ApplicationContext applicationContext;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelay = 60 * 60 * 1000)
//    @Scheduled(fixedDelay = 2 * 60 * 1000)
    public void run() {
        UUID jobRunId = UUID.randomUUID();
        if (!running.compareAndSet(false, true)) {
            log.info("[EntityDeduplicationJob] 上一次融合仍在运行，跳过本轮定时任务, jobRunId={}", jobRunId);
            return;
        }
        try {
            runInternal(jobRunId);
        } finally {
            running.set(false);
        }
    }

    public boolean tryStartAsync(UUID jobRunId) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        applicationContext.getBean(EntityDeduplicationJob.class).runAsyncAcquired(jobRunId);
        return true;
    }

    @Async("pipelineThreadPool")
    public void runAsyncAcquired(UUID jobRunId) {
        try {
            runInternal(jobRunId);
        } finally {
            running.set(false);
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void runInternal(UUID jobRunId) {
        log.info("[EntityDeduplicationJob] 开始融合, jobRunId={}", jobRunId);

        int totalMerged = 0;
        totalMerged += deduplicateEntities("person", jobRunId);
        totalMerged += deduplicateEntities("organization", jobRunId);
        totalMerged += deduplicateEntities("event", jobRunId);
        totalMerged += deduplicateEntities("narrative", jobRunId);

        log.info("[EntityDeduplicationJob] 融合完成, jobRunId={}, totalMerged={}", jobRunId, totalMerged);
        logPendingStats();
    }

    private int deduplicateEntities(String entityType, UUID jobRunId) {
        return switch (entityType) {
            case "person" -> deduplicatePersons(jobRunId);
            case "organization" -> deduplicateOrganizations(jobRunId);
            case "event" -> deduplicateEvents(jobRunId);
            case "narrative" -> deduplicateNarratives(jobRunId);
            default -> 0;
        };
    }

    private int deduplicatePersons(UUID jobRunId) {
        int merged = 0;
        for (String name : personMapper.selectDuplicateCanonicalNames(BATCH_LIMIT)) {
            try {
                List<Person> pending = personMapper.selectPendingByCanonicalName(name);
                if (pending.size() < 2) {
                    continue;
                }
                Person survivor = pending.get(0);
                List<Person> mergedList = pending.subList(1, pending.size());
                UUID[] mergedIds = mergedList.stream().map(Person::getId).toArray(UUID[]::new);
                String[] mergedNames = mergedList.stream().map(Person::getCanonicalName).toArray(String[]::new);
                int totalContentCount = pending.stream().mapToInt(item -> contentCount(item.getContentCount())).sum();

                personMapper.updateSurvivorAfterMerge(survivor.getId(), totalContentCount, mergedIds);
                personMapper.update(null, new UpdateWrapper<Person>()
                        .in("id", Arrays.asList(mergedIds))
                        .set("dedup_status", "deduplicated")
                        .setSql("updated_at = NOW()"));

                boolean neo4jMerged = mergeNeo4jNodes(mergedIds, survivor.getId(), "Person", personProperties(survivor));
                insertRecord("person", survivor.getId(), survivor.getCanonicalName(), mergedIds, mergedNames,
                        survivor.getContentCount(), totalContentCount, neo4jMerged, jobRunId);
                merged += mergedList.size();
            } catch (Exception e) {
                log.warn("[EntityDeduplicationJob] person融合分组失败, canonicalName={}", name, e);
            }
        }
        return merged;
    }

    private int deduplicateOrganizations(UUID jobRunId) {
        int merged = 0;
        for (String name : organizationMapper.selectDuplicateCanonicalNames(BATCH_LIMIT)) {
            try {
                List<Organization> pending = organizationMapper.selectPendingByCanonicalName(name);
                if (pending.size() < 2) {
                    continue;
                }
                Organization survivor = pending.get(0);
                List<Organization> mergedList = pending.subList(1, pending.size());
                UUID[] mergedIds = mergedList.stream().map(Organization::getId).toArray(UUID[]::new);
                String[] mergedNames = mergedList.stream().map(Organization::getCanonicalName).toArray(String[]::new);
                int totalContentCount = pending.stream().mapToInt(item -> contentCount(item.getContentCount())).sum();

                organizationMapper.updateSurvivorAfterMerge(survivor.getId(), totalContentCount, mergedIds);
                organizationMapper.update(null, new UpdateWrapper<Organization>()
                        .in("id", Arrays.asList(mergedIds))
                        .set("dedup_status", "deduplicated")
                        .setSql("updated_at = NOW()"));

                boolean neo4jMerged = mergeNeo4jNodes(
                        mergedIds, survivor.getId(), "Organization", organizationProperties(survivor));
                insertRecord("organization", survivor.getId(), survivor.getCanonicalName(), mergedIds, mergedNames,
                        survivor.getContentCount(), totalContentCount, neo4jMerged, jobRunId);
                merged += mergedList.size();
            } catch (Exception e) {
                log.warn("[EntityDeduplicationJob] organization融合分组失败, canonicalName={}", name, e);
            }
        }
        return merged;
    }

    private int deduplicateEvents(UUID jobRunId) {
        int merged = 0;
        for (String name : eventMapper.selectDuplicateCanonicalNames(BATCH_LIMIT)) {
            try {
                List<Event> pending = eventMapper.selectPendingByCanonicalName(name);
                if (pending.size() < 2) {
                    continue;
                }
                Event survivor = pending.get(0);
                List<Event> mergedList = pending.subList(1, pending.size());
                UUID[] mergedIds = mergedList.stream().map(Event::getId).toArray(UUID[]::new);
                String[] mergedNames = mergedList.stream().map(Event::getCanonicalName).toArray(String[]::new);
                int totalContentCount = pending.stream().mapToInt(item -> contentCount(item.getContentCount())).sum();

                eventMapper.updateSurvivorAfterMerge(survivor.getId(), totalContentCount, mergedIds);
                eventMapper.update(null, new UpdateWrapper<Event>()
                        .in("id", Arrays.asList(mergedIds))
                        .set("dedup_status", "deduplicated")
                        .setSql("updated_at = NOW()"));

                boolean neo4jMerged = mergeNeo4jNodes(mergedIds, survivor.getId(), "Event", eventProperties(survivor));
                insertRecord("event", survivor.getId(), survivor.getCanonicalName(), mergedIds, mergedNames,
                        survivor.getContentCount(), totalContentCount, neo4jMerged, jobRunId);
                merged += mergedList.size();
            } catch (Exception e) {
                log.warn("[EntityDeduplicationJob] event融合分组失败, canonicalName={}", name, e);
            }
        }
        return merged;
    }

    private int deduplicateNarratives(UUID jobRunId) {
        int merged = 0;
        for (String name : narrativeMapper.selectDuplicateCanonicalNames(BATCH_LIMIT)) {
            try {
                List<Narrative> pending = narrativeMapper.selectPendingByCanonicalName(name);
                if (pending.size() < 2) {
                    continue;
                }
                Narrative survivor = pending.get(0);
                List<Narrative> mergedList = pending.subList(1, pending.size());
                UUID[] mergedIds = mergedList.stream().map(Narrative::getId).toArray(UUID[]::new);
                String[] mergedNames = mergedList.stream().map(Narrative::getCanonicalLabel).toArray(String[]::new);
                int totalContentCount = pending.stream().mapToInt(item -> contentCount(item.getContentCount())).sum();

                narrativeMapper.updateSurvivorAfterMerge(survivor.getId(), totalContentCount, mergedIds);
                narrativeMapper.update(null, new UpdateWrapper<Narrative>()
                        .in("id", Arrays.asList(mergedIds))
                        .set("dedup_status", "deduplicated")
                        .setSql("updated_at = NOW()"));

                boolean neo4jMerged = mergeNeo4jNodes(
                        mergedIds, survivor.getId(), "Narrative", narrativeProperties(survivor));
                insertRecord("narrative", survivor.getId(), survivor.getCanonicalLabel(), mergedIds, mergedNames,
                        survivor.getContentCount(), totalContentCount, neo4jMerged, jobRunId);
                merged += mergedList.size();
            } catch (Exception e) {
                log.warn("[EntityDeduplicationJob] narrative融合分组失败, canonicalLabel={}", name, e);
            }
        }
        return merged;
    }

    private boolean mergeNeo4jNodes(UUID[] mergedIds, UUID survivorId, String label,
                                    Map<String, Object> survivorProperties) {
        boolean allMerged = true;
        try {
            neo4jGraphService.mergeNode(label, survivorId.toString(), survivorProperties);
        } catch (Exception e) {
            allMerged = false;
            log.warn("Neo4j survivor节点写入失败, targetId={}, label={}", survivorId, label, e);
        }
        for (UUID mergedId : mergedIds) {
            try {
                neo4jGraphService.mergeNodes(mergedId.toString(), survivorId.toString(), label);
            } catch (Exception e) {
                allMerged = false;
                log.warn("Neo4j节点合并失败, sourceId={}, targetId={}, label={}",
                        mergedId, survivorId, label, e);
            }
        }
        return allMerged;
    }

    private Map<String, Object> personProperties(Person person) {
        Map<String, Object> props = baseProperties();
        putIfNotNull(props, "canonicalName", person.getCanonicalName());
        if (person.getImportanceScore() != null) {
            props.put("importanceScore", person.getImportanceScore().doubleValue());
        }
        return props;
    }

    private Map<String, Object> organizationProperties(Organization organization) {
        Map<String, Object> props = baseProperties();
        putIfNotNull(props, "canonicalName", organization.getCanonicalName());
        putIfNotNull(props, "orgType", organization.getOrgType());
        putIfNotNull(props, "country", organization.getCountry());
        if (organization.getImportanceScore() != null) {
            props.put("importanceScore", organization.getImportanceScore().doubleValue());
        }
        return props;
    }

    private Map<String, Object> eventProperties(Event event) {
        Map<String, Object> props = baseProperties();
        putIfNotNull(props, "canonicalName", event.getCanonicalName());
        putIfNotNull(props, "eventType", event.getEventType());
        putIfNotNull(props, "country", event.getCountry());
        if (event.getImportanceScore() != null) {
            props.put("importanceScore", event.getImportanceScore().doubleValue());
        }
        return props;
    }

    private Map<String, Object> narrativeProperties(Narrative narrative) {
        Map<String, Object> props = baseProperties();
        putIfNotNull(props, "canonicalLabel", narrative.getCanonicalLabel());
        putIfNotNull(props, "frameType", narrative.getFrameType());
        if (narrative.getImportanceScore() != null) {
            props.put("importanceScore", narrative.getImportanceScore().doubleValue());
        }
        return props;
    }

    private Map<String, Object> baseProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("source", "entity_dedup_job");
        return props;
    }

    private void putIfNotNull(Map<String, Object> props, String key, Object value) {
        if (value != null) {
            props.put(key, value);
        }
    }

    private void insertRecord(String entityType, UUID survivorId, String survivorName,
                              UUID[] mergedIds, String[] mergedNames,
                              Integer contentCountBefore, int contentCountAfter,
                              boolean neo4jMerged, UUID jobRunId) {
        EntityFusionRecord record = new EntityFusionRecord();
        record.setId(UUID.randomUUID());
        record.setEntityType(entityType);
        record.setSurvivorId(survivorId);
        record.setSurvivorName(survivorName);
        record.setMergedIds(mergedIds);
        record.setMergedNames(mergedNames);
        record.setMergedCount(mergedIds.length);
        record.setFusionMethod("exact_name");
        record.setContentCountBefore(contentCountBefore);
        record.setContentCountAfter(contentCountAfter);
        record.setNeo4jMerged(neo4jMerged);
        record.setJobRunId(jobRunId);
        entityFusionRecordMapper.insert(record);
    }

    private void logPendingStats() {
        long persons = personMapper.countByDedupStatus("pending");
        long organizations = organizationMapper.countByDedupStatus("pending");
        long events = eventMapper.countByDedupStatus("pending");
        long narratives = narrativeMapper.countByDedupStatus("pending");
        log.info("[EntityDeduplicationJob] pending stats: persons={}, organizations={}, events={}, narratives={}",
                persons, organizations, events, narratives);
    }

    private int contentCount(Integer value) {
        return value == null ? 0 : value;
    }
}

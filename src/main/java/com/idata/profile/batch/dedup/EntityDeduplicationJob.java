package com.idata.profile.batch.dedup;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t3.T3ResolveRequest;
import com.idata.profile.agentproxy.dto.t3.T3ResolveResponse;
import com.idata.profile.common.util.StableUuidUtil;
import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.dedup.EntityFusionRecord;
import com.idata.profile.entity.graph.Event;
import com.idata.profile.entity.graph.Narrative;
import com.idata.profile.entity.graph.Organization;
import com.idata.profile.entity.graph.Person;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.account.SocialAccountMapper;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final SocialAccountMapper socialAccountMapper;
    private final EntityFusionRecordMapper entityFusionRecordMapper;
    private final Neo4jGraphService neo4jGraphService;
    private final AgentProxyClient agentProxyClient;
    private final ApplicationContext applicationContext;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelay = 60 * 60 * 1000)
    public void run() {
        UUID jobRunId = UUID.randomUUID();
        if (!running.compareAndSet(false, true)) {
            log.info("[EntityDeduplicationJob] previous run is still active, skip scheduled run, jobRunId={}", jobRunId);
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
        log.info("[EntityDeduplicationJob] start, jobRunId={}", jobRunId);

        int exactMerged = 0;
        exactMerged += deduplicateEntities("person", jobRunId);
        exactMerged += deduplicateEntities("organization", jobRunId);
        exactMerged += deduplicateEntities("event", jobRunId);
        exactMerged += deduplicateEntities("narrative", jobRunId);
        log.info("[EntityDeduplicationJob] exact-name stage done, merged={}", exactMerged);

        int t3Merged = deduplicateWithT3(jobRunId);
        log.info("[EntityDeduplicationJob] T3-assisted stage done, merged={}", t3Merged);

        log.info("[EntityDeduplicationJob] done, jobRunId={}, totalMerged={}", jobRunId, exactMerged + t3Merged);
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

                boolean neo4jMerged = mergeNeo4jNodes(mergedIds, survivor.getId(), "Person",
                        personProperties(survivor), survivor.getCanonicalName(), "person");
                insertRecord("person", survivor.getId(), survivor.getCanonicalName(), mergedIds, mergedNames,
                        survivor.getContentCount(), totalContentCount, neo4jMerged, jobRunId, "exact_name");
                merged += mergedList.size();
            } catch (Exception e) {
                log.warn("[EntityDeduplicationJob] person group failed, canonicalName={}", name, e);
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

                boolean neo4jMerged = mergeNeo4jNodes(mergedIds, survivor.getId(), "Organization",
                        organizationProperties(survivor), survivor.getCanonicalName(), "organization");
                insertRecord("organization", survivor.getId(), survivor.getCanonicalName(), mergedIds, mergedNames,
                        survivor.getContentCount(), totalContentCount, neo4jMerged, jobRunId, "exact_name");
                merged += mergedList.size();
            } catch (Exception e) {
                log.warn("[EntityDeduplicationJob] organization group failed, canonicalName={}", name, e);
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

                boolean neo4jMerged = mergeNeo4jNodes(mergedIds, survivor.getId(), "Event",
                        eventProperties(survivor), survivor.getCanonicalName(), "event");
                insertRecord("event", survivor.getId(), survivor.getCanonicalName(), mergedIds, mergedNames,
                        survivor.getContentCount(), totalContentCount, neo4jMerged, jobRunId, "exact_name");
                merged += mergedList.size();
            } catch (Exception e) {
                log.warn("[EntityDeduplicationJob] event group failed, canonicalName={}", name, e);
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

                boolean neo4jMerged = mergeNeo4jNodes(mergedIds, survivor.getId(), "Narrative",
                        narrativeProperties(survivor), survivor.getCanonicalLabel(), "narrative");
                insertRecord("narrative", survivor.getId(), survivor.getCanonicalLabel(), mergedIds, mergedNames,
                        survivor.getContentCount(), totalContentCount, neo4jMerged, jobRunId, "exact_name");
                merged += mergedList.size();
            } catch (Exception e) {
                log.warn("[EntityDeduplicationJob] narrative group failed, canonicalLabel={}", name, e);
            }
        }
        return merged;
    }

    private int deduplicateWithT3(UUID jobRunId) {
        int merged = 0;
        for (String entityType : List.of("person", "organization", "event", "narrative")) {
            merged += resolveEntityTypeWithT3(entityType, jobRunId);
        }
        return merged;
    }

    private int resolveEntityTypeWithT3(String entityType, UUID jobRunId) {
        List<?> pendingEntities = selectPendingEntities(entityType, BATCH_LIMIT);
        if (pendingEntities.size() < 2) {
            return 0;
        }

        List<T3ResolveRequest.EntityCandidate> candidates = pendingEntities.stream()
                .map(entity -> toCandidate(entity, entityType))
                .filter(candidate -> candidate.getId() != null && candidate.getCanonicalName() != null)
                .toList();
        if (candidates.size() < 2) {
            return 0;
        }

        T3ResolveRequest request = new T3ResolveRequest();
        request.setEntities(candidates);

        T3ResolveResponse response;
        try {
            response = agentProxyClient.call("T3", "resolve_entities", request, T3ResolveResponse.class);
        } catch (Exception e) {
            log.warn("[EntityDeduplicationJob] T3 resolve_entities failed, entityType={}", entityType, e);
            return 0;
        }

        if (response == null || response.getMergeGroups() == null || response.getMergeGroups().isEmpty()) {
            return 0;
        }

        int merged = 0;
        for (T3ResolveResponse.MergeGroup group : response.getMergeGroups()) {
            if (group == null || group.getConfidence() == null || group.getConfidence() < 0.8D
                    || group.getMergedIds() == null || group.getMergedIds().isEmpty()) {
                continue;
            }
            try {
                merged += executeMergeGroup(group, entityType, jobRunId);
            } catch (Exception e) {
                log.warn("[EntityDeduplicationJob] T3 merge group failed, survivorId={}",
                        group.getSurvivorId(), e);
            }
        }
        return merged;
    }

    private List<?> selectPendingEntities(String entityType, int limit) {
        return switch (entityType) {
            case "person" -> personMapper.selectByDedupStatus("pending", limit);
            case "organization" -> organizationMapper.selectByDedupStatus("pending", limit);
            case "event" -> eventMapper.selectByDedupStatus("pending", limit);
            case "narrative" -> narrativeMapper.selectByDedupStatus("pending", limit);
            default -> List.of();
        };
    }

    private T3ResolveRequest.EntityCandidate toCandidate(Object entity, String entityType) {
        T3ResolveRequest.EntityCandidate candidate = new T3ResolveRequest.EntityCandidate();
        candidate.setEntityType(entityType);
        candidate.setAliases(List.of());
        if (entity instanceof Person person) {
            candidate.setId(person.getId().toString());
            candidate.setCanonicalName(person.getCanonicalName());
            candidate.setImportanceScore(score(person.getImportanceScore()));
            fillSourceIdentifiers(candidate, person);
        } else if (entity instanceof Organization organization) {
            candidate.setId(organization.getId().toString());
            candidate.setCanonicalName(organization.getCanonicalName());
            candidate.setImportanceScore(score(organization.getImportanceScore()));
        } else if (entity instanceof Event event) {
            candidate.setId(event.getId().toString());
            candidate.setCanonicalName(event.getCanonicalName());
            candidate.setImportanceScore(score(event.getImportanceScore()));
        } else if (entity instanceof Narrative narrative) {
            candidate.setId(narrative.getId().toString());
            candidate.setCanonicalName(narrative.getCanonicalLabel());
            candidate.setImportanceScore(score(narrative.getImportanceScore()));
        }
        return candidate;
    }

    private void fillSourceIdentifiers(T3ResolveRequest.EntityCandidate candidate, Person person) {
        try {
            List<SocialAccount> accounts = socialAccountMapper.selectByEntityPersonId(person.getId());
            if (accounts == null || accounts.isEmpty()) {
                return;
            }
            Map<String, String> sourceIds = new LinkedHashMap<>();
            for (SocialAccount account : accounts) {
                if (account != null && hasText(account.getPlatform()) && hasText(account.getPlatformUserId())) {
                    sourceIds.put(account.getPlatform(), account.getPlatformUserId());
                }
            }
            if (!sourceIds.isEmpty()) {
                candidate.setSourceIdentifiers(sourceIds);
            }
        } catch (Exception e) {
            log.warn("[EntityDeduplicationJob] load source identifiers failed, personId={}", person.getId(), e);
        }
    }

    private int executeMergeGroup(T3ResolveResponse.MergeGroup group, String entityType, UUID jobRunId) {
        return switch (entityType) {
            case "person" -> executePersonMergeGroup(group, jobRunId);
            case "organization" -> executeOrganizationMergeGroup(group, jobRunId);
            case "event" -> executeEventMergeGroup(group, jobRunId);
            case "narrative" -> executeNarrativeMergeGroup(group, jobRunId);
            default -> 0;
        };
    }

    private int executePersonMergeGroup(T3ResolveResponse.MergeGroup group, UUID jobRunId) {
        UUID survivorId = parseUuid(group.getSurvivorId());
        List<UUID> mergedUuidIds = mergedUuidIds(group, survivorId);
        if (survivorId == null || mergedUuidIds.isEmpty()) {
            return 0;
        }
        Person survivor = personMapper.selectById(survivorId);
        List<Person> mergedList = personMapper.selectBatchIds(mergedUuidIds);
        if (survivor == null || mergedList.isEmpty()) {
            return 0;
        }
        UUID[] mergedIds = mergedList.stream().map(Person::getId).toArray(UUID[]::new);
        String[] mergedNames = mergedList.stream().map(Person::getCanonicalName).toArray(String[]::new);
        int totalContentCount = contentCount(survivor.getContentCount())
                + mergedList.stream().mapToInt(item -> contentCount(item.getContentCount())).sum();

        personMapper.updateSurvivorAfterMerge(survivorId, totalContentCount, mergedIds);
        personMapper.update(null, new UpdateWrapper<Person>()
                .in("id", Arrays.asList(mergedIds))
                .set("dedup_status", "deduplicated")
                .setSql("updated_at = NOW()"));
        boolean neo4jMerged = mergeNeo4jNodes(mergedIds, survivorId, "Person",
                personProperties(survivor), survivor.getCanonicalName(), "person");
        insertRecord("person", survivorId, survivor.getCanonicalName(), mergedIds, mergedNames,
                survivor.getContentCount(), totalContentCount, neo4jMerged, jobRunId, fusionMethod(group));
        return mergedList.size();
    }

    private int executeOrganizationMergeGroup(T3ResolveResponse.MergeGroup group, UUID jobRunId) {
        UUID survivorId = parseUuid(group.getSurvivorId());
        List<UUID> mergedUuidIds = mergedUuidIds(group, survivorId);
        if (survivorId == null || mergedUuidIds.isEmpty()) {
            return 0;
        }
        Organization survivor = organizationMapper.selectById(survivorId);
        List<Organization> mergedList = organizationMapper.selectBatchIds(mergedUuidIds);
        if (survivor == null || mergedList.isEmpty()) {
            return 0;
        }
        UUID[] mergedIds = mergedList.stream().map(Organization::getId).toArray(UUID[]::new);
        String[] mergedNames = mergedList.stream().map(Organization::getCanonicalName).toArray(String[]::new);
        int totalContentCount = contentCount(survivor.getContentCount())
                + mergedList.stream().mapToInt(item -> contentCount(item.getContentCount())).sum();

        organizationMapper.updateSurvivorAfterMerge(survivorId, totalContentCount, mergedIds);
        organizationMapper.update(null, new UpdateWrapper<Organization>()
                .in("id", Arrays.asList(mergedIds))
                .set("dedup_status", "deduplicated")
                .setSql("updated_at = NOW()"));
        boolean neo4jMerged = mergeNeo4jNodes(mergedIds, survivorId, "Organization",
                organizationProperties(survivor), survivor.getCanonicalName(), "organization");
        insertRecord("organization", survivorId, survivor.getCanonicalName(), mergedIds, mergedNames,
                survivor.getContentCount(), totalContentCount, neo4jMerged, jobRunId, fusionMethod(group));
        return mergedList.size();
    }

    private int executeEventMergeGroup(T3ResolveResponse.MergeGroup group, UUID jobRunId) {
        UUID survivorId = parseUuid(group.getSurvivorId());
        List<UUID> mergedUuidIds = mergedUuidIds(group, survivorId);
        if (survivorId == null || mergedUuidIds.isEmpty()) {
            return 0;
        }
        Event survivor = eventMapper.selectById(survivorId);
        List<Event> mergedList = eventMapper.selectBatchIds(mergedUuidIds);
        if (survivor == null || mergedList.isEmpty()) {
            return 0;
        }
        UUID[] mergedIds = mergedList.stream().map(Event::getId).toArray(UUID[]::new);
        String[] mergedNames = mergedList.stream().map(Event::getCanonicalName).toArray(String[]::new);
        int totalContentCount = contentCount(survivor.getContentCount())
                + mergedList.stream().mapToInt(item -> contentCount(item.getContentCount())).sum();

        eventMapper.updateSurvivorAfterMerge(survivorId, totalContentCount, mergedIds);
        eventMapper.update(null, new UpdateWrapper<Event>()
                .in("id", Arrays.asList(mergedIds))
                .set("dedup_status", "deduplicated")
                .setSql("updated_at = NOW()"));
        boolean neo4jMerged = mergeNeo4jNodes(mergedIds, survivorId, "Event",
                eventProperties(survivor), survivor.getCanonicalName(), "event");
        insertRecord("event", survivorId, survivor.getCanonicalName(), mergedIds, mergedNames,
                survivor.getContentCount(), totalContentCount, neo4jMerged, jobRunId, fusionMethod(group));
        return mergedList.size();
    }

    private int executeNarrativeMergeGroup(T3ResolveResponse.MergeGroup group, UUID jobRunId) {
        UUID survivorId = parseUuid(group.getSurvivorId());
        List<UUID> mergedUuidIds = mergedUuidIds(group, survivorId);
        if (survivorId == null || mergedUuidIds.isEmpty()) {
            return 0;
        }
        Narrative survivor = narrativeMapper.selectById(survivorId);
        List<Narrative> mergedList = narrativeMapper.selectBatchIds(mergedUuidIds);
        if (survivor == null || mergedList.isEmpty()) {
            return 0;
        }
        UUID[] mergedIds = mergedList.stream().map(Narrative::getId).toArray(UUID[]::new);
        String[] mergedNames = mergedList.stream().map(Narrative::getCanonicalLabel).toArray(String[]::new);
        int totalContentCount = contentCount(survivor.getContentCount())
                + mergedList.stream().mapToInt(item -> contentCount(item.getContentCount())).sum();

        narrativeMapper.updateSurvivorAfterMerge(survivorId, totalContentCount, mergedIds);
        narrativeMapper.update(null, new UpdateWrapper<Narrative>()
                .in("id", Arrays.asList(mergedIds))
                .set("dedup_status", "deduplicated")
                .setSql("updated_at = NOW()"));
        boolean neo4jMerged = mergeNeo4jNodes(mergedIds, survivorId, "Narrative",
                narrativeProperties(survivor), survivor.getCanonicalLabel(), "narrative");
        insertRecord("narrative", survivorId, survivor.getCanonicalLabel(), mergedIds, mergedNames,
                survivor.getContentCount(), totalContentCount, neo4jMerged, jobRunId, fusionMethod(group));
        return mergedList.size();
    }

    private boolean mergeNeo4jNodes(UUID[] mergedIds, UUID survivorId, String label,
                                    Map<String, Object> survivorProperties,
                                    String canonicalName, String entityType) {
        try {
            String stableId = stableUuid(entityType + ":" + canonicalName);
            survivorProperties.put("pgSurvivorId", survivorId.toString());
            neo4jGraphService.mergeNode(label, stableId, survivorProperties);
            return true;
        } catch (Exception e) {
            log.warn("Neo4j node property update failed, label={}, canonicalName={}", label, canonicalName, e);
            return false;
        }
    }

    private String stableUuid(String seed) {
        return StableUuidUtil.fromSeed(seed);
    }

    private List<UUID> mergedUuidIds(T3ResolveResponse.MergeGroup group, UUID survivorId) {
        List<UUID> result = new ArrayList<>();
        if (group.getMergedIds() == null) {
            return result;
        }
        for (String id : group.getMergedIds()) {
            UUID uuid = parseUuid(id);
            if (uuid != null && !uuid.equals(survivorId)) {
                result.add(uuid);
            }
        }
        return result;
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

    private String fusionMethod(T3ResolveResponse.MergeGroup group) {
        return hasText(group.getMatchMethod()) ? group.getMatchMethod() : "t3_resolve";
    }

    private double score(java.math.BigDecimal value) {
        return value == null ? 0D : value.doubleValue();
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
                              boolean neo4jMerged, UUID jobRunId, String fusionMethod) {
        EntityFusionRecord record = new EntityFusionRecord();
        record.setId(UUID.randomUUID());
        record.setEntityType(entityType);
        record.setSurvivorId(survivorId);
        record.setSurvivorName(survivorName);
        record.setMergedIds(mergedIds);
        record.setMergedNames(mergedNames);
        record.setMergedCount(mergedIds.length);
        record.setFusionMethod(fusionMethod);
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

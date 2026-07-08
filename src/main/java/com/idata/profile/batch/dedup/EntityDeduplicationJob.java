package com.idata.profile.batch.dedup;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t3.T3ResolveBatchRequest;
import com.idata.profile.agentproxy.dto.t3.T3ResolveBatchResponse;
import com.idata.profile.agentproxy.dto.t3.T3ResolveRequest;
import com.idata.profile.agentproxy.dto.t3.T3ResolveResponse;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingRequest;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingResponse;
import com.idata.profile.common.util.StableUuidUtil;
import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.dedup.EntityFusionRecord;
import com.idata.profile.entity.graph.Event;
import com.idata.profile.entity.graph.Narrative;
import com.idata.profile.entity.graph.Organization;
import com.idata.profile.entity.graph.Person;
import com.idata.profile.infra.elasticsearch.EntityEsService;
import com.idata.profile.infra.milvus.MilvusVectorService;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.account.SocialAccountMapper;
import com.idata.profile.mapper.dedup.EntityFusionRecordMapper;
import com.idata.profile.mapper.graph.EventMapper;
import com.idata.profile.mapper.graph.NarrativeMapper;
import com.idata.profile.mapper.graph.OrganizationMapper;
import com.idata.profile.mapper.graph.PersonMapper;
import com.idata.profile.service.EntityCandidateRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
    private final EntityCandidateRetrievalService candidateRetrievalService;
    private final EntityEsService entityEsService;
    private final MilvusVectorService milvusVectorService;
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
        if (pendingEntities.isEmpty()) {
            return 0;
        }

        List<T3ResolveBatchRequest.ResolveItem> resolveItems = new ArrayList<>();
        Map<String, T3ResolveBatchRequest.Candidate> candidatesById = new HashMap<>();
        for (Object entity : pendingEntities) {
            T3ResolveRequest.EntityCandidate mention = toCandidate(entity, entityType);
            if (mention.getId() == null || mention.getCanonicalName() == null) {
                continue;
            }

            List<T3ResolveBatchRequest.Candidate> candidates = candidateRetrievalService.retrieveCandidates(
                    mention.getCanonicalName(), entityType, 10);
            if (candidates.isEmpty()) {
                continue;
            }
            for (T3ResolveBatchRequest.Candidate candidate : candidates) {
                if (candidate != null && hasText(candidate.getEntityId())) {
                    candidatesById.put(candidate.getEntityId(), candidate);
                }
            }
            resolveItems.add(toBatchResolveItem(mention, candidates));
        }

        if (resolveItems.isEmpty()) {
            return 0;
        }

        T3ResolveBatchRequest request = new T3ResolveBatchRequest();
        request.setItems(resolveItems);
        T3ResolveBatchRequest.Strategy strategy = new T3ResolveBatchRequest.Strategy();
        strategy.setAutoMergeThreshold(0.9D);
        strategy.setReviewThreshold(0.6D);
        request.setStrategy(strategy);
        log.debug("[EntityDeduplicationJob] T3 resolve_batch request, entityType={}, items={}, firstCandidates={}",
                entityType, resolveItems.size(), resolveItems.get(0).getCandidates().size());

        T3ResolveBatchResponse response;
        try {
            response = agentProxyClient.call("T3", "resolve_batch", request, T3ResolveBatchResponse.class);
        } catch (Exception e) {
            log.warn("[EntityDeduplicationJob] T3 resolve_batch failed, entityType={}", entityType, e);
            return 0;
        }

        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return 0;
        }

        int merged = 0;
        for (T3ResolveBatchResponse.ResolveResult result : response.getResults()) {
            if (result == null || result.getConfidence() == null || !hasText(result.getMentionId())
                    || !hasText(result.getMatchedEntityId())) {
                continue;
            }
            double confidence = result.getConfidence();
            T3ResolveResponse.MergeGroup group = toMergeGroup(result, entityType, candidatesById);
            try {
                if ("MERGE".equalsIgnoreCase(result.getAction()) && confidence >= 0.9D) {
                    merged += executeMergeGroup(group, entityType, jobRunId);
                    log.info("[EntityDeduplicationJob] auto-merge, entityType={}, survivorId={}, confidence={}",
                            entityType, group.getSurvivorId(), confidence);
                } else if (confidence >= 0.6D) {
                    insertPendingReviewRecord(group, entityType, jobRunId, confidence,
                            result.getMatchMethod(), response.getModelVersion());
                    log.info("[EntityDeduplicationJob] pending-review, entityType={}, survivorId={}, confidence={}",
                            entityType, group.getSurvivorId(), confidence);
                }
            } catch (Exception e) {
                log.warn("[EntityDeduplicationJob] merge group failed, survivorId={}",
                        group.getSurvivorId(), e);
            }
        }
        return merged;
    }

    private T3ResolveBatchRequest.ResolveItem toBatchResolveItem(
            T3ResolveRequest.EntityCandidate candidate,
            List<T3ResolveBatchRequest.Candidate> candidates) {
        T3ResolveBatchRequest.ResolveItem item = new T3ResolveBatchRequest.ResolveItem();
        T3ResolveBatchRequest.Mention mention = new T3ResolveBatchRequest.Mention();
        mention.setMentionId(candidate.getId());
        mention.setName(candidate.getCanonicalName());
        mention.setNormalizedName(candidate.getCanonicalName());
        mention.setType(candidate.getEntityType());
        mention.setAliases(candidate.getAliases());
        mention.setAttributes(Map.of());
        item.setMention(mention);
        item.setCandidates(candidates);
        T3ResolveBatchRequest.Context context = new T3ResolveBatchRequest.Context();
        context.setLanguage("zh");
        context.setTextWindow(candidate.getCanonicalName());
        item.setContext(context);
        return item;
    }

    private T3ResolveResponse.MergeGroup toMergeGroup(T3ResolveBatchResponse.ResolveResult result,
                                                      String entityType,
                                                      Map<String, T3ResolveBatchRequest.Candidate> candidatesById) {
        T3ResolveResponse.MergeGroup group = new T3ResolveResponse.MergeGroup();
        UUID survivorPgId = resolvePgId(candidatesById.get(result.getMatchedEntityId()), entityType);
        group.setSurvivorId(survivorPgId != null ? survivorPgId.toString() : result.getMatchedEntityId());
        group.setMergedIds(List.of(result.getMentionId()));
        group.setConfidence(result.getConfidence());
        group.setMatchMethod(result.getMatchMethod());
        return group;
    }

    private UUID resolvePgId(T3ResolveBatchRequest.Candidate candidate, String entityType) {
        if (candidate == null || !hasText(candidate.getCanonicalName())) {
            return null;
        }
        return switch (entityType) {
            case "person" -> personMapper.selectCanonicalIdByName(candidate.getCanonicalName());
            case "organization" -> organizationMapper.selectCanonicalIdByName(candidate.getCanonicalName());
            case "event" -> eventMapper.selectCanonicalIdByName(candidate.getCanonicalName());
            case "narrative" -> narrativeMapper.selectCanonicalIdByLabel(candidate.getCanonicalName());
            default -> null;
        };
    }

    /**
     * 三路候选召回：ES名称匹配 + Milvus向量ANN + Neo4j别名匹配。
     * 结果合并去重，按综合分数排序，返回 TopK。
     */
    private List<Map<String, Object>> retrieveCandidates(String canonicalName,
                                                         String entityType, int topK) {
        Map<String, Map<String, Object>> candidates = new LinkedHashMap<>();

        try {
            List<Map<String, Object>> esResults =
                    entityEsService.searchEntities(canonicalName, entityType, topK);
            for (Map<String, Object> r : esResults) {
                String entityId = stringValue(r.get("entity_id"));
                if (entityId == null) {
                    continue;
                }
                Map<String, Object> candidate = toCandidateMap(r, "NAME_INDEX");
                candidates.put(entityId, candidate);
            }
        } catch (Exception e) {
            log.warn("[EntityDeduplicationJob] ES candidate retrieval failed, name={}", canonicalName, e);
        }

        try {
            T4EmbeddingRequest req = new T4EmbeddingRequest();
            req.setText(canonicalName);
            T4EmbeddingResponse embResp = agentProxyClient.call(
                    "T4", "generate_text_embedding", req, T4EmbeddingResponse.class);
            if (embResp != null && embResp.getEmbedding() != null) {
                List<String> milvusIds = milvusVectorService.searchEntityEmbeddings(
                        embResp.getEmbedding(), topK, entityType);
                for (String entityId : milvusIds) {
                    if (candidates.containsKey(entityId)) {
                        addRetrievalChannel(candidates.get(entityId), "VECTOR_INDEX");
                    } else {
                        Map<String, Object> candidate = new LinkedHashMap<>();
                        candidate.put("entityId", entityId);
                        candidate.put("score", 0.7D);
                        candidate.put("retrievalChannels", new ArrayList<>(List.of("VECTOR_INDEX")));
                        candidates.put(entityId, candidate);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[EntityDeduplicationJob] Milvus candidate retrieval failed, name={}", canonicalName, e);
        }

        // Neo4j的别名匹配已经在 searchEntities 端点里实现，ES召回覆盖了大部分场景。
        return new ArrayList<>(candidates.values()).stream()
                .sorted((a, b) -> Double.compare(scoreOf(b.get("score")), scoreOf(a.get("score"))))
                .limit(topK)
                .toList();
    }

    private Map<String, Object> toCandidateMap(Map<String, Object> esResult, String channel) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("entityId", esResult.get("entity_id"));
        candidate.put("canonicalName", esResult.get("canonical_name"));
        candidate.put("type", esResult.get("entity_type"));
        candidate.put("aliases", esResult.getOrDefault("aliases", List.of()));
        candidate.put("importanceScore", esResult.getOrDefault("importance_score", 0.0D));
        candidate.put("score", esResult.getOrDefault("score", 0.5D));
        candidate.put("retrievalChannels", new ArrayList<>(List.of(channel)));
        return candidate;
    }

    @SuppressWarnings("unchecked")
    private void addRetrievalChannel(Map<String, Object> candidate, String channel) {
        Object value = candidate.get("retrievalChannels");
        if (value instanceof List<?> channels) {
            ((List<String>) channels).add(channel);
            return;
        }
        candidate.put("retrievalChannels", new ArrayList<>(List.of(channel)));
    }

    private void insertPendingReviewRecord(T3ResolveResponse.MergeGroup group,
                                           String entityType, UUID jobRunId, double confidence,
                                           String matchMethod, String resolverModel) {
        try {
            EntityFusionRecord record = new EntityFusionRecord();
            record.setId(UUID.randomUUID());
            record.setEntityType(entityType);
            record.setSurvivorId(parseUuid(group.getSurvivorId()));
            record.setSurvivorName(group.getSurvivorId());
            record.setMergedIds(group.getMergedIds().stream()
                    .map(this::parseUuid)
                    .filter(id -> id != null)
                    .toArray(UUID[]::new));
            record.setMergedCount(group.getMergedIds().size());
            record.setFusionMethod("t3_pending_review");
            record.setNeo4jMerged(false);
            record.setJobRunId(jobRunId);
            record.setMatchMethod(matchMethod);
            record.setMatchScore(BigDecimal.valueOf(confidence));
            record.setResolverModel(resolverModel);
            record.setIsAutoMerged(false);
            entityFusionRecordMapper.insert(record);
        } catch (Exception e) {
            log.warn("[EntityDeduplicationJob] insertPendingReviewRecord failed, survivorId={}",
                    group.getSurvivorId(), e);
        }
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

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private double scoreOf(Object value) {
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
        record.setMatchMethod(fusionMethod);
        record.setIsAutoMerged(true);
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

package com.idata.profile.infra.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EntityEsService {

    private static final String ENTITY_INDEX = "entities_index";

    private final ElasticsearchClient esClient;

    /**
     * 确保索引存在（应用启动时调用）。
     */
    public void ensureIndex() {
        try {
            boolean exists = esClient.indices().exists(e -> e.index(ENTITY_INDEX)).value();
            if (exists) {
                log.info("ES entity index already exists: {}", ENTITY_INDEX);
                return;
            }
            esClient.indices().create(c -> c
                    .index(ENTITY_INDEX)
                    .settings(s -> s
                            .analysis(a -> a
                                    .analyzer("ik_max_word_analyzer", an -> an
                                            .custom(cu -> cu
                                                    .tokenizer("ik_max_word")
                                                    .filter(List.of("lowercase"))))))
                    .mappings(m -> m
                            .properties("entity_id", p -> p.keyword(k -> k))
                            .properties("canonical_name", p -> p
                                    .text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                            .properties("normalized_name", p -> p
                                    .text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                            .properties("aliases", p -> p
                                    .text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                            .properties("entity_type", p -> p.keyword(k -> k))
                            .properties("source_id", p -> p.keyword(k -> k))
                            .properties("platform", p -> p.keyword(k -> k))
                            .properties("account_entity_type", p -> p.keyword(k -> k))
                            .properties("account_type", p -> p.keyword(k -> k))
                            .properties("content_type", p -> p.keyword(k -> k))
                            .properties("importance_score", p -> p.float_(f -> f))));
            log.info("ES entity index created: {}", ENTITY_INDEX);
        } catch (IOException e) {
            log.error("Failed to ensure ES entity index", e);
        }
    }

    /**
     * 写入/更新实体到 ES。
     */
    public void indexEntity(String entityId, String canonicalName,
                            List<String> aliases, String entityType, double importanceScore) {
        indexEntity(entityId, canonicalName, aliases, entityType, importanceScore, Map.of());
    }

    public void indexEntity(String entityId, String canonicalName,
                            List<String> aliases, String entityType, double importanceScore,
                            Map<String, Object> extraFields) {
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("entity_id", entityId);
            doc.put("canonical_name", canonicalName);
            doc.put("normalized_name", normalize(canonicalName));
            doc.put("aliases", aliases != null ? aliases : List.of());
            doc.put("entity_type", normalizeEntityType(entityType));
            doc.put("importance_score", importanceScore);
            if (extraFields != null && !extraFields.isEmpty()) {
                doc.putAll(extraFields);
            }

            esClient.index(i -> i
                    .index(ENTITY_INDEX)
                    .id(entityId)
                    .document(doc));
            log.debug("Indexed entity to ES, entityId={}, type={}", entityId, entityType);
        } catch (IOException e) {
            log.warn("Failed to index entity to ES, entityId={}", entityId, e);
        }
    }

    /**
     * 按名称模糊搜索实体（候选召回用）。
     */
    public List<Map<String, Object>> searchEntities(String keyword, String entityType, int topK) {
        return searchEntities(keyword, entityType, topK, Map.of());
    }

    public List<Map<String, Object>> searchEntities(String keyword, String entityType, int topK,
                                                    Map<String, String> filters) {
        try {
            var response = esClient.search(s -> {
                var q = s.index(ENTITY_INDEX).size(topK)
                        .query(qb -> qb.bool(b -> {
                            b.must(m -> m.multiMatch(mm -> mm
                                    .query(keyword)
                                    .fields("canonical_name^3", "normalized_name^2", "aliases^2", "source_id")));
                            if (entityType != null && !entityType.isBlank()) {
                                b.filter(f -> f.term(t -> t.field("entity_type").value(normalizeEntityType(entityType))));
                            }
                            if (filters != null) {
                                filters.forEach((field, value) -> {
                                    if (field != null && value != null && !value.isBlank()) {
                                        b.filter(f -> f.term(t -> t.field(field).value(value)));
                                    }
                                });
                            }
                            return b;
                        }));
                return q;
            }, Map.class);

            return response.hits().hits().stream()
                    .map(h -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("entityId", h.id());
                        result.put("score", h.score());
                        if (h.source() != null) {
                            result.putAll(h.source());
                        }
                        return result;
                    })
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to search entities in ES, keyword={}", keyword, e);
            return List.of();
        }
    }

    /**
     * 按ID批量查询实体，给 Milvus 命中但 ES 名称匹配没命中的候选回填名字信息。
     */
    public Map<String, Map<String, Object>> getEntitiesByIds(List<String> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) {
            return Map.of();
        }
        try {
            var response = esClient.mget(m -> m.index(ENTITY_INDEX).ids(entityIds), Map.class);
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (var item : response.docs()) {
                if (item.result() != null && Boolean.TRUE.equals(item.result().found())
                        && item.result().source() != null) {
                    result.put(item.result().id(), item.result().source());
                }
            }
            return result;
        } catch (IOException e) {
            log.warn("Failed to mget entities from ES, ids={}", entityIds, e);
            return Map.of();
        }
    }

    /**
     * 批量删除实体，EntityDeduplicationJob 合并实体后清理被合并掉的旧实体 ES 记录。
     */
    public void deleteEntities(List<String> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) {
            return;
        }
        for (String entityId : entityIds) {
            try {
                esClient.delete(d -> d.index(ENTITY_INDEX).id(entityId));
            } catch (IOException e) {
                log.warn("Failed to delete entity from ES, entityId={}", entityId, e);
            }
        }
    }

    /**
     * 之前 normalized_name 一直是 canonical_name 的重复值，没有真正的归一化处理。
     * 这里做真正有意义的归一化：去重音符号、转小写、标点替换成空格、合并多余空白，
     * 让候选检索能对上"U.S. Central Command" vs "US Central Command"这类只有大小写/
     * 标点/重音符号差异的写法。
     */
    private String normalize(String name) {
        if (name == null) {
            return null;
        }
        String decomposed = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD);
        String noDiacritics = decomposed.replaceAll("\\p{M}", "");
        String noPunct = noDiacritics.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{Punct}]", " ");
        return noPunct.trim().replaceAll("\\s+", " ");
    }

    private String normalizeEntityType(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return entityType;
        }
        return switch (entityType.trim().toLowerCase()) {
            case "person" -> "Person";
            case "organization", "org" -> "Organization";
            case "event" -> "Event";
            case "location" -> "Location";
            case "socialaccount", "social_account", "account" -> "SocialAccount";
            case "mediacontent", "media_content", "content" -> "MediaContent";
            default -> entityType.trim();
        };
    }
}

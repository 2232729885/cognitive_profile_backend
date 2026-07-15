package com.idata.profile.infra.elasticsearch;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaContentEsService {

    private static final String MEDIA_CONTENTS_INDEX = "media_contents_index";

    private final ElasticsearchClient esClient;

    /**
     * 确保索引存在并应用正确的 mapping（含 IK 分词器）。
     * 应用启动时调用一次，索引已存在时跳过。
     */
    public void ensureIndex() {
        try {
            boolean exists = esClient.indices().exists(e -> e.index(MEDIA_CONTENTS_INDEX)).value();
            if (exists) {
                log.info("ES index already exists: {}", MEDIA_CONTENTS_INDEX);
                return;
            }

            esClient.indices().create(c -> c
                    .index(MEDIA_CONTENTS_INDEX)
                    .settings(s -> s
                            .analysis(a -> a
                                    .analyzer("ik_max_word_analyzer", an -> an
                                            .custom(cu -> cu
                                                    .tokenizer("ik_max_word")
                                                    .filter(List.of("lowercase"))))
                                    .analyzer("ik_smart_analyzer", an -> an
                                            .custom(cu -> cu
                                                    .tokenizer("ik_smart")
                                                    .filter(List.of("lowercase"))))))
                    .mappings(m -> m
                            .properties("body_text", p -> p
                                    .text(t -> t
                                            .analyzer("ik_max_word")
                                            .searchAnalyzer("ik_smart")))
                            .properties("title", p -> p
                                    .text(t -> t
                                            .analyzer("ik_max_word")
                                            .searchAnalyzer("ik_smart")))
                            .properties("summary", p -> p
                                    .text(t -> t
                                            .analyzer("ik_max_word")
                                            .searchAnalyzer("ik_smart")))
                            .properties("platform", p -> p.keyword(k -> k))
                            .properties("language", p -> p.keyword(k -> k))
                            .properties("hashtags", p -> p.keyword(k -> k))
                            .properties("topic_category", p -> p.keyword(k -> k))
                            .properties("topic_type", p -> p.keyword(k -> k))
                            .properties("sentiment_label", p -> p.keyword(k -> k))
                            .properties("stance_label", p -> p.keyword(k -> k))
                            .properties("aigc_type", p -> p.keyword(k -> k))
                            .properties("ideology_label", p -> p.keyword(k -> k))
                            .properties("language_style_labels", p -> p.keyword(k -> k))
                            .properties("manipulation_methods", p -> p.keyword(k -> k))
                            .properties("risk_label", p -> p.keyword(k -> k))
                            .properties("risk_types", p -> p.keyword(k -> k))
                            .properties("published_at", p -> p
                                    .date(d -> d.format("strict_date_optional_time||epoch_millis")))));
            log.info("ES index created with IK analyzer: {}", MEDIA_CONTENTS_INDEX);
        } catch (IOException e) {
            log.error("Failed to ensure ES index: {}", MEDIA_CONTENTS_INDEX, e);
        }
    }

    public void index(String contentId, Object document) {
        try {
            IndexResponse response = esClient.index(i -> i
                    .index(MEDIA_CONTENTS_INDEX)
                    .id(contentId)
                    .document(document));
            log.debug("Indexed media content to Elasticsearch, contentId={}, result={}",
                    contentId, response.result());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to index media content to Elasticsearch: " + contentId, e);
        }
    }

    /**
     * 关键词全文检索 body_text 和 title，返回匹配的 content_id 列表（按相关性排序）
     * platform 和 language 为 null 时不作为过滤条件
     */
    public List<String> searchByKeyword(String keyword, String platform,
                                        String language, int size) {
        if (esClient == null || !hasText(keyword) || size <= 0) {
            return List.of();
        }

        try {
            SearchResponse<Map> response = esClient.search(s -> s
                            .index(MEDIA_CONTENTS_INDEX)
                            .size(size)
                            .query(q -> q.bool(b -> {
                                b.must(m -> m.multiMatch(mm -> mm
                                        .query(keyword)
                                        .fields("body_text", "title")));
                                if (hasText(platform)) {
                                    b.filter(f -> f.term(t -> t.field("platform").value(platform)));
                                }
                                if (hasText(language)) {
                                    b.filter(f -> f.term(t -> t.field("language").value(language)));
                                }
                                return b;
                            })),
                    Map.class);
            return response.hits().hits().stream()
                    .map(Hit::id)
                    .toList();
        } catch (ElasticsearchException e) {
            if (isIndexNotFound(e)) {
                return List.of();
            }
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to search media content by keyword: " + keyword, e);
        }
    }

    public List<EsSearchResult> searchByKeywordWithHighlight(String keyword, String platform,
                                                             String language, int size) {
        if (esClient == null || !hasText(keyword) || size <= 0) {
            return List.of();
        }

        try {
            SearchResponse<Map> response = esClient.search(s -> s
                            .index(MEDIA_CONTENTS_INDEX)
                            .size(size)
                            .query(q -> q.bool(b -> {
                                b.must(m -> m.multiMatch(mm -> mm
                                        .query(keyword)
                                        .fields("body_text", "title")));
                                if (hasText(platform)) {
                                    b.filter(f -> f.term(t -> t.field("platform").value(platform)));
                                }
                                if (hasText(language)) {
                                    b.filter(f -> f.term(t -> t.field("language").value(language)));
                                }
                                return b;
                            }))
                            .highlight(h -> h
                                    .fields("body_text", f -> f
                                            .numberOfFragments(3)
                                            .fragmentSize(150)
                                            .preTags("<em class=\"highlight\">")
                                            .postTags("</em>"))
                                    .fields("title", f -> f
                                            .numberOfFragments(1)
                                            .preTags("<em class=\"highlight\">")
                                            .postTags("</em>"))),
                    Map.class);

            return response.hits().hits().stream()
                    .map(hit -> new EsSearchResult(hit.id(), hit.highlight()))
                    .toList();
        } catch (ElasticsearchException e) {
            if (isIndexNotFound(e)) {
                return List.of();
            }
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to search media content by keyword: " + keyword, e);
        }
    }

    /**
     * 按 hashtag 精确检索，返回包含该 hashtag 的内容 ID 列表
     */
    public List<String> searchByHashtag(String hashtag, int size) {
        if (esClient == null || !hasText(hashtag) || size <= 0) {
            return List.of();
        }

        try {
            SearchResponse<Map> response = esClient.search(s -> s
                            .index(MEDIA_CONTENTS_INDEX)
                            .size(size)
                            .query(q -> q.term(t -> t
                                    .field("hashtags")
                                    .value(hashtag))),
                    Map.class);
            return response.hits().hits().stream()
                    .map(Hit::id)
                    .toList();
        } catch (ElasticsearchException e) {
            if (isIndexNotFound(e)) {
                return List.of();
            }
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to search media content by hashtag: " + hashtag, e);
        }
    }

    private boolean isIndexNotFound(ElasticsearchException e) {
        return e != null && e.error() != null && "index_not_found_exception".equals(e.error().type());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @Data
    @AllArgsConstructor
    public static class EsSearchResult {
        private String contentId;
        /**
         * key: 字段名（body_text/title），value: 高亮 HTML 片段列表
         */
        private Map<String, List<String>> highlights;
    }
}

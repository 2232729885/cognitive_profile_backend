package com.idata.profile.infra.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaContentEsService {

    private static final String MEDIA_CONTENTS_INDEX = "media_contents_index";
    private static final Set<String> ENGLISH_STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in",
            "is", "it", "its", "of", "on", "or", "that", "the", "than", "this", "to",
            "was", "were", "with", "another", "somehow");

    private final ElasticsearchClient esClient;

    /**
     * 确保索引存在并应用正确的 mapping（含 IK 分词器）。
     * 应用启动时调用一次，索引已存在时跳过。
     */
    public void ensureIndex() {
        try {
            boolean exists = esClient.indices().exists(e -> e.index(MEDIA_CONTENTS_INDEX)).value();
            if (exists) {
                ensureTranslationFields();
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
                            .properties("translated_title", p -> p
                                    .text(t -> t
                                            .analyzer("standard")
                                            .searchAnalyzer("standard")))
                            .properties("translated_body_text", p -> p
                                    .text(t -> t
                                            .analyzer("standard")
                                            .searchAnalyzer("standard")))
                            .properties("translated_summary", p -> p
                                    .text(t -> t
                                            .analyzer("standard")
                                            .searchAnalyzer("standard")))
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

    private void ensureTranslationFields() {
        try {
            esClient.indices().putMapping(m -> m
                    .index(MEDIA_CONTENTS_INDEX)
                    .properties("translated_title", p -> p
                            .text(t -> t.analyzer("standard").searchAnalyzer("standard")))
                    .properties("translated_body_text", p -> p
                            .text(t -> t.analyzer("standard").searchAnalyzer("standard")))
                    .properties("translated_summary", p -> p
                            .text(t -> t.analyzer("standard").searchAnalyzer("standard"))));
        } catch (Exception e) {
            log.warn("Failed to ensure ES translation fields, index={}", MEDIA_CONTENTS_INDEX, e);
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
     * 关键词全文检索 body_text 和 title，返回匹配的 content_id、高亮片段和 ES 相关性得分（按相关性排序）
     * platform 和 language 为 null 时不作为过滤条件
     */
    public List<EsSearchResult> searchByKeywordWithHighlight(String keyword, String platform,
                                                             String language, int size) {
        if (esClient == null || !hasText(keyword) || size <= 0) {
            return List.of();
        }

        try {
            String normalizedKeyword = keyword.trim();
            String effectiveKeyword = effectiveKeyword(normalizedKeyword);
            SearchResponse<Map> response = esClient.search(s -> s
                            .index(MEDIA_CONTENTS_INDEX)
                            .size(size)
                            .query(q -> q.bool(b -> {
                                b.should(sh -> sh.matchPhrase(mp -> mp
                                        .field("title")
                                        .query(normalizedKeyword)
                                        .boost(14F)));
                                b.should(sh -> sh.matchPhrase(mp -> mp
                                        .field("body_text")
                                        .query(normalizedKeyword)
                                        .boost(12F)));
                                b.should(sh -> sh.matchPhrase(mp -> mp
                                        .field("summary")
                                        .query(normalizedKeyword)
                                        .boost(10F)));
                                b.should(sh -> sh.matchPhrase(mp -> mp
                                        .field("translated_title")
                                        .query(normalizedKeyword)
                                        .boost(12F)));
                                b.should(sh -> sh.matchPhrase(mp -> mp
                                        .field("translated_body_text")
                                        .query(normalizedKeyword)
                                        .boost(8F)));
                                b.should(sh -> sh.matchPhrase(mp -> mp
                                        .field("translated_summary")
                                        .query(normalizedKeyword)
                                        .boost(8F)));
                                b.should(sh -> sh.multiMatch(mm -> mm
                                        .query(effectiveKeyword)
                                        .fields("title^5", "body_text^3", "summary^2",
                                                "translated_title^4", "translated_body_text^2", "translated_summary^2")
                                        .minimumShouldMatch("70%")
                                        .boost(4F)));
                                b.should(sh -> sh.multiMatch(mm -> mm
                                        .query(effectiveKeyword)
                                        .fields("title^3", "body_text", "summary",
                                                "translated_title^3", "translated_body_text", "translated_summary")
                                        .minimumShouldMatch("40%")
                                        .boost(1F)));
                                b.minimumShouldMatch("1");
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
                                            .postTags("</em>"))
                                    .fields("translated_body_text", f -> f
                                            .numberOfFragments(2)
                                            .fragmentSize(150)
                                            .preTags("<em class=\"highlight\">")
                                            .postTags("</em>"))
                                    .fields("translated_title", f -> f
                                            .numberOfFragments(1)
                                            .preTags("<em class=\"highlight\">")
                                            .postTags("</em>"))),
                    Map.class);

            return response.hits().hits().stream()
                    .map(hit -> new EsSearchResult(hit.id(), hit.highlight(), hit.score()))
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

    private String effectiveKeyword(String keyword) {
        if (!hasText(keyword)) {
            return keyword;
        }
        String normalized = keyword.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        if (!hasText(normalized)) {
            return keyword;
        }
        List<String> tokens = Arrays.stream(normalized.split(" "))
                .filter(this::hasText)
                .filter(token -> token.length() > 1)
                .filter(token -> !ENGLISH_STOP_WORDS.contains(token))
                .distinct()
                .toList();
        return tokens.size() >= 2 ? String.join(" ", tokens) : keyword;
    }

    @Data
    @AllArgsConstructor
    public static class EsSearchResult {
        private String contentId;
        /**
         * key: 字段名（body_text/title），value: 高亮 HTML 片段列表
         */
        private Map<String, List<String>> highlights;
        /**
         * ES 原始相关性得分（BM25），用于前端展示排序依据，跟不同来源（Milvus/Neo4j）的得分不是同一个量纲，
         * 不要跨渠道直接比较数值大小
         */
        private Double score;
    }
}

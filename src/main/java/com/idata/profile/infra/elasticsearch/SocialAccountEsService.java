package com.idata.profile.infra.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.idata.profile.entity.account.SocialAccount;
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
public class SocialAccountEsService {

    private static final String ACCOUNT_INDEX = "social_accounts_index";

    private final ElasticsearchClient esClient;

    /**
     * 确保索引存在并应用正确的 mapping（含 IK 分词器）。
     * 应用启动时调用一次，索引已存在时跳过。
     */
    public void ensureIndex() {
        try {
            boolean exists = esClient.indices().exists(e -> e.index(ACCOUNT_INDEX)).value();
            if (exists) {
                log.info("ES index already exists: {}", ACCOUNT_INDEX);
                return;
            }

            esClient.indices().create(c -> c
                    .index(ACCOUNT_INDEX)
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
                            .properties("handle", p -> p
                                    .text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                            .properties("display_name", p -> p
                                    .text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                            .properties("bio", p -> p
                                    .text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                            .properties("platform", p -> p.keyword(k -> k))
                            .properties("account_entity_type", p -> p.keyword(k -> k))
                            .properties("account_type", p -> p.keyword(k -> k))
                            .properties("verified", p -> p.boolean_(b -> b))
                            .properties("is_suspended", p -> p.boolean_(b -> b))
                            .properties("followers_count", p -> p.long_(l -> l))));
            log.info("ES index created with IK analyzer: {}", ACCOUNT_INDEX);
        } catch (IOException e) {
            log.error("Failed to ensure ES index: {}", ACCOUNT_INDEX, e);
        }
    }

    /**
     * 写入/更新账号到 ES。account_type 是 T1 annotate_account_type 判断出来的分类结果，
     * 可能为 null（还没标注完成或者标注失败），null 也照常写入，不阻塞。
     */
    public void indexAccount(SocialAccount account) {
        if (account == null || account.getId() == null) {
            return;
        }
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("platform", account.getPlatform());
            doc.put("handle", account.getHandle());
            doc.put("display_name", account.getDisplayName());
            doc.put("bio", account.getBio());
            doc.put("account_entity_type", account.getAccountEntityType());
            doc.put("account_type", account.getAccountType());
            doc.put("verified", account.getVerified());
            doc.put("is_suspended", account.getIsSuspended());
            doc.put("followers_count", account.getFollowersCount());

            esClient.index(i -> i
                    .index(ACCOUNT_INDEX)
                    .id(account.getId().toString())
                    .document(doc));
            log.debug("Indexed social account to ES, accountId={}, platform={}",
                    account.getId(), account.getPlatform());
        } catch (IOException e) {
            log.warn("Failed to index social account to ES, accountId={}", account.getId(), e);
        }
    }

    /**
     * 关键词全文检索 handle/display_name/bio，platform/accountType 为 null 时不作为过滤条件，
     * 返回匹配的 account_id 列表（按相关性排序）。
     */
    public List<String> searchByKeyword(String keyword, String platform, String accountType, int size) {
        if (esClient == null || !hasText(keyword) || size <= 0) {
            return List.of();
        }

        try {
            SearchResponse<Map> response = esClient.search(s -> s
                            .index(ACCOUNT_INDEX)
                            .size(size)
                            .query(q -> q.bool(b -> {
                                b.must(m -> m.multiMatch(mm -> mm
                                        .query(keyword)
                                        .fields("handle^2", "display_name^2", "bio")));
                                if (hasText(platform)) {
                                    b.filter(f -> f.term(t -> t.field("platform").value(platform)));
                                }
                                if (hasText(accountType)) {
                                    b.filter(f -> f.term(t -> t.field("account_type").value(accountType)));
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
            throw new IllegalStateException("Failed to search social accounts by keyword: " + keyword, e);
        }
    }

    public List<EsAccountSearchResult> searchByKeywordWithScore(String keyword, String platform,
                                                                 String accountType, int size) {
        if (esClient == null || !hasText(keyword) || size <= 0) {
            return List.of();
        }

        try {
            SearchResponse<Map> response = esClient.search(s -> s
                            .index(ACCOUNT_INDEX)
                            .size(size)
                            .query(q -> q.bool(b -> {
                                b.must(m -> m.multiMatch(mm -> mm
                                        .query(keyword)
                                        .fields("handle^2", "display_name^2", "bio")));
                                if (hasText(platform)) {
                                    b.filter(f -> f.term(t -> t.field("platform").value(platform)));
                                }
                                if (hasText(accountType)) {
                                    b.filter(f -> f.term(t -> t.field("account_type").value(accountType)));
                                }
                                return b;
                            })),
                    Map.class);
            return response.hits().hits().stream()
                    .map(hit -> new EsAccountSearchResult(hit.id(), hit.score()))
                    .toList();
        } catch (ElasticsearchException e) {
            if (isIndexNotFound(e)) {
                return List.of();
            }
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to search social accounts by keyword: " + keyword, e);
        }
    }

    private boolean isIndexNotFound(ElasticsearchException e) {
        return e != null && e.error() != null && "index_not_found_exception".equals(e.error().type());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public record EsAccountSearchResult(String accountId, Double score) {
    }
}

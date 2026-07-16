package com.idata.profile.infra.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.idata.profile.entity.content.MediaAsset;
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
public class MediaAssetEsService {

    private static final String MEDIA_ASSETS_INDEX = "media_assets_index";

    private final ElasticsearchClient esClient;

    public void ensureIndex() {
        if (esClient == null) {
            return;
        }
        try {
            boolean exists = esClient.indices().exists(e -> e.index(MEDIA_ASSETS_INDEX)).value();
            if (exists) {
                log.info("ES index already exists: {}", MEDIA_ASSETS_INDEX);
                return;
            }

            esClient.indices().create(c -> c
                    .index(MEDIA_ASSETS_INDEX)
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
                            .properties("ocr_text", p -> p
                                    .text(t -> t
                                            .analyzer("ik_max_word")
                                            .searchAnalyzer("ik_smart")))
                            .properties("source_asset_id", p -> p.keyword(k -> k))
                            .properties("asset_type", p -> p.keyword(k -> k))
                            .properties("content_id", p -> p.keyword(k -> k))
                            .properties("source_url", p -> p.keyword(k -> k))
                            .properties("storage_uri", p -> p.keyword(k -> k))
                            .properties("mime_type", p -> p.keyword(k -> k))
                            .properties("minio_bucket", p -> p.keyword(k -> k))
                            .properties("minio_key", p -> p.keyword(k -> k))
                            .properties("created_at", p -> p
                                    .date(d -> d.format("strict_date_optional_time||epoch_millis")))));
            log.info("ES index created with IK analyzer: {}", MEDIA_ASSETS_INDEX);
        } catch (IOException e) {
            log.error("Failed to ensure ES index: {}", MEDIA_ASSETS_INDEX, e);
        }
    }

    public void indexImageAsset(MediaAsset asset) {
        if (esClient == null || asset == null || asset.getId() == null
                || !"image".equalsIgnoreCase(asset.getAssetType())) {
            return;
        }
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("source_asset_id", asset.getSourceAssetId());
            doc.put("asset_type", asset.getAssetType());
            doc.put("content_id", asset.getContentId() != null ? asset.getContentId().toString() : null);
            doc.put("source_url", asset.getSourceUrl());
            doc.put("storage_uri", asset.getStorageUri());
            doc.put("mime_type", asset.getMimeType());
            doc.put("ocr_text", asset.getOcrText());
            doc.put("minio_bucket", asset.getMinioBucket());
            doc.put("minio_key", asset.getMinioKey());
            doc.put("width", asset.getWidth());
            doc.put("height", asset.getHeight());
            doc.put("created_at", asset.getCreatedAt() != null ? asset.getCreatedAt().toString() : null);

            esClient.index(i -> i
                    .index(MEDIA_ASSETS_INDEX)
                    .id(asset.getId().toString())
                    .document(doc));
            log.debug("Indexed image asset to ES, assetId={}", asset.getId());
        } catch (IOException e) {
            log.warn("Failed to index image asset to ES, assetId={}", asset.getId(), e);
        }
    }

    public List<EsImageAssetSearchResult> searchImagesByKeyword(String keyword, int size) {
        if (esClient == null || !hasText(keyword) || size <= 0) {
            return List.of();
        }

        try {
            SearchResponse<Map> response = esClient.search(s -> s
                            .index(MEDIA_ASSETS_INDEX)
                            .size(size)
                            .query(q -> q.bool(b -> b
                                    .filter(f -> f.term(t -> t.field("asset_type").value("image")))
                                    .must(m -> m.match(mm -> mm
                                            .field("ocr_text")
                                            .query(keyword))))),
                    Map.class);
            return response.hits().hits().stream()
                    .map(hit -> new EsImageAssetSearchResult(hit.id(), hit.score()))
                    .toList();
        } catch (ElasticsearchException e) {
            if (isIndexNotFound(e)) {
                return List.of();
            }
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to search image assets by keyword: " + keyword, e);
        }
    }

    private boolean isIndexNotFound(ElasticsearchException e) {
        return e != null && e.error() != null && "index_not_found_exception".equals(e.error().type());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public record EsImageAssetSearchResult(String assetId, Double score) {
    }
}

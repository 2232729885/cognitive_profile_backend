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
                            .properties("asr_text", p -> p
                                    .text(t -> t
                                            .analyzer("ik_max_word")
                                            .searchAnalyzer("ik_smart")))
                            .properties("caption_text", p -> p
                                    .text(t -> t
                                            .analyzer("ik_max_word")
                                            .searchAnalyzer("ik_smart")))
                            .properties("asset_id", p -> p.keyword(k -> k))
                            .properties("segment_id", p -> p.keyword(k -> k))
                            .properties("source_asset_id", p -> p.keyword(k -> k))
                            .properties("asset_type", p -> p.keyword(k -> k))
                            .properties("media_type", p -> p.keyword(k -> k))
                            .properties("content_id", p -> p.keyword(k -> k))
                            .properties("source_url", p -> p.keyword(k -> k))
                            .properties("storage_uri", p -> p.keyword(k -> k))
                            .properties("mime_type", p -> p.keyword(k -> k))
                            .properties("minio_bucket", p -> p.keyword(k -> k))
                            .properties("minio_key", p -> p.keyword(k -> k))
                            .properties("segment_start", p -> p.float_(f -> f))
                            .properties("segment_end", p -> p.float_(f -> f))
                            .properties("created_at", p -> p
                                    .date(d -> d.format("strict_date_optional_time||epoch_millis")))));
            log.info("ES index created with IK analyzer: {}", MEDIA_ASSETS_INDEX);
        } catch (IOException e) {
            log.error("Failed to ensure ES index: {}", MEDIA_ASSETS_INDEX, e);
        }
    }

    public void indexImageAsset(MediaAsset asset) {
        indexAsset(asset);
    }

    public void indexAsset(MediaAsset asset) {
        indexAssetSegment(asset, null, null, null, buildCaptionText(asset));
    }

    public void indexAssetSegment(MediaAsset asset, String segmentId,
                                  Float segmentStart, Float segmentEnd,
                                  String captionText) {
        if (esClient == null || asset == null || asset.getId() == null) {
            return;
        }
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("asset_id", asset.getId().toString());
            doc.put("segment_id", segmentId);
            doc.put("source_asset_id", asset.getSourceAssetId());
            doc.put("asset_type", asset.getAssetType());
            doc.put("media_type", asset.getAssetType());
            doc.put("content_id", asset.getContentId() != null ? asset.getContentId().toString() : null);
            doc.put("source_url", asset.getSourceUrl());
            doc.put("storage_uri", asset.getStorageUri());
            doc.put("mime_type", asset.getMimeType());
            doc.put("ocr_text", asset.getOcrText());
            doc.put("asr_text", asset.getAsrText());
            doc.put("caption_text", captionText);
            doc.put("minio_bucket", asset.getMinioBucket());
            doc.put("minio_key", asset.getMinioKey());
            doc.put("width", asset.getWidth());
            doc.put("height", asset.getHeight());
            doc.put("segment_start", segmentStart);
            doc.put("segment_end", segmentEnd);
            doc.put("created_at", asset.getCreatedAt() != null ? asset.getCreatedAt().toString() : null);

            esClient.index(i -> i
                    .index(MEDIA_ASSETS_INDEX)
                    .id(documentId(asset, segmentId))
                    .document(doc));
            log.debug("Indexed media asset to ES, assetId={}", asset.getId());
        } catch (IOException e) {
            log.warn("Failed to index media asset to ES, assetId={}", asset.getId(), e);
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
                                    .filter(f -> f.bool(bb -> bb
                                            .should(sh -> sh.term(t -> t.field("media_type").value("image")))
                                            .should(sh -> sh.term(t -> t.field("media_type").value("video")))
                                            .should(sh -> sh.term(t -> t.field("media_type").value("audio")))
                                            .should(sh -> sh.term(t -> t.field("asset_type").value("image")))
                                            .should(sh -> sh.term(t -> t.field("asset_type").value("video")))
                                            .should(sh -> sh.term(t -> t.field("asset_type").value("audio")))
                                            .minimumShouldMatch("1")))
                                    .must(m -> m.multiMatch(mm -> mm
                                            .query(keyword)
                                            .fields("ocr_text", "asr_text", "caption_text")))))
                            .source(src -> src.filter(f -> f.includes("asset_id", "segment_id",
                                    "source_asset_id", "media_type", "asset_type", "content_id",
                                    "source_url", "storage_uri", "mime_type", "minio_bucket", "minio_key",
                                    "segment_start", "segment_end", "caption_text"))),
                    Map.class);
            return response.hits().hits().stream()
                    .map(hit -> {
                        Map source = hit.source();
                        Object assetId = source == null ? null : source.get("asset_id");
                        Object segmentId = source == null ? null : source.get("segment_id");
                        Object contentId = source == null ? null : source.get("content_id");
                        Object mediaType = source == null ? null : firstNonNull(
                                source.get("media_type"), source.get("asset_type"));
                        Object segmentStart = source == null ? null : source.get("segment_start");
                        Object segmentEnd = source == null ? null : source.get("segment_end");
                        return new EsImageAssetSearchResult(
                                assetId == null ? hit.id() : assetId.toString(),
                                segmentId == null ? null : segmentId.toString(),
                                contentId == null ? null : contentId.toString(),
                                mediaType == null ? null : mediaType.toString(),
                                toFloat(segmentStart),
                                toFloat(segmentEnd),
                                hit.score());
                    })
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

    private String documentId(MediaAsset asset, String segmentId) {
        if (!hasText(segmentId)) {
            return asset.getId().toString();
        }
        return asset.getId() + "_" + segmentId;
    }

    @SuppressWarnings("deprecation")
    private String buildCaptionText(MediaAsset asset) {
        if (asset == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        appendCaptionPart(text, asset.getSceneLabel());
        appendCaptionPart(text, asset.getObjectAnnotations());
        return text.isEmpty() ? null : text.toString();
    }

    private void appendCaptionPart(StringBuilder text, String value) {
        if (!hasText(value)) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(value.trim());
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private Float toFloat(Object value) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Float.parseFloat(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record EsImageAssetSearchResult(String assetId, String segmentId, String contentId,
                                           String mediaType, Float segmentStart, Float segmentEnd,
                                           Double score) {
    }
}

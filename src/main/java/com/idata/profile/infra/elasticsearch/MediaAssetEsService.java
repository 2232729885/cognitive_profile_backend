package com.idata.profile.infra.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.idata.profile.entity.content.MediaAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaAssetEsService {

    private static final String MEDIA_ASSETS_INDEX = "media_assets_index";
    private static final Set<String> ENGLISH_STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in",
            "is", "it", "its", "of", "on", "or", "that", "the", "than", "this", "to",
            "was", "were", "with", "another", "somehow");

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
        indexAssetSegment(asset, null, null, null,
                firstText(asset != null ? asset.getCaptionText() : null, buildCaptionText(asset)));
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
        return searchMediaByKeyword(keyword, size, "image");
    }

    public List<EsImageAssetSearchResult> searchMediaByKeyword(String keyword, int size, String mediaType) {
        if (esClient == null || !hasText(keyword) || size <= 0) {
            return List.of();
        }

        try {
            String normalizedKeyword = keyword.trim();
            String effectiveKeyword = effectiveKeyword(normalizedKeyword);
            String normalizedMediaType = normalizeMediaType(mediaType);
            SearchResponse<Map> response = esClient.search(s -> s
                            .index(MEDIA_ASSETS_INDEX)
                            .size(size)
                            .query(q -> q.bool(b -> {
                                b.filter(f -> f.bool(bb -> {
                                    if (hasText(normalizedMediaType)) {
                                        bb.should(sh -> sh.term(t -> t.field("media_type").value(normalizedMediaType)))
                                                .should(sh -> sh.term(t -> t.field("asset_type").value(normalizedMediaType)));
                                    } else {
                                        bb.should(sh -> sh.term(t -> t.field("media_type").value("image")))
                                                .should(sh -> sh.term(t -> t.field("media_type").value("video")))
                                                .should(sh -> sh.term(t -> t.field("media_type").value("audio")))
                                                .should(sh -> sh.term(t -> t.field("asset_type").value("image")))
                                                .should(sh -> sh.term(t -> t.field("asset_type").value("video")))
                                                .should(sh -> sh.term(t -> t.field("asset_type").value("audio")));
                                    }
                                    return bb.minimumShouldMatch("1");
                                }));
                                return b.should(sh -> sh.matchPhrase(mp -> mp
                                                .field("ocr_text")
                                                .query(normalizedKeyword)
                                                .boost(12F)
                                                .queryName("ocr_text")))
                                        .should(sh -> sh.matchPhrase(mp -> mp
                                                .field("asr_text")
                                                .query(normalizedKeyword)
                                                .boost(10F)
                                                .queryName("asr_text")))
                                        .should(sh -> sh.matchPhrase(mp -> mp
                                                .field("caption_text")
                                                .query(normalizedKeyword)
                                                .boost(6F)
                                                .queryName("caption_text")))
                                        .should(sh -> sh.multiMatch(mm -> mm
                                                .query(effectiveKeyword)
                                                .fields("ocr_text^4", "asr_text^3", "caption_text^2")
                                                .minimumShouldMatch("70%")
                                                .boost(3F)
                                                .queryName("media_text")))
                                        .minimumShouldMatch("1");
                            }))
                            .source(src -> src.filter(f -> f.includes("asset_id", "segment_id",
                                    "source_asset_id", "media_type", "asset_type", "content_id",
                                    "source_url", "storage_uri", "mime_type", "minio_bucket", "minio_key",
                                    "segment_start", "segment_end", "caption_text")))
                            .highlight(h -> h
                                    .fields("ocr_text", hf -> hf)
                                    .fields("asr_text", hf -> hf)
                                    .fields("caption_text", hf -> hf)),
                    Map.class);
            return response.hits().hits().stream()
                    .map(hit -> {
                        Map source = hit.source();
                        Object assetId = source == null ? null : source.get("asset_id");
                        Object segmentId = source == null ? null : source.get("segment_id");
                        Object contentId = source == null ? null : source.get("content_id");
                        Object mediaTypeValue = source == null ? null : firstNonNull(
                                source.get("media_type"), source.get("asset_type"));
                        Object segmentStart = source == null ? null : source.get("segment_start");
                        Object segmentEnd = source == null ? null : source.get("segment_end");
                        return new EsImageAssetSearchResult(
                                assetId == null ? hit.id() : assetId.toString(),
                                segmentId == null ? null : segmentId.toString(),
                                contentId == null ? null : contentId.toString(),
                                mediaTypeValue == null ? null : mediaTypeValue.toString(),
                                toFloat(segmentStart),
                                toFloat(segmentEnd),
                                resolveHitField(hit.matchedQueries(), hit.highlight(), source),
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

    private String normalizeMediaType(String mediaType) {
        if (!hasText(mediaType)) {
            return null;
        }
        String normalized = mediaType.trim().toLowerCase();
        return switch (normalized) {
            case "image", "video", "audio" -> normalized;
            default -> null;
        };
    }

    private boolean isIndexNotFound(ElasticsearchException e) {
        return e != null && e.error() != null && "index_not_found_exception".equals(e.error().type());
    }

    private String resolveHitField(List<String> matchedQueries,
                                   Map<String, List<String>> highlights,
                                   Map source) {
        if (highlights != null && !highlights.isEmpty()) {
            if (highlights.containsKey("ocr_text")) {
                return "ocr_text";
            }
            if (highlights.containsKey("asr_text")) {
                return "asr_text";
            }
            if (highlights.containsKey("caption_text")) {
                return "caption_text";
            }
        }
        if (matchedQueries != null) {
            if (matchedQueries.contains("ocr_text")) {
                return "ocr_text";
            }
            if (matchedQueries.contains("asr_text")) {
                return "asr_text";
            }
            if (matchedQueries.contains("caption_text")) {
                return "caption_text";
            }
        }
        if (source != null && hasText(toString(source.get("caption_text")))) {
            return "caption_text";
        }
        if (source != null && hasText(toString(source.get("asr_text")))) {
            return "asr_text";
        }
        return "ocr_text";
    }

    private String toString(Object value) {
        return value == null ? null : value.toString();
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

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
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
                                           String hitField, Double score) {
    }
}

package com.idata.profile.common.util;

import com.idata.profile.agentproxy.dto.t1.T1AnnotateResponse;
import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageAnnotationUtil {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Neo4jGraphService neo4jGraphService;

    @Value("${minio.endpoint}")
    private String minioEndpoint;

    public String buildImageUrl(MediaAsset asset) {
        if (hasText(asset.getMinioBucket()) && hasText(asset.getMinioKey())) {
            return stripTrailingSlash(minioEndpoint) + "/" + asset.getMinioBucket() + "/" + asset.getMinioKey();
        }
        if (hasText(asset.getSourceUrl())) {
            return asset.getSourceUrl();
        }
        return null;
    }

    public void applyImageAnnotations(MediaAsset asset, T1AnnotateResponse response) {
        T1AnnotateResponse.Annotations annotations = response.getAnnotations();
        if (annotations == null) {
            return;
        }

        if (annotations.getTextOcr() != null) {
            asset.setOcrText(annotations.getTextOcr());
        }

        if (annotations.getAigcSuspicion() != null) {
            asset.setAigcScore(toAigcScore(annotations.getAigcSuspicion()));
        }

        if (annotations.getObjects() != null) {
            try {
                asset.setObjectAnnotations(OBJECT_MAPPER.writeValueAsString(annotations.getObjects()));
            } catch (JacksonException e) {
                log.warn("Failed to serialize objects annotation, assetId={}", asset.getId(), e);
            }
        }

        if (annotations.getScene() != null) {
            asset.setSceneLabel(annotations.getScene());
        }
    }

    public void updateNeo4jAnnotations(MediaAsset asset) {
        try {
            Map<String, Object> props = buildMediaAssetProperties(asset);
            props.put("source", "backend_structural");
            neo4jGraphService.mergeNode("MediaAsset", asset.getId().toString(), props);
            if (asset.getContentId() != null) {
                Map<String, Object> relationProps = new HashMap<>();
                putIfHasText(relationProps, "assetType", asset.getAssetType());
                relationProps.put("source", "backend_structural");
                neo4jGraphService.mergeRelation(
                        "MediaContent", asset.getContentId().toString(),
                        "MediaAsset", asset.getId().toString(),
                        "HAS_MEDIA",
                        relationProps);
            }
        } catch (Exception e) {
            log.warn("Failed to update MediaAsset Neo4j annotations, assetId={}", asset.getId(), e);
        }
    }

    private Map<String, Object> buildMediaAssetProperties(MediaAsset asset) {
        Map<String, Object> props = new HashMap<>();
        putIfHasText(props, "assetType", asset.getAssetType());
        putIfHasText(props, "mimeType", asset.getMimeType());
        putIfHasText(props, "sourceUrl", asset.getSourceUrl());
        putIfHasText(props, "minioBucket", asset.getMinioBucket());
        putIfHasText(props, "minioKey", asset.getMinioKey());
        putIfNotNull(props, "fileSizeBytes", asset.getFileSizeBytes());
        putIfNotNull(props, "width", asset.getWidth());
        putIfNotNull(props, "height", asset.getHeight());
        putIfNotNull(props, "durationSeconds", asset.getDurationSeconds());
        putIfHasText(props, "ocrText", asset.getOcrText());
        putIfHasText(props, "asrText", asset.getAsrText());
        putIfHasText(props, "sceneLabel", asset.getSceneLabel());
        putIfHasText(props, "objectAnnotations", asset.getObjectAnnotations());
        putIfNotNull(props, "aigcScore",
                asset.getAigcScore() != null ? asset.getAigcScore().doubleValue() : null);
        return props;
    }

    private BigDecimal toAigcScore(String aigcSuspicion) {
        if (aigcSuspicion == null) {
            return null;
        }
        return switch (aigcSuspicion.toLowerCase()) {
            case "none" -> BigDecimal.ZERO;
            case "low" -> new BigDecimal("0.2");
            case "medium" -> new BigDecimal("0.5");
            case "high" -> new BigDecimal("0.85");
            default -> null;
        };
    }

    private String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value);
        }
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}

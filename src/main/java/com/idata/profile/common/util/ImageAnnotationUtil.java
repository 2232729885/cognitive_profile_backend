package com.idata.profile.common.util;

import com.idata.profile.agentproxy.dto.t1.T1AnnotateResponse;
import com.idata.profile.entity.content.MediaAsset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

@Slf4j
@Component
public class ImageAnnotationUtil {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
}

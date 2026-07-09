package com.idata.profile.common.util;

import com.idata.profile.entity.content.MediaAsset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ImageAnnotationUtil {

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

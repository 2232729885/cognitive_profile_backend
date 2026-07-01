package com.idata.profile.ingestion.normalizer;

import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.ingestion.consumer.IngestionMessageSupport;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@Component
public class MediaAssetNormalizer {

    public MediaAsset normalize(Object kafkaMessage, RawRecord rawRecord) {
        JsonNode data = IngestionMessageSupport.data(kafkaMessage);

        MediaAsset asset = new MediaAsset();
        asset.setId(UUID.randomUUID());
        asset.setRawRecordId(rawRecord.getId());
        asset.setSourceAssetId(IngestionMessageSupport.text(data, "asset_id"));
        asset.setAssetType(readAssetType(data));
        asset.setSourceUrl(IngestionMessageSupport.text(data, "source_url"));
        asset.setStorageUri(IngestionMessageSupport.text(data, "storage_uri"));
        asset.setMimeType(IngestionMessageSupport.text(data, "mime_type"));
        asset.setSha256(IngestionMessageSupport.text(data, "sha256"));
        asset.setFileSizeBytes(readLong(data, "file_size_bytes"));
        asset.setWidth(readInteger(data, "width"));
        asset.setHeight(readInteger(data, "height"));
        asset.setDurationSeconds(readInteger(data, "duration_seconds"));
        asset.setThumbnailUri(IngestionMessageSupport.text(data, "thumbnail_uri"));
        asset.setOcrText(IngestionMessageSupport.text(data, "ocr_text"));
        asset.setAsrText(IngestionMessageSupport.text(data, "asr_text"));
        asset.setMinioBucket(IngestionMessageSupport.text(data, "minio_bucket"));
        asset.setMinioKey(IngestionMessageSupport.text(data, "minio_key"));

        return asset;
    }

    private String readAssetType(JsonNode data) {
        String assetType = IngestionMessageSupport.text(data, "asset_type");
        if (!IngestionMessageSupport.hasText(assetType)) {
            return null;
        }
        return switch (assetType) {
            case "image", "video", "audio", "thumbnail" -> assetType;
            default -> throw new IllegalArgumentException("Unsupported media asset_type: " + assetType);
        };
    }

    private Long readLong(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        String text = value.asText();
        if (!IngestionMessageSupport.hasText(text)) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer readInteger(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        String text = value.asText();
        if (!IngestionMessageSupport.hasText(text)) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

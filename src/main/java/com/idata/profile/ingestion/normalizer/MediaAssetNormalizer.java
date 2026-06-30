package com.idata.profile.ingestion.normalizer;

import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.entity.raw.RawRecord;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * media_asset 的Step3标准化映射逻辑。
 * sha256去重，见 mapper.content.MediaAssetMapper.insertIgnoreOnConflictSha256。
 */
@Component
public class MediaAssetNormalizer {

    public MediaAsset normalize(Object kafkaMessage, RawRecord rawRecord) {
        MediaAsset asset = new MediaAsset();
        asset.setId(UUID.randomUUID());
        asset.setRawRecordId(rawRecord.getId());
        // TODO: 从kafkaMessage.data提取：sourceAssetId, assetType, sourceUrl,
        //   storageUri, mimeType, sha256, width, height, durationSeconds,
        //   thumbnailUri, ocrText, asrText

        return asset;
    }
}

package com.idata.profile.entity.content;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@TableName("media_assets")
public class MediaAsset {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID rawRecordId;
    private UUID contentId;
    private String sourceAssetId;
    private String assetType;           // image | video | audio | thumbnail

    private String sourceUrl;
    private String storageUri;
    private String mimeType;
    private String sha256;
    private Long fileSizeBytes;
    private Integer width;
    private Integer height;
    private Integer durationSeconds;

    private String thumbnailUri;
    /** @deprecated 自T1多模态改造起不再写入，仅保留历史数据，完整标注结果见 MediaContent.t1Annotation。 */
    @Deprecated
    private String ocrText;
    private String asrText;
    private String captionText;
    /** @deprecated 自T1多模态改造起不再写入，仅保留历史数据，完整标注结果见 MediaContent.t1Annotation。 */
    @Deprecated
    private BigDecimal aigcScore;
    /** @deprecated 自T1多模态改造起不再写入，仅保留历史数据，完整标注结果见 MediaContent.t1Annotation。 */
    @Deprecated
    private String objectAnnotations;
    /** @deprecated 自T1多模态改造起不再写入，仅保留历史数据，完整标注结果见 MediaContent.t1Annotation。 */
    @Deprecated
    private String sceneLabel;
    private Boolean t1Annotated;
    private String minioBucket;
    private String minioKey;
    private String embeddingId;

    private OffsetDateTime createdAt;
}

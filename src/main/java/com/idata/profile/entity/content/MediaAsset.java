package com.idata.profile.entity.content;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * L1标准化层：多媒体附件。
 * 适用 record_type：media_asset（也可从 social_content.data.media[] 拆分）。
 * sha256去重，同一文件被多条内容引用时只存一份（INSERT ON CONFLICT(sha256) DO NOTHING）。
 *
 * 对应表：media_assets
 */
@Data
@TableName("media_assets")
public class MediaAsset {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID rawRecordId;
    private UUID contentId;
    private String sourceAssetId;       // 课题三原始asset_id，唯一可空
    private String assetType;           // image | video | audio | thumbnail

    private String sourceUrl;
    private String storageUri;          // 课题三对象存储路径 s3://kt3-media/...
    private String mimeType;
    private String sha256;              // 唯一可空，去重关键字段
    private Long fileSizeBytes;
    private Integer width;
    private Integer height;
    private Integer durationSeconds;

    private String thumbnailUri;
    private String ocrText;
    private String asrText;             // YouTube视频语音转写，分析重要输入

    private java.math.BigDecimal aigcScore;  // T1写入
    private String objectAnnotations;   // T1图像标注：物体检测结果JSON
    private String sceneLabel;          // T1图像标注：场景分类标签
    private Boolean t1Annotated;        // T1图像标注是否已完成
    private String minioBucket;
    private String minioKey;
    private String embeddingId;         // T4写入，对应Milvus image_embeddings向量ID

    private OffsetDateTime createdAt;
}

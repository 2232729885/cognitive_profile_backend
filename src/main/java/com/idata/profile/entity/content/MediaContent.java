package com.idata.profile.entity.content;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.idata.profile.infra.mybatis.JsonbStringTypeHandler;
import com.idata.profile.infra.mybatis.StringArrayTypeHandler;
import com.idata.profile.infra.mybatis.UuidArrayTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@TableName(value = "media_contents", autoResultMap = true)
public class MediaContent {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID rawRecordId;
    private String platform;
    private String contentType;
    private String platformContentId;
    private String authorPlatformUserId;
    private UUID authorAccountId;

    private String title;
    private String bodyText;
    private String language;
    private OffsetDateTime publishedAt;
    private String url;

    private String parentContentId;
    private String rootContentId;
    private String repostOfContentId;
    private String quotedContentId;

    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] hashtags;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] mentions;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] externalUrls;

    private Long likeCount;
    private Long commentCount;
    private Long shareCount;
    private Long repostCount;
    private Long quoteCount;
    private Long viewCount;
    private Long reactionCount;

    @TableField(typeHandler = UuidArrayTypeHandler.class)
    private UUID[] mediaAssetIds;

    private String newsSourceName;
    private String newsDomain;
    private String newsAuthor;
    private String newsSection;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] newsTags;

    private OffsetDateTime t1AnnotatedAt;
    private String t1ModelVersion;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String t1Annotation;

    // 以下字段自T1多模态改造起不再由代码写入，仅保留历史数据，完整标注结果见 t1Annotation 字段。
    @Deprecated
    private String topicCategory;
    @Deprecated
    private String topicSubcategory;
    @Deprecated
    private BigDecimal eventHeatScore;
    @Deprecated
    private String sentimentLabel;
    @Deprecated
    private BigDecimal sentimentScore;
    @Deprecated
    private String stanceLabel;
    @Deprecated
    private String stanceTarget;
    @Deprecated
    private BigDecimal aigcScore;
    @Deprecated
    private String aigcType;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    @Deprecated
    private String entitiesHint;
    @Deprecated
    private String narrativeHint;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    @Deprecated
    private String[] keywords;
    @Deprecated
    private String summary;
    @Deprecated
    private String ideologyLabel;
    @Deprecated
    private String ideologyIntensity;
    @Deprecated
    private String overallStance;
    @Deprecated
    private String coreStanceStrength;
    @Deprecated
    private String eventHeat;
    @Deprecated
    private String accountTypeHint;
    @Deprecated
    private String riskLevel;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    @Deprecated
    private String[] riskTypes;
    @Deprecated
    private String riskEvidence;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    @Deprecated
    private String[] languageStyleTags;
    @Deprecated
    private String sentimentPrimaryEmotion;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    @Deprecated
    private String[] emotionLabels;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    @Deprecated
    private String bendTactics;
    @Deprecated
    private String contentPurposePrimary;
    @Deprecated
    private String publicAttitudeGroup;
    @Deprecated
    private String publicAttitudeLabel;
    @Deprecated
    private String publicAttitudeIntensity;
    @Deprecated
    private String eventTypeLabel;
    @Deprecated
    private BigDecimal t1OverallConfidence;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    @Deprecated
    private String[] t1ReviewReasons;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    @Deprecated
    private String[] t1FailedModules;
    @Deprecated
    private Boolean needHumanReview;

    private String humanReviewStatus;
    private Boolean propagationSyncedToNeo4j;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

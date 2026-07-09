package com.idata.profile.entity.content;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.idata.profile.infra.mybatis.JsonbStringTypeHandler;
import com.idata.profile.infra.mybatis.StringArrayTypeHandler;
import com.idata.profile.infra.mybatis.UuidArrayTypeHandler;
import lombok.Data;

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
    private String topicCategory;
    private String topicSubcategory;
    private java.math.BigDecimal eventHeatScore;
    private String sentimentLabel;
    private java.math.BigDecimal sentimentScore;
    private String stanceLabel;
    private String stanceTarget;
    private java.math.BigDecimal aigcScore;
    private String aigcType;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String entitiesHint;
    private String narrativeHint;

    // ---- T1 标注接口规约 v1.1 新增字段 ----
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] keywords;
    private String summary;
    /** 维度1：意识形态标签 */
    private String ideologyLabel;
    /** 维度1：意识形态倾向强度 */
    private String ideologyIntensity;
    /** 维度2：内容级核心立场 */
    private String overallStance;
    /** 维度4：事件热度感知 */
    private String eventHeat;
    /** 维度5：账户类别提示 */
    private String accountTypeHint;
    /** 维度8：综合风险等级 */
    private String riskLevel;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] riskTypes;
    private String riskEvidence;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] languageStyleTags;
    /** 维度3：主要情绪类型 */
    private String sentimentPrimaryEmotion;
    /** 维度10：BEND 叙事操纵手法数组（JSONB） */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String bendTactics;

    // ---- T1 标注接口规约 v0.5 新增字段 ----
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String t1AnnotationV05;
    /** 维度2扩展：内容级立场强度 */
    private String coreStanceStrength;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] emotionLabels;
    /** 维度8：内容目的主分类 */
    private String contentPurposePrimary;
    /** @deprecated v0.5 修正版已去掉 public_attitude 维度，此字段不再写入，仅保留历史数据 */
    /** 维度4：民众/公众群体类型 */
    @Deprecated
    private String publicAttitudeGroup;
    /** @deprecated v0.5 修正版已去掉 public_attitude 维度，此字段不再写入，仅保留历史数据 */
    /** 维度4：民众态度标签 */
    @Deprecated
    private String publicAttitudeLabel;
    /** @deprecated v0.5 修正版已去掉 public_attitude 维度，此字段不再写入，仅保留历史数据 */
    /** 维度4：民众态度强度 */
    @Deprecated
    private String publicAttitudeIntensity;
    /** 维度15：事件类型 */
    private String eventTypeLabel;
    private java.math.BigDecimal t1OverallConfidence;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] t1ReviewReasons;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] t1FailedModules;

    private Boolean needHumanReview;
    private String humanReviewStatus;
    private Boolean propagationSyncedToNeo4j;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

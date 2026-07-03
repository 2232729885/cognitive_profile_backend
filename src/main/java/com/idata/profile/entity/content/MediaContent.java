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
    private Boolean needHumanReview;
    private String humanReviewStatus;
    private Boolean propagationSyncedToNeo4j;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

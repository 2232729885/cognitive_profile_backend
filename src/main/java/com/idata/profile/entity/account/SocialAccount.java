package com.idata.profile.entity.account;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * L1标准化层：账号/频道/群组最新状态。
 * 适用 record_type：social_account。
 * 按(platform, platformUserId)UPSERT，历史变化追加到 SocialAccountSnapshot。
 *
 * 对应表：social_accounts
 */
@Data
@TableName("social_accounts")
public class SocialAccount {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;     // 与Neo4j SocialAccount节点id保持一致

    private String platform;
    private String platformUserId;      // 联合唯一: platform+platformUserId
    private String accountEntityType;   // user|channel|page|group|community|forum_board|news_source
    private String platformNativeType;  // youtube_channel|telegram_group等

    private String handle;
    private String displayName;
    private String bio;
    private String avatarUrl;
    private String profileUrl;
    private String selfDeclaredLocation;
    private Boolean verified;
    private String verifiedType;        // none|blue|org|government|media
    private Boolean isSuspended;
    private OffsetDateTime accountCreatedAt;

    private Long followersCount;
    private Long followingCount;
    private Long subscriberCount;
    private Long memberCount;
    private Long postCount;
    private Long viewCount;

    private String accountType;         // T1标注: official|media|kol|normal|bot|cyborg
    private java.math.BigDecimal accountTypeConfidence;

    private UUID entityPersonId;        // EntityDeduplicationJob融合后回填
    private UUID entityOrgId;           // EntityDeduplicationJob融合后回填

    private OffsetDateTime latestSnapshotAt;
    private OffsetDateTime firstSeenAt;
    private OffsetDateTime lastActiveAt;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

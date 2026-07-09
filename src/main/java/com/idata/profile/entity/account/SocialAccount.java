package com.idata.profile.entity.account;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@TableName("social_accounts")
public class SocialAccount {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private String platform;
    private String platformUserId;
    private String accountEntityType;
    private String platformNativeType;

    private String handle;
    private String displayName;
    private String bio;
    private String avatarUrl;
    private String profileUrl;
    private String selfDeclaredLocation;
    private Boolean verified;
    private String verifiedType;
    private Boolean isSuspended;
    private OffsetDateTime accountCreatedAt;

    private Long followersCount;
    private Long followingCount;
    private Long subscriberCount;
    private Long memberCount;
    private Long postCount;
    private Long viewCount;

    private String accountType;
    private BigDecimal accountTypeConfidence;

    private UUID entityPersonId;
    private UUID entityOrgId;
    private OffsetDateTime identityResolvedAt;
    private OffsetDateTime latestSnapshotAt;
    private OffsetDateTime firstSeenAt;
    private OffsetDateTime lastActiveAt;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

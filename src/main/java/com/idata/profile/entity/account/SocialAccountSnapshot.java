package com.idata.profile.entity.account;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 账号历史快照，只追加不覆盖，不更新不删除。
 * 用于分析账号在特定时间段的特征变化（如叙事高峰期粉丝数是否异常增长）。
 *
 * 对应表：social_account_snapshots
 */
@Data
@TableName("social_account_snapshots")
public class SocialAccountSnapshot {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID accountId;
    private UUID rawRecordId;
    private OffsetDateTime snapshotAt;   // 课题三采集该快照的时间

    private String displayName;
    private String bio;
    private Boolean verified;
    private String verifiedType;
    private String selfDeclaredLocation;
    private Boolean isSuspended;

    private Long followersCount;
    private Long followingCount;
    private Long subscriberCount;
    private Long memberCount;
    private Long postCount;
    private Long viewCount;

    private String snapshotPayload;      // JSONB 完整快照备用
    private OffsetDateTime createdAt;
}

package com.idata.profile.entity.account;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * L1标准化层：账号间事实关系（关注/订阅/成员/管理员）。
 * 适用 record_type：account_relation（课题三v2.0新增，替代原interaction）。
 *
 * 入库时 fromAccountId/toAccountId 为null，由独立的回填批处理任务
 * （batch.relation.AccountRelationBackfillJob）按platform+platformUserId匹配后写入，
 * 不依赖T3，不阻塞入库主流程。
 *
 * syncedToNeo4j 标记是否已写入图谱，仅当两端UUID均回填完成才会置true，
 * 避免回填批处理重复扫描和重复写图谱。
 *
 * 对应表：account_relations
 */
@Data
@TableName("account_relations")
public class AccountRelation {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID rawRecordId;
    private String sourcePlatformUserId; // 关系源账号平台原始ID，入库时直接存字符串
    private String targetPlatformUserId;
    private String platform;

    private String relationType;        // 见 RelationType 枚举
    private OffsetDateTime observedAt;   // 课题三采集到这条关系的时间
    private OffsetDateTime occurredAt;   // 关系真实发生时间，可选
    private String source;              // following_list|follower_list|...
    private java.math.BigDecimal confidence; // 默认1.0，课题三直接交付的事实关系

    private UUID fromAccountId;          // 入库时为null，回填批处理写入
    private UUID toAccountId;            // 入库时为null，回填批处理写入
    private Boolean syncedToNeo4j;       // 默认false，两端UUID就位后才同步图谱

    private OffsetDateTime createdAt;
}

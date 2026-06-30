package com.idata.profile.entity.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.idata.profile.infra.mybatis.UuidArrayTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * T6目标识别任务。
 * 对应表：identification_tasks
 */
@Data
@TableName(value = "identification_tasks", autoResultMap = true)
public class IdentificationTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID workflowTaskId;
    private String triggerType;         // narrative|account_list|manual
    private UUID narrativeId;
    @TableField(typeHandler = UuidArrayTypeHandler.class)
    private UUID[] inputAccountIds;

    private String status;
    private Integer accountsAnalyzed;
    private Integer targetsIdentified;
    private Integer groupsDetected;

    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime createdAt;
}

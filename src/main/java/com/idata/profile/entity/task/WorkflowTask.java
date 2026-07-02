package com.idata.profile.entity.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.idata.profile.infra.mybatis.JsonbStringTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@TableName(value = "workflow_tasks", autoResultMap = true)
public class WorkflowTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID sessionId;
    private UUID userId;
    private String inputText;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String inputFiles;

    private String status;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String intentParsed;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String executionPlan;
    private Boolean isFallback;
    private String fallbackReason;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String steps;

    private String resultSummary;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String resultData;
    private Integer llmTokensUsed;
    private Integer totalDurationMs;
    private String errorMessage;

    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

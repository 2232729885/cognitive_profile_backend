package com.idata.profile.entity.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 分析线任务（第二条线，用户触发）。
 * 协调Agent（analysis.orchestrator.CoordinatorAgentService）解析意图生成执行计划，
 * isFallback标记LLM编排失败时是否降级为固定工作流（T4→T6→T5顺序执行）。
 *
 * 对应表：workflow_tasks
 */
@Data
@TableName("workflow_tasks")
public class WorkflowTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID sessionId;
    private UUID userId;
    private String inputText;
    private String inputFiles;          // JSONB

    private String status;              // PENDING|RUNNING|DONE|FAILED
    private String intentParsed;        // JSONB
    private String executionPlan;       // JSONB
    private Boolean isFallback;
    private String fallbackReason;
    private String steps;               // JSONB数组，各步执行记录

    private String resultSummary;       // LLM生成的最终中文分析结论
    private String resultData;          // JSONB
    private Integer llmTokensUsed;
    private Integer totalDurationMs;
    private String errorMessage;

    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

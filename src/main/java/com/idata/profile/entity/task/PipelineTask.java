package com.idata.profile.entity.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 入库流水线调度表。仅 social_content/news_article 会创建对应任务记录。
 * 触发方式：Step4写完表后，pipeline.PipelineExecutor 直接将taskId提交线程池异步执行。
 * T3和T4可并行触发（T4只依赖body_text，不依赖T3融合结果）。
 *
 * 对应表：pipeline_tasks
 */
@Data
@TableName("pipeline_tasks")
public class PipelineTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID rawRecordId;
    private UUID contentId;
    private String status;              // PENDING|RUNNING|DONE|FAILED

    private String t1Status;            // pending|running|done|failed
    private OffsetDateTime t1StartedAt;
    private OffsetDateTime t1DoneAt;
    private Integer t1DurationMs;

    private String t2Status;
    private OffsetDateTime t2StartedAt;
    private OffsetDateTime t2DoneAt;
    private Integer t2DurationMs;

    private String t3Status;
    private OffsetDateTime t3StartedAt;
    private OffsetDateTime t3DoneAt;
    private Integer t3DurationMs;

    private String t4Status;
    private OffsetDateTime t4StartedAt;
    private OffsetDateTime t4DoneAt;
    private Integer t4DurationMs;

    private Short retryCount;
    private Short maxRetries;           // 默认3
    private String errorStep;           // T1|T2|T3|T4
    private String errorMessage;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

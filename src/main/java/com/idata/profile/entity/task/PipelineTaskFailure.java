package com.idata.profile.entity.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pipeline task failure attempt history.
 */
@Data
@TableName("pipeline_task_failures")
public class PipelineTaskFailure {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID taskId;
    private UUID rawRecordId;
    private UUID contentId;

    private String failedStep;
    private Short attemptNo;
    private Short maxRetries;
    private Boolean willRetry;

    private String taskStatus;
    private String stepStatus;

    private String errorClass;
    private String errorMessage;
    private String rootErrorClass;
    private String rootErrorMessage;

    private OffsetDateTime createdAt;
}

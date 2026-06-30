package com.idata.profile.entity.system;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 批量文件导入任务。对应表：batch_import_tasks */
@Data
@TableName("batch_import_tasks")
public class BatchImportTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID userId;
    private String fileName;
    private String fileFormat;          // json|jsonl|csv|excel|zip
    private String minioKey;
    private Integer totalRecords;
    private Integer processedRecords;
    private Integer successRecords;
    private Integer failedRecords;
    private Integer duplicateRecords;
    private String status;              // PENDING|RUNNING|DONE|FAILED|PARTIAL

    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private String errorMessage;
    private OffsetDateTime createdAt;
}

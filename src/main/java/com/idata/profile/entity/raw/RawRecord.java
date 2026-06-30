package com.idata.profile.entity.raw;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.idata.profile.infra.mybatis.JsonbStringTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * L0原始采集层：Kafka或文件导入的原始消息。
 * 每条数据必须先落这里，一字不改，支持任意步骤失败后重跑。
 * t1_output/t2_output/t3_output/t4_output 存各步完整响应，单行可追溯全部处理过程。
 *
 * 对应表：raw_records（见 docs/init_db.sql）
 */
@Data
@TableName(value = "raw_records", autoResultMap = true)
public class RawRecord {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private String schemaVersion;      // 默认 kt3_to_kt4_v1
    private String recordType;         // 见 RecordType 枚举
    private String sourceRecordId;     // 课题三全局唯一原始记录ID
    private String crawlTaskId;
    private OffsetDateTime collectedAt;
    private String sourceUrl;
    private String payloadHash;        // SHA256(raw_payload)，去重用
    private String sourceType;         // kafka | file_upload
    private String sourceTopic;
    private String platform;
    private String language;
    private String contentType;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String rawPayload;         // JSONB，TypeHandler转换，见 infra 包内自定义Handler（待补充）
    private OffsetDateTime rawPublishedAt;
    private OffsetDateTime normalizedAt;

    private String pipelineStatus;     // 见 PipelineStatus 枚举
    private UUID pipelineTaskId;
    private String errorMessage;
    private Short retryCount;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String t1Output;           // JSONB
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String t2Output;           // JSONB
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String t3Output;           // JSONB
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String t4Output;           // JSONB

    private UUID batchImportId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;  // 由DB触发器自动维护，MyBatis-Plus不要手动SET
}

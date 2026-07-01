package com.idata.profile.entity.content;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.idata.profile.infra.mybatis.JsonbStringTypeHandler;
import com.idata.profile.infra.mybatis.StringArrayTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * L1标准化层：课题三采集任务元数据。
 * 适用 record_type：collection_task。
 * 验收时靠这张表核查数据覆盖范围（语种、平台、时间窗口）。
 *
 * 对应表：collection_tasks
 */
@Data
@TableName(value = "collection_tasks", autoResultMap = true)
public class CollectionTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID rawRecordId;
    private String crawlTaskId;         // 全局唯一

    private String collectionMethod;    // api | crawler | rss | manual_import
    private String seedType;            // keyword | account | url | topic | hashtag
    private String seedValue;
    private String queryExpression;
    private String platform;
    private OffsetDateTime timeWindowStart;
    private OffsetDateTime timeWindowEnd;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] targetLanguages;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] targetRegions;
    private String collectorVersion;
    private Integer recordsCollected;   // 验收核查关键字段

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String rawPayload;          // JSONB
    private OffsetDateTime createdAt;
}

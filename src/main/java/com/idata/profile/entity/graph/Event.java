package com.idata.profile.entity.graph;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * L2实体层（精简）：事件实体索引表，详细属性存Neo4j。
 * 设计说明同 Person，见该类注释。
 *
 * 对应表：events
 */
@Data
@TableName("events")
public class Event {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;   // 与Neo4j Event节点id一致

    private String canonicalName;
    private String eventType;           // election|military|diplomatic|protest|disaster|other
    private OffsetDateTime occurredAtStart;
    private OffsetDateTime occurredAtEnd;
    private String country;
    private java.math.BigDecimal importanceScore;
    private Integer contentCount;
    private String dedupStatus;
    private UUID[] mergeHistory;
    private String eventHeatLevel;
    private java.math.BigDecimal eventHeatScore;
    private java.math.BigDecimal eventHeatConfidence;
    private Integer eventRelatedContentCount;
    private OffsetDateTime eventHeatComputedAt;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

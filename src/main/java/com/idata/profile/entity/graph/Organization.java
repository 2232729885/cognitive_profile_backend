package com.idata.profile.entity.graph;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * L2实体层（精简）：组织实体索引表，详细属性存Neo4j。
 * 设计说明同 Person，见该类注释。
 *
 * 对应表：organizations
 */
@Data
@TableName("organizations")
public class Organization {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;   // 与Neo4j Organization节点id一致

    private String canonicalName;
    private String orgType;             // government|media|ngo|political_party|military|company|other
    private String country;
    private java.math.BigDecimal importanceScore;
    private Boolean isHighValue;
    private Integer contentCount;
    private UUID[] mergeHistory;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

package com.idata.profile.entity.graph;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * L2实体层（精简）：人物实体索引表。
 *
 * 重要：这是PG侧的精简版本，只存统计/索引/画像关联需要的核心字段。
 * 详细属性（别名/国籍/职业/组织关系等）存Neo4j（见 infra.neo4j 包下对应节点），
 * 两边通过相同的id（UUID）关联，不在PG重复存全量属性。
 *
 * T2写入/更新这张表（importanceScore, contentCount+1等统计字段），
 * T3做实体归一时记录mergeHistory，同时去写Neo4j的完整属性。
 *
 * 对应表：persons
 */
@Data
@TableName("persons")
public class Person {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;   // 与Neo4j Person节点id一致

    private String canonicalName;       // 标准化姓名，用于ES检索
    private java.math.BigDecimal importanceScore;  // 0-100，影响画像生成优先级
    private Boolean isHighValue;        // 高价值目标，定时任务优先生成画像
    private Integer contentCount;       // 出现在多少条内容中，T2识别时+1

    private OffsetDateTime firstSeenAt;
    private OffsetDateTime lastSeenAt;
    private UUID[] mergeHistory;        // T3归一时记录被合并的旧实体UUID（实体归一逻辑，非冲突覆盖）

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

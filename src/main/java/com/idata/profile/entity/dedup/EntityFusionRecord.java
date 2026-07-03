package com.idata.profile.entity.dedup;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.idata.profile.infra.mybatis.StringArrayTypeHandler;
import com.idata.profile.infra.mybatis.UuidArrayTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@TableName(value = "entity_fusion_records", autoResultMap = true)
public class EntityFusionRecord {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private String entityType;
    private UUID survivorId;
    private String survivorName;
    @TableField(typeHandler = UuidArrayTypeHandler.class)
    private UUID[] mergedIds;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] mergedNames;
    private Integer mergedCount;
    private String fusionMethod;
    private Integer contentCountBefore;
    private Integer contentCountAfter;
    private Boolean neo4jMerged;
    private UUID jobRunId;
    private OffsetDateTime createdAt;
}

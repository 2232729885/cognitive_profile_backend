package com.idata.profile.entity.graph;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.idata.profile.infra.mybatis.JsonbStringTypeHandler;
import com.idata.profile.infra.mybatis.UuidArrayTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@TableName(value = "narratives", autoResultMap = true)
public class Narrative {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private String canonicalLabel;
    private String frameType;
    private String lifecycleState;
    private Integer contentCount;
    private Integer accountCount;
    private BigDecimal importanceScore;
    private Boolean isActive;

    private OffsetDateTime firstDetectedAt;
    private OffsetDateTime peakAt;
    @TableField(typeHandler = UuidArrayTypeHandler.class)
    private UUID[] mergeHistory;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String claimAtoms;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

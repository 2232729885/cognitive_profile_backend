package com.idata.profile.entity.task;

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
@TableName(value = "identification_results", autoResultMap = true)
public class IdentificationResult {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID taskId;
    private String targetType;
    private String targetEntityType;
    private UUID targetEntityId;
    private BigDecimal confidence;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String bendDistribution;
    private String evidenceText;
    @TableField(typeHandler = UuidArrayTypeHandler.class)
    private UUID[] evidenceContentIds;

    private OffsetDateTime createdAt;
}

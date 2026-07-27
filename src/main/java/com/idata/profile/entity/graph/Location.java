package com.idata.profile.entity.graph;

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
@TableName(value = "locations", autoResultMap = true)
public class Location {

    @TableId(type = IdType.INPUT)
    private UUID id;

    private String canonicalName;
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] aliases;
    private String locationType;
    private String country;
    private java.math.BigDecimal importanceScore;
    private Integer contentCount;
    private String dedupStatus;
    @TableField(typeHandler = UuidArrayTypeHandler.class)
    private UUID[] mergeHistory;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

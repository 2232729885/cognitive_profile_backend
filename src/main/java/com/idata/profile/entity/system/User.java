package com.idata.profile.entity.system;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 系统用户。对应表：users */
@Data
@TableName("users")
public class User {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private String username;
    private String passwordHash;        // bcrypt，不存明文
    private String displayName;
    private String role;                // admin|analyst|reviewer|readonly
    private Boolean isActive;
    private OffsetDateTime lastLoginAt;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

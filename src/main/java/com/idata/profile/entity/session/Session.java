package com.idata.profile.entity.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 多轮对话会话。对应表：sessions */
@Data
@TableName("sessions")
public class Session {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID userId;
    private String title;
    private Integer messageCount;
    private OffsetDateTime lastMessageAt;
    private Boolean isArchived;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

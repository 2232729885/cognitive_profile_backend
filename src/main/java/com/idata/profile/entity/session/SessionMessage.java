package com.idata.profile.entity.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 会话消息。对应表：session_messages */
@Data
@TableName("session_messages")
public class SessionMessage {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID sessionId;
    private String role;                // user|assistant
    private String content;
    private UUID workflowTaskId;        // assistant消息对应的分析任务

    private OffsetDateTime createdAt;
}

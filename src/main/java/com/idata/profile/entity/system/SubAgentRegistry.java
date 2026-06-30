package com.idata.profile.entity.system;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * T1-T6子Agent注册表。
 * activeUrlType控制mock/真实地址一键切换，改这个字段即可，无需修改代码。
 * 见 agentproxy.AgentProxyClient 的统一调用实现。
 *
 * 对应表：sub_agent_registry
 */
@Data
@TableName("sub_agent_registry")
public class SubAgentRegistry {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private String agentCode;           // T1~T6，唯一
    private String agentName;
    private String description;
    private String actions;             // JSONB，能力列表，序列化后作为LLM Function Calling定义

    private String baseUrl;             // 算法服务真实地址（生产）
    private String mockUrl;             // Mock服务地址（开发）
    private String activeUrlType;       // mock|real

    private Integer timeoutSeconds;
    private Short maxRetries;
    private Boolean isActive;
    private String healthStatus;        // healthy|degraded|down|unknown
    private OffsetDateTime lastHealthCheck;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

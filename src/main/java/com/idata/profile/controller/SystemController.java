package com.idata.profile.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F8 系统管理。
 * 用户与权限（JWT+RBAC）、T1-T6 Agent注册表管理（mock/real地址切换）、系统监控。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {
    // TODO: 用户管理接口、sub_agent_registry的active_url_type切换接口
}

package com.idata.profile.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.idata.profile.auth.JwtAuthFilter;
import com.idata.profile.common.response.Result;
import com.idata.profile.entity.system.SubAgentRegistry;
import com.idata.profile.entity.system.User;
import com.idata.profile.mapper.system.SubAgentRegistryMapper;
import com.idata.profile.mapper.system.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private static final Set<String> VALID_ROLES = Set.of("admin", "analyst", "reviewer", "readonly");
    private static final Set<String> VALID_URL_TYPES = Set.of("mock", "real");

    private final SubAgentRegistryMapper subAgentRegistryMapper;
    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final RestClient healthRestClient = createHealthRestClient();

    @GetMapping("/agents")
    public Result<List<SubAgentRegistry>> listAgents() {
        return Result.ok(subAgentRegistryMapper.selectList(
                new LambdaQueryWrapper<SubAgentRegistry>()
                        .orderByAsc(SubAgentRegistry::getAgentCode)));
    }

    @GetMapping("/agents/{agentCode}")
    public Result<SubAgentRegistry> getAgent(@PathVariable String agentCode) {
        SubAgentRegistry agent = subAgentRegistryMapper.selectByAgentCode(agentCode);
        if (agent == null) {
            return Result.fail("NOT_FOUND", "Agent\u4e0d\u5b58\u5728");
        }
        return Result.ok(agent);
    }

    @PutMapping("/agents/{agentCode}/url-type")
    public Result<SubAgentRegistry> updateUrlType(@PathVariable String agentCode,
                                                  @RequestBody UpdateUrlTypeRequest request) {
        if (request == null || !VALID_URL_TYPES.contains(request.getActiveUrlType())) {
            return Result.fail("INVALID_PARAM", "activeUrlType\u53ea\u80fd\u662fmock\u6216real");
        }

        SubAgentRegistry agent = subAgentRegistryMapper.selectByAgentCode(agentCode);
        if (agent == null) {
            return Result.fail("NOT_FOUND", "Agent\u4e0d\u5b58\u5728");
        }
        if ("real".equals(request.getActiveUrlType()) && !hasText(agent.getBaseUrl())) {
            return Result.fail("CONFIG_MISSING", "\u771f\u5b9e\u5730\u5740(baseUrl)\u672a\u914d\u7f6e\uff0c\u65e0\u6cd5\u5207\u6362\u5230real\u6a21\u5f0f");
        }

        agent.setActiveUrlType(request.getActiveUrlType());
        agent.setUpdatedAt(OffsetDateTime.now());
        subAgentRegistryMapper.updateById(agent);
        return Result.ok(subAgentRegistryMapper.selectByAgentCode(agentCode));
    }

    @PutMapping("/agents/{agentCode}/base-url")
    public Result<SubAgentRegistry> updateBaseUrl(@PathVariable String agentCode,
                                                  @RequestBody UpdateBaseUrlRequest request) {
        SubAgentRegistry agent = subAgentRegistryMapper.selectByAgentCode(agentCode);
        if (agent == null) {
            return Result.fail("NOT_FOUND", "Agent\u4e0d\u5b58\u5728");
        }
        agent.setBaseUrl(request == null ? null : request.getBaseUrl());
        agent.setUpdatedAt(OffsetDateTime.now());
        subAgentRegistryMapper.updateById(agent);
        return Result.ok(subAgentRegistryMapper.selectByAgentCode(agentCode));
    }

    @PostMapping("/agents/{agentCode}/health-check")
    public Result<Map<String, Object>> healthCheck(@PathVariable String agentCode) {
        SubAgentRegistry agent = subAgentRegistryMapper.selectByAgentCode(agentCode);
        if (agent == null) {
            return Result.fail("NOT_FOUND", "Agent\u4e0d\u5b58\u5728");
        }

        OffsetDateTime checkedAt = OffsetDateTime.now();
        String activeUrl = "real".equals(agent.getActiveUrlType()) ? agent.getBaseUrl() : agent.getMockUrl();
        String healthStatus = "down";
        if (hasText(activeUrl)) {
            try {
                healthRestClient.get()
                        .uri(activeUrl + "/health")
                        .retrieve()
                        .toBodilessEntity();
                healthStatus = "healthy";
            } catch (Exception ignored) {
                healthStatus = "down";
            }
        }

        agent.setHealthStatus(healthStatus);
        agent.setLastHealthCheck(checkedAt);
        agent.setUpdatedAt(checkedAt);
        subAgentRegistryMapper.updateById(agent);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentCode", agent.getAgentCode());
        result.put("healthStatus", healthStatus);
        result.put("activeUrlType", agent.getActiveUrlType());
        result.put("checkedAt", checkedAt.toString());
        return Result.ok(result);
    }

    @GetMapping("/users")
    public Result<List<User>> listUsers() {
        List<User> users = userMapper.selectList(
                new LambdaQueryWrapper<User>().orderByAsc(User::getUsername));
        users.forEach(this::maskPassword);
        return Result.ok(users);
    }

    @PostMapping("/users")
    public Result<User> createUser(@RequestBody CreateUserRequest request) {
        if (request == null || !hasText(request.getUsername()) || !hasText(request.getPassword())) {
            return Result.fail("INVALID_PARAM", "username\u548cpassword\u4e0d\u80fd\u4e3a\u7a7a");
        }
        if (!VALID_ROLES.contains(request.getRole())) {
            return Result.fail("INVALID_PARAM", "role\u53ea\u80fd\u662fadmin/analyst/reviewer/readonly");
        }
        if (userMapper.selectByUsername(request.getUsername()) != null) {
            return Result.fail("DUPLICATE_USERNAME", "\u7528\u6237\u540d\u5df2\u5b58\u5728");
        }

        OffsetDateTime now = OffsetDateTime.now();
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName());
        user.setRole(request.getRole());
        user.setIsActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);

        maskPassword(user);
        return Result.ok(user);
    }

    @PutMapping("/users/{userId}/role")
    public Result<User> updateUserRole(@PathVariable UUID userId,
                                       @RequestBody UpdateUserRoleRequest request,
                                       HttpServletRequest servletRequest) {
        String currentRole = (String) servletRequest.getAttribute(JwtAuthFilter.ATTR_ROLE);
        if (!"admin".equals(currentRole)) {
            return Result.fail("FORBIDDEN", "\u53ea\u6709admin\u624d\u80fd\u4fee\u6539\u7528\u6237\u89d2\u8272");
        }
        if (request == null || !VALID_ROLES.contains(request.getRole())) {
            return Result.fail("INVALID_PARAM", "role\u53ea\u80fd\u662fadmin/analyst/reviewer/readonly");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.fail("NOT_FOUND", "\u7528\u6237\u4e0d\u5b58\u5728");
        }
        user.setRole(request.getRole());
        user.setUpdatedAt(OffsetDateTime.now());
        userMapper.updateById(user);
        maskPassword(user);
        return Result.ok(user);
    }

    private static RestClient createHealthRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    private void maskPassword(User user) {
        if (user != null) {
            user.setPasswordHash(null);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @Data
    public static class UpdateUrlTypeRequest {
        private String activeUrlType;
    }

    @Data
    public static class UpdateBaseUrlRequest {
        private String baseUrl;
    }

    @Data
    public static class CreateUserRequest {
        private String username;
        private String password;
        private String displayName;
        private String role;
    }

    @Data
    public static class UpdateUserRoleRequest {
        private String role;
    }
}

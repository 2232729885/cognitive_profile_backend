package com.idata.profile.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.idata.profile.analysis.AnalysisTaskResponse;
import com.idata.profile.analysis.orchestrator.CoordinatorAgentService;
import com.idata.profile.analysis.workflow.WorkflowTaskService;
import com.idata.profile.auth.JwtAuthFilter;
import com.idata.profile.common.response.Result;
import com.idata.profile.entity.session.Session;
import com.idata.profile.entity.session.SessionMessage;
import com.idata.profile.entity.task.WorkflowTask;
import com.idata.profile.mapper.session.SessionMapper;
import com.idata.profile.mapper.session.SessionMessageMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final WorkflowTaskService workflowTaskService;
    private final CoordinatorAgentService coordinatorAgentService;
    private final SessionMapper sessionMapper;
    private final SessionMessageMapper sessionMessageMapper;

    @PostMapping("/tasks")
    public Result<AnalysisTaskResponse> createTask(@RequestBody CreateAnalysisTaskRequest request,
                                                   HttpServletRequest servletRequest) {
        UUID userId = (UUID) servletRequest.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        if (userId == null) {
            return Result.fail("UNAUTHORIZED", "\u672a\u8ba4\u8bc1");
        }
        if (request == null || request.getInputText() == null || request.getInputText().isBlank()) {
            return Result.fail("BAD_REQUEST", "inputText\u4e0d\u80fd\u4e3a\u7a7a");
        }

        Session session = resolveSession(request.getSessionId(), userId, request.getInputText());
        if (session == null) {
            return Result.fail("NOT_FOUND", "\u4f1a\u8bdd\u4e0d\u5b58\u5728");
        }

        WorkflowTask task = workflowTaskService.createTask(session.getId(), userId, request.getInputText());
        insertUserMessage(session, request.getInputText());
        coordinatorAgentService.executeAsync(task.getId(), session.getId(), request.getInputText());

        return Result.ok(new AnalysisTaskResponse(
                task.getId(),
                session.getId(),
                task.getStatus(),
                "/api/analysis/" + task.getId() + "/stream"
        ));
    }

    @PostMapping("/tasks/with-file")
    public Result<AnalysisTaskResponse> createTaskWithFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("inputText") String inputText,
            @RequestParam(value = "sessionId", required = false) UUID sessionId,
            HttpServletRequest request) throws IOException {

        UUID userId = (UUID) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        if (userId == null) {
            return Result.fail("UNAUTHORIZED", "\u672a\u8ba4\u8bc1");
        }
        if (inputText == null || inputText.isBlank()) {
            return Result.fail("BAD_REQUEST", "inputText\u4e0d\u80fd\u4e3a\u7a7a");
        }

        String fileContent = extractFileContent(file);
        String fullInput = inputText + "\n\n【附件内容】\n"
                + fileContent.substring(0, Math.min(fileContent.length(), 3000));

        Session session = resolveSession(sessionId, userId, inputText);
        if (session == null) {
            return Result.fail("NOT_FOUND", "\u4f1a\u8bdd\u4e0d\u5b58\u5728");
        }

        WorkflowTask task = workflowTaskService.createTask(session.getId(), userId, inputText);
        insertUserMessage(session, inputText + " [附件: " + safeFilename(file.getOriginalFilename()) + "]");
        coordinatorAgentService.executeAsync(task.getId(), session.getId(), fullInput);

        return Result.ok(new AnalysisTaskResponse(
                task.getId(),
                session.getId(),
                "PENDING",
                "/api/analysis/" + task.getId() + "/stream"
        ));
    }

    @GetMapping("/tasks/{taskId}")
    public Result<WorkflowTask> getTask(@PathVariable UUID taskId) {
        WorkflowTask task = workflowTaskService.findById(taskId);
        if (task == null) {
            return Result.fail("NOT_FOUND", "\u4efb\u52a1\u4e0d\u5b58\u5728");
        }
        return Result.ok(task);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Result<Void> cancelTask(@PathVariable UUID taskId) {
        boolean cancelled = coordinatorAgentService.cancelTask(taskId.toString());
        if (cancelled) {
            return Result.ok(null);
        }
        return Result.fail("NOT_RUNNING", "\u4efb\u52a1\u672a\u5728\u8fd0\u884c\u4e2d");
    }

    @GetMapping("/sessions")
    public Result<List<Session>> listSessions(HttpServletRequest request) {
        UUID userId = (UUID) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        return Result.ok(sessionMapper.selectList(
                new LambdaQueryWrapper<Session>()
                        .eq(Session::getUserId, userId)
                        .eq(Session::getIsArchived, false)
                        .orderByDesc(Session::getLastMessageAt)
                        .orderByDesc(Session::getCreatedAt)
        ));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<SessionMessage>> listMessages(@PathVariable UUID sessionId) {
        return Result.ok(sessionMessageMapper.selectBySessionIdOrderByCreatedAt(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable UUID sessionId,
                                      HttpServletRequest request) {
        UUID userId = (UUID) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        Session session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            return Result.fail("NOT_FOUND", "\u4f1a\u8bdd\u4e0d\u5b58\u5728");
        }
        session.setIsArchived(true);
        session.setUpdatedAt(OffsetDateTime.now());
        sessionMapper.updateById(session);
        return Result.ok(null);
    }

    @PutMapping("/sessions/{sessionId}/title")
    public Result<Void> renameSession(@PathVariable UUID sessionId,
                                      @RequestBody Map<String, String> body,
                                      HttpServletRequest request) {
        UUID userId = (UUID) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        Session session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            return Result.fail("NOT_FOUND", "\u4f1a\u8bdd\u4e0d\u5b58\u5728");
        }
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            return Result.fail("BAD_REQUEST", "\u6807\u9898\u4e0d\u80fd\u4e3a\u7a7a");
        }
        String trimmed = title.trim();
        session.setTitle(trimmed.substring(0, Math.min(trimmed.length(), 50)));
        session.setUpdatedAt(OffsetDateTime.now());
        sessionMapper.updateById(session);
        return Result.ok(null);
    }

    @GetMapping("/agent-info")
    public Result<Map<String, Object>> getAgentInfo() {
        Map<String, Object> info = new LinkedHashMap<>();

        info.put("tools", List.of(
                Map.of(
                        "name", "searchContent",
                        "description", "检索与分析主题相关的社交媒体内容和新闻",
                        "icon", "🔍",
                        "params", List.of("query（查询关键词）", "platform（平台过滤）", "language（语言过滤）", "topK（返回数量）")
                ),
                Map.of(
                        "name", "identifyTargets",
                        "description", "识别信息操控的重点目标账号，分析 BEND 手法分布",
                        "icon", "🎯",
                        "params", List.of("triggerType（narrative/account_list）", "narrativeId（叙事ID）", "accountIds（账号UUID列表）")
                ),
                Map.of(
                        "name", "queryGraph",
                        "description", "查询某个实体在知识图谱中的关联关系网络",
                        "icon", "🕸️",
                        "params", List.of("nodeId（节点UUID）", "nodeLabel（节点类型）")
                ),
                Map.of(
                        "name", "generateProfile",
                        "description", "对指定人物生成全息画像，包含 15 个维度的深度分析",
                        "icon", "👤",
                        "params", List.of("personId（人物UUID）")
                )
        ));
        info.put("systemPrompt", coordinatorAgentService.getSystemPrompt());
        info.put("model", "Qwen3-VL-32B");
        info.put("maxTokens", 4096);

        return Result.ok(info);
    }

    private Session resolveSession(UUID sessionId, UUID userId, String inputText) {
        if (sessionId != null) {
            Session session = sessionMapper.selectById(sessionId);
            if (session == null || !userId.equals(session.getUserId())) {
                return null;
            }
            return session;
        }

        OffsetDateTime now = OffsetDateTime.now();
        Session session = new Session();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        session.setTitle(buildTitle(inputText));
        session.setMessageCount(0);
        session.setLastMessageAt(now);
        session.setIsArchived(false);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);
        return session;
    }

    private void insertUserMessage(Session session, String inputText) {
        SessionMessage message = new SessionMessage();
        message.setId(UUID.randomUUID());
        message.setSessionId(session.getId());
        message.setRole("user");
        message.setContent(inputText);
        message.setCreatedAt(OffsetDateTime.now());
        sessionMessageMapper.insert(message);

        session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 1);
        session.setLastMessageAt(message.getCreatedAt());
        sessionMapper.updateById(session);
    }

    private String buildTitle(String inputText) {
        String normalized = inputText == null ? "\u65b0\u4f1a\u8bdd" : inputText.trim();
        if (normalized.length() <= 40) {
            return normalized;
        }
        return normalized.substring(0, 40);
    }

    private String extractFileContent(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase() : "";
        if (filename.endsWith(".txt") || filename.endsWith(".md")
                || filename.endsWith(".json") || filename.endsWith(".csv")) {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        }
        return "[\u6587\u4ef6\u7c7b\u578b " + filename + " \u6682\u4e0d\u652f\u6301\u6587\u672c\u63d0\u53d6]";
    }

    private String safeFilename(String filename) {
        return filename == null || filename.isBlank() ? "\u672a\u547d\u540d\u6587\u4ef6" : filename;
    }

    @Data
    public static class CreateAnalysisTaskRequest {
        private UUID sessionId;
        private String inputText;
    }
}

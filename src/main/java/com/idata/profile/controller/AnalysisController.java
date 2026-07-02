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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
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
        coordinatorAgentService.executeAsync(task.getId(), request.getInputText());

        return Result.ok(new AnalysisTaskResponse(
                task.getId(),
                session.getId(),
                task.getStatus(),
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

    @Data
    public static class CreateAnalysisTaskRequest {
        private UUID sessionId;
        private String inputText;
    }
}

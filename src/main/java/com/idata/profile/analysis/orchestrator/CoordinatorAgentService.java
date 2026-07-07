package com.idata.profile.analysis.orchestrator;

import com.idata.profile.analysis.tools.GenerateProfileTool;
import com.idata.profile.analysis.tools.IdentifyTargetsTool;
import com.idata.profile.analysis.tools.QueryGraphTool;
import com.idata.profile.analysis.tools.SearchContentTool;
import com.idata.profile.analysis.workflow.WorkflowTaskService;
import com.idata.profile.entity.session.Session;
import com.idata.profile.entity.session.SessionMessage;
import com.idata.profile.mapper.session.SessionMapper;
import com.idata.profile.mapper.session.SessionMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoordinatorAgentService {

    private static final ThreadLocal<String> CURRENT_TASK_ID = new ThreadLocal<>();
    private static final String SYSTEM_PROMPT = """
            你是一个专业的信息操控分析系统，具备以下工具：
            - searchContent：检索社交媒体内容和新闻
            - identifyTargets：识别信息操控目标账号
            - queryGraph：查询知识图谱关联关系
            - generateProfile：生成人物全息画像

            工作原则：
            1. 优先调用工具获取真实数据，不要凭空推断
            2. identifyTargets 的 accountIds 必须是纯 UUID 格式（去掉 ft_user_ 等前缀）
            3. 每次工具调用后基于返回数据做分析，不要重复调用相同参数
            4. 数据不足时直接说明，给出基于现有数据的专业判断
            5. 最终输出用 Markdown 格式，包含：账号识别、操控手法、潜在影响、行动建议
            """;

    private final ChatClient chatClient;
    private final WorkflowTaskService workflowTaskService;
    private final SearchContentTool searchContentTool;
    private final IdentifyTargetsTool identifyTargetsTool;
    private final QueryGraphTool queryGraphTool;
    private final GenerateProfileTool generateProfileTool;
    private final SessionMapper sessionMapper;
    private final SessionMessageMapper sessionMessageMapper;

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Disposable> runningTasks = new ConcurrentHashMap<>();

    public SseEmitter registerEmitter(String taskId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitters.put(taskId, emitter);
        emitter.onCompletion(() -> emitters.remove(taskId));
        emitter.onTimeout(() -> {
            emitters.remove(taskId);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(taskId));
        return emitter;
    }

    @Async("pipelineThreadPool")
    public void executeAsync(UUID taskId, UUID sessionId, String inputText) {
        long startMs = System.currentTimeMillis();
        String taskIdStr = taskId.toString();
        CURRENT_TASK_ID.set(taskIdStr);
        workflowTaskService.updateStatus(taskId, "RUNNING");
        sendSseEvent(taskIdStr, "task_started",
                Map.of("taskId", taskId, "message", "\u5206\u6790\u4efb\u52a1\u5df2\u542f\u52a8"));

        try {
            StringBuilder fullResult = new StringBuilder();
            List<Message> historyMessages = buildHistoryMessages(sessionId);
            AtomicBoolean finished = new AtomicBoolean(false);

            Disposable disposable = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .messages(historyMessages)
                    .user(inputText)
                    .toolCallbacks(
                            searchContentCallback(taskIdStr),
                            identifyTargetsCallback(taskIdStr),
                            queryGraphCallback(taskIdStr),
                            generateProfileCallback(taskIdStr)
                    )
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        fullResult.append(token);
                        sendSseEvent(taskIdStr, "token",
                                Map.of("token", token));
                    })
                    .doOnComplete(() -> {
                        runningTasks.remove(taskIdStr);
                        finished.set(true);
                        int totalMs = (int) Math.min(Integer.MAX_VALUE,
                                System.currentTimeMillis() - startMs);
                        String result = fullResult.toString();
                        workflowTaskService.completeTask(taskId, result, null, 0, totalMs);
                        insertAssistantMessage(sessionId, taskId, result);
                        sendSseEvent(taskIdStr, "task_completed",
                                Map.of("taskId", taskId, "summary", safeString(result), "durationMs", totalMs));
                    })
                    .doOnError(e -> {
                        runningTasks.remove(taskIdStr);
                        finished.set(true);
                        log.error("??????????, taskId={}", taskId, e);
                        workflowTaskService.failTask(taskId, e.getMessage());
                        sendSseEvent(taskIdStr, "task_failed",
                                Map.of("taskId", taskId, "error", safeString(e.getMessage())));
                    })
                    .doOnCancel(() -> {
                        runningTasks.remove(taskIdStr);
                        finished.set(true);
                        int totalMs = (int) Math.min(Integer.MAX_VALUE,
                                System.currentTimeMillis() - startMs);
                        String partialResult = fullResult.toString();
                        workflowTaskService.cancelTask(taskId, partialResult, null, 0, totalMs);
                        if (!partialResult.isBlank()) {
                            insertAssistantMessage(sessionId, taskId, partialResult);
                        }
                        sendSseEvent(taskIdStr, "task_cancelled",
                                Map.of("taskId", taskId, "partial", safeString(partialResult)));
                    })
                    .subscribe();

            if (!finished.get()) {
                runningTasks.put(taskIdStr, disposable);
            }
            try {
                while (!finished.get() && !disposable.isDisposed()) {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                runningTasks.remove(taskIdStr, disposable);
            }
        } catch (Exception e) {
            runningTasks.remove(taskIdStr);
            log.error("????????, taskId={}", taskId, e);
            workflowTaskService.failTask(taskId, e.getMessage());
            sendSseEvent(taskIdStr, "task_failed",
                    Map.of("taskId", taskId, "error", safeString(e.getMessage())));
        } finally {
            CURRENT_TASK_ID.remove();
        }
    }

    public void sendSseEvent(String taskId, String eventType, Object data) {
        SseEmitter emitter = emitters.get(taskId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(eventType)
                    .data(data));
        } catch (IOException | IllegalStateException e) {
            log.warn("SSE推送失败, taskId={}, eventType={}", taskId, eventType);
            emitters.remove(taskId);
        }
    }

    public String currentTaskId() {
        return CURRENT_TASK_ID.get();
    }

    public boolean cancelTask(String taskId) {
        Disposable disposable = runningTasks.get(taskId);
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
            runningTasks.remove(taskId);
            return true;
        }
        return false;
    }

    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private List<Message> buildHistoryMessages(UUID sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        List<SessionMessage> history = new ArrayList<>(
                sessionMessageMapper.selectRecentBySessionId(sessionId, 11));
        Collections.reverse(history);
        if (!history.isEmpty()) {
            SessionMessage latest = history.get(history.size() - 1);
            if ("user".equals(latest.getRole())) {
                history.remove(history.size() - 1);
            }
        }

        List<Message> messages = new ArrayList<>();
        for (SessionMessage msg : history) {
            if ("user".equals(msg.getRole())) {
                messages.add(new UserMessage(safeString(msg.getContent())));
            } else if ("assistant".equals(msg.getRole())) {
                messages.add(new AssistantMessage(safeString(msg.getContent())));
            }
        }
        return messages;
    }

    private void insertAssistantMessage(UUID sessionId, UUID taskId, String result) {
        if (sessionId == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        SessionMessage message = new SessionMessage();
        message.setId(UUID.randomUUID());
        message.setSessionId(sessionId);
        message.setRole("assistant");
        message.setContent(safeString(result));
        message.setWorkflowTaskId(taskId);
        message.setCreatedAt(now);
        sessionMessageMapper.insert(message);

        Session session = sessionMapper.selectById(sessionId);
        if (session != null) {
            session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 1);
            session.setLastMessageAt(now);
            session.setUpdatedAt(now);
            sessionMapper.updateById(session);
        }
    }

    private ToolCallback searchContentCallback(String taskId) {
        return FunctionToolCallback.builder("searchContent",
                        (SearchContentTool.Request request) -> searchContentTool.applyWithTaskId(request, taskId))
                .description("检索与分析主题相关的社交媒体内容和新闻")
                .inputType(SearchContentTool.Request.class)
                .build();
    }

    private ToolCallback identifyTargetsCallback(String taskId) {
        return FunctionToolCallback.builder("identifyTargets",
                        (IdentifyTargetsTool.Request request) -> identifyTargetsTool.applyWithTaskId(request, taskId))
                .description("识别信息操控的重点目标账号，分析BEND手法分布")
                .inputType(IdentifyTargetsTool.Request.class)
                .build();
    }

    private ToolCallback queryGraphCallback(String taskId) {
        return FunctionToolCallback.builder("queryGraph",
                        (QueryGraphTool.Request request) -> queryGraphTool.applyWithTaskId(request, taskId))
                .description("查询某个实体在知识图谱中的关联关系网络")
                .inputType(QueryGraphTool.Request.class)
                .build();
    }

    private ToolCallback generateProfileCallback(String taskId) {
        return FunctionToolCallback.builder("generateProfile",
                        (GenerateProfileTool.Request request) -> generateProfileTool.applyWithTaskId(request, taskId))
                .description("对指定人物生成全息画像，包含15个维度的深度分析")
                .inputType(GenerateProfileTool.Request.class)
                .build();
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }
}

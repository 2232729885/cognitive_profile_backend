package com.idata.profile.analysis.orchestrator;

import com.idata.profile.analysis.tools.GenerateProfileTool;
import com.idata.profile.analysis.tools.IdentifyTargetsTool;
import com.idata.profile.analysis.tools.QueryGraphTool;
import com.idata.profile.analysis.tools.SearchContentTool;
import com.idata.profile.analysis.workflow.WorkflowTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoordinatorAgentService {

    private static final ThreadLocal<String> CURRENT_TASK_ID = new ThreadLocal<>();

    private final ChatClient chatClient;
    private final WorkflowTaskService workflowTaskService;
    private final SearchContentTool searchContentTool;
    private final IdentifyTargetsTool identifyTargetsTool;
    private final QueryGraphTool queryGraphTool;
    private final GenerateProfileTool generateProfileTool;

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

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
    public void executeAsync(UUID taskId, String inputText) {
        long startMs = System.currentTimeMillis();
        CURRENT_TASK_ID.set(taskId.toString());
        workflowTaskService.updateStatus(taskId, "RUNNING");
        sendSseEvent(taskId.toString(), "task_started",
                Map.of("taskId", taskId, "message", "\u5206\u6790\u4efb\u52a1\u5df2\u542f\u52a8"));

        try {
            StringBuilder fullResult = new StringBuilder();

            chatClient.prompt()
                    .user(inputText)
                    .toolCallbacks(
                            searchContentCallback(),
                            identifyTargetsCallback(),
                            queryGraphCallback(),
                            generateProfileCallback()
                    )
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        fullResult.append(token);
                        sendSseEvent(taskId.toString(), "token",
                                Map.of("token", token));
                    })
                    .doOnComplete(() -> {
                        int totalMs = (int) Math.min(Integer.MAX_VALUE,
                                System.currentTimeMillis() - startMs);
                        String result = fullResult.toString();
                        workflowTaskService.completeTask(taskId, result, null, 0, totalMs);
                        sendSseEvent(taskId.toString(), "task_completed",
                                Map.of("taskId", taskId, "summary", safeString(result), "durationMs", totalMs));
                    })
                    .doOnError(e -> {
                        log.error("??????????, taskId={}", taskId, e);
                        workflowTaskService.failTask(taskId, e.getMessage());
                        sendSseEvent(taskId.toString(), "task_failed",
                                Map.of("taskId", taskId, "error", safeString(e.getMessage())));
                    })
                    .blockLast();
        } catch (Exception e) {
            log.error("分析任务执行失败, taskId={}", taskId, e);
            workflowTaskService.failTask(taskId, e.getMessage());
            sendSseEvent(taskId.toString(), "task_failed",
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

    private ToolCallback searchContentCallback() {
        return FunctionToolCallback.builder("searchContent", searchContentTool)
                .description("检索与分析主题相关的社交媒体内容和新闻")
                .inputType(SearchContentTool.Request.class)
                .build();
    }

    private ToolCallback identifyTargetsCallback() {
        return FunctionToolCallback.builder("identifyTargets", identifyTargetsTool)
                .description("识别信息操控的重点目标账号，分析BEND手法分布")
                .inputType(IdentifyTargetsTool.Request.class)
                .build();
    }

    private ToolCallback queryGraphCallback() {
        return FunctionToolCallback.builder("queryGraph", queryGraphTool)
                .description("查询某个实体在知识图谱中的关联关系网络")
                .inputType(QueryGraphTool.Request.class)
                .build();
    }

    private ToolCallback generateProfileCallback() {
        return FunctionToolCallback.builder("generateProfile", generateProfileTool)
                .description("对指定人物生成全息画像，包含15个维度的深度分析")
                .inputType(GenerateProfileTool.Request.class)
                .build();
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }
}

package com.idata.profile.analysis.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t5.T5GenerateProfileRequest;
import com.idata.profile.agentproxy.dto.t5.T5GenerateProfileResponse;
import com.idata.profile.analysis.orchestrator.CoordinatorAgentService;
import com.idata.profile.analysis.workflow.WorkflowTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Component("generateProfileTool")
@RequiredArgsConstructor
public class GenerateProfileTool implements Function<GenerateProfileTool.Request, GenerateProfileTool.Response> {

    private final AgentProxyClient agentProxyClient;
    private final ApplicationContext applicationContext;

    public record Request(
            @JsonProperty(required = true)
            @JsonPropertyDescription("需要补全画像的人物ID（UUID格式）")
            String personId
    ) {
    }

    public record Response(
            boolean success,
            String personId,
            String targetType,
            String manipulationRisk,
            String summary
    ) {
    }

    @Override
    public Response apply(Request request) {
        long startedAt = System.currentTimeMillis();
        String taskId = currentTaskId();
        sendStarted(taskId, "generate_profile", request, startedAt);

        try {
            T5GenerateProfileRequest profileRequest = new T5GenerateProfileRequest();
            profileRequest.setPersonId(UUID.fromString(request.personId()));
            T5GenerateProfileResponse profile = agentProxyClient.call(
                    "T5", "generate_full_profile", profileRequest, T5GenerateProfileResponse.class);
            Response response = new Response(
                    true,
                    request.personId(),
                    profile == null ? null : profile.getTargetType(),
                    profile == null ? null : profile.getManipulationRisk(),
                    buildSummary(profile)
            );
            sendDone(taskId, "generate_profile", request, response, startedAt);
            return response;
        } catch (RuntimeException e) {
            sendFailed(taskId, "generate_profile", request, e, startedAt);
            throw e;
        }
    }

    private String buildSummary(T5GenerateProfileResponse profile) {
        if (profile == null) {
            return "\u672a\u83b7\u53d6\u5230\u753b\u50cf\u7ed3\u679c";
        }
        return "\u76ee\u6807\u7c7b\u578b=" + profile.getTargetType()
                + "\uff0c\u64cd\u63a7\u98ce\u9669=" + profile.getManipulationRisk()
                + "\uff0c\u653f\u6cbb\u503e\u5411=" + profile.getPoliticalOrientation()
                + "\uff0c\u5f71\u54cd\u529b=" + profile.getInfluenceScore()
                + "\uff0c\u8bc1\u636e=" + truncate(profile.getTargetEvidence(), 160);
    }

    private String currentTaskId() {
        return applicationContext.getBean(CoordinatorAgentService.class).currentTaskId();
    }

    private void sendStarted(String taskId, String stepName, Object input, long startedAt) {
        if (taskId == null) {
            return;
        }
        coordinator().sendSseEvent(taskId, "tool_started", Map.of("stepName", stepName, "input", input));
        workflow().appendStep(UUID.fromString(taskId),
                new WorkflowTaskService.StepRecord(stepName, "running", startedAt, null, input, null, null));
    }

    private void sendDone(String taskId, String stepName, Object input, Object output, long startedAt) {
        if (taskId == null) {
            return;
        }
        long doneAt = System.currentTimeMillis();
        coordinator().sendSseEvent(taskId, "tool_completed",
                Map.of("stepName", stepName, "output", output, "durationMs", doneAt - startedAt));
        workflow().appendStep(UUID.fromString(taskId),
                new WorkflowTaskService.StepRecord(stepName, "done", startedAt, doneAt, input, output, null));
    }

    private void sendFailed(String taskId, String stepName, Object input, Exception e, long startedAt) {
        if (taskId == null) {
            return;
        }
        long doneAt = System.currentTimeMillis();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepName", stepName);
        data.put("error", e.getMessage());
        coordinator().sendSseEvent(taskId, "tool_failed", data);
        workflow().appendStep(UUID.fromString(taskId),
                new WorkflowTaskService.StepRecord(stepName, "failed", startedAt, doneAt, input, null, e.getMessage()));
    }

    private CoordinatorAgentService coordinator() {
        return applicationContext.getBean(CoordinatorAgentService.class);
    }

    private WorkflowTaskService workflow() {
        return applicationContext.getBean(WorkflowTaskService.class);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

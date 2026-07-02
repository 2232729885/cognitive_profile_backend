package com.idata.profile.analysis.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.idata.profile.analysis.orchestrator.CoordinatorAgentService;
import com.idata.profile.analysis.workflow.WorkflowTaskService;
import com.idata.profile.entity.task.IdentificationResult;
import com.idata.profile.entity.task.IdentificationTask;
import com.idata.profile.identification.IdentificationTaskService;
import com.idata.profile.mapper.task.IdentificationResultMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Component("identifyTargetsTool")
@RequiredArgsConstructor
public class IdentifyTargetsTool implements Function<IdentifyTargetsTool.Request, IdentifyTargetsTool.Response> {

    private final IdentificationTaskService identificationTaskService;
    private final IdentificationResultMapper identificationResultMapper;
    private final ApplicationContext applicationContext;

    public record Request(
            @JsonProperty(required = true)
            @JsonPropertyDescription("触发方式：narrative（以叙事为入口）或 account_list（直接指定账号列表）")
            String triggerType,

            @JsonPropertyDescription("叙事ID，triggerType=narrative时必填")
            String narrativeId,

            @JsonPropertyDescription("账号ID列表，triggerType=account_list时必填")
            List<String> accountIds
    ) {
    }

    public record Response(
            List<TargetSummary> targets,
            int accountsAnalyzed,
            int groupsDetected
    ) {
    }

    public record TargetSummary(
            String targetType,
            String targetEntityId,
            double confidence,
            String evidenceText
    ) {
    }

    @Override
    public Response apply(Request request) {
        long startedAt = System.currentTimeMillis();
        String taskId = currentTaskId();
        sendStarted(taskId, "identify_targets", request, startedAt);

        try {
            IdentificationTask task;
            if ("narrative".equals(request.triggerType())) {
                task = identificationTaskService.identifyByNarrative(UUID.fromString(request.narrativeId()));
            } else if ("account_list".equals(request.triggerType())) {
                UUID[] accountIds = request.accountIds() == null ? new UUID[0] : request.accountIds().stream()
                        .map(UUID::fromString)
                        .toArray(UUID[]::new);
                task = identificationTaskService.identifyByAccountList(accountIds);
            } else {
                throw new IllegalArgumentException("Unsupported triggerType: " + request.triggerType());
            }

            List<IdentificationResult> results = identificationResultMapper.selectList(
                    new LambdaQueryWrapper<IdentificationResult>()
                            .eq(IdentificationResult::getTaskId, task.getId())
            );
            Response response = new Response(
                    results.stream().map(this::toSummary).toList(),
                    task.getAccountsAnalyzed() == null ? 0 : task.getAccountsAnalyzed(),
                    task.getGroupsDetected() == null ? 0 : task.getGroupsDetected()
            );
            sendDone(taskId, "identify_targets", request, response, startedAt);
            return response;
        } catch (RuntimeException e) {
            sendFailed(taskId, "identify_targets", request, e, startedAt);
            throw e;
        }
    }

    private TargetSummary toSummary(IdentificationResult result) {
        BigDecimal confidence = result.getConfidence();
        return new TargetSummary(
                result.getTargetType(),
                result.getTargetEntityId() == null ? null : result.getTargetEntityId().toString(),
                confidence == null ? 0D : confidence.doubleValue(),
                result.getEvidenceText()
        );
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
}

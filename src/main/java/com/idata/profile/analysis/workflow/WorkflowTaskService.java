package com.idata.profile.analysis.workflow;

import com.idata.profile.entity.task.WorkflowTask;
import com.idata.profile.mapper.task.WorkflowTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowTaskService {

    private final WorkflowTaskMapper workflowTaskMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public WorkflowTask createTask(UUID sessionId, UUID userId, String inputText) {
        OffsetDateTime now = OffsetDateTime.now();
        WorkflowTask task = new WorkflowTask();
        task.setId(UUID.randomUUID());
        task.setSessionId(sessionId);
        task.setUserId(userId);
        task.setInputText(inputText);
        task.setStatus("PENDING");
        task.setIsFallback(false);
        task.setSteps("[]");
        task.setLlmTokensUsed(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        workflowTaskMapper.insert(task);
        return task;
    }

    @Transactional
    public void updateStatus(UUID taskId, String status) {
        WorkflowTask task = workflowTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(status);
        if ("RUNNING".equals(status) && task.getStartedAt() == null) {
            task.setStartedAt(OffsetDateTime.now());
        }
        workflowTaskMapper.updateById(task);
    }

    @Transactional
    public void appendStep(UUID taskId, StepRecord step) {
        WorkflowTask task = workflowTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        List<Object> steps = readSteps(task.getSteps());
        steps.add(step);
        task.setSteps(toJson(steps));
        workflowTaskMapper.updateById(task);
    }

    @Transactional
    public void completeTask(UUID taskId, String resultSummary, String resultData,
                             int llmTokensUsed, int totalDurationMs) {
        WorkflowTask task = workflowTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus("DONE");
        task.setResultSummary(resultSummary);
        task.setResultData(resultData);
        task.setLlmTokensUsed(llmTokensUsed);
        task.setTotalDurationMs(totalDurationMs);
        task.setCompletedAt(OffsetDateTime.now());
        workflowTaskMapper.updateById(task);
    }

    @Transactional
    public void failTask(UUID taskId, String errorMessage) {
        WorkflowTask task = workflowTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus("FAILED");
        task.setErrorMessage(errorMessage);
        task.setCompletedAt(OffsetDateTime.now());
        workflowTaskMapper.updateById(task);
    }

    public WorkflowTask findById(UUID taskId) {
        return workflowTaskMapper.selectById(taskId);
    }

    @SuppressWarnings("unchecked")
    private List<Object> readSteps(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            Object value = objectMapper.readValue(stepsJson, List.class);
            if (value instanceof List<?> list) {
                return new ArrayList<>((List<Object>) list);
            }
            return new ArrayList<>();
        } catch (JacksonException e) {
            return new ArrayList<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize workflow task step", e);
        }
    }

    public record StepRecord(
            String stepName,
            String status,
            long startedAt,
            Long doneAt,
            Object input,
            Object output,
            String errorMessage
    ) {
    }
}

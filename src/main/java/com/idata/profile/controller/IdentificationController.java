package com.idata.profile.controller;

import com.idata.profile.common.response.Result;
import com.idata.profile.entity.task.IdentificationTask;
import com.idata.profile.identification.IdentificationTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/identification")
@RequiredArgsConstructor
public class IdentificationController {

    private final IdentificationTaskService identificationTaskService;

    @PostMapping("/narratives/{narrativeId}/identify")
    public Result<IdentificationTask> identifyByNarrative(@PathVariable UUID narrativeId) {
        return Result.ok(identificationTaskService.identifyByNarrative(narrativeId));
    }

    @PostMapping("/tasks/{taskId}/run")
    public Result<IdentificationTask> runTask(@PathVariable UUID taskId) {
        return Result.ok(identificationTaskService.runTask(taskId));
    }

    @GetMapping("/tasks/{taskId}")
    public Result<IdentificationTask> getTask(@PathVariable UUID taskId) {
        IdentificationTask task = identificationTaskService.getTask(taskId);
        if (task == null) {
            return Result.fail("NOT_FOUND", "任务不存在");
        }
        return Result.ok(task);
    }
}

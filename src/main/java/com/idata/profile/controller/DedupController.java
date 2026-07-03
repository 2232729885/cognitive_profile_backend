package com.idata.profile.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.idata.profile.common.response.Result;
import com.idata.profile.dedup.FusionService;
import com.idata.profile.entity.dedup.EntityFusionRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/dedup")
@RequiredArgsConstructor
public class DedupController {

    private final FusionService fusionService;

    @GetMapping("/fusion-records")
    public Result<IPage<EntityFusionRecord>> listFusionRecords(
            @RequestParam(required = false) String entityType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!fusionService.isValidEntityType(entityType)) {
            return Result.fail("INVALID_PARAM", "entityType只能是person/organization/event/narrative");
        }
        return Result.ok(fusionService.listFusionRecords(entityType, page, size));
    }

    @GetMapping("/fusion-records/{jobRunId}")
    public Result<List<EntityFusionRecord>> listByJobRunId(@PathVariable UUID jobRunId) {
        return Result.ok(fusionService.listByJobRunId(jobRunId));
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(fusionService.stats());
    }

    @PostMapping("/trigger")
    public Result<String> trigger() {
        UUID jobRunId = fusionService.trigger();
        if (jobRunId == null) {
            return Result.fail("JOB_RUNNING", "任务已在运行中");
        }
        return Result.ok(jobRunId.toString());
    }
}

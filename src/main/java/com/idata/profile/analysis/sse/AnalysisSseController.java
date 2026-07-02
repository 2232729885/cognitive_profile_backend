package com.idata.profile.analysis.sse;

import com.idata.profile.analysis.orchestrator.CoordinatorAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class AnalysisSseController {

    private final CoordinatorAgentService coordinatorAgentService;

    @GetMapping("/api/analysis/{taskId}/stream")
    public SseEmitter stream(@PathVariable String taskId,
                             @RequestParam(required = false) String token) {
        return coordinatorAgentService.registerEmitter(taskId);
    }
}

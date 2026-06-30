package com.idata.profile.analysis.sse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 分析任务执行过程的SSE实时推送给前端。 */
@RestController
public class AnalysisSseController {

    @GetMapping("/api/analysis/{taskId}/stream")
    public SseEmitter stream(String taskId) {
        // TODO: 建立SSE连接，推送各步骤进度（步骤开始/完成、画像补全进度、任务完成/降级）
        throw new UnsupportedOperationException("TODO");
    }
}

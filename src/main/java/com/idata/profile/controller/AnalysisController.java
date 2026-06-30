package com.idata.profile.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F3 智能分析工作台。
 * 自然语言对话发起分析、会话历史管理，实际SSE推送见
 * analysis.sse.AnalysisSseController（单独的SSE端点，不在这里）。
 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {
    // TODO: 发起分析任务接口（创建workflow_tasks）、会话列表/历史接口
}

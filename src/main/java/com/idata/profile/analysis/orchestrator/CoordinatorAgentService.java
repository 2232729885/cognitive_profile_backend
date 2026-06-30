package com.idata.profile.analysis.orchestrator;

import org.springframework.stereotype.Service;

/**
 * 分析线（第二条线）核心：协调Agent。
 * 用户触发，意图解析→执行计划生成→调度T4/T6/T5→SSE推送→降级固定工作流。
 * 见 docs/课题三向课题四数据交付需求.md 及历次架构图讨论中的"分析线"设计。
 *
 * 这一层涉及Spring AI（pom.xml已引入spring-ai-bom）的Function Calling能力，
 * 具体实现待单独排期，当前先占位标记包结构。
 */
@Service
public class CoordinatorAgentService {
    // TODO: 意图解析、执行计划生成（JSON Schema校验+重试2次）、
    //   T4检索→T6识别→T5补全的调度、LLM失败时降级固定工作流
}

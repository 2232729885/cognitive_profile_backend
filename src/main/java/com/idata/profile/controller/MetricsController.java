package com.idata.profile.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F7 指标看板与报告。
 * T1-T6功能性指标、数据规模统计（画像库规模、三元组数量）、报告导出。
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {
    // TODO: 指标查询接口（复用 mapper.task.PipelineTaskMapper.selectPerformanceStats等）
}

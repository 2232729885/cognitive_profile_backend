package com.idata.profile.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F1 数据接入与管理。
 * 批量文件上传（JSON/JSONL/CSV/Excel/ZIP）、处理状态跟踪（RECEIVED→T4_INDEXED）、
 * Kafka消费进度监控、采集任务覆盖范围查询（collection_tasks）。
 */
@RestController
@RequestMapping("/api/ingestion")
public class DataIngestionController {
    // TODO: 批量上传接口、batch_import_tasks查询接口、
    //   raw_records按pipeline_status统计接口
}

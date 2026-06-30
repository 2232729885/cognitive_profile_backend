package com.idata.profile.common.constant;

/**
 * pipeline_tasks.t1_status / t2_status / t3_status / t4_status 单步状态。
 */
public enum StepStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    /** 仅用于非流水线类型在 pipeline_tasks 中的占位（实际上这些类型不会创建 pipeline_tasks 记录） */
    SKIPPED
}

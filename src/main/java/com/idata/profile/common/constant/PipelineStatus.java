package com.idata.profile.common.constant;

/**
 * raw_records.pipeline_status 状态机。
 * 仅 SOCIAL_CONTENT / NEWS_ARTICLE 会经历 T1_DONE→T2_DONE→T3_DONE→T4_INDEXED 这几步，
 * 其余四种类型直接 RECEIVED→NORMALIZED 即为终态。
 */
public enum PipelineStatus {

    /** 刚写入 raw_records，尚未做任何标准化处理 */
    RECEIVED,

    /** 已完成L1标准化映射。非流水线类型的终态 */
    NORMALIZED,

    /** T1自动标注完成 */
    T1_DONE,

    /** T2信息抽取完成 */
    T2_DONE,

    /** T3信息融合完成（可与T4并行） */
    T3_DONE,

    /** T4索引构建完成，流水线类型的终态 */
    T4_INDEXED,

    /** 重试超过 max_retries 次后的失败终态，推入死信队列 */
    FAILED
}

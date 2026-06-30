package com.idata.profile.common.constant;

/**
 * 课题三六种数据类型，对应 raw_records.record_type 字段。
 * 决定接入适配器走哪条标准化映射路径，以及是否进入 T1-T4 流水线。
 *
 * @see PipelineStatus
 */
public enum RecordType {

    /** 社交媒体内容（主贴/评论/转发/引用等）。走完整 T1→T2→T3→T4 流水线 */
    SOCIAL_CONTENT("social_content", true),

    /** 账号/频道/群组快照。只做标准化映射，不走流水线 */
    SOCIAL_ACCOUNT("social_account", false),

    /** 账号关系（关注/订阅/成员/管理员）。课题三v2.0新增，替代原interaction。不走流水线 */
    ACCOUNT_RELATION("account_relation", false),

    /** 多媒体附件（图片/视频/音频）。不走流水线，T4异步补图像向量 */
    MEDIA_ASSET("media_asset", false),

    /** 新闻报道。处理路径与 SOCIAL_CONTENT 完全相同，走完整流水线 */
    NEWS_ARTICLE("news_article", true),

    /** 采集任务元数据。不走流水线，仅用于验收核查数据覆盖范围 */
    COLLECTION_TASK("collection_task", false);

    private final String code;
    private final boolean requiresPipeline;

    RecordType(String code, boolean requiresPipeline) {
        this.code = code;
        this.requiresPipeline = requiresPipeline;
    }

    public String getCode() {
        return code;
    }

    /** 是否需要进入 T1-T4 流水线（仅 SOCIAL_CONTENT / NEWS_ARTICLE 为 true） */
    public boolean requiresPipeline() {
        return requiresPipeline;
    }

    public static RecordType fromCode(String code) {
        for (RecordType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的 record_type: " + code);
    }
}

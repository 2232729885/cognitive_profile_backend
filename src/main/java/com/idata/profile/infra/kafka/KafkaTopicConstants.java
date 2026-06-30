package com.idata.profile.infra.kafka;

/**
 * Kafka Topic名称常量。与课题三约定的topic命名对齐，
 * 见 docs/课题三向课题四数据交付需求.md。
 */
public final class KafkaTopicConstants {

    public static final String SOCIAL_CONTENT = "kt3.social_content";
    public static final String SOCIAL_ACCOUNT = "kt3.social_account";
    public static final String ACCOUNT_RELATION = "kt3.account_relation";
    public static final String MEDIA_ASSET = "kt3.media_asset";
    public static final String NEWS_ARTICLE = "kt3.news_article";
    public static final String COLLECTION_TASK = "kt3.collection_task";

    /** 内部流转用topic（pipeline_tasks触发，可选方案，见Step4触发方式B） */
    public static final String PIPELINE_PENDING = "kt4.pipeline.pending";

    private KafkaTopicConstants() {}
}

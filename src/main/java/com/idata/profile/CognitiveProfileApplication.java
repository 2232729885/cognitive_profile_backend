package com.idata.profile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.retry.annotation.EnableRetry;

/**
 * 课题四全息画像系统 · 启动类
 *
 * @EnableScheduling 支撑 batch 包下的定时任务：
 *   - AccountRelationBackfillJob（账号关系UUID回填）
 *   - ImageEmbeddingJob（T4异步图像向量化）
 *   - PersonProfileGenerationJob（画像批量生成）
 *
 * @EnableRetry 支撑 pipeline.retry 包下 T1-T4 失败重试（指数退避）
 */
@SpringBootApplication
@EnableScheduling
@EnableRetry
public class CognitiveProfileApplication {

    public static void main(String[] args) {
        SpringApplication.run(CognitiveProfileApplication.class, args);
    }
}

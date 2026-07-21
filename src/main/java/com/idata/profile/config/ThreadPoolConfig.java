package com.idata.profile.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 线程池配置。
 *
 * pipelineThreadPool: pipeline.PipelineExecutor 用于异步执行T1-T4，
 *   见 docs/01-CODEGEN-CONTEXT.md 3.1节。T3和T4并行触发也用这个池。
 *
 * taskScheduler: pipeline.retry.RetryHandler 用于指数退避延迟重试调度。
 */
@Configuration
public class ThreadPoolConfig {

    @Bean
    public ExecutorService pipelineThreadPool(
            @Value("${pipeline.thread-pool-size:64}") int threadPoolSize) {
        // TODO: 按实际吞吐量调整线程数，初期可用固定大小，
        // 生产环境建议根据T1-T6 Agent的并发承载能力调整
        return Executors.newFixedThreadPool(Math.max(1, threadPoolSize));
    }

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("pipeline-retry-");
        scheduler.initialize();
        return scheduler;
    }
}

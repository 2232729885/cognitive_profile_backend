package com.idata.profile.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.RejectedExecutionException;

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
            @Value("${pipeline.thread-pool-size:16}") int threadPoolSize,
            @Value("${pipeline.thread-pool-queue-capacity:32}") int queueCapacity) {
        int poolSize = Math.max(1, threadPoolSize);
        int capacity = Math.max(1, queueCapacity);
        // 不使用 CallerRunsPolicy：Kafka afterCommit 回调可能在消费者线程中执行，
        // 让消费者线程直接跑流水线会把 Neo4j 写入带入已提交的事务上下文。
        // 队列满时阻塞提交者，直到 pipeline-worker 有空位，但始终由工作线程执行任务。
        RejectedExecutionHandler backpressure = (runnable, executor) -> {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("Pipeline executor is shutting down");
            }
            try {
                executor.getQueue().put(runnable);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Interrupted while enqueueing pipeline task", e);
            }
        };
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "pipeline-worker");
                    thread.setDaemon(false);
                    return thread;
                },
                backpressure);
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

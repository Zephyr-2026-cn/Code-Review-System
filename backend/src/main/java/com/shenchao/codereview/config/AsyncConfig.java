package com.shenchao.codereview.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置。
 *
 * <p>按用途隔离，禁止共用：审查任务是长任务（分钟级），审计日志是短任务（毫秒级）。
 * 若共用队列，一次批量审查会挤占队列导致审计日志全部丢失。
 *
 * <p>拒绝策略的选择：
 * <ul>
 *   <li>审计：{@code CallerRunsPolicy}——宁可拖慢业务线程，也不能丢审计（合规要求）</li>
 *   <li>审查：{@code AbortPolicy}——队列满时明确失败并告警，避免任务静默堆积到 OOM</li>
 * </ul>
 */
@Slf4j
@Configuration
public class AsyncConfig {

    public static final String AUDIT_EXECUTOR = "auditExecutor";
    public static final String REVIEW_EXECUTOR = "reviewExecutor";

    @Bean(name = AUDIT_EXECUTOR)
    public ThreadPoolTaskExecutor auditExecutor(
            @Value("${app.async.audit.core-pool-size:2}") int corePoolSize,
            @Value("${app.async.audit.max-pool-size:8}") int maxPoolSize,
            @Value("${app.async.audit.queue-capacity:2000}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("audit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    @Bean(name = REVIEW_EXECUTOR)
    public ThreadPoolTaskExecutor reviewExecutor(
            @Value("${app.async.review.core-pool-size:4}") int corePoolSize,
            @Value("${app.async.review.max-pool-size:8}") int maxPoolSize,
            @Value("${app.async.review.queue-capacity:100}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("review-");
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            log.error("审查线程池已满，任务被拒绝：poolSize={}, active={}, queue={}",
                    pool.getPoolSize(), pool.getActiveCount(), pool.getQueue().size());
            throw new java.util.concurrent.RejectedExecutionException("审查线程池已满，请稍后重试");
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}

package com.shenchao.codereview.task.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 审查执行相关配置。
 *
 * @param enabled        总开关；关闭后任务只入库不入队（便于演示任务状态机而不真跑分析）
 * @param concurrency    worker 线程数，与 CPU 核数同量级即可；PMD 内部还会并行
 * @param queueKey       Redis Stream 键名
 * @param consumerGroup  消费者组名
 * @param pollBlock      单次 XREADGROUP 阻塞时长
 * @param idleBackoff    队列空/故障时的退避时长
 * @param taskTimeout    单任务总超时，超时截断并记 PARTIAL
 * @param staleTimeout   在途任务无心跳多久判定为僵尸
 * @param stuckCreatedAge CREATED 状态超过多久视为"没入队成功"，由恢复任务重新投递
 * @param maxFiles       单任务最多分析的文件数，超出截断
 * @param maxFileSizeKb  单文件大小上限，超出跳过（生成代码、压缩资源会拖垮解析）
 * @param includeTests   是否把 src/test 也纳入扫描
 * @param reporterMaxIssues 报告接口最多返回的缺陷数
 */
@ConfigurationProperties(prefix = "app.review")
public record ReviewProperties(
        Boolean enabled,
        Integer concurrency,
        String queueKey,
        String consumerGroup,
        Duration pollBlock,
        Duration idleBackoff,
        Duration taskTimeout,
        Duration staleTimeout,
        Duration stuckCreatedAge,
        Integer maxFiles,
        Integer maxFileSizeKb,
        Boolean includeTests,
        Integer reporterMaxIssues
) {

    public ReviewProperties {
        enabled = enabled == null || enabled;
        concurrency = concurrency == null || concurrency < 1 ? 2 : Math.min(concurrency, 16);
        queueKey = (queueKey == null || queueKey.isBlank()) ? "review:queue" : queueKey;
        consumerGroup = (consumerGroup == null || consumerGroup.isBlank()) ? "review-workers" : consumerGroup;
        pollBlock = pollBlock == null ? Duration.ofSeconds(2) : pollBlock;
        idleBackoff = idleBackoff == null ? Duration.ofSeconds(2) : idleBackoff;
        taskTimeout = taskTimeout == null ? Duration.ofMinutes(5) : taskTimeout;
        staleTimeout = staleTimeout == null ? Duration.ofMinutes(15) : staleTimeout;
        stuckCreatedAge = stuckCreatedAge == null ? Duration.ofSeconds(30) : stuckCreatedAge;
        maxFiles = maxFiles == null || maxFiles < 1 ? 2000 : maxFiles;
        maxFileSizeKb = maxFileSizeKb == null || maxFileSizeKb < 1 ? 1024 : maxFileSizeKb;
        includeTests = includeTests != null && includeTests;
        reporterMaxIssues = reporterMaxIssues == null || reporterMaxIssues < 1 ? 500 : reporterMaxIssues;
    }
}

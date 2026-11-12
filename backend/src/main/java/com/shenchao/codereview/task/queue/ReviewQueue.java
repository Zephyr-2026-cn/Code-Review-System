package com.shenchao.codereview.task.queue;

import java.time.Duration;
import java.util.Optional;

/**
 * 审查任务队列。
 *
 * <p>用接口隔开 Redis 的原因：单元测试与对比实验（E1）不需要真的起一个 Redis，
 * 传一个内存实现即可；生产换 RabbitMQ 也只换实现。
 */
public interface ReviewQueue {

    /**
     * 投递任务。
     *
     * <p>允许重复投递：消费端用数据库条件更新做抢占，重复消息会被直接丢弃。
     * 这比"保证只投递一次"现实得多——分布式环境下真正的 exactly-once 不存在。
     *
     * <p><b>不抛异常</b>：投递发生在事务提交之后，此时抛异常只会让已经落库的任务
     * 返回 500，用户看到的失败与数据库实际状态不一致。投递失败记日志即可，
     * 任务停留在 {@code CREATED}，由恢复任务重新投递。
     *
     * @return 是否投递成功
     */
    boolean enqueue(long taskId);

    /**
     * 拉取一条消息。
     *
     * @param block 最长阻塞时间
     * @return 空表示这段时间没有新消息
     */
    Optional<QueueMessage> poll(Duration block);

    /** 确认消息已处理（无论成功失败都要 ack，否则会一直挂在 pending 列表）。 */
    void ack(QueueMessage message);

    /** 裁剪历史消息，避免 Stream 无限增长。 */
    void trim();

    /** 队列是否可用；Redis 故障时返回 false，调用方据此决定是否降级。 */
    boolean available();

    /** 队列消息。 */
    record QueueMessage(String id, Long taskId) {
    }
}

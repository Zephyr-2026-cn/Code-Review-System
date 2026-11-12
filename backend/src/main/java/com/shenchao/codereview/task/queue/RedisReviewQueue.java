package com.shenchao.codereview.task.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 Redis Stream 消费者组的任务队列。
 *
 * <p>为什么是 Stream 而不是 List：
 * <ul>
 *   <li>List 的 {@code LPOP} 出队即丢失，进程崩溃时消息直接蒸发</li>
 *   <li>Stream 有 pending 列表，消息处理到一半进程被杀，仍可用 {@code XAUTOCLAIM} 找回</li>
 *   <li>消费者组天然支持多 worker 分摊，不需要自己实现分布式锁</li>
 * </ul>
 *
 * <p>可靠性仍然依赖数据库兜底：Stream 只保证"至少一次"，
 * 真正防重复执行的是 {@code review_task} 的条件更新（见 ReviewTaskMapper）。
 */
@Slf4j
@Component
public class RedisReviewQueue implements ReviewQueue {

    /** 保留的历史消息条数，够排障用，又不至于把内存占满。 */
    private static final long MAX_STREAM_LEN = 5000;

    private final StringRedisTemplate redisTemplate;
    private final ReviewProperties properties;
    private final String consumerName;

    /** 消费者组是否已创建；用 volatile + 双检锁，避免每个 worker 线程都去建一次组。 */
    private volatile boolean groupReady;

    /** Redis 故障时只打一次完整堆栈，避免刷爆日志。 */
    private volatile long lastErrorLogAt;

    public RedisReviewQueue(StringRedisTemplate redisTemplate, ReviewProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.consumerName = buildConsumerName();
    }

    @Override
    public boolean enqueue(long taskId) {
        try {
            ensureGroup();
            MapRecord<String, String, String> record = MapRecord.create(
                    properties.queueKey(), Map.of("taskId", Long.toString(taskId)));
            redisTemplate.opsForStream().add(record);
            log.debug("任务已入队: taskId={}", taskId);
            return true;
        } catch (RuntimeException ex) {
            logErrorThrottled("任务入队失败，任务将停留在 CREATED 等待恢复任务重投: taskId=" + taskId, ex);
            return false;
        }
    }

    /**
     * 阻塞拉取一条消息。
     *
     * <p>异常不在这里吞：worker 只有收到异常才会退避。若在此处 catch 后返回 empty，
     * worker 会立刻进入下一轮 poll，Redis 持续故障时就是每秒数万次的空转。
     */
    @Override
    public Optional<QueueMessage> poll(Duration block) {
        ensureGroup();
        List<MapRecord<String, String, String>> records = streamOps().read(
                Consumer.from(properties.consumerGroup(), consumerName),
                StreamReadOptions.empty().count(1).block(block),
                StreamOffset.create(properties.queueKey(), ReadOffset.lastConsumed()));

        if (records == null || records.isEmpty()) {
            return Optional.empty();
        }

        MapRecord<String, String, String> record = records.get(0);
        String rawTaskId = record.getValue().get("taskId");
        Long taskId = parseTaskId(rawTaskId);
        if (taskId == null) {
            // 脏消息直接确认掉，否则会阻塞队列头部
            log.error("队列消息缺少合法 taskId，已丢弃: id={}, payload={}", record.getId(), record.getValue());
            ack(new QueueMessage(record.getId().getValue(), null));
            return Optional.empty();
        }
        return Optional.of(new QueueMessage(record.getId().getValue(), taskId));
    }

    @Override
    public void ack(QueueMessage message) {
        if (message == null || message.id() == null) {
            return;
        }
        try {
            streamOps().acknowledge(properties.queueKey(), properties.consumerGroup(), message.id());
        } catch (RuntimeException ex) {
            // ack 失败会导致消息留在 pending，被 XAUTOCLAIM 重新投递；
            // 任务已进入终态，重投时会被条件更新挡掉，因此只要记日志
            logErrorThrottled("消息 ack 失败: id=" + message.id(), ex);
        }
    }

    @Override
    public void trim() {
        try {
            streamOps().trim(properties.queueKey(), MAX_STREAM_LEN, true);
        } catch (RuntimeException ex) {
            logErrorThrottled("裁剪队列失败", ex);
        }
    }

    @Override
    public boolean available() {
        try {
            redisTemplate.hasKey(properties.queueKey());
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // ---------------- 内部 ----------------

    /**
     * 确保消费者组存在。
     *
     * <p>用 {@code MKSTREAM} 语义：组不存在时连 Stream 一起创建，
     * 因此首次部署不需要人工执行 {@code XGROUP CREATE}。
     */
    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        synchronized (this) {
            if (groupReady) {
                return;
            }
            try {
                streamOps().createGroup(properties.queueKey(), ReadOffset.from("0"),
                        properties.consumerGroup());
                log.info("已创建审查队列消费者组: stream={}, group={}",
                        properties.queueKey(), properties.consumerGroup());
            } catch (RuntimeException ex) {
                // 组已存在属于正常情况（多实例部署、重启后再次创建）。
                // 注意不能只看 ex.getMessage()：Spring Data Redis 会把错误包装成
                // "Error in execution"，真正的 BUSYGROUP 在 cause 链末端，
                // 判断失误会让 worker 每一轮都重建组，形成空转刷日志的死循环。
                if (!isGroupAlreadyExists(ex)) {
                    throw ex;
                }
            }
            groupReady = true;
        }
    }

    /** 沿 cause 链找 BUSYGROUP：不依赖具体客户端异常类型，Lettuce/Jedis 都能识别。 */
    private boolean isGroupAlreadyExists(RuntimeException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.toUpperCase(Locale.ROOT).contains("BUSYGROUP")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private StreamOperations<String, String, String> streamOps() {
        return redisTemplate.opsForStream();
    }

    private Long parseTaskId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 一分钟内最多打一次堆栈，Redis 抖动时不至于把磁盘写满。 */
    private void logErrorThrottled(String message, RuntimeException ex) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLogAt > 60_000L) {
            lastErrorLogAt = now;
            log.error(message, ex);
        } else {
            log.warn("{}（同类错误已限流）: {}", message, ex.getMessage());
        }
    }

    private String buildConsumerName() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            host = "unknown";
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        String suffix = UUID.randomUUID().toString().substring(0, 4);
        return "%s-%s-%s".formatted(host, pid, suffix);
    }
}

package com.shenchao.codereview.task.queue;

import io.lettuce.core.RedisBusyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 队列消费者组初始化的边界用例。
 *
 * <p>重点锁住"组已存在"这条路径：Spring Data Redis 会把 BUSYGROUP 包装成
 * {@code RedisSystemException: Error in execution}，只看外层 message 判断会漏，
 * 导致 worker 每轮 poll 都重建组、空转刷日志。
 */
@DisplayName("Redis 审查队列")
class RedisReviewQueueTest {

    private StringRedisTemplate redisTemplate;
    private StreamOperations<String, String, String> streamOps;
    private RedisReviewQueue queue;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        // opsForStream() 的泛型由调用方推断，when(...) 会推成 <String,Object,Object>，
        // 这里用 doReturn 绕开类型推断
        doReturn(streamOps).when(redisTemplate).opsForStream();

        ReviewProperties properties = new ReviewProperties(
                true, 2, "review:stream", "review-workers",
                Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(5),
                2000, 512, false, 500);
        queue = new RedisReviewQueue(redisTemplate, properties);
    }

    @Test
    @DisplayName("消费者组已存在时不报错，且只尝试创建一次")
    void busyGroupIsTreatedAsReady() {
        when(streamOps.createGroup(anyString(), any(ReadOffset.class), anyString()))
                .thenThrow(new RedisSystemException("Error in execution",
                        new RedisBusyException("BUSYGROUP Consumer Group name already exists")));
        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class),
                ArgumentMatchers.<StreamOffset<String>>any()))
                .thenReturn(List.of());

        Optional<ReviewQueue.QueueMessage> first = queue.poll(Duration.ofMillis(10));
        Optional<ReviewQueue.QueueMessage> second = queue.poll(Duration.ofMillis(10));

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        // 第二次 poll 走 groupReady 短路，不该再去建组
        verify(streamOps, times(1)).createGroup(anyString(), any(ReadOffset.class), anyString());
    }

    @Test
    @DisplayName("组已存在但首次创建报 BUSYGROUP 时仍能正常取到消息")
    void busyGroupStillConsumesMessages() {
        when(streamOps.createGroup(anyString(), any(ReadOffset.class), anyString()))
                .thenThrow(new RedisSystemException("Error in execution",
                        new RedisBusyException("BUSYGROUP Consumer Group name already exists")));
        MapRecord<String, String, String> record = MapRecord.create("review:stream", java.util.Map.of("taskId", "42"));
        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class),
                ArgumentMatchers.<StreamOffset<String>>any()))
                .thenReturn(List.of(record));

        Optional<ReviewQueue.QueueMessage> message = queue.poll(Duration.ofMillis(10));

        assertThat(message).isPresent();
        assertThat(message.get().taskId()).isEqualTo(42L);
        assertThat(message.get().id()).isEqualTo(record.getId().getValue());
    }

    @Test
    @DisplayName("Redis 连不上时异常向上抛，交给 worker 退避")
    void redisFailurePropagates() {
        when(streamOps.createGroup(anyString(), any(ReadOffset.class), anyString()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        assertThatThrownBy(() -> queue.poll(Duration.ofMillis(10)))
                .isInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    @DisplayName("脏消息被 ack 丢弃，不会阻塞队头")
    void dirtyMessageIsAcked() {
        when(streamOps.createGroup(anyString(), any(ReadOffset.class), anyString())).thenReturn("OK");
        MapRecord<String, String, String> record = MapRecord.create("review:stream", java.util.Map.of("taskId", "abc"));
        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class),
                ArgumentMatchers.<StreamOffset<String>>any()))
                .thenReturn(List.of(record));

        assertThat(queue.poll(Duration.ofMillis(10))).isEmpty();
        verify(streamOps).acknowledge(eq("review:stream"), eq("review-workers"), eq(record.getId().getValue()));
    }
}

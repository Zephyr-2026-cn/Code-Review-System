package com.shenchao.codereview.task.worker;

import com.shenchao.codereview.task.pipeline.ReviewPipeline;
import com.shenchao.codereview.task.queue.ReviewProperties;
import com.shenchao.codereview.task.queue.ReviewQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 审查任务消费者池。
 *
 * <p>为什么用自建线程而不是 {@code @Async}：
 * <ul>
 *   <li>需要"持续阻塞拉取"的循环，{@code @Async} 是一次性任务模型</li>
 *   <li>需要可控的并发度与优雅停机；Spring 的线程池在这里反而绕</li>
 * </ul>
 *
 * <p>线程全部设为 daemon：即使停机钩子没跑完，也不会阻止 JVM 退出。
 * 正在执行的任务靠 {@code staleTimeout} 巡检兜底（进程被杀时任务会停在 ANALYZING，
 * 由恢复任务标记失败），所以这里不做强制中断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewWorkerPool implements SmartLifecycle {

    private final ReviewQueue queue;
    private final ReviewPipeline pipeline;
    private final ReviewProperties properties;

    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;

    @Override
    public void start() {
        if (!Boolean.TRUE.equals(properties.enabled())) {
            log.warn("审查执行器已禁用（app.review.enabled=false），任务将只入库不入队");
            return;
        }
        running = true;
        int concurrency = properties.concurrency();
        for (int i = 0; i < concurrency; i++) {
            Thread worker = new Thread(this::loop, "review-worker-" + i);
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
        }
        log.info("审查 worker 已启动: 并发={}, 队列={}, 消费组={}",
                concurrency, properties.queueKey(), properties.consumerGroup());
    }

    @Override
    public void stop() {
        running = false;
        workers.forEach(Thread::interrupt);
        workers.clear();
        log.info("审查 worker 已停止");
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 在 Web 容器起来之后再开始消费，避免启动阶段抢占数据库连接。 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private void loop() {
        String threadName = Thread.currentThread().getName();
        log.debug("worker 循环启动: {}", threadName);
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Optional<ReviewQueue.QueueMessage> message = queue.poll(properties.pollBlock());
                if (message.isEmpty()) {
                    continue;
                }
                ReviewQueue.QueueMessage task = message.get();
                try {
                    pipeline.execute(task.taskId());
                } catch (Throwable ex) {
                    // 管道内部已把失败写进任务状态，这里只保证 worker 不被打死
                    log.error("任务执行抛出未捕获异常: taskId={}", task.taskId(), ex);
                } finally {
                    queue.ack(task);
                }
            } catch (Throwable ex) {
                // Redis 抖动、网络中断：退避后继续，不能退出循环
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.error("worker 拉取任务失败，{} 后重试", properties.idleBackoff(), ex);
                sleepQuietly();
            }
        }
        log.debug("worker 循环退出: {}", threadName);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(properties.idleBackoff().toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}

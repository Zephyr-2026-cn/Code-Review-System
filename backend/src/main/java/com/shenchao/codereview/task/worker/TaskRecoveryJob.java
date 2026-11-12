package com.shenchao.codereview.task.worker;

import com.shenchao.codereview.task.entity.ReviewTask;
import com.shenchao.codereview.task.mapper.ReviewTaskMapper;
import com.shenchao.codereview.task.model.TaskStatus;
import com.shenchao.codereview.task.queue.ReviewProperties;
import com.shenchao.codereview.task.queue.ReviewQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 任务恢复与僵尸巡检。
 *
 * <p>解决两类"任务永远卡住"的场景：
 * <ol>
 *   <li><b>入了库但没入队</b>：投递时 Redis 不可用，任务停在 CREATED/QUEUED。
 *       这里按 {@code stuckCreatedAge} 重新投递，重复投递由执行侧的条件更新兜底</li>
 *   <li><b>执行中被杀</b>：进程崩溃或容器重启，任务停在 FETCHING..FUSING。
 *       超过 {@code staleTimeout} 无心跳即判失败，把失败阶段写清楚</li>
 * </ol>
 *
 * <p>巡检与执行业务共用同一台机器，所以用 {@code fixedDelay} 而不是 cron：
 * 上一次没跑完不会再起一个，避免多个实例同时改同一批任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRecoveryJob {

    private static final int BATCH = 20;

    private final ReviewTaskMapper taskMapper;
    private final ReviewQueue queue;
    private final ReviewProperties properties;

    @Scheduled(fixedDelayString = "${app.review.recovery-interval:60s}", initialDelayString = "20s")
    public void recover() {
        if (!Boolean.TRUE.equals(properties.enabled())) {
            return;
        }
        requeueStuck();
        failStale();
        queue.trim();
    }

    /** 重新投递长时间停留在 CREATED/QUEUED 的任务。 */
    private void requeueStuck() {
        OffsetDateTime before = OffsetDateTime.now().minus(properties.stuckCreatedAge());
        List<ReviewTask> stuck = taskMapper.selectRequeueCandidates(before, BATCH);
        for (ReviewTask task : stuck) {
            // 只有真正抢到 CREATED → QUEUED 的实例才投递，避免与正常创建流程重复入队
            int moved = taskMapper.compareAndSetStatus(task.getId(), TaskStatus.CREATED.name(),
                    TaskStatus.QUEUED.name());
            if (moved == 0 && !TaskStatus.QUEUED.name().equals(task.getStatus())) {
                continue;
            }
            if (queue.enqueue(task.getId())) {
                log.info("恢复任务：重新投递成功: taskId={}, 原状态={}", task.getId(), task.getStatus());
            }
        }
    }

    /** 在途超时的任务判失败，写清卡在哪个阶段。 */
    private void failStale() {
        OffsetDateTime before = OffsetDateTime.now().minus(properties.staleTimeout());
        List<ReviewTask> stale = taskMapper.selectStaleActive(before, BATCH);
        for (ReviewTask task : stale) {
            String stage = task.getStatus();
            String reason = "任务超过 " + properties.staleTimeout().toMinutes()
                    + " 分钟无进度更新，判定为执行中断（可能因进程重启）";
            int updated = taskMapper.finish(task.getId(), TaskStatus.FAILED.name(), stage, reason,
                    task.getIssueCount() == null ? 0 : task.getIssueCount(),
                    "{\"watchdog\":true}");
            if (updated > 0) {
                log.warn("巡检判定任务失败: taskId={}, stage={}, 最后更新={}", task.getId(), stage, task.getUpdatedAt());
            }
        }
    }
}

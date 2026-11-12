package com.shenchao.codereview.task.dto;

import com.shenchao.codereview.task.entity.ReviewTask;
import com.shenchao.codereview.task.model.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 任务视图。
 *
 * <p>{@code progress} 由服务端按状态给出，而不是前端自己映射：
 * 状态机是后端的知识，前端只负责画进度条。
 */
@Schema(description = "审查任务")
public record TaskVO(
        @Schema(description = "任务 ID") Long id,
        @Schema(description = "项目 ID") Long projectId,
        @Schema(description = "项目名称") String projectName,
        @Schema(description = "项目编码") String projectCode,
        @Schema(description = "状态") String status,
        @Schema(description = "状态中文名") String statusLabel,
        @Schema(description = "进度百分比 0-100") int progress,
        @Schema(description = "审查模式") String reviewMode,
        @Schema(description = "触发方式") String triggerType,
        @Schema(description = "基线提交") String baseCommit,
        @Schema(description = "目标提交") String headCommit,
        @Schema(description = "目标提交短号") String shortCommit,
        @Schema(description = "失败阶段") String failStage,
        @Schema(description = "失败原因") String failReason,
        @Schema(description = "缺陷数") Integer issueCount,
        @Schema(description = "引擎开关快照") Map<String, Object> engineFlags,
        @Schema(description = "统计信息") Map<String, Object> stats,
        @Schema(description = "发起人 ID") Long createdBy,
        @Schema(description = "发起人") String createdByName,
        @Schema(description = "开始时间") OffsetDateTime startedAt,
        @Schema(description = "结束时间") OffsetDateTime finishedAt,
        @Schema(description = "耗时(ms)") Long durationMs,
        @Schema(description = "创建时间") OffsetDateTime createdAt
) {

    public static TaskVO of(ReviewTask task,
                            String projectName,
                            String projectCode,
                            String createdByName,
                            Map<String, Object> engineFlags,
                            Map<String, Object> stats) {
        TaskStatus status = TaskStatus.fromCode(task.getStatus());
        return new TaskVO(
                task.getId(),
                task.getProjectId(),
                projectName,
                projectCode,
                status.name(),
                status.label(),
                status.progress(),
                task.getReviewMode(),
                task.getTriggerType(),
                task.getBaseCommit(),
                task.getHeadCommit(),
                shortCommit(task.getHeadCommit()),
                task.getFailStage(),
                task.getFailReason(),
                task.getIssueCount(),
                engineFlags,
                stats,
                task.getCreatedBy(),
                createdByName,
                task.getStartedAt(),
                task.getFinishedAt(),
                durationOf(task),
                task.getCreatedAt());
    }

    /** 只保留前 8 位，列表页不需要完整 40 位 SHA。 */
    public static String shortCommit(String commit) {
        if (commit == null || commit.isBlank()) {
            return null;
        }
        return commit.length() <= 8 ? commit : commit.substring(0, 8);
    }

    private static Long durationOf(ReviewTask task) {
        if (task.getStartedAt() == null) {
            return null;
        }
        OffsetDateTime end = task.getFinishedAt() == null ? OffsetDateTime.now() : task.getFinishedAt();
        return Math.max(0, Duration.between(task.getStartedAt(), end).toMillis());
    }
}

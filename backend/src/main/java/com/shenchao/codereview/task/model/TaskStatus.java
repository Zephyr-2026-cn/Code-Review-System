package com.shenchao.codereview.task.model;

import java.util.List;
import java.util.Set;

/**
 * 审查任务状态机。
 *
 * <p>状态不是标签，而是有向图：只有 {@link #canTransitTo} 允许的迁移才写库，
 * 非法迁移一律以 409 返回。这样"任务已完成后又被 worker 改成 RUNNING"这类脏状态
 * 在数据库层面就不可能发生。
 *
 * <pre>
 * CREATED → QUEUED → FETCHING → SLICING → ANALYZING → FUSING → DONE / PARTIAL
 *    └──────────────┴───────────┴─────────┴───────────┴─────────→ FAILED / CANCELED
 * </pre>
 */
public enum TaskStatus {

    /** 已入库，尚未进入队列。 */
    CREATED(0, "已创建"),

    /** 已入队，等待 worker。 */
    QUEUED(5, "排队中"),

    /** 校验工作目录与提交。 */
    FETCHING(15, "拉取代码"),

    /** 收集待分析文件（全量扫描 / 增量切片）。 */
    SLICING(30, "代码切片"),

    /** 静态引擎分析。 */
    ANALYZING(60, "引擎分析"),

    /** 结果归一、去重、融合。 */
    FUSING(90, "结果融合"),

    /** 完成。 */
    DONE(100, "已完成"),

    /** 完成但有截断（超时或文件数超上限），结果可用但不完整。 */
    PARTIAL(100, "部分完成"),

    /** 失败，{@code fail_stage} 指出卡在哪一步。 */
    FAILED(100, "失败"),

    /** 已取消。 */
    CANCELED(100, "已取消");

    private static final Set<TaskStatus> ACTIVE = Set.of(
            CREATED, QUEUED, FETCHING, SLICING, ANALYZING, FUSING);

    private final int progress;
    private final String label;

    TaskStatus(int progress, String label) {
        this.progress = progress;
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 前端进度条百分比。 */
    public int progress() {
        return progress;
    }

    public boolean terminal() {
        return !ACTIVE.contains(this);
    }

    /** 是否在途（会被僵尸巡检与"同提交去重"索引关注）。 */
    public boolean active() {
        return ACTIVE.contains(this);
    }

    /** 状态机允许的迁移表。 */
    public boolean canTransitTo(TaskStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case CREATED -> target == QUEUED || target == CANCELED || target == FAILED;
            case QUEUED -> target == FETCHING || target == CANCELED || target == FAILED;
            case FETCHING -> target == SLICING || target == CANCELED || target == FAILED;
            case SLICING -> target == ANALYZING || target == CANCELED || target == FAILED;
            case ANALYZING -> target == FUSING || target == CANCELED || target == FAILED;
            case FUSING -> target == DONE || target == PARTIAL || target == CANCELED || target == FAILED;
            case DONE, PARTIAL, FAILED, CANCELED -> false;
        };
    }

    /** 在途状态的字符串列表，供 SQL 使用。 */
    public static List<String> activeNames() {
        return ACTIVE.stream().map(Enum::name).sorted().toList();
    }

    public static TaskStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return CREATED;
        }
        for (TaskStatus status : values()) {
            if (status.name().equalsIgnoreCase(code.trim())) {
                return status;
            }
        }
        return CREATED;
    }
}

package com.shenchao.codereview.task.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 审查任务。
 *
 * <p>状态机字段是本表的灵魂：{@code status} 表示当前阶段，{@code failStage} 记录失败发生在哪一步，
 * 前端据此显示"卡在哪一步"，而不是笼统地报"审查失败"。
 */
@Getter
@Setter
@TableName("review_task")
public class ReviewTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** MANUAL / WEBHOOK。 */
    private String triggerType;

    private String baseCommit;

    private String headCommit;

    /** FULL / INCREMENTAL。 */
    private String reviewMode;

    /** 见 {@link com.shenchao.codereview.task.model.TaskStatus}。 */
    private String status;

    private String failStage;

    private String failReason;

    /** 运行中请求取消：置位后由 worker 在阶段之间检查并退出。 */
    private Boolean cancelRequested;

    /** JSONB：{"pmd":true,"spotbugs":false,"llm":false,"rag":false}。 */
    private String engineFlags;

    /** JSONB：各阶段耗时、文件数、缺陷数快照。 */
    private String statsJson;

    private Integer issueCount;

    private Long createdBy;

    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    public boolean cancelRequested() {
        return Boolean.TRUE.equals(cancelRequested);
    }
}

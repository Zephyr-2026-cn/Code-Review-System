package com.shenchao.codereview.task.dto;

import com.shenchao.codereview.common.api.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "审查任务查询条件")
public class TaskQuery extends PageQuery {

    @Schema(description = "项目 ID")
    private Long projectId;

    @Schema(description = "任务状态：CREATED/QUEUED/FETCHING/SLICING/ANALYZING/FUSING/DONE/PARTIAL/FAILED/CANCELED")
    @Pattern(regexp = "^(CREATED|QUEUED|FETCHING|SLICING|ANALYZING|FUSING|DONE|PARTIAL|FAILED|CANCELED)$",
            message = "状态取值不合法")
    private String status;

    @Schema(description = "审查模式：FULL / INCREMENTAL")
    @Pattern(regexp = "^(FULL|INCREMENTAL)$", message = "审查模式取值不合法")
    private String reviewMode;

    @Schema(description = "仅看未完成的任务（含排队中与执行中）")
    private Boolean activeOnly;

    @Schema(description = "仅看我发起的任务")
    private Boolean mineOnly;

    @Schema(description = "提交号模糊匹配")
    @Size(max = 64, message = "提交号长度不能超过 64")
    private String commit;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReviewMode() {
        return reviewMode;
    }

    public void setReviewMode(String reviewMode) {
        this.reviewMode = reviewMode;
    }

    public Boolean getActiveOnly() {
        return activeOnly;
    }

    public void setActiveOnly(Boolean activeOnly) {
        this.activeOnly = activeOnly;
    }

    public Boolean getMineOnly() {
        return mineOnly;
    }

    public void setMineOnly(Boolean mineOnly) {
        this.mineOnly = mineOnly;
    }

    public String getCommit() {
        return commit == null ? null : commit.trim();
    }

    public void setCommit(String commit) {
        this.commit = commit;
    }
}

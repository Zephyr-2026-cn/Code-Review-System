package com.shenchao.codereview.task.dto;

import com.shenchao.codereview.common.api.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "问题查询条件")
public class IssueQuery extends PageQuery {

    @Schema(description = "任务 ID")
    private Long taskId;

    @Schema(description = "项目 ID")
    private Long projectId;

    @Schema(description = "严重度：BLOCKER/CRITICAL/MAJOR/MINOR/INFO")
    @Pattern(regexp = "^(BLOCKER|CRITICAL|MAJOR|MINOR|INFO)$", message = "严重度取值不合法")
    private String severity;

    @Schema(description = "类别：NULL_POINTER/RESOURCE_LEAK/CONCURRENCY/INJECTION/PERFORMANCE/MAINTAINABILITY/LOGIC_ERROR/CODE_STYLE")
    @Size(max = 32, message = "类别长度不能超过 32")
    private String category;

    @Schema(description = "来源：STATIC/LLM/FUSED")
    @Pattern(regexp = "^(STATIC|LLM|FUSED)$", message = "来源取值不合法")
    private String source;

    @Schema(description = "处理状态：OPEN/CONFIRMED/FALSE_POSITIVE/FIXED/WONTFIX")
    @Size(max = 24, message = "状态长度不能超过 24")
    private String status;

    @Schema(description = "文件路径模糊匹配")
    @Size(max = 512, message = "路径长度不能超过 512")
    private String filePath;

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFilePath() {
        return filePath == null ? null : filePath.trim();
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }
}

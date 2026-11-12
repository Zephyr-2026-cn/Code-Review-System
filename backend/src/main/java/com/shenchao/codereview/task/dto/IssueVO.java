package com.shenchao.codereview.task.dto;

import com.shenchao.codereview.engine.model.DefectCategory;
import com.shenchao.codereview.engine.model.Severity;
import com.shenchao.codereview.task.entity.ReviewIssue;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/** 缺陷视图。 */
@Schema(description = "缺陷/问题")
public record IssueVO(
        @Schema(description = "问题 ID") Long id,
        @Schema(description = "任务 ID") Long taskId,
        @Schema(description = "项目 ID") Long projectId,
        @Schema(description = "文件相对路径") String filePath,
        @Schema(description = "起始行") Integer startLine,
        @Schema(description = "结束行") Integer endLine,
        @Schema(description = "类别") String category,
        @Schema(description = "类别中文名") String categoryLabel,
        @Schema(description = "严重度") String severity,
        @Schema(description = "严重度中文名") String severityLabel,
        @Schema(description = "结论") String title,
        @Schema(description = "详细说明") String description,
        @Schema(description = "修复建议") String suggestion,
        @Schema(description = "修复片段") String fixSnippet,
        @Schema(description = "来源：STATIC/LLM/FUSED") String source,
        @Schema(description = "规则标识") String ruleId,
        @Schema(description = "置信度 0-1") Double confidence,
        @Schema(description = "融合标记") String flag,
        @Schema(description = "处理状态") String status,
        @Schema(description = "发现时间") OffsetDateTime createdAt
) {

    public static IssueVO from(ReviewIssue issue) {
        return new IssueVO(
                issue.getId(),
                issue.getTaskId(),
                issue.getProjectId(),
                issue.getFilePath(),
                issue.getStartLine(),
                issue.getEndLine(),
                issue.getCategory(),
                DefectCategory.fromCode(issue.getCategory()).label(),
                issue.getSeverity(),
                Severity.fromCode(issue.getSeverity()).label(),
                issue.getTitle(),
                issue.getDescription(),
                issue.getSuggestion(),
                issue.getFixSnippet(),
                issue.getSource(),
                issue.getRuleId(),
                issue.getConfidence(),
                issue.getFlag(),
                issue.getStatus(),
                issue.getCreatedAt());
    }
}

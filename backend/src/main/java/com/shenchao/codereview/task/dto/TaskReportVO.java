package com.shenchao.codereview.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * 审查报告。
 *
 * <p>报告页与任务详情页的区别：详情页看"跑到哪一步"，报告页看"发现了什么"。
 * 因此这里把三个维度的聚合结果直接给出来，前端不需要再对缺陷列表做二次统计。
 *
 * @param task    任务快照
 * @param summary 聚合统计
 * @param issues  缺陷明细（按严重度、文件、行号排序，上限由服务端控制）
 */
@Schema(description = "审查报告")
public record TaskReportVO(
        TaskVO task,
        ReportSummary summary,
        List<IssueVO> issues
) {

    /**
     * @param total         缺陷总数
     * @param bySeverity    按严重度聚合
     * @param byCategory    按统一类别聚合
     * @param bySource      按来源聚合（静态/大模型/融合）
     * @param scannedFiles  进入切片的文件数
     * @param analyzedFiles 实际完成分析的文件数
     * @param truncated     是否被超时或上限截断
     * @param durationMs    总耗时
     * @param warnings      阶段告警（文件解析失败等）
     */
    @Schema(description = "报告聚合统计")
    public record ReportSummary(
            long total,
            Map<String, Long> bySeverity,
            Map<String, Long> byCategory,
            Map<String, Long> bySource,
            int scannedFiles,
            int analyzedFiles,
            boolean truncated,
            long durationMs,
            List<String> warnings
    ) {
    }
}

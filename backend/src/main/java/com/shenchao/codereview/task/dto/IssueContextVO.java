package com.shenchao.codereview.task.dto;

import com.shenchao.codereview.task.pipeline.DiffSlicer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * 缺陷上下文：报告页右侧代码面板所需的全部数据。
 *
 * <p>一次请求拿全，避免"点一条缺陷发三个请求"——报告页的交互是高频的，
 * 每点一次都要等三次往返，体验上会明显发涩。
 *
 * @param issue       缺陷本体
 * @param filePath    仓库相对路径
 * @param language    语言标识（由后缀推断，前端据此决定着色规则）
 * @param totalLines  文件总行数
 * @param windowStart 返回片段的首行行号（1 基）
 * @param windowEnd   返回片段的末行行号（1 基，含）
 * @param lines       片段内容
 * @param evidence    融合证据（静态规则、模型标识、双置信度、两侧原始表述）
 * @param diff        增量任务的差异块；全量任务或文件不在变更集内为 {@code null}
 * @param notice      降级提示（文件读不到、内容被截断）；正常为 {@code null}
 */
@Schema(description = "缺陷上下文")
public record IssueContextVO(
        @Schema(description = "缺陷") IssueVO issue,
        @Schema(description = "文件相对路径") String filePath,
        @Schema(description = "语言") String language,
        @Schema(description = "文件总行数") int totalLines,
        @Schema(description = "片段起始行") int windowStart,
        @Schema(description = "片段结束行") int windowEnd,
        @Schema(description = "片段内容") List<CodeLine> lines,
        @Schema(description = "证据") Map<String, String> evidence,
        @Schema(description = "差异块") DiffSlicer.FileDiff diff,
        @Schema(description = "降级提示") String notice
) {

    public IssueContextVO {
        lines = lines == null ? List.of() : List.copyOf(lines);
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }

    /** 代码行。行号由后端算好，前端不再做偏移运算，避免差一错误。 */
    @Schema(description = "代码行")
    public record CodeLine(
            @Schema(description = "行号（1 基）") int no,
            @Schema(description = "行内容（不含换行符）") String text
    ) {
    }
}

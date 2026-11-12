package com.shenchao.codereview.engine.spi;

import java.util.List;

/**
 * 引擎统一返回值。
 *
 * <p>只返回 {@code List<RawDefect>} 不够用：报告页要显示"扫描了多少文件、是否被超时截断"，
 * 这些信息只有引擎自己知道。把统计随结果一起返回，避免引擎为了上报统计而反向依赖业务层。
 *
 * @param defects       原始缺陷
 * @param analyzedFiles 实际完成分析的文件数（超时截断时小于入参文件数）
 * @param truncated     是否因超时被截断，对应任务终态 {@code PARTIAL}
 * @param errors        文件级错误（单个文件解析失败不应让整个任务失败）
 */
public record AnalysisResult(
        List<RawDefect> defects,
        int analyzedFiles,
        boolean truncated,
        List<String> errors
) {

    public AnalysisResult {
        defects = defects == null ? List.of() : List.copyOf(defects);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static AnalysisResult empty() {
        return new AnalysisResult(List.of(), 0, false, List.of());
    }
}

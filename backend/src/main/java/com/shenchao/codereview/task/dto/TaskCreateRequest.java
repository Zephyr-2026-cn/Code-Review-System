package com.shenchao.codereview.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建审查任务。
 *
 * <p>{@code headCommit} 留空表示"审查项目当前 HEAD"，由服务端在创建时冻结为具体提交号——
 * 否则任务执行期间仓库被同步到新提交，报告里的行号会和实际代码对不上。
 */
@Schema(description = "创建审查任务")
public record TaskCreateRequest(

        @Schema(description = "项目 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "项目不能为空")
        Long projectId,

        @Schema(description = "审查模式：FULL 全量 / INCREMENTAL 增量", defaultValue = "FULL")
        @Pattern(regexp = "^(FULL|INCREMENTAL)$", message = "审查模式只能是 FULL 或 INCREMENTAL")
        String reviewMode,

        @Schema(description = "增量审查的基线提交；INCREMENTAL 模式必填")
        @Size(max = 64, message = "提交号长度不能超过 64")
        String baseCommit,

        @Schema(description = "目标提交；留空表示当前 HEAD")
        @Size(max = 64, message = "提交号长度不能超过 64")
        String headCommit,

        @Schema(description = "引擎开关；不传则使用默认：PMD 开、LLM 关")
        @Valid
        EngineFlags engineFlags
) {

    public TaskCreateRequest {
        reviewMode = (reviewMode == null || reviewMode.isBlank()) ? "FULL" : reviewMode.trim().toUpperCase();
    }

    /**
     * 引擎开关。
     *
     * @param pmd      静态分析（默认开）
     * @param spotbugs 字节码分析（默认关，需要项目可编译）
     * @param llm      语义审查（M3 才接入，当前置 true 会被忽略并记录告警）
     * @param rag      历史缺陷召回（依赖 llm）
     */
    @Schema(description = "引擎开关")
    public record EngineFlags(Boolean pmd, Boolean spotbugs, Boolean llm, Boolean rag) {

        public boolean pmdEnabled() {
            return pmd == null || pmd;
        }

        public boolean spotbugsEnabled() {
            return Boolean.TRUE.equals(spotbugs);
        }

        public boolean llmEnabled() {
            return Boolean.TRUE.equals(llm);
        }

        public boolean ragEnabled() {
            return Boolean.TRUE.equals(rag) && llmEnabled();
        }
    }
}

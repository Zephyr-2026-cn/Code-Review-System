package com.shenchao.codereview.engine.staticanalysis.pmd;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * PMD 引擎配置。
 *
 * @param enabled            总开关；关闭后任务仍可执行，但只有 LLM 引擎产出结果
 * @param rulesets           规则集资源路径（classpath），默认使用本项目的精简规则集
 * @param threads            分析线程数；PMD 内部按文件并行，线程数超过 CPU 核数没有收益
 * @param minPriority        最低优先级（1 最高 5 最低），5 表示全部规则
 * @param chunkSize          分块大小：每块独立一次 PMD 分析，便于在块之间检查超时并保留部分结果
 * @param maxViolationsPerFile 单文件缺陷上限，防止某条规则把结果表打爆
 */
@ConfigurationProperties(prefix = "app.engine.pmd")
public record PmdProperties(
        Boolean enabled,
        List<String> rulesets,
        Integer threads,
        Integer minPriority,
        Integer chunkSize,
        Integer maxViolationsPerFile
) {

    public PmdProperties {
        enabled = enabled == null || enabled;
        rulesets = (rulesets == null || rulesets.isEmpty()) ? List.of("pmd/ruleset.xml") : List.copyOf(rulesets);
        threads = threads == null || threads < 1 ? Math.max(1, Runtime.getRuntime().availableProcessors() / 2) : threads;
        minPriority = minPriority == null || minPriority < 1 || minPriority > 5 ? 4 : minPriority;
        chunkSize = chunkSize == null || chunkSize < 1 ? 100 : chunkSize;
        maxViolationsPerFile = maxViolationsPerFile == null || maxViolationsPerFile < 1 ? 200 : maxViolationsPerFile;
    }
}

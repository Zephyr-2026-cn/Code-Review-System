package com.shenchao.codereview.engine.spi;

import java.util.List;
import java.util.Set;

/**
 * 静态分析引擎插件接口（蓝皮书 6.2.1）。
 *
 * <p>当前只有 PMD 一个实现（Java 源码级、零编译依赖）。抽象这层的目的不是"为了扩展而扩展"：
 * 接 ESLint / Pylint 时只需新增一个 {@code @Component} 实现，
 * 任务编排、缺陷落库、融合、门禁都不需要改。
 *
 * <p>实现约束：
 * <ul>
 *   <li>禁止读写数据库、禁止依赖 HTTP 上下文</li>
 *   <li>禁止抛出受检异常，失败以运行时异常上抛，由管道统一记为阶段失败</li>
 *   <li>必须尊重 {@link AnalysisContext#expired()}，在文件之间检查并提前返回</li>
 * </ul>
 */
public interface StaticAnalyzer {

    /** 引擎标识，与 {@code engine_flags} 的键一致，例如 {@code pmd}。 */
    String name();

    /** 支持的源语言，例如 {@code java}。 */
    Set<String> supportedLanguages();

    /** 是否要求被审查项目先编译成功（PMD 为 false，SpotBugs 为 true）。 */
    boolean requiresCompile();

    /** 执行顺序，数值小的先跑。 */
    int priority();

    /**
     * 执行分析。
     *
     * @return 缺陷与统计信息；无缺陷时返回空结果，不允许返回 null
     */
    AnalysisResult analyze(AnalysisContext context);
}

package com.shenchao.codereview.engine.spi;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Comparator;

/**
 * 静态分析器注册表。
 *
 * <p>所有 {@link StaticAnalyzer} 实现由 Spring 自动注入，按 {@code priority} 排序。
 * 调度侧只与这个注册表交互，因此"新增一个语言/引擎"不会波及任务编排代码。
 */
@Component
public class AnalyzerRegistry {

    private final Map<String, StaticAnalyzer> analyzers;

    public AnalyzerRegistry(List<StaticAnalyzer> discovered) {
        Map<String, StaticAnalyzer> ordered = new LinkedHashMap<>();
        discovered.stream()
                .sorted(Comparator.comparingInt(StaticAnalyzer::priority))
                .forEach(analyzer -> ordered.put(analyzer.name().toLowerCase(Locale.ROOT), analyzer));
        this.analyzers = Map.copyOf(ordered);
    }

    public Optional<StaticAnalyzer> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(analyzers.get(name.toLowerCase(Locale.ROOT)));
    }

    public Set<String> names() {
        return analyzers.keySet();
    }

    /**
     * 按启动开关与语言筛选可用引擎。
     *
     * @param enabledFlags {@code engine_flags} 中被打开的引擎名（小写）
     * @param language     项目主语言，例如 {@code JAVA}
     */
    public List<StaticAnalyzer> select(Set<String> enabledFlags, String language) {
        String lang = language == null ? "java" : language.toLowerCase(Locale.ROOT);
        return analyzers.values().stream()
                .filter(analyzer -> enabledFlags.contains(analyzer.name().toLowerCase(Locale.ROOT)))
                .filter(analyzer -> analyzer.supportedLanguages().contains(lang))
                .toList();
    }
}

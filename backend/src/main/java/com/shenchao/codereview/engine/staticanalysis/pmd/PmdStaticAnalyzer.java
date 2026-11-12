package com.shenchao.codereview.engine.staticanalysis.pmd;

import com.shenchao.codereview.engine.model.DefectCategory;
import com.shenchao.codereview.engine.model.RuleCategory;
import com.shenchao.codereview.engine.model.Severity;
import com.shenchao.codereview.engine.spi.AnalysisContext;
import com.shenchao.codereview.engine.spi.AnalysisResult;
import com.shenchao.codereview.engine.spi.RawDefect;
import com.shenchao.codereview.engine.spi.RuleCategoryMapper;
import com.shenchao.codereview.engine.spi.StaticAnalyzer;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.rule.Rule;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.lang.rule.RuleSetLoader;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PMD 7 静态分析器（主引擎）。
 *
 * <p>三个关键工程决策：
 * <ol>
 *   <li><b>用 Java API 内嵌调用</b>，不拼命令行：避免进程启动开销，也避免"命令行超时后子进程僵死"</li>
 *   <li><b>分块分析</b>：每块单独一次 {@link PmdAnalysis}，块之间检查截止时间。
 *       这样超时能及时止损，已完成的块仍然产出有效缺陷（任务记 {@code PARTIAL}），
 *       而不是整任务丢弃重跑</li>
 *   <li><b>规则集每次重新加载</b>：PMD 的 {@link Rule} 实例不是线程安全的，
 *       多个任务并发时共享同一份 RuleSet 会出现规则内部状态串味。
 *       加载一份 XML 只有几十毫秒，相比正确性这个代价可以接受</li>
 * </ol>
 */
@Slf4j
@Component
public class PmdStaticAnalyzer implements StaticAnalyzer {

    public static final String ENGINE_NAME = "pmd";

    /** 引擎错误列表上限，防止一个坏仓库刷出上万条错误把统计字段撑爆。 */
    private static final int MAX_ERRORS = 20;

    private final PmdProperties properties;
    private final RuleCategoryMapper ruleCategoryMapper;

    public PmdStaticAnalyzer(PmdProperties properties, RuleCategoryMapper ruleCategoryMapper) {
        this.properties = properties;
        this.ruleCategoryMapper = ruleCategoryMapper;
    }

    @Override
    public String name() {
        return ENGINE_NAME;
    }

    @Override
    public Set<String> supportedLanguages() {
        return Set.of("java");
    }

    @Override
    public boolean requiresCompile() {
        return false;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public AnalysisResult analyze(AnalysisContext context) {
        if (!Boolean.TRUE.equals(properties.enabled())) {
            log.debug("PMD 引擎已关闭，跳过静态分析: projectId={}", context.projectId());
            return AnalysisResult.empty();
        }

        List<Path> files = context.files().stream().filter(this::isJavaSource).toList();
        if (files.isEmpty()) {
            return AnalysisResult.empty();
        }

        List<RuleSet> ruleSets = loadRuleSets();
        List<RawDefect> defects = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int analyzedFiles = 0;
        boolean truncated = false;

        int chunkSize = properties.chunkSize();
        for (int from = 0; from < files.size(); from += chunkSize) {
            if (context.expired()) {
                truncated = true;
                log.warn("PMD 分析超时，已分析 {}/{} 个文件，结果标记为 PARTIAL: projectId={}",
                        analyzedFiles, files.size(), context.projectId());
                break;
            }
            List<Path> chunk = files.subList(from, Math.min(from + chunkSize, files.size()));
            try {
                defects.addAll(analyzeChunk(context, ruleSets, chunk));
                analyzedFiles += chunk.size();
            } catch (RuntimeException ex) {
                // 单块失败不终止整个任务：剩下的块继续跑，错误汇总进 stats
                String message = "第 %d-%d 个文件分析失败: %s".formatted(from + 1, from + chunk.size(), safeMessage(ex));
                log.error("PMD 分块分析失败: projectId={}, {}", context.projectId(), message, ex);
                if (errors.size() < MAX_ERRORS) {
                    errors.add(message);
                }
            }
        }

        log.info("PMD 分析完成: projectId={}, 文件 {}/{}, 缺陷 {}, 截断={}, 错误 {}",
                context.projectId(), analyzedFiles, files.size(), defects.size(), truncated, errors.size());

        return new AnalysisResult(defects, analyzedFiles, truncated, errors);
    }

    // ---------------- 内部分析 ----------------

    private List<RawDefect> analyzeChunk(AnalysisContext context, List<RuleSet> ruleSets, List<Path> chunk) {
        PMDConfiguration config = new PMDConfiguration();
        config.setThreads(properties.threads());
        config.setMinimumPriority(RulePriority.valueOf(properties.minPriority()));
        // 被 @SuppressWarnings 抑制的告警不计入报告，但要保留在统计里，
        // 否则"代码里到处 @SuppressWarnings 压制告警"这类问题会被掩盖
        config.setShowSuppressedViolations(true);

        List<RawDefect> defects = new ArrayList<>();
        Map<String, Integer> perFileCount = new HashMap<>();

        try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
            ruleSets.forEach(pmd::addRuleSet);
            chunk.forEach(pmd.files()::addFile);

            Report report = pmd.performAnalysisAndCollectReport();

            for (RuleViolation violation : report.getViolations()) {
                RawDefect defect = toRawDefect(context, violation, perFileCount);
                if (defect != null) {
                    defects.add(defect);
                }
            }
        }
        return defects;
    }

    /**
     * 单条 PMD 告警转统一缺陷。
     *
     * @return 被规则开关过滤或超过单文件上限时返回 null
     */
    private RawDefect toRawDefect(AnalysisContext context, RuleViolation violation,
                                  Map<String, Integer> perFileCount) {
        Rule rule = violation.getRule();
        String ruleId = rule.getName();
        if (context.disabledRules().contains(ruleId)) {
            return null;
        }

        String path = context.relativePath(Path.of(violation.getFileId().getAbsolutePath()));
        int count = perFileCount.merge(path, 1, Integer::sum);
        if (count > context.maxViolationsPerFile()) {
            // 同一文件同类问题过多时只保留前 N 条，其余在统计里体现为被截断
            return null;
        }

        Optional<RuleCategory> mapped = ruleCategoryMapper.find(ruleId);
        DefectCategory category = mapped.map(RuleCategory::category)
                .orElseGet(() -> inferCategory(rule.getRuleSetName()));
        Severity severity = mapped.map(RuleCategory::severity)
                .orElseGet(() -> Severity.fromPmdPriority(rule.getPriority().getPriority()));

        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("engine", ENGINE_NAME);
        evidence.put("rule", ruleId);
        evidence.put("ruleSet", nullSafe(rule.getRuleSetName()));
        evidence.put("priority", String.valueOf(rule.getPriority().getPriority()));
        evidence.put("message", safeMessage(violation.getDescription()));

        return new RawDefect(
                path,
                violation.getBeginLine(),
                violation.getEndLine(),
                ruleId,
                category,
                severity,
                ruleId + " · " + category.label(),
                violation.getDescription(),
                evidence
        );
    }

    /**
     * 规则未在 {@code rule_category_map} 中登记时，按 PMD 规则集名做兜底推断。
     *
     * <p>兜底存在的意义：新增规则不需要先改数据库就能跑，不会因为一条没登记的规则导致类别为空。
     */
    private DefectCategory inferCategory(String ruleSetName) {
        String name = nullSafe(ruleSetName).toLowerCase(Locale.ROOT);
        if (name.contains("security")) {
            return DefectCategory.INJECTION;
        }
        if (name.contains("multithreading")) {
            return DefectCategory.CONCURRENCY;
        }
        if (name.contains("performance")) {
            return DefectCategory.PERFORMANCE;
        }
        if (name.contains("codestyle")) {
            return DefectCategory.CODE_STYLE;
        }
        if (name.contains("errorprone")) {
            return DefectCategory.LOGIC_ERROR;
        }
        return DefectCategory.MAINTAINABILITY;
    }

    private List<RuleSet> loadRuleSets() {
        try {
            RuleSetLoader loader = new RuleSetLoader()
                    .loadResourcesWith(getClass().getClassLoader())
                    // 规则集里可能引用已废弃规则，允许引用但由 PMD 打警告，避免整个任务因一条废弃规则失败
                    .includeDeprecatedRuleReferences(true);
            return loader.loadFromResources(properties.rulesets());
        } catch (RuntimeException ex) {
            log.error("PMD 规则集加载失败, rulesets={}", properties.rulesets(), ex);
            throw new IllegalStateException("PMD 规则集加载失败: " + safeMessage(ex), ex);
        }
    }

    private boolean isJavaSource(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".java");
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** 把第三方异常压缩成一行，避免把堆栈或本地路径写进任务失败原因。 */
    private String safeMessage(Exception ex) {
        return safeMessage(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        String singleLine = message.replaceAll("\\s+", " ").trim();
        return singleLine.length() > 300 ? singleLine.substring(0, 300) : singleLine;
    }
}

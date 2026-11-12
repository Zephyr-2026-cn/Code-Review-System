package com.shenchao.codereview.engine.staticanalysis.pmd;

import com.shenchao.codereview.engine.spi.AnalysisContext;
import com.shenchao.codereview.engine.spi.AnalysisResult;
import com.shenchao.codereview.engine.spi.RawDefect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PMD 引擎集成测试。
 *
 * <p>这个用例的价值不在"测 PMD 能不能用"，而在<b>守住规则集文件</b>：
 * {@code pmd/ruleset.xml} 里任何一个规则名写错，PMD 都会在加载时抛异常，
 * 而这个错误只会在任务真正运行时才暴露。放在单元测试里，改规则集立刻会红。
 */
class PmdStaticAnalyzerTest {

    private static final String SAMPLE = """
            package demo;

            import java.io.FileInputStream;

            public class Sample {

                public void emptyCatch() {
                    try {
                        Integer.parseInt("x");
                    } catch (Exception e) {
                    }
                }

                public void print() {
                    System.out.println("hello");
                }

                public boolean compare(String a, String b) {
                    return a == b;
                }

                public void trace() {
                    try {
                        throw new RuntimeException("boom");
                    } catch (RuntimeException e) {
                        e.printStackTrace();
                    }
                }

                public int unused() {
                    int unusedLocal = 42;
                    return 0;
                }

                public void leak() throws Exception {
                    FileInputStream in = new FileInputStream("a.txt");
                    in.read();
                }
            }
            """;

    @Test
    @DisplayName("规则集可加载，并能命中空 catch、字符串比较、打印栈等典型问题")
    void analyzesSampleSource(@TempDir Path workspace) throws IOException {
        Path source = workspace.resolve("src/main/java/demo/Sample.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, SAMPLE, StandardCharsets.UTF_8);

        PmdStaticAnalyzer analyzer = new PmdStaticAnalyzer(
                new PmdProperties(true, List.of("pmd/ruleset.xml"), 1, 5, 10, 200),
                ruleId -> Optional.empty());

        AnalysisContext context = new AnalysisContext(
                1L, "demo", workspace, List.of(source), Set.of(), Instant.now().plusSeconds(60), 200);

        AnalysisResult result = analyzer.analyze(context);

        assertThat(result.analyzedFiles()).isEqualTo(1);
        assertThat(result.truncated()).isFalse();
        assertThat(result.errors()).isEmpty();

        Set<String> rules = result.defects().stream().map(RawDefect::ruleId).collect(java.util.stream.Collectors.toSet());
        assertThat(rules)
                .as("规则集应命中这些确定性规则")
                .contains("EmptyCatchBlock", "UseEqualsToCompareStrings", "SystemPrintln", "AvoidPrintStackTrace");

        // 每条缺陷都必须带位置与类别，否则融合层无法归类
        assertThat(result.defects()).allSatisfy(defect -> {
            assertThat(defect.filePath()).isEqualTo("src/main/java/demo/Sample.java");
            assertThat(defect.startLine()).isPositive();
            assertThat(defect.category()).isNotNull();
            assertThat(defect.severity()).isNotNull();
            assertThat(defect.evidence()).containsKey("engine");
        });
    }

    @Test
    @DisplayName("规则被关闭时对应缺陷应被过滤")
    void respectsDisabledRules(@TempDir Path workspace) throws IOException {
        Path source = workspace.resolve("Sample.java");
        Files.writeString(source, SAMPLE, StandardCharsets.UTF_8);

        PmdStaticAnalyzer analyzer = new PmdStaticAnalyzer(
                new PmdProperties(true, List.of("pmd/ruleset.xml"), 1, 5, 10, 200),
                ruleId -> Optional.empty());

        AnalysisContext context = new AnalysisContext(
                1L, "demo", workspace, List.of(source), Set.of("SystemPrintln"),
                Instant.now().plusSeconds(60), 200);

        AnalysisResult result = analyzer.analyze(context);

        assertThat(result.defects())
                .extracting(RawDefect::ruleId)
                .doesNotContain("SystemPrintln");
    }

    @Test
    @DisplayName("空文件列表直接返回空结果，不启动 PMD")
    void handlesEmptyFileList(@TempDir Path workspace) {
        PmdStaticAnalyzer analyzer = new PmdStaticAnalyzer(
                new PmdProperties(true, List.of("pmd/ruleset.xml"), 1, 4, 10, 200),
                ruleId -> Optional.empty());

        AnalysisResult result = analyzer.analyze(new AnalysisContext(
                1L, "demo", workspace, List.of(), Set.of(), Instant.now(), 200));

        assertThat(result.defects()).isEmpty();
        assertThat(result.analyzedFiles()).isZero();
    }

    @Test
    @DisplayName("截止时间已过时应截断，且不抛异常")
    void truncatesWhenDeadlinePassed(@TempDir Path workspace) throws IOException {
        Path source = workspace.resolve("Sample.java");
        Files.writeString(source, SAMPLE, StandardCharsets.UTF_8);

        PmdStaticAnalyzer analyzer = new PmdStaticAnalyzer(
                new PmdProperties(true, List.of("pmd/ruleset.xml"), 1, 4, 10, 200),
                ruleId -> Optional.empty());

        AnalysisResult result = analyzer.analyze(new AnalysisContext(
                1L, "demo", workspace, List.of(source), Set.of(), Instant.now().minusSeconds(1), 200));

        assertThat(result.truncated()).isTrue();
        assertThat(result.analyzedFiles()).isZero();
    }
}

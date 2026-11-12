package com.shenchao.codereview.engine.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchao.codereview.engine.model.DefectCategory;
import com.shenchao.codereview.engine.model.DefectSource;
import com.shenchao.codereview.engine.model.Severity;
import com.shenchao.codereview.engine.spi.RawDefect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("语义审查引擎")
class SemanticCodeAnalyzerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SemanticCodeAnalyzer analyzer;

    private static LlmProperties properties(int maxIssuesPerFile, double minConfidence) {
        return new LlmProperties(true, "https://api.deepseek.com", "sk-test", "deepseek-chat",
                0.2, 2048, Duration.ofSeconds(30), 1, 2,
                40, 4, 14000, 6000, 262144, minConfidence, maxIssuesPerFile);
    }

    @BeforeEach
    void setUp() {
        analyzer = new SemanticCodeAnalyzer(properties(8, 0.5), mock(DeepSeekClient.class));
    }

    /**
     * 构造一批送审文件：伪造的行内容形如 {@code code-12;}，
     * 测试里给出的 quote 必须与它一致才算"引用核对通过"。
     */
    private static SemanticCodeAnalyzer.PromptBatch batch(String path, int lineCount) {
        String[] lines = new String[lineCount];
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < lineCount; i++) {
            lines[i] = "code-" + (i + 1) + ";";
            numbered.append(String.format("%4d", i + 1)).append("| ").append(lines[i]).append('\n');
        }
        return new SemanticCodeAnalyzer.PromptBatch(
                List.of(new SemanticCodeAnalyzer.CodeFile(path, numbered.toString(), lineCount, lines)));
    }

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    @DisplayName("正常结果按字段归一")
    void parseValidIssue() throws Exception {
        JsonNode root = json("""
                {"issues":[
                  {"file":"src/A.java","line":12,"quote":"code-12;","severity":"CRITICAL","category":"NULL_POINTER",
                   "title":"下游未判空","description":"detail","suggestion":"加判空","confidence":0.88}
                ]}""");

        SemanticCodeAnalyzer.BatchOutcome outcome = analyzer.parse(batch("src/A.java", 100), root);

        assertThat(outcome.defects()).hasSize(1);
        RawDefect defect = outcome.defects().get(0);
        assertThat(defect.filePath()).isEqualTo("src/A.java");
        assertThat(defect.startLine()).isEqualTo(12);
        assertThat(defect.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(defect.category()).isEqualTo(DefectCategory.NULL_POINTER);
        assertThat(defect.confidence()).isEqualTo(0.88);
        assertThat(defect.ruleId()).isEqualTo(SemanticCodeAnalyzer.RULE_ID);
        assertThat(defect.evidence()).containsEntry("model", "deepseek-chat");
        assertThat(outcome.analyzedFiles()).isEqualTo(1);
    }

    @Test
    @DisplayName("引擎来源声明为 LLM，供融合层区分")
    void declaresLlmSource() {
        assertThat(analyzer.source()).isEqualTo(DefectSource.LLM);
        assertThat(analyzer.name()).isEqualTo("llm");
    }

    @Test
    @DisplayName("行号越界、路径不存在、置信度过低的结果被丢弃")
    void dropInvalidIssues() throws Exception {
        JsonNode root = json("""
                {"issues":[
                  {"file":"src/A.java","line":0,"severity":"MAJOR","category":"LOGIC_ERROR","title":"行号非法","confidence":0.9},
                  {"file":"src/A.java","line":9999,"severity":"MAJOR","category":"LOGIC_ERROR","title":"行号越界","confidence":0.9},
                  {"file":"src/B.java","line":3,"severity":"MAJOR","category":"LOGIC_ERROR","title":"文件不在本批","confidence":0.9},
                  {"file":"src/A.java","line":5,"severity":"MINOR","category":"CODE_STYLE","title":"置信度不足","confidence":0.2},
                  {"file":"src/A.java","line":6,"severity":"MAJOR","category":"LOGIC_ERROR","confidence":0.9}
                ]}""");

        SemanticCodeAnalyzer.BatchOutcome outcome = analyzer.parse(batch("src/A.java", 100), root);

        assertThat(outcome.defects()).isEmpty();
        assertThat(outcome.errors()).isEmpty();
    }

    @Test
    @DisplayName("同文件同行同类别的重复报告只保留一条")
    void dedupeSameLineAndCategory() throws Exception {
        JsonNode root = json("""
                {"issues":[
                  {"file":"src/A.java","line":9,"quote":"code-9;","severity":"MAJOR","category":"LOGIC_ERROR","title":"第一次","confidence":0.8},
                  {"file":"src/A.java","line":9,"quote":"code-9;","severity":"CRITICAL","category":"LOGIC_ERROR","title":"第二次","confidence":0.9}
                ]}""");

        SemanticCodeAnalyzer.BatchOutcome outcome = analyzer.parse(batch("src/A.java", 100), root);

        assertThat(outcome.defects()).hasSize(1);
        assertThat(outcome.defects().get(0).title()).isEqualTo("第一次");
    }

    @Test
    @DisplayName("单文件缺陷数受上限约束，防止一条提示词刷屏")
    void respectMaxIssuesPerFile() throws Exception {
        SemanticCodeAnalyzer capped = new SemanticCodeAnalyzer(properties(2, 0.5), mock(DeepSeekClient.class));
        JsonNode root = json("""
                {"issues":[
                  {"file":"src/A.java","line":1,"quote":"code-1;","severity":"MAJOR","category":"LOGIC_ERROR","title":"a","confidence":0.9},
                  {"file":"src/A.java","line":2,"quote":"code-2;","severity":"MAJOR","category":"LOGIC_ERROR","title":"b","confidence":0.9},
                  {"file":"src/A.java","line":3,"quote":"code-3;","severity":"MAJOR","category":"LOGIC_ERROR","title":"c","confidence":0.9}
                ]}""");

        assertThat(capped.parse(batch("src/A.java", 100), root).defects()).hasSize(2);
    }

    @Test
    @DisplayName("引用原文对不上真实代码的结果被丢弃（反幻觉）")
    void fabricatedQuoteIsDropped() throws Exception {
        JsonNode root = json("""
                {"issues":[
                  {"file":"src/A.java","line":12,"quote":"if (user != null && user.isActive()) {",
                   "severity":"CRITICAL","category":"NULL_POINTER","title":"编造的行","confidence":0.95}
                ]}""");

        assertThat(analyzer.parse(batch("src/A.java", 100), root).defects()).isEmpty();
    }

    @Test
    @DisplayName("引用缺失时同样丢弃：要求模型必须定位到原文")
    void missingQuoteIsDropped() throws Exception {
        JsonNode root = json("""
                {"issues":[
                  {"file":"src/A.java","line":12,"severity":"MAJOR","category":"LOGIC_ERROR",
                   "title":"没有给引用","confidence":0.9}
                ]}""");

        assertThat(analyzer.parse(batch("src/A.java", 100), root).defects()).isEmpty();
    }

    @Test
    @DisplayName("引用带行号前缀或缩进差异时仍能对上")
    void quoteWithLineNumberPrefixStillMatches() throws Exception {
        JsonNode root = json("""
                {"issues":[
                  {"file":"src/A.java","line":12,"quote":"  12|   code-12;",
                   "severity":"MAJOR","category":"LOGIC_ERROR","title":"带前缀的引用","confidence":0.8}
                ]}""");

        assertThat(analyzer.parse(batch("src/A.java", 100), root).defects()).hasSize(1);
    }

    @Test
    @DisplayName("返回结构缺 issues 时记错误而不是抛异常")
    void missingIssuesArrayReportedAsError() throws Exception {
        SemanticCodeAnalyzer.BatchOutcome outcome = analyzer.parse(batch("src/A.java", 100), json("{\"foo\":1}"));

        assertThat(outcome.defects()).isEmpty();
        assertThat(outcome.errors()).hasSize(1);
    }

    @Test
    @DisplayName("模型偶发用 Markdown 围栏包裹 JSON 时仍能解析")
    void parseJsonWithCodeFence() {
        DeepSeekClient client = new DeepSeekClient(properties(8, 0.5), MAPPER);
        JsonNode node = client.parseJson("```json\n{\"issues\":[]}\n```");

        assertThat(node.path("issues").isArray()).isTrue();
    }

    @Test
    @DisplayName("源码加行号且行号与真实行一致")
    void numberLinesKeepsRealNumbers() {
        String numbered = SemanticCodeAnalyzer.numberLines("a\nb\nc", 10_000);

        assertThat(numbered).contains("   1| a").contains("   2| b").contains("   3| c");
    }

    @Test
    @DisplayName("超长文件截断后保留头尾，行号不重排")
    void numberLinesTruncatesButKeepsNumbers() {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 500; i++) {
            content.append("line-content-").append(i).append('\n');
        }

        String numbered = SemanticCodeAnalyzer.numberLines(content.toString(), 3000);

        assertThat(numbered).contains("   1| line-content-1");
        assertThat(numbered).contains("500| line-content-500");
        assertThat(numbered).contains("省略中间");
        assertThat(numbered).doesNotContain("250| line-content-250");
    }

    @Test
    @DisplayName("按文件数与字符预算切批")
    void buildBatchesRespectsBudget() {
        List<SemanticCodeAnalyzer.CodeFile> files = List.of(
                new SemanticCodeAnalyzer.CodeFile("a.java", "x".repeat(5000), 10, new String[0]),
                new SemanticCodeAnalyzer.CodeFile("b.java", "x".repeat(5000), 10, new String[0]),
                new SemanticCodeAnalyzer.CodeFile("c.java", "x".repeat(5000), 10, new String[0]));

        List<SemanticCodeAnalyzer.PromptBatch> batches = analyzer.buildBatches(files);

        // 单请求字符预算 14000：每文件约 5040 字符，第三个会溢出
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0).files()).hasSize(2);
        assertThat(batches.get(1).files()).hasSize(1);
    }
}

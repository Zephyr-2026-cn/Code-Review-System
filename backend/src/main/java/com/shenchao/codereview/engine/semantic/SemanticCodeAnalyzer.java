package com.shenchao.codereview.engine.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.shenchao.codereview.engine.model.DefectCategory;
import com.shenchao.codereview.engine.model.DefectSource;
import com.shenchao.codereview.engine.model.Severity;
import com.shenchao.codereview.engine.spi.AnalysisContext;
import com.shenchao.codereview.engine.spi.AnalysisResult;
import com.shenchao.codereview.engine.spi.RawDefect;
import com.shenchao.codereview.engine.spi.StaticAnalyzer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 大模型语义审查引擎（蓝皮书 6.3）。
 *
 * <p>与 PMD 的分工：PMD 看"写法"，这里看"意图"。PMD 能发现 {@code catch} 块为空，
 * 但发现不了"catch 住之后吞掉异常导致上游状态不一致"；能发现 {@code ==} 比较字符串，
 * 但发现不了"这里本该用 {@code Objects.equals} 比较业务主键"。
 *
 * <p><b>成本控制</b>：三层预算逐级收敛——
 * 单任务 {@code maxFilesPerTask} 个文件 → 单请求 {@code maxFilesPerRequest} 个文件 /
 * {@code maxCharsPerRequest} 字符 → 单文件 {@code maxFileChars} 字符。
 * 超出预算的部分记入 {@code truncated}，任务终态变 PARTIAL，让用户知道"没审全"而不是假装审完了。
 *
 * <p><b>失败隔离</b>：单个请求失败只影响这一批文件，其余批次继续；
 * 全部失败才通过 {@code errors} 上报，由管道决定是否让任务失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticCodeAnalyzer implements StaticAnalyzer {

    /** 引擎标识，与 {@code engine_flags.llm} 对应。 */
    public static final String ENGINE_NAME = "llm";

    /** 规则标识，落库后可按此聚类，区分"哪版提示词产出的缺陷"。 */
    static final String RULE_ID = "llm/semantic-v1";

    private static final String PROMPT_PATH = "prompts/semantic-review.md";

    /** 引文核对窗口：行号上下各取多少行，容忍模型把起始行报偏一两行。 */
    private static final int QUOTE_WINDOW = 6;

    private static final Set<String> LANGUAGES =
            Set.of("java", "kotlin", "javascript", "typescript", "python", "go");

    /** 只有这些后缀才值得花 token 送审；其余（配置、SQL、Markdown）语义审查收益极低。 */
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".java", ".kt", ".js", ".jsx", ".ts", ".tsx", ".py", ".go");

    private final LlmProperties properties;
    private final DeepSeekClient client;

    /** 提示词全文，首次使用时加载并常驻；改文件需重启，避免运行期读到半个文件。 */
    private volatile String systemPrompt;

    @PostConstruct
    void checkConfiguration() {
        if (!properties.enabled()) {
            log.info("语义审查引擎已关闭（app.engine.llm.enabled=false）");
        } else if (!properties.usable()) {
            log.warn("语义审查引擎已启用但未配置 DEEPSEEK_API_KEY，相关任务会跳过语义分析");
        } else {
            log.info("语义审查引擎就绪: model={}, baseUrl={}, 并发={}, 单任务文件上限={}",
                    properties.model(), properties.baseUrl(), properties.concurrency(),
                    properties.maxFilesPerTask());
        }
    }

    @Override
    public String name() {
        return ENGINE_NAME;
    }

    @Override
    public DefectSource source() {
        return DefectSource.LLM;
    }

    @Override
    public Set<String> supportedLanguages() {
        return LANGUAGES;
    }

    @Override
    public boolean requiresCompile() {
        return false;
    }

    /** 排在 PMD 之后：先让静态结果落定，语义结果在融合阶段与之对齐。 */
    @Override
    public int priority() {
        return 20;
    }

    @Override
    public AnalysisResult analyze(AnalysisContext context) {
        if (!properties.enabled()) {
            return new AnalysisResult(List.of(), 0, false, List.of("语义审查已关闭"));
        }
        if (!properties.usable()) {
            return new AnalysisResult(List.of(), 0, false,
                    List.of("语义审查不可用：未配置 DEEPSEEK_API_KEY"));
        }

        List<CodeFile> selected = selectFiles(context);
        if (selected.isEmpty()) {
            return new AnalysisResult(List.of(), 0, false, List.of());
        }
        boolean budgetTruncated = selected.size() < countCandidates(context);
        if (budgetTruncated) {
            log.info("语义审查文件数超预算，仅送审前 {} 个: projectId={}",
                    selected.size(), context.projectId());
        }

        List<PromptBatch> batches = buildBatches(selected);
        BatchOutcome outcome = runBatches(batches, context);

        List<String> errors = new ArrayList<>(outcome.errors());
        if (budgetTruncated) {
            errors.add("语义审查文件数超上限 " + properties.maxFilesPerTask()
                    + "，仅送审 " + selected.size() + " 个");
        }
        return new AnalysisResult(outcome.defects(), outcome.analyzedFiles(),
                outcome.truncated() || budgetTruncated, errors);
    }

    // ---------------- 文件筛选与分批 ----------------

    private int countCandidates(AnalysisContext context) {
        int count = 0;
        for (Path file : context.files()) {
            if (isCandidate(file)) {
                count++;
            }
        }
        return count;
    }

    /** 按后缀、体积、可读性筛选；排序保证同一提交多次运行送审顺序一致，便于对比实验。 */
    private List<CodeFile> selectFiles(AnalysisContext context) {
        List<CodeFile> selected = new ArrayList<>();
        for (Path file : context.files()) {
            if (selected.size() >= properties.maxFilesPerTask()) {
                break;
            }
            if (!isCandidate(file)) {
                continue;
            }
            CodeFile codeFile = read(context, file);
            if (codeFile != null) {
                selected.add(codeFile);
            }
        }
        selected.sort(Comparator.comparing(CodeFile::relativePath));
        return selected;
    }

    private boolean isCandidate(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (SOURCE_EXTENSIONS.stream().noneMatch(name::endsWith)) {
            return false;
        }
        try {
            return Files.size(file) <= properties.maxFileBytes();
        } catch (IOException ex) {
            return false;
        }
    }

    private CodeFile read(AnalysisContext context, Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            String content = new String(bytes, StandardCharsets.UTF_8);
            // 含 NUL 字节基本是二进制（或者编码不是 UTF-8），送审只会得到一堆幻觉
            if (content.indexOf('\0') >= 0) {
                return null;
            }
            String relative = context.relativePath(file);
            String numbered = numberLines(content, properties.maxFileChars());
            String[] lines = content.split("\n", -1);
            return new CodeFile(relative, numbered, lines.length, lines);
        } catch (IOException ex) {
            log.debug("读取文件失败，跳过语义审查: file={}, cause={}", file, ex.getMessage());
            return null;
        }
    }

    /**
     * 给源码加行号。
     *
     * <p>不让模型自己数行：它数不准，行号一错，缺陷就挂到无关代码上，
     * 用户点开一看是别的地方，整个功能的可信度就没了。
     *
     * <p>超出 {@code maxChars} 时保留头尾、掐掉中间——类定义和异常处理通常在两端，
     * 中间多是大段 getter 或数据表。截断处保留真实行号，不重新编号。
     */
    static String numberLines(String content, int maxChars) {
        String[] lines = content.split("\n", -1);
        int maxLineNo = lines.length;
        int width = Math.max(4, String.valueOf(maxLineNo).length());

        if (content.length() <= maxChars) {
            StringBuilder sb = new StringBuilder(content.length() + lines.length * (width + 2));
            for (int i = 0; i < lines.length; i++) {
                sb.append(pad(i + 1, width)).append("| ").append(stripCr(lines[i])).append('\n');
            }
            return sb.toString();
        }

        int headLines = Math.max(1, (int) (lines.length * 0.7));
        StringBuilder head = new StringBuilder();
        int used = 0;
        int index = 0;
        while (index < headLines && used < maxChars * 0.7) {
            String line = pad(index + 1, width) + "| " + stripCr(lines[index]) + "\n";
            head.append(line);
            used += line.length();
            index++;
        }

        StringBuilder tail = new StringBuilder();
        int tailIndex = lines.length - 1;
        used = 0;
        while (tailIndex >= index && used < maxChars * 0.3) {
            String line = pad(tailIndex + 1, width) + "| " + stripCr(lines[tailIndex]) + "\n";
            tail.insert(0, line);
            used += line.length();
            tailIndex--;
        }

        int omitted = tailIndex - index + 1;
        return head + "… 省略中间 " + omitted + " 行（第 " + (index + 1) + "~" + tailIndex + " 行）…\n" + tail;
    }

    private static String pad(int number, int width) {
        String value = Integer.toString(number);
        return value.length() >= width ? value : " ".repeat(width - value.length()) + value;
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /** 按文件数与字符预算切批；单文件已超预算时也会独占一批，避免把别的文件挤掉。 */
    List<PromptBatch> buildBatches(List<CodeFile> files) {
        List<PromptBatch> batches = new ArrayList<>();
        List<CodeFile> current = new ArrayList<>();
        int currentChars = 0;

        for (CodeFile file : files) {
            int size = file.numbered().length() + file.relativePath().length() + 40;
            boolean wouldOverflow = !current.isEmpty()
                    && (current.size() >= properties.maxFilesPerRequest()
                    || currentChars + size > properties.maxCharsPerRequest());
            if (wouldOverflow) {
                batches.add(new PromptBatch(List.copyOf(current)));
                current.clear();
                currentChars = 0;
            }
            current.add(file);
            currentChars += size;
        }
        if (!current.isEmpty()) {
            batches.add(new PromptBatch(List.copyOf(current)));
        }
        return batches;
    }

    // ---------------- 调用与解析 ----------------

    private BatchOutcome runBatches(List<PromptBatch> batches, AnalysisContext context) {
        List<RawDefect> defects = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int analyzedFiles = 0;
        boolean truncated = false;

        int concurrency = Math.min(properties.concurrency(), Math.max(1, batches.size()));
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "llm-review-" + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, factory);
        try {
            int cursor = 0;
            while (cursor < batches.size()) {
                if (context.expired()) {
                    truncated = true;
                    log.warn("语义审查到达截止时间，剩余 {} 批未送审: projectId={}",
                            batches.size() - cursor, context.projectId());
                    break;
                }
                int end = Math.min(cursor + concurrency, batches.size());
                List<PromptBatch> window = batches.subList(cursor, end);
                List<Future<BatchOutcome>> futures = new ArrayList<>(window.size());
                for (PromptBatch batch : window) {
                    futures.add(pool.submit(() -> callBatch(batch)));
                }
                for (Future<BatchOutcome> future : futures) {
                    BatchOutcome outcome = await(future, errors);
                    if (outcome == null) {
                        continue;
                    }
                    defects.addAll(outcome.defects());
                    analyzedFiles += outcome.analyzedFiles();
                    errors.addAll(outcome.errors());
                }
                cursor = end;
            }
        } finally {
            pool.shutdownNow();
        }
        return new BatchOutcome(defects, analyzedFiles, truncated, errors);
    }

    /** 等一个批次；超时或线程异常都只记错误，不打断其他批次。 */
    private BatchOutcome await(Future<BatchOutcome> future, List<String> errors) {
        try {
            return future.get(properties.timeout().plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            errors.add("语义审查请求超时");
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            errors.add("语义审查被中断");
            return null;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            errors.add("语义审查失败: " + brief(cause.getMessage()));
            log.warn("语义审查批次失败: {}", cause.getMessage());
            return null;
        }
    }

    private BatchOutcome callBatch(PromptBatch batch) {
        String userPrompt = buildUserPrompt(batch);
        JsonNode root = client.completeJson(systemPrompt(), userPrompt);
        return parse(batch, root);
    }

    String buildUserPrompt(PromptBatch batch) {
        StringBuilder sb = new StringBuilder(properties.maxCharsPerRequest() + 512);
        sb.append("请审查以下 ").append(batch.files().size()).append(" 个文件，只输出 JSON 对象。\n");
        sb.append("每行左侧是真实行号，report 时直接引用该数字。\n");
        for (CodeFile file : batch.files()) {
            sb.append("\n=== FILE: ").append(file.relativePath()).append(" ===\n");
            sb.append(file.numbered());
            sb.append("=== END FILE ===\n");
        }
        return sb.toString();
    }

    /**
     * 把模型输出归一成 {@link RawDefect}。
     *
     * <p>这里是整个引擎最需要"不信任外部输入"的地方：模型返回的路径可能不存在、
     * 行号可能越界、枚举值可能自创。任何一条都不应该让任务失败，而是丢弃并记录。
     */
    BatchOutcome parse(PromptBatch batch, JsonNode root) {
        List<RawDefect> defects = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (root == null) {
            return new BatchOutcome(defects, batch.files().size(), false, errors);
        }
        JsonNode issues = root.path("issues");
        if (!issues.isArray()) {
            errors.add("语义审查返回缺少 issues 数组");
            return new BatchOutcome(defects, batch.files().size(), false, errors);
        }

        Map<String, CodeFile> byPath = new LinkedHashMap<>();
        batch.files().forEach(file -> byPath.put(file.relativePath(), file));
        Map<String, Integer> perFileCount = new LinkedHashMap<>();
        Set<String> dedup = new HashSet<>();
        int dropped = 0;

        for (JsonNode node : issues) {
            String rawPath = text(node, "file");
            CodeFile file = resolveFile(byPath, rawPath);
            if (file == null) {
                dropped++;
                continue;
            }
            String title = text(node, "title");
            if (title == null || title.isBlank()) {
                dropped++;
                continue;
            }
            int line = node.path("line").asInt(0);
            if (line < 1 || line > Math.max(file.lineCount(), 1)) {
                dropped++;
                continue;
            }
            double confidence = node.path("confidence").asDouble(0.6d);
            if (confidence < properties.minConfidence()) {
                dropped++;
                continue;
            }
            // 引用核对：模型必须抄出被举报的源码原文。
            // 这是最有效的一道反幻觉措施——编造的缺陷往往连引文都对不上，
            // 而真实缺陷的引文必然能在文件里找到。
            String quote = text(node, "quote");
            if (quote == null || quote.isBlank() || !quoteMatches(file, line, quote)) {
                dropped++;
                continue;
            }
            DefectCategory category = DefectCategory.fromCode(text(node, "category"));
            String key = file.relativePath() + "#" + line + "#" + category;
            if (!dedup.add(key)) {
                continue;
            }
            int count = perFileCount.merge(file.relativePath(), 1, Integer::sum);
            if (count > properties.maxIssuesPerFile()) {
                continue;
            }

            Map<String, String> evidence = new LinkedHashMap<>();
            evidence.put("model", properties.model());
            evidence.put("prompt", RULE_ID);
            evidence.put("confidence", String.format(Locale.ROOT, "%.2f", confidence));
            evidence.put("quoteVerified", "true");
            if (text(node, "suggestion") != null) {
                evidence.put("suggestion", text(node, "suggestion"));
            }

            defects.add(new RawDefect(
                    file.relativePath(),
                    line,
                    line,
                    RULE_ID,
                    category,
                    Severity.fromCode(text(node, "severity")),
                    truncate(title, 120),
                    truncate(text(node, "description"), 1000),
                    evidence,
                    confidence));
        }
        if (dropped > 0) {
            log.debug("语义审查丢弃 {} 条无效结果（路径不匹配/行号越界/置信度不足）", dropped);
        }
        return new BatchOutcome(defects, batch.files().size(), false, errors);
    }

    /**
     * 核对模型给出的原文引用是否真的出现在被举报行附近。
     *
     * <p>只比较"去掉所有空白后的字符序列"：缩进、行尾空格、换行方式都不应影响判定，
     * 但字符本身必须一致——把 {@code ==} 写成 {@code equals} 就算伪造。
     * 窗口取行号上下各 6 行，容忍模型把起始行报偏了一两行。
     */
    static boolean quoteMatches(CodeFile file, int line, String quote) {
        if (file == null || file.rawLines() == null || file.rawLines().length == 0) {
            return false;
        }
        String needle = normalizeSnippet(quote);
        if (needle.isEmpty()) {
            return false;
        }
        int center = Math.max(0, Math.min(file.rawLines().length - 1, line - 1));
        int from = Math.max(0, center - QUOTE_WINDOW);
        int to = Math.min(file.rawLines().length - 1, center + QUOTE_WINDOW);
        StringBuilder window = new StringBuilder();
        for (int i = from; i <= to; i++) {
            window.append(file.rawLines()[i]).append('\n');
        }
        return normalizeSnippet(window.toString()).contains(needle);
    }

    /**
     * 归一化引文：去掉行号前缀与所有空白。
     *
     * <p>模型偶尔会把"42| code"这种带行号的行整段抄回来，直接比对必然失败，
     * 这里先剥掉前缀再比，避免把格式差异当成伪造。
     */
    private static String normalizeSnippet(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (String line : value.split("\n", -1)) {
            sb.append(line.replaceFirst("^\\s*\\d+\\s*\\|", ""));
        }
        return sb.toString().replaceAll("\\s+", "");
    }

    /** 路径必须落在本批次内：模型偶尔会把路径写成绝对路径或只给文件名。 */    private CodeFile resolveFile(Map<String, CodeFile> byPath, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String normalized = rawPath.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        CodeFile exact = byPath.get(normalized);
        if (exact != null) {
            return exact;
        }
        // 退一步：按后缀匹配，兼容模型把 src/main/java/... 写成 java/com/...
        for (Map.Entry<String, CodeFile> entry : byPath.entrySet()) {
            if (entry.getKey().endsWith("/" + normalized) || normalized.endsWith("/" + entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String systemPrompt() {
        String cached = systemPrompt;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (systemPrompt == null) {
                systemPrompt = loadPrompt();
            }
            return systemPrompt;
        }
    }

    private String loadPrompt() {
        try (InputStream in = new ClassPathResource(PROMPT_PATH).getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (content.isBlank()) {
                log.warn("提示词文件为空，语义审查结果质量会明显下降: {}", PROMPT_PATH);
            }
            return content;
        } catch (IOException ex) {
            log.error("加载语义审查提示词失败，使用内置兜底提示词: {}", ex.getMessage());
            return "你是资深 Java 代码审查专家。只输出 JSON：{\"issues\":[{\"file\":\"\",\"line\":0,"
                    + "\"quote\":\"原文\",\"severity\":\"MAJOR\",\"category\":\"LOGIC_ERROR\","
                    + "\"title\":\"\",\"description\":\"\",\"suggestion\":\"\",\"confidence\":0.8}]}";
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
    }

    private static String brief(String value) {
        if (value == null) {
            return "未知错误";
        }
        String single = value.replaceAll("\\s+", " ").trim();
        return single.length() > 200 ? single.substring(0, 200) + "…" : single;
    }

    /** 送审文件：已加行号的源码 + 真实行数 + 原始行（用于核对模型引用）。 */
    record CodeFile(String relativePath, String numbered, int lineCount, String[] rawLines) {
    }

    /** 一批同时送审的文件。 */
    record PromptBatch(List<CodeFile> files) {
    }

    /** 单个批次的执行结果。 */
    record BatchOutcome(List<RawDefect> defects, int analyzedFiles, boolean truncated, List<String> errors) {
    }
}

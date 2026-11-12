package com.shenchao.codereview.task.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.exception.BizException;
import com.shenchao.codereview.engine.model.Defect;
import com.shenchao.codereview.engine.model.DefectSource;
import com.shenchao.codereview.engine.model.RuleCategory;
import com.shenchao.codereview.engine.model.Severity;
import com.shenchao.codereview.engine.spi.AnalysisContext;
import com.shenchao.codereview.engine.spi.AnalysisResult;
import com.shenchao.codereview.engine.spi.AnalyzerRegistry;
import com.shenchao.codereview.engine.spi.RawDefect;
import com.shenchao.codereview.engine.spi.StaticAnalyzer;
import com.shenchao.codereview.project.entity.RepoProject;
import com.shenchao.codereview.project.git.GitRepositoryService;
import com.shenchao.codereview.project.mapper.RepoProjectMapper;
import com.shenchao.codereview.task.entity.ReviewIssue;
import com.shenchao.codereview.task.entity.ReviewTask;
import com.shenchao.codereview.task.entity.ReviewTaskFile;
import com.shenchao.codereview.task.mapper.ReviewIssueMapper;
import com.shenchao.codereview.task.mapper.ReviewTaskFileMapper;
import com.shenchao.codereview.task.mapper.ReviewTaskMapper;
import com.shenchao.codereview.task.model.TaskStatus;
import com.shenchao.codereview.task.queue.ReviewProperties;
import com.shenchao.codereview.task.service.RuleMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 审查任务执行管道。
 *
 * <p><b>事务纪律</b>：整条管道<b>不加事务</b>。一次审查可能跑几十秒到几分钟，
 * 把它包在一个数据库事务里会长时间占用连接、让 vacuum 无法回收，还会导致
 * "分析成功了但提交失败"这种最难排查的结果。这里每一步都是独立的自动提交语句，
 * 进度靠 {@code stats_json} 与 {@code status} 逐步推进，失败时保留已完成的部分。
 *
 * <p><b>幂等</b>：进入管道的第一件事是抢占（{@code QUEUED → FETCHING} 条件更新）。
 * 队列重复投递时只有一个执行者能抢到，其余直接退出。
 *
 * <p><b>取消</b>：每个阶段之间检查 {@code cancel_requested}，
 * 不做线程中断——中断 PMD 内部线程会留下不确定状态，阶段间检查已经足够快。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewPipeline {

    private static final int BATCH_SIZE = 500;

    private final ReviewTaskMapper taskMapper;
    private final ReviewIssueMapper issueMapper;
    private final ReviewTaskFileMapper taskFileMapper;
    private final RepoProjectMapper projectMapper;
    private final GitRepositoryService gitRepositoryService;
    private final AnalyzerRegistry analyzerRegistry;
    private final RuleMetadataService ruleMetadataService;
    private final WorkspaceScanner workspaceScanner;
    private final ObjectMapper objectMapper;
    private final ReviewProperties properties;

    /**
     * 执行一个任务。
     *
     * <p>本方法不会抛异常：worker 线程池里的异常会静默丢失，
     * 所有失败都必须落到任务状态上，让用户在页面上看得见。
     */
    public void execute(Long taskId) {
        MDC.put("traceId", "task-" + taskId);
        try {
            if (taskId == null) {
                return;
            }
            ReviewTask task = claim(taskId);
            if (task == null) {
                // 重复投递、已被取消、或已被其他 worker 接管
                log.debug("任务未抢到执行权，跳过: taskId={}", taskId);
                return;
            }
            run(task);
        } catch (Exception ex) {
            log.error("审查任务执行异常: taskId={}", taskId, ex);
            markFailed(taskId, null, "执行异常: " + safeMessage(ex), Map.of());
        } finally {
            MDC.remove("traceId");
        }
    }

    // ---------------- 主流程 ----------------

    private void run(ReviewTask task) {
        Long taskId = task.getId();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("engineFlags", parseFlags(task.getEngineFlags()));

        long totalStart = System.currentTimeMillis();
        OffsetDateTime deadline = OffsetDateTime.now().plus(properties.taskTimeout());
        TaskStatus current = TaskStatus.FETCHING;
        List<String> warnings = new ArrayList<>();
        boolean truncated = false;

        try {
            RepoProject project = projectMapper.selectById(task.getProjectId());
            if (project == null) {
                throw new BizException(ErrorCode.PROJECT_NOT_FOUND, "项目不存在或已被删除");
            }
            stats.put("projectCode", project.getCode());

            // ① FETCHING：确认工作目录与提交可用
            long stageStart = System.currentTimeMillis();
            Path workspace = fetch(task, project, stats);
            stats.put("fetchMs", elapsed(stageStart));
            updateStats(taskId, stats);
            if (aborted(taskId, stats)) {
                return;
            }

            // ② SLICING：收集待分析文件
            current = TaskStatus.SLICING;
            moveTo(taskId, TaskStatus.FETCHING, TaskStatus.SLICING);
            stageStart = System.currentTimeMillis();
            WorkspaceScanner.ScanResult scan = workspaceScanner.scan(workspace, taskId);
            persistScannedFiles(taskId, scan);
            stats.put("filesTotal", scan.files().size());
            stats.put("filesSkipped", scan.skipped().size());
            stats.put("sliceMs", elapsed(stageStart));
            if (scan.truncated()) {
                truncated = true;
                stats.put("truncated", true);
                warnings.add("文件数超过上限 " + properties.maxFiles() + "，仅分析前 " + scan.files().size() + " 个");
            }
            updateStats(taskId, stats);
            if (aborted(taskId, stats)) {
                return;
            }

            // ③ ANALYZING：引擎分析
            current = TaskStatus.ANALYZING;
            moveTo(taskId, TaskStatus.SLICING, TaskStatus.ANALYZING);
            stageStart = System.currentTimeMillis();
            AnalysisOutcome outcome = analyze(task, project, workspace, scan.paths(), deadline, stats, warnings);
            stats.put("analyzeMs", elapsed(stageStart));
            stats.put("filesAnalyzed", outcome.analyzedFiles());
            truncated = truncated || outcome.truncated();
            if (outcome.truncated()) {
                stats.put("truncated", true);
                warnings.add("分析超时，已分析 " + outcome.analyzedFiles() + "/" + scan.files().size() + " 个文件");
            }
            updateStats(taskId, stats);
            if (aborted(taskId, stats)) {
                return;
            }

            // ④ FUSING：归一、去重、落库
            current = TaskStatus.FUSING;
            moveTo(taskId, TaskStatus.ANALYZING, TaskStatus.FUSING);
            stageStart = System.currentTimeMillis();
            List<ReviewIssue> issues = fuse(taskId, task.getProjectId(), outcome.defects(), stats);
            stats.put("fuseMs", elapsed(stageStart));
            stats.put("totalMs", elapsed(totalStart));
            stats.put("warnings", warnings);
            if (!outcome.errors().isEmpty()) {
                stats.put("engineErrors", outcome.errors());
            }
            updateStats(taskId, stats);

            TaskStatus terminal = truncated ? TaskStatus.PARTIAL : TaskStatus.DONE;
            taskMapper.finish(taskId, terminal.name(), null, null, issues.size(), writeJson(stats));
            log.info("审查任务完成: taskId={}, status={}, 缺陷 {} 条, 耗时 {}ms",
                    taskId, terminal, issues.size(), elapsed(totalStart));

        } catch (TaskAbortedException ex) {
            // 状态已被其他操作改动（取消、巡检），无需再写终态
            log.info("任务执行被中止: taskId={}, 当前阶段={}", taskId, current);
        } catch (Exception ex) {
            String reason = safeMessage(ex);
            if (ex instanceof BizException biz && biz.getErrorCode() != null) {
                reason = biz.getErrorCode().message() + ": " + reason;
            }
            log.warn("审查任务失败: taskId={}, stage={}, reason={}", taskId, current, reason);
            markFailed(taskId, current, reason, stats);
        }
    }

    // ---------------- 各阶段 ----------------

    private Path fetch(ReviewTask task, RepoProject project, Map<String, Object> stats) {
        if (!RepoProject.SYNC_READY.equals(project.getSyncStatus())) {
            throw new BizException(ErrorCode.REPO_CLONE_FAILED, "项目仓库尚未同步完成，请先在项目页触发同步");
        }
        // 冻结提交与工作目录必须一致：仓库若在任务执行前被同步到新提交，
        // 报告里的行号会与实际代码错位，这种"看起来正常但全是错的"结果最危险
        if (!Objects.equals(project.getLastCommitId(), task.getHeadCommit())) {
            throw new BizException(ErrorCode.CONFLICT, "仓库已更新到 " + shortCommit(project.getLastCommitId())
                    + "，与任务发起时的提交 " + shortCommit(task.getHeadCommit()) + " 不一致，请重新发起审查");
        }
        Path workspace = gitRepositoryService.resolveWorkDir(project.getCode());
        if (!Files.isDirectory(workspace.resolve(".git"))) {
            throw new BizException(ErrorCode.REPO_CLONE_FAILED, "本地工作目录不存在或已损坏，请重新同步仓库");
        }
        stats.put("commit", task.getHeadCommit());
        return workspace;
    }

    private AnalysisOutcome analyze(ReviewTask task,
                                    RepoProject project,
                                    Path workspace,
                                    List<Path> files,
                                    OffsetDateTime deadline,
                                    Map<String, Object> stats,
                                    List<String> warnings) {
        Set<String> enabled = parseFlags(task.getEngineFlags()).entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                .map(entry -> entry.getKey().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());

        List<StaticAnalyzer> analyzers = analyzerRegistry.select(enabled, project.getLanguage());
        if (analyzers.isEmpty()) {
            throw new BizException(ErrorCode.ENGINE_UNAVAILABLE,
                    "没有可用的分析引擎（可用引擎: " + analyzerRegistry.names() + "，已开启: " + enabled + "）");
        }
        stats.put("engines", analyzers.stream().map(StaticAnalyzer::name).toList());

        Set<String> disabledRules = ruleMetadataService.disabledRules();
        AnalysisContext context = new AnalysisContext(
                project.getId(),
                project.getCode(),
                workspace,
                files,
                disabledRules,
                deadline.toInstant(),
                0);

        List<Defect> defects = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int analyzedFiles = 0;
        boolean truncated = false;
        Map<String, Object> perEngine = new LinkedHashMap<>();

        for (StaticAnalyzer analyzer : analyzers) {
            long engineStart = System.currentTimeMillis();
            try {
                AnalysisResult result = analyzer.analyze(context);
                analyzedFiles += result.analyzedFiles();
                truncated = truncated || result.truncated();
                errors.addAll(result.errors());
                for (RawDefect raw : result.defects()) {
                    double confidence = ruleMetadataService.find(raw.ruleId())
                            .map(RuleCategory::weight)
                            .orElse(0.7d);
                    defects.add(Defect.fromRaw(raw, DefectSource.STATIC, confidence));
                }
                perEngine.put(analyzer.name(), Map.of(
                        "defects", result.defects().size(),
                        "analyzedFiles", result.analyzedFiles(),
                        "truncated", result.truncated(),
                        "ms", elapsed(engineStart)));
            } catch (RuntimeException ex) {
                // 单个引擎失败不拖垮整个任务：PMD 挂了但 SpotBugs 成功，仍有可用结果
                String message = analyzer.name() + " 引擎失败: " + safeMessage(ex);
                log.error("引擎执行失败: taskId={}, engine={}", task.getId(), analyzer.name(), ex);
                errors.add(message);
                perEngine.put(analyzer.name(), Map.of("error", message));
            }
        }

        stats.put("enginesDetail", perEngine);
        warnings.addAll(errors);

        boolean nothingProduced = defects.isEmpty() && analyzedFiles == 0;
        if (nothingProduced && !errors.isEmpty()) {
            throw new BizException(ErrorCode.ENGINE_UNAVAILABLE, "静态分析未产出任何结果：" + errors.get(0));
        }
        return new AnalysisOutcome(defects, analyzedFiles, truncated, errors);
    }

    /**
     * 融合与落库。
     *
     * <p>当前只有静态引擎，融合退化为"按指纹去重 + 按严重度排序"；
     * M3 接入 LLM 后，这里会扩成蓝皮书 6.5 的双路交叉验证：
     * 同位置同类别记 {@code CROSS_VALIDATED}，仅静态命中降权记 {@code STATIC_ONLY}。
     */
    private List<ReviewIssue> fuse(Long taskId, Long projectId, List<Defect> defects, Map<String, Object> stats) {
        Map<String, Defect> unique = new LinkedHashMap<>();
        for (Defect defect : defects) {
            unique.putIfAbsent(defect.fingerprint(), defect);
        }
        List<Defect> ordered = unique.values().stream()
                .sorted(Comparator
                        .comparingInt((Defect d) -> d.severity().rank())
                        .thenComparing(Defect::filePath)
                        .thenComparingInt(Defect::startLine))
                .toList();

        List<ReviewIssue> entities = new ArrayList<>(ordered.size());
        Map<String, Integer> perFile = new LinkedHashMap<>();
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        Map<String, Integer> bySource = new LinkedHashMap<>();

        for (Defect defect : ordered) {
            ReviewIssue issue = new ReviewIssue();
            issue.setTaskId(taskId);
            issue.setProjectId(projectId);
            issue.setFilePath(defect.filePath());
            issue.setStartLine(defect.startLine());
            issue.setEndLine(defect.endLine());
            issue.setCategory(defect.category().name());
            issue.setSeverity(defect.severity().name());
            issue.setTitle(defect.title());
            issue.setDescription(defect.description());
            issue.setSuggestion(defect.suggestion());
            issue.setFixSnippet(defect.fixSnippet());
            issue.setSource(defect.source().name());
            issue.setRuleId(defect.ruleId());
            issue.setConfidence(defect.confidence());
            issue.setFlag(DefectSource.STATIC == defect.source() ? "STATIC_ONLY" : "CROSS_VALIDATED");
            issue.setStatus(ReviewIssue.STATUS_OPEN);
            issue.setEvidence(writeJson(defect.evidence()));
            issue.setFingerprint(defect.fingerprint());
            entities.add(issue);

            perFile.merge(defect.filePath(), 1, Integer::sum);
            bySeverity.merge(defect.severity().name(), 1, Integer::sum);
            byCategory.merge(defect.category().name(), 1, Integer::sum);
            bySource.merge(defect.source().name(), 1, Integer::sum);
        }

        for (int from = 0; from < entities.size(); from += BATCH_SIZE) {
            issueMapper.insertBatch(entities.subList(from, Math.min(from + BATCH_SIZE, entities.size())));
        }

        // 只回写有缺陷的文件，全量回写会让"扫了 2000 个文件"变成 2000 条 update
        perFile.forEach((filePath, count) -> {
            try {
                taskFileMapper.markAnalyzed(taskId, filePath, count);
            } catch (RuntimeException ex) {
                log.warn("回写文件缺陷数失败: taskId={}, file={}", taskId, filePath);
            }
        });

        stats.put("defects", entities.size());
        stats.put("bySeverity", bySeverity);
        stats.put("byCategory", byCategory);
        stats.put("bySource", bySource);
        stats.put("filesWithDefects", perFile.size());
        return entities;
    }

    // ---------------- 状态机与辅助 ----------------

    /**
     * 抢占任务。
     *
     * <p>先按 QUEUED 抢，失败再按 CREATED 抢：恢复任务会把遗留的 CREATED 直接入队，
     * 消息到达时任务可能还没被改成 QUEUED，两种情形都要能接住。
     */
    private ReviewTask claim(Long taskId) {
        int claimed = taskMapper.compareAndSetStatus(taskId, TaskStatus.QUEUED.name(), TaskStatus.FETCHING.name());
        if (claimed == 0) {
            claimed = taskMapper.compareAndSetStatus(taskId, TaskStatus.CREATED.name(), TaskStatus.FETCHING.name());
        }
        return claimed == 0 ? null : taskMapper.selectById(taskId);
    }

    private void moveTo(Long taskId, TaskStatus from, TaskStatus to) {
        if (!from.canTransitTo(to)) {
            throw new IllegalStateException("非法的状态迁移: " + from + " → " + to);
        }
        int updated = taskMapper.compareAndSetStatus(taskId, from.name(), to.name());
        if (updated == 0) {
            throw new TaskAbortedException();
        }
    }

    /** 阶段之间检查取消标记，命中则直接写入取消终态。 */
    private boolean aborted(Long taskId, Map<String, Object> stats) {
        if (Boolean.TRUE.equals(taskMapper.selectCancelRequested(taskId))) {
            taskMapper.finish(taskId, TaskStatus.CANCELED.name(), null, "用户取消", 0, writeJson(stats));
            log.info("任务已按用户请求取消: taskId={}", taskId);
            return true;
        }
        return false;
    }

    private void markFailed(Long taskId, TaskStatus stage, String reason, Map<String, Object> stats) {
        if (taskId == null) {
            return;
        }
        try {
            Map<String, Object> finalStats = new LinkedHashMap<>(stats);
            finalStats.put("error", reason);
            taskMapper.finish(taskId, TaskStatus.FAILED.name(),
                    stage == null ? null : stage.name(),
                    truncate(reason, 1000), 0, writeJson(finalStats));
        } catch (RuntimeException ex) {
            log.error("写入任务失败状态时再次失败: taskId={}", taskId, ex);
        }
    }

    private void updateStats(Long taskId, Map<String, Object> stats) {
        try {
            taskMapper.updateProgress(taskId, writeJson(stats));
        } catch (RuntimeException ex) {
            // 进度更新失败不应中断任务
            log.warn("更新任务进度失败: taskId={}, cause={}", taskId, ex.getMessage());
        }
    }

    private void persistScannedFiles(Long taskId, WorkspaceScanner.ScanResult scan) {
        List<ReviewTaskFile> rows = new ArrayList<>(scan.files().size() + scan.skipped().size());
        for (WorkspaceScanner.ScannedFile file : scan.files()) {
            ReviewTaskFile row = new ReviewTaskFile();
            row.setTaskId(taskId);
            row.setFilePath(file.relativePath());
            row.setChangeType(ReviewTaskFile.CHANGE_SCANNED);
            row.setLanguage("JAVA");
            row.setAdditions(0);
            row.setDeletions(0);
            row.setAnalyzed(false);
            row.setDefectCount(0);
            rows.add(row);
        }
        rows.addAll(scan.skipped());
        for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
            taskFileMapper.insertBatch(rows.subList(from, Math.min(from + BATCH_SIZE, rows.size())));
        }
    }

    private Map<String, Object> parseFlags(String json) {
        if (json == null || json.isBlank()) {
            return Map.of("pmd", true);
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            log.warn("解析 engine_flags 失败，回退为仅 PMD: {}", ex.getMessage());
            return Map.of("pmd", true);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            log.warn("序列化统计信息失败: {}", ex.getMessage());
            return "{}";
        }
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private String shortCommit(String commit) {
        return commit == null ? "-" : (commit.length() <= 8 ? commit : commit.substring(0, 8));
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String singleLine = message.replaceAll("\\s+", " ").trim();
        return singleLine.length() > 500 ? singleLine.substring(0, 500) : singleLine;
    }

    /** 引擎层输出汇总。 */
    private record AnalysisOutcome(List<Defect> defects, int analyzedFiles, boolean truncated, List<String> errors) {
    }

    /** 状态已被外部改动（取消/巡检），管道应静默退出。 */
    private static class TaskAbortedException extends RuntimeException {
        TaskAbortedException() {
            super(null, null, false, false);
        }
    }
}

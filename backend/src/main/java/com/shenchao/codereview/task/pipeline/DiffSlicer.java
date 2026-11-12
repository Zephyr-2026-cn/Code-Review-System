package com.shenchao.codereview.task.pipeline;

import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 增量切片：只把 {@code baseCommit..headCommit} 之间改动过的文件交给引擎。
 *
 * <p>全量审查一个中型仓库要扫几千个文件，其中 99% 是本次没动过的代码——
 * 既浪费 PMD 的解析时间，也白白烧掉大模型的 token。增量模式把范围收敛到真正变更的部分，
 * 这也是接入 CI 后"每次提交都跑一遍"的前提。
 *
 * <p><b>为什么用 JGit 的 DiffFormatter 而不是起一个 {@code git diff} 子进程</b>：
 * 子进程要处理路径转义、编码、exit code，而且无法在同一进程内做超时控制；
 * DiffFormatter 直接读对象库，行数统计来自 {@link Edit} 列表，是精确值而非解析补丁文本得到的结果。
 */
@Slf4j
@Component
public class DiffSlicer {

    public static final String TYPE_ADDED = "ADDED";
    public static final String TYPE_MODIFIED = "MODIFIED";
    public static final String TYPE_RENAMED = "RENAMED";

    /** 只有这些后缀参与审查，其余（资源、文档、配置）改了也不影响代码质量。 */
    private static final List<String> SOURCE_SUFFIXES = List.of(
            ".java", ".kt", ".js", ".jsx", ".ts", ".tsx", ".py", ".go");

    /**
     * 计算变更文件清单。
     *
     * @param workspace   仓库工作目录
     * @param baseCommit  基线提交（不含），支持短哈希
     * @param headCommit  目标提交（含），支持短哈希
     * @param maxFiles    文件数上限，超出截断并置 {@code truncated}
     * @throws BizException 提交不存在、仓库损坏等可预期错误
     */
    public DiffResult slice(Path workspace, String baseCommit, String headCommit, int maxFiles) {
        if (!Files.isDirectory(workspace.resolve(".git"))) {
            throw new BizException(ErrorCode.REPO_CLONE_FAILED, "本地工作目录不存在或已损坏，请重新同步仓库");
        }
        int limit = maxFiles <= 0 ? 2000 : maxFiles;
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(workspace.resolve(".git").toFile())
                .readEnvironment()
                .findGitDir()
                .build()) {

            ObjectId base = resolve(repository, baseCommit, "基线提交");
            ObjectId head = resolve(repository, headCommit, "目标提交");
            if (base.equals(head)) {
                return new DiffResult(List.of(), 0, 0, false);
            }

            List<FileChange> changes = new ArrayList<>();
            int additions = 0;
            int deletions = 0;
            boolean truncated = false;

            try (ObjectReader reader = repository.newObjectReader();
                 DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                formatter.setRepository(repository);
                formatter.setDetectRenames(true);

                List<DiffEntry> entries = formatter.scan(
                        treeParser(repository, reader, base),
                        treeParser(repository, reader, head));

                for (DiffEntry entry : entries) {
                    if (changes.size() >= limit) {
                        truncated = true;
                        break;
                    }
                    FileChange change = toChange(workspace, formatter, entry);
                    if (change == null) {
                        continue;
                    }
                    changes.add(change);
                    additions += change.additions();
                    deletions += change.deletions();
                }
            }

            log.info("增量切片完成: base={}, head={}, 变更文件 {} 个, +{}/-{}",
                    brief(baseCommit), brief(headCommit), changes.size(), additions, deletions);
            return new DiffResult(changes, additions, deletions, truncated);

        } catch (BizException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BizException(ErrorCode.REPO_CLONE_FAILED, "计算提交差异失败: " + ex.getMessage());
        }
    }

    /** 单个 diff 条目转成变更记录；删除、二进制、非源码文件返回 null。 */
    private FileChange toChange(Path workspace, DiffFormatter formatter, DiffEntry entry) throws IOException {
        DiffEntry.ChangeType changeType = entry.getChangeType();
        if (changeType == DiffEntry.ChangeType.DELETE) {
            // 文件都删了，没有可审查的代码
            return null;
        }
        String path = entry.getNewPath();
        if (path == null || !isSource(path)) {
            return null;
        }
        Path absolute = workspace.resolve(path);
        if (!Files.isRegularFile(absolute)) {
            // 工作目录与提交不一致时会走到这里（例如同步过程中被改动），跳过而不是报错
            log.debug("变更文件在工作目录中不存在，跳过: {}", path);
            return null;
        }

        int added = 0;
        int removed = 0;
        try {
            for (Edit edit : formatter.toFileHeader(entry).toEditList()) {
                removed += edit.getEndA() - edit.getBeginA();
                added += edit.getEndB() - edit.getBeginB();
            }
        } catch (IOException ex) {
            // 行数统计失败不影响"要分析这个文件"这个结论
            log.debug("统计变更行数失败: file={}, cause={}", path, ex.getMessage());
        }

        String type = switch (changeType) {
            case ADD -> TYPE_ADDED;
            case RENAME, COPY -> TYPE_RENAMED;
            default -> TYPE_MODIFIED;
        };
        return new FileChange(path, type, added, removed);
    }

    private CanonicalTreeParser treeParser(Repository repository, ObjectReader reader, ObjectId commitId)
            throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(commitId);
            CanonicalTreeParser parser = new CanonicalTreeParser();
            parser.reset(reader, commit.getTree());
            return parser;
        }
    }

    private ObjectId resolve(Repository repository, String revision, String label) throws IOException {
        if (revision == null || revision.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, label + "不能为空");
        }
        ObjectId id = repository.resolve(revision.trim() + "^{commit}");
        if (id == null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "仓库中找不到" + label + " " + brief(revision) + "，请先同步仓库");
        }
        return id;
    }

    private boolean isSource(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return SOURCE_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    private static String brief(String value) {
        if (value == null) {
            return "-";
        }
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    /**
     * 单个变更文件。
     *
     * @param path       仓库相对路径（正斜杠）
     * @param changeType ADDED / MODIFIED / RENAMED
     * @param additions  新增行数
     * @param deletions  删除行数
     */
    public record FileChange(String path, String changeType, int additions, int deletions) {
    }

    /**
     * 单个文件的差异块，供报告页展示"这次改了什么"。
     *
     * <p>全量审查没有基线，返回 {@code null}；有基线但该文件不在变更集里也返回 {@code null}，
     * 调用方据此退回"只看当前源码"。
     *
     * @param path      仓库相对路径
     * @param hunks     差异块（按行号升序）
     * @param truncated 行数超过 {@code maxLines} 被截断
     */
    public record FileDiff(String path, List<Hunk> hunks, boolean truncated) {

        public FileDiff {
            hunks = hunks == null ? List.of() : List.copyOf(hunks);
        }
    }

    /**
     * 单个差异块。
     *
     * @param oldStart 旧文件起始行（1 基，0 表示旧文件无对应行）
     * @param newStart 新文件起始行（1 基）
     */
    public record Hunk(int oldStart, int newStart, List<DiffLine> lines) {

        public Hunk {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /**
     * 差异块中的一行。
     *
     * @param type    CONTEXT / ADD / DELETE
     * @param oldLine 旧文件行号，新增行为 0
     * @param newLine 新文件行号，删除行为 0
     */
    public record DiffLine(String type, int oldLine, int newLine, String text) {
    }

    /** 差异行类型。 */
    public static final String LINE_CONTEXT = "CONTEXT";
    public static final String LINE_ADD = "ADD";
    public static final String LINE_DELETE = "DELETE";

    /**
     * 取单个文件在 {@code base..head} 之间的差异。
     *
     * <p>与 {@link #slice} 共用对象库遍历，区别在于这里要的是<b>行内容</b>而不只是行数，
     * 所以额外打开两侧 blob 做逐行对照。hunk 自己拼而不套用 {@code DiffFormatter} 的补丁输出：
     * 报告页要的是结构化的「行号 + 类型 + 文本」，不是要解析的 unified diff 文本。
     *
     * @param workspace    仓库工作目录
     * @param baseCommit   基线提交
     * @param headCommit   目标提交
     * @param filePath     仓库相对路径（正反斜杠均可）
     * @param contextLines 变更点前后保留的上下文行数，超出 [0,50] 会被夹紧
     * @param maxLines     单个文件输出行数上限，防止超大文件把前端撑爆
     * @param focusLine    需要聚焦的行号（新文件），0 表示不聚焦
     * @return 差异；文件不在变更集内或已删除时返回 {@code null}
     */
    public FileDiff fileDiff(Path workspace,
                             String baseCommit,
                             String headCommit,
                             String filePath,
                             int contextLines,
                             int maxLines,
                             int focusLine) {
        if (filePath == null || filePath.isBlank() || !Files.isDirectory(workspace.resolve(".git"))) {
            return null;
        }
        String targetPath = filePath.replace('\\', '/');
        int ctx = Math.max(0, Math.min(contextLines, 50));
        int limit = maxLines <= 0 ? 400 : maxLines;

        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(workspace.resolve(".git").toFile())
                .readEnvironment()
                .findGitDir()
                .build()) {

            ObjectId base = resolve(repository, baseCommit, "基线提交");
            ObjectId head = resolve(repository, headCommit, "目标提交");

            try (ObjectReader reader = repository.newObjectReader();
                 DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                formatter.setRepository(repository);
                formatter.setDetectRenames(true);

                List<DiffEntry> entries = formatter.scan(
                        treeParser(repository, reader, base),
                        treeParser(repository, reader, head));

                DiffEntry target = null;
                for (DiffEntry entry : entries) {
                    if (entry.getChangeType() == DiffEntry.ChangeType.DELETE) {
                        continue;
                    }
                    if (targetPath.equals(entry.getNewPath())) {
                        target = entry;
                        break;
                    }
                }
                if (target == null) {
                    return null;
                }

                byte[] oldBytes = readBlob(repository, target.getOldId().toObjectId());
                byte[] newBytes = readBlob(repository, target.getNewId().toObjectId());
                RawText oldText = new RawText(oldBytes);
                RawText newText = new RawText(newBytes);

                List<Hunk> hunks = new ArrayList<>();
                // 上一个 hunk 已经输出到的新文件行号：相邻 hunk 的上下文会重叠，靠它去重
                int lastNewLine = 0;

                for (Edit edit : formatter.toFileHeader(target).toEditList()) {
                    int ctxBefore = Math.min(ctx, edit.getBeginB());
                    int startB = edit.getBeginB() - ctxBefore;
                    int startA = edit.getBeginA() - ctxBefore;

                    List<DiffLine> lines = new ArrayList<>();
                    int oldCursor = startA;
                    int newCursor = startB;

                    for (int i = 0; i < ctxBefore; i++, oldCursor++, newCursor++) {
                        if (newCursor + 1 <= lastNewLine) {
                            continue;
                        }
                        lines.add(new DiffLine(LINE_CONTEXT, oldCursor + 1, newCursor + 1,
                                safeLine(newText, newCursor)));
                        lastNewLine = newCursor + 1;
                    }
                    for (int i = edit.getBeginA(); i < edit.getEndA(); i++) {
                        lines.add(new DiffLine(LINE_DELETE, i + 1, 0, safeLine(oldText, i)));
                    }
                    for (int i = edit.getBeginB(); i < edit.getEndB(); i++) {
                        lines.add(new DiffLine(LINE_ADD, 0, i + 1, safeLine(newText, i)));
                        lastNewLine = i + 1;
                    }
                    int ctxAfter = Math.min(ctx, newText.size() - edit.getEndB());
                    for (int i = 0; i < ctxAfter; i++, oldCursor++, newCursor++) {
                        int newLine = edit.getEndB() + i;
                        if (newLine + 1 <= lastNewLine) {
                            continue;
                        }
                        lines.add(new DiffLine(LINE_CONTEXT, oldCursor + 1, newLine + 1,
                                safeLine(newText, newLine)));
                        lastNewLine = newLine + 1;
                    }

                    if (!lines.isEmpty()) {
                        hunks.add(new Hunk(startA + 1, startB + 1, lines));
                    }
                }

                // 聚焦并限流：整个文件新增数百行时，缺陷所在的那一段往往排在第 5 屏之后，
                // 只给"从头开始的前 N 行"等于没给。
                FocusResult focused = focus(hunks, focusLine, limit);
                return new FileDiff(targetPath, focused.hunks(), focused.truncated());
            }
        } catch (BizException ex) {
            throw ex;
        } catch (IOException ex) {
            // 展示层拿不到 diff 不该让整个页面失败，降级成"只看源码"
            log.debug("读取文件差异失败: path={}, cause={}", filePath, ex.getMessage());
            return null;
        }
    }

    /**
     * 聚焦并限流：挑出与关注行相关的差异块，再按行数上限裁剪。
     *
     * <p>两级裁剪缺一不可。只按块裁剪时，若整个文件都是新增（一个块覆盖全文件），
     * 裁剪退化成"取前 N 行"，缺陷行照样落在窗口外；只按行裁剪则会把无关的块也带进来。
     *
     * <p>没有精确命中时退回"行号最近的差异块"而不是全部：
     * 缺陷常常指向未改动的历史代码，此时最能说明问题的仍是文件里离它最近的那处改动。
     */
    private FocusResult focus(List<Hunk> hunks, int focusLine, int limit) {
        if (hunks.isEmpty()) {
            return new FocusResult(List.of(), false);
        }

        List<Hunk> scoped = hunks;
        if (focusLine > 0) {
            List<Hunk> hit = new ArrayList<>();
            for (Hunk hunk : hunks) {
                for (DiffLine line : hunk.lines()) {
                    if (line.newLine() == focusLine || line.oldLine() == focusLine) {
                        hit.add(hunk);
                        break;
                    }
                }
            }
            if (!hit.isEmpty()) {
                scoped = hit;
            } else {
                Hunk nearest = null;
                int bestDistance = Integer.MAX_VALUE;
                for (Hunk hunk : hunks) {
                    int distance = Math.abs(hunk.newStart() - focusLine);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        nearest = hunk;
                    }
                }
                scoped = nearest == null ? hunks : List.of(nearest);
            }
        }

        List<Hunk> out = new ArrayList<>(scoped.size());
        int produced = 0;
        boolean truncated = false;
        for (Hunk hunk : scoped) {
            List<DiffLine> lines = hunk.lines();
            if (produced + lines.size() > limit) {
                truncated = true;
                if (produced == 0 && focusLine > 0) {
                    // 单个块就超限：以缺陷行为中心开窗，保证缺陷一定在窗口内
                    int index = indexOfLine(lines, focusLine);
                    int from = Math.max(0, Math.min(index, lines.size() - 1) - limit / 3);
                    from = Math.max(0, Math.min(from, lines.size() - limit));
                    lines = lines.subList(from, from + limit);
                } else {
                    lines = lines.subList(0, Math.max(0, limit - produced));
                }
            }
            if (lines.isEmpty()) {
                break;
            }
            out.add(new Hunk(firstOldLine(lines, hunk.oldStart()), firstNewLine(lines, hunk.newStart()),
                    new ArrayList<>(lines)));
            produced += lines.size();
            if (truncated) {
                break;
            }
        }
        return new FocusResult(out, truncated);
    }

    private int indexOfLine(List<DiffLine> lines, int focusLine) {
        for (int i = 0; i < lines.size(); i++) {
            DiffLine line = lines.get(i);
            if (line.newLine() == focusLine || line.oldLine() == focusLine) {
                return i;
            }
        }
        return 0;
    }

    private int firstNewLine(List<DiffLine> lines, int fallback) {
        for (DiffLine line : lines) {
            if (line.newLine() > 0) {
                return line.newLine();
            }
        }
        return fallback;
    }

    private int firstOldLine(List<DiffLine> lines, int fallback) {
        for (DiffLine line : lines) {
            if (line.oldLine() > 0) {
                return line.oldLine();
            }
        }
        return fallback;
    }

    /** 聚焦后的结果：裁剪过就是 {@code truncated}，前端据此提示"仅展示部分"。 */
    private record FocusResult(List<Hunk> hunks, boolean truncated) {
    }

    private byte[] readBlob(Repository repository, ObjectId id) throws IOException {
        if (id == null || ObjectId.zeroId().equals(id)) {
            return new byte[0];
        }
        try (ObjectReader reader = repository.newObjectReader()) {
            return reader.open(id, Constants.OBJ_BLOB).getBytes();
        }
    }

    /** RawText 越界访问会抛异常；文件末尾缺换行等情况按空行处理更安全。 */
    private String safeLine(RawText text, int index) {
        return index >= 0 && index < text.size() ? text.getString(index) : "";
    }

    /**
     * 切片结果。
     *
     * @param truncated 变更文件数超过上限
     */
    public record DiffResult(List<FileChange> changes, int additions, int deletions, boolean truncated) {

        public DiffResult {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }

        public List<Path> paths(Path workspace) {
            return changes.stream().map(change -> workspace.resolve(change.path())).toList();
        }
    }
}

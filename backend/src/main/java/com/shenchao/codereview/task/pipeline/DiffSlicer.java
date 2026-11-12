package com.shenchao.codereview.task.pipeline;

import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
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

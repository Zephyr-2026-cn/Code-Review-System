package com.shenchao.codereview.project.git;

import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Git 操作封装（JGit）。
 *
 * <p>与业务服务分离的原因：{@code Git} 对象持有文件句柄，必须显式关闭；
 * 把生命周期管理挤在业务事务方法里，很容易出现"克隆成功但句柄未释放，
 * Windows 下目录删不掉"的问题。这里每个方法内部 try-with-resources 收口。
 *
 * <p>克隆策略：
 * <ul>
 *   <li><b>浅克隆 depth=1</b>：审查只关心当前快照，拉全量历史会让大仓库的克隆时间从秒级涨到分钟级</li>
 *   <li><b>单分支</b>：只检出任一分支，减少 90% 以上的传输量</li>
 *   <li><b>体积上限</b>：克隆后统计工作目录大小，超限直接清理并判失败，防止磁盘被塞满</li>
 * </ul>
 */
@Slf4j
@Component
public class GitRepositoryService {

    private final GitProperties properties;

    public GitRepositoryService(GitProperties properties) {
        this.properties = properties;
    }

    /** 仓库访问凭据，明文只在内存中短暂存在。 */
    public record GitCredential(String username, String token) {
    }

    /** 仓库 HEAD 快照。 */
    public record HeadInfo(String commitId, String shortCommitId, String message, OffsetDateTime commitAt, String branch) {
    }

    // ---------------- 目录解析 ----------------

    /**
     * 解析项目工作目录，并校验它确实位于配置的 workspace 之下。
     *
     * <p>这道 {@code startsWith} 校验是路径穿越的最后一道闸：即使 repoKey 因某种
     * 原因被写入了 {@code ../} 片段，也无法把克隆目标指到 workspace 之外。
     */
    public Path resolveWorkDir(String repoKey) {
        if (repoKey == null || repoKey.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "项目编码不能为空");
        }
        Path root = properties.workspace().toAbsolutePath().normalize();
        Path target = root.resolve(repoKey).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            log.error("检测到路径穿越尝试: repoKey={}, resolved={}", repoKey, target);
            throw new BizException(ErrorCode.PARAM_INVALID, "项目编码不合法");
        }
        return target;
    }

    // ---------------- 克隆 / 更新 ----------------

    /**
     * 克隆或更新仓库到指定目录。
     *
     * <p>目录已存在但<b>不是</b>合法 Git 仓库时（上次克隆中断留下的残骸），
     * 递归删除后重新克隆，而不是直接失败——否则一次网络中断会让该项目永久无法同步。
     *
     * @return HEAD 快照
     */
    public HeadInfo cloneOrFetch(String url, String branch, String repoKey, GitCredential credential) {
        Path workDir = resolveWorkDir(repoKey);
        Path gitDir = workDir.resolve(".git");

        try {
            if (Files.isDirectory(gitDir)) {
                return fetchAndReset(workDir, url, branch, credential);
            }
            if (Files.exists(workDir)) {
                log.warn("工作目录存在但不是有效仓库，清理后重新克隆: {}", workDir);
                deleteRecursively(workDir);
            }
            Files.createDirectories(workDir.getParent());
            return shallowClone(url, branch, workDir, credential);
        } catch (InvalidRemoteException ex) {
            throw new BizException(ErrorCode.REPO_URL_INVALID, "仓库地址无效或无权访问: " + safeMessage(ex));
        } catch (TransportException ex) {
            // 认证失败、网络不可达、分支不存在都落这里，统一收敛为"拉取失败"
            throw new BizException(ErrorCode.REPO_CLONE_FAILED, "代码拉取失败: " + safeMessage(ex));
        } catch (GitAPIException ex) {
            throw new BizException(ErrorCode.REPO_CLONE_FAILED, "代码拉取失败: " + safeMessage(ex));
        } catch (IOException ex) {
            log.error("Git 工作目录操作失败: dir={}", workDir, ex);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "代码拉取失败：本地磁盘操作异常");
        }
    }

    private HeadInfo shallowClone(String url, String branch, Path workDir, GitCredential credential)
            throws GitAPIException, IOException {
        long startMs = System.currentTimeMillis();
        try (Git git = Git.cloneRepository()
                .setURI(url)
                .setDirectory(workDir.toFile())
                .setCredentialsProvider(credentialsOf(credential))
                .setTimeout(cloneTimeoutSeconds())
                .setDepth(1)
                .setCloneAllBranches(false)
                .setBranch(branch == null || branch.isBlank() ? null : branch)
                .call()) {

            log.info("仓库克隆完成: url={}, dir={}, branch={}, cost={}ms",
                    url, workDir, branch, System.currentTimeMillis() - startMs);
            assertSizeWithinLimit(workDir);
            return head(git.getRepository());
        }
    }

    private HeadInfo fetchAndReset(Path workDir, String url, String branch, GitCredential credential)
            throws GitAPIException, IOException {
        try (Git git = Git.open(workDir.toFile())) {
            String targetBranch = (branch == null || branch.isBlank()) ? currentBranch(git.getRepository()) : branch;
            if (targetBranch == null) {
                throw new BizException(ErrorCode.REPO_BRANCH_NOT_FOUND, "无法确定要拉取的分支，请显式指定");
            }

            git.fetch()
                    .setRemote(url)
                    .setCredentialsProvider(credentialsOf(credential))
                    .setTimeout(cloneTimeoutSeconds())
                    .setRefSpecs(new RefSpec("+refs/heads/" + targetBranch + ":refs/remotes/origin/" + targetBranch))
                    .setRemoveDeletedRefs(true)
                    .call();

            // 强制对齐到远端：本地可能有残留改动（例如上一次审查写入了临时文件）
            git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef("origin/" + targetBranch)
                    .call();

            log.info("仓库更新完成: url={}, dir={}, branch={}", url, workDir, targetBranch);
            assertSizeWithinLimit(workDir);
            return head(git.getRepository());
        }
    }

    /**
     * 查询远端分支列表（不落盘），供前端绑定表单做分支下拉。
     *
     * <p>不克隆整个仓库：{@code ls-remote} 只取引用列表，代价是毫秒级的。
     * 失败时返回空列表而不是抛异常——分支列举属于"辅助信息"，
     * 让它阻塞仓库接入属于本末倒置（前端可退化为手动输入分支名）。
     */
    public List<String> listRemoteBranches(String url, GitCredential credential) {
        try {
            var refs = Git.lsRemoteRepository()
                    .setRemote(url)
                    .setCredentialsProvider(credentialsOf(credential))
                    .setTimeout(cloneTimeoutSeconds())
                    .setHeads(true)
                    .setTags(false)
                    .call();
            return refs.stream()
                    .map(org.eclipse.jgit.lib.Ref::getName)
                    .filter(name -> name.startsWith("refs/heads/"))
                    .map(name -> name.substring("refs/heads/".length()))
                    .distinct()
                    .sorted()
                    .toList();
        } catch (Exception ex) {
            log.warn("查询远端分支失败: url={}, cause={}", url, safeMessage(ex));
            return List.of();
        }
    }

    // ---------------- HEAD ----------------

    public HeadInfo head(Repository repository) throws IOException {
        ObjectId headId = repository.resolve("HEAD");
        if (headId == null) {
            throw new BizException(ErrorCode.CODE_PARSE_FAILED, "仓库没有提交记录，无法审查");
        }
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(headId);
            OffsetDateTime commitAt = OffsetDateTime.ofInstant(
                    Instant.ofEpochSecond(commit.getCommitTime()), ZoneId.systemDefault());
            String message = commit.getFullMessage();
            if (message != null && message.length() > 500) {
                message = message.substring(0, 500);
            }
            return new HeadInfo(
                    commit.getId().getName(),
                    commit.getId().abbreviate(8).name(),
                    message,
                    commitAt,
                    currentBranch(repository));
        }
    }

    // ---------------- 清理 ----------------

    /** 删除工作目录（项目删除时调用）。失败只告警，不阻塞业务事务。 */
    public void deleteWorkDir(String repoKey) {
        try {
            Path workDir = resolveWorkDir(repoKey);
            if (Files.exists(workDir)) {
                deleteRecursively(workDir);
                log.info("已删除仓库工作目录: {}", workDir);
            }
        } catch (Exception ex) {
            log.warn("删除仓库工作目录失败: repoKey={}, cause={}", repoKey, safeMessage(ex));
        }
    }

    // ---------------- 内部方法 ----------------

    private String currentBranch(Repository repository) throws IOException {
        String fullBranch = repository.getFullBranch();
        if (fullBranch == null) {
            return null;
        }
        // refs/heads/main -> main
        return fullBranch.startsWith("refs/heads/")
                ? fullBranch.substring("refs/heads/".length())
                : fullBranch;
    }

    private CredentialsProvider credentialsOf(GitCredential credential) {
        if (credential == null || credential.username() == null || credential.token() == null) {
            return CredentialsProvider.getDefault();
        }
        return new UsernamePasswordCredentialsProvider(credential.username(), credential.token());
    }

    private int cloneTimeoutSeconds() {
        long seconds = properties.cloneTimeout().toSeconds();
        // JGit 的 setTimeout 传 0 表示"永不超时"，这是我们不想要的行为
        return (int) Math.max(5, Math.min(seconds, 3600));
    }

    /**
     * 校验工作目录体积。
     *
     * <p>用流式遍历而不是先收集所有路径：目录可能有几十万个文件，
     * 先收集再统计会把内存打满。
     */
    private void assertSizeWithinLimit(Path workDir) throws IOException {
        long limit = properties.maxRepoSizeBytes();
        long[] total = {0L};
        Files.walkFileTree(workDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                total[0] += attrs.size();
                if (total[0] > limit) {
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // 单个文件读失败（权限、符号链接失效）不应中断整体统计
                return FileVisitResult.CONTINUE;
            }
        });

        if (total[0] > limit) {
            deleteRecursively(workDir);
            throw new BizException(ErrorCode.REPO_CLONE_FAILED,
                    "仓库体积超过上限 " + properties.maxRepoSizeMb() + "MB，已清理本地副本");
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> toDelete = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                toDelete.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        for (Path path : toDelete) {
            Files.deleteIfExists(path);
        }
        Files.deleteIfExists(root);
    }

    /** 把第三方异常信息压缩成一行可展示文案，避免把堆栈或内部路径暴露给前端。 */
    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String singleLine = message.replaceAll("\\s+", " ").trim();
        return singleLine.length() > 200 ? singleLine.substring(0, 200) : singleLine;
    }
}

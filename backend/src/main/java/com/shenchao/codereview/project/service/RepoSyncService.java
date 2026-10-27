package com.shenchao.codereview.project.service;

import com.shenchao.codereview.project.entity.RepoProject;
import com.shenchao.codereview.project.git.GitRepositoryService;
import com.shenchao.codereview.project.mapper.RepoProjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 仓库同步执行器。
 *
 * <p>与 {@code ProjectService} 分离的原因：本项目里"改业务数据"和"拉代码"是两件不同生命周期的事。
 * 混在一起会让 {@code @Transactional} 覆盖到克隆操作——一次 120 秒的克隆会长时间占用数据库连接，
 * 连接池很快被耗尽，这是典型的"把 IO 放进事务"事故。
 *
 * <p>这里的每个状态更新都是独立短事务，克隆本身在事务之外执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoSyncService {

    private static final int MAX_ERROR_LENGTH = 500;

    private final RepoProjectMapper projectMapper;
    private final GitRepositoryService gitRepositoryService;
    private final CredentialService credentialService;

    /**
     * 执行一次同步（阻塞方法，由异步线程调用）。
     *
     * <p>流程：抢占状态 → 克隆/更新 → 落 HEAD → 标记 READY；任一步失败标记 FAILED 并记录原因。
     * 抢占失败（别人正在克隆）时立即返回，不抛异常——这不是错误，是幂等保护。
     */
    public void sync(Long projectId, String reason) {
        if (projectId == null) {
            return;
        }
        if (projectMapper.tryMarkCloning(projectId) == 0) {
            log.info("仓库已在同步中或已被他人抢占，跳过: projectId={}, reason={}", projectId, reason);
            return;
        }

        RepoProject project = projectMapper.selectById(projectId);
        if (project == null) {
            log.warn("待同步项目不存在，忽略: projectId={}", projectId);
            return;
        }
        if (!project.isBound()) {
            // 抢占成功但没绑定地址：把状态退回 UNBOUND，避免卡在 CLONING
            projectMapper.markFailed(projectId, "项目尚未绑定仓库地址");
            return;
        }

        long startMs = System.currentTimeMillis();
        try {
            GitRepositoryService.GitCredential credential =
                    credentialService.resolveForSystem(project.getCredentialId());

            GitRepositoryService.HeadInfo head = gitRepositoryService.cloneOrFetch(
                    project.getRepoUrl(), project.getRepoBranch(), project.getCode(), credential);

            projectMapper.markReady(projectId, head.shortCommitId(), head.message(), head.commitAt());
            log.info("仓库同步成功: projectId={}, code={}, commit={}, branch={}, reason={}, cost={}ms",
                    projectId, project.getCode(), head.shortCommitId(), head.branch(), reason,
                    System.currentTimeMillis() - startMs);
        } catch (Exception ex) {
            // 异常必须全部吞掉：异步线程里抛出只会进 UncaughtExceptionHandler，无人可见
            String message = truncate(describe(ex));
            projectMapper.markFailed(projectId, message);
            log.warn("仓库同步失败: projectId={}, code={}, reason={}, cause={}",
                    projectId, project.getCode(), reason, message);
        }
    }

    /** 取异常里最适合展示给用户的那一层信息（业务异常自带人话文案）。 */
    private String describe(Exception ex) {
        if (ex instanceof com.shenchao.codereview.common.exception.BizException bizException) {
            return bizException.getMessage();
        }
        String message = ex.getMessage();
        return (message == null || message.isBlank()) ? ex.getClass().getSimpleName() : message;
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}

package com.shenchao.codereview.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shenchao.codereview.auth.security.LoginUser;
import com.shenchao.codereview.auth.security.SecurityUtils;
import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.api.PageResult;
import com.shenchao.codereview.common.exception.BizException;
import com.shenchao.codereview.project.dto.ProjectCreateRequest;
import com.shenchao.codereview.project.dto.ProjectQuery;
import com.shenchao.codereview.project.dto.ProjectUpdateRequest;
import com.shenchao.codereview.project.dto.ProjectVO;
import com.shenchao.codereview.project.dto.RepoBindRequest;
import com.shenchao.codereview.project.entity.RepoCredential;
import com.shenchao.codereview.project.entity.RepoProject;
import com.shenchao.codereview.project.event.RepoSyncEvent;
import com.shenchao.codereview.project.git.GitRepositoryService;
import com.shenchao.codereview.project.git.GitUrlValidator;
import com.shenchao.codereview.project.mapper.RepoCredentialMapper;
import com.shenchao.codereview.project.mapper.RepoProjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 项目管理（项目 = 被审查的代码仓库绑定）。
 *
 * <p>权限模型：
 * <ul>
 *   <li>读：拥有 {@code project:repo:list} 的角色可看全部项目（团队共享视图）</li>
 *   <li>写：仅项目归属人或管理员，避免"改别人的仓库地址"这类供应链风险</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private static final int MAX_CODE_ATTEMPTS = 20;

    private final RepoProjectMapper projectMapper;
    private final RepoCredentialMapper credentialMapper;
    private final GitUrlValidator gitUrlValidator;
    private final GitRepositoryService gitRepositoryService;
    private final CredentialService credentialService;
    private final ApplicationEventPublisher eventPublisher;

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public PageResult<ProjectVO> page(ProjectQuery query) {
        LambdaQueryWrapper<RepoProject> wrapper = Wrappers.<RepoProject>lambdaQuery();

        String keyword = query.getKeyword();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(RepoProject::getName, keyword)
                    .or().like(RepoProject::getCode, keyword)
                    .or().like(RepoProject::getRepoUrl, keyword));
        }
        if (query.getSyncStatus() != null && !query.getSyncStatus().isBlank()) {
            wrapper.eq(RepoProject::getSyncStatus, query.getSyncStatus());
        }
        if (Boolean.TRUE.equals(query.getMineOnly())) {
            wrapper.eq(RepoProject::getOwnerId, SecurityUtils.requireUserId());
        }
        wrapper.orderByDesc(RepoProject::getId);

        Page<RepoProject> page = new Page<>(query.getPage(), query.getPageSize());
        projectMapper.selectPage(page, wrapper);

        Map<Long, String> credentialNames = loadCredentialNames(page.getRecords());
        List<ProjectVO> records = page.getRecords().stream()
                .map(project -> ProjectVO.from(project, credentialNames.get(project.getCredentialId())))
                .toList();

        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Transactional(readOnly = true)
    public ProjectVO detail(Long id) {
        RepoProject project = requireProject(id);
        return ProjectVO.from(project, credentialService.nameOf(project.getCredentialId()));
    }

    /** 远端分支列表，供绑定表单下拉选择。失败返回空列表而不是报错。 */
    public List<String> listRemoteBranches(Long id) {
        RepoProject project = requireProject(id);
        SecurityUtils.requireOwnerOrAdmin(project.getOwnerId());
        if (!project.isBound()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "项目尚未绑定仓库地址");
        }
        return gitRepositoryService.listRemoteBranches(project.getRepoUrl(),
                credentialService.resolveForSystem(project.getCredentialId()));
    }

    // ---------------- 写操作 ----------------

    @Transactional(rollbackFor = Exception.class)
    public ProjectVO create(ProjectCreateRequest request) {
        String repoUrl = request.normalizedRepoUrl();
        String normalizedUrl = null;
        if (repoUrl != null) {
            normalizedUrl = gitUrlValidator.normalize(repoUrl);
            assertRepoUrlNotUsed(normalizedUrl, null);
            assertCredentialAccessible(request.credentialId());
        }

        String code = resolveCode(request.normalizedCode(), normalizedUrl, request.normalizedName());

        RepoProject project = new RepoProject();
        project.setName(request.normalizedName());
        project.setCode(code);
        project.setDescription(request.normalizedDescription());
        project.setLanguage(request.normalizedLanguage());
        project.setRepoUrl(normalizedUrl);
        project.setRepoBranch(request.normalizedRepoBranch());
        project.setCredentialId(request.credentialId());
        // 新建时一律是 UNBOUND：真正的 CLONING 状态由同步线程抢占后写入，
        // 在这里直接置 CLONING 会导致异步任务因"已被占用"而放弃执行
        project.setSyncStatus(RepoProject.SYNC_UNBOUND);
        project.setOwnerId(SecurityUtils.requireUserId());
        project.setDeleted(0);
        projectMapper.insert(project);

        log.info("创建项目: id={}, code={}, repoUrl={}, operator={}",
                project.getId(), code, normalizedUrl, LoginUser.currentUsernameOrNull());

        if (normalizedUrl != null) {
            eventPublisher.publishEvent(new RepoSyncEvent(project.getId(), "CREATE"));
        }
        return detail(project.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectVO update(Long id, ProjectUpdateRequest request) {
        RepoProject project = requireProject(id);
        SecurityUtils.requireOwnerOrAdmin(project.getOwnerId());

        RepoProject update = new RepoProject();
        update.setId(id);
        update.setName(request.normalizedName());
        update.setDescription(request.normalizedDescription());
        update.setLanguage(request.normalizedLanguage());
        projectMapper.updateById(update);

        log.info("修改项目: id={}, operator={}", id, LoginUser.currentUsernameOrNull());
        return detail(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        RepoProject project = requireProject(id);
        SecurityUtils.requireOwnerOrAdmin(project.getOwnerId());

        // 先删库记录，再清理磁盘：磁盘清理失败只告警，不能因为"删不掉目录"而阻止业务删除
        projectMapper.deleteById(id);
        gitRepositoryService.deleteWorkDir(project.getCode());

        log.info("删除项目: id={}, code={}, operator={}", id, project.getCode(),
                LoginUser.currentUsernameOrNull());
    }

    /**
     * 绑定 / 更新仓库地址。
     *
     * <p>绑定后立即抛事件触发异步克隆，接口立刻返回（状态稍后由同步流程推进到 CLONING/READY）。
     * 这样即便仓库很大，前端也不会看着一个转圈 2 分钟的请求超时。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectVO bindRepo(Long id, RepoBindRequest request) {
        RepoProject project = requireProject(id);
        SecurityUtils.requireOwnerOrAdmin(project.getOwnerId());

        String normalizedUrl = gitUrlValidator.normalize(request.repoUrl());
        assertRepoUrlNotUsed(normalizedUrl, id);
        assertCredentialAccessible(request.credentialId());

        RepoProject update = new RepoProject();
        update.setId(id);
        update.setRepoUrl(normalizedUrl);
        update.setRepoBranch(request.normalizedRepoBranch());
        update.setCredentialId(request.credentialId());
        update.setLastError(null);
        projectMapper.updateById(update);

        log.info("绑定仓库: projectId={}, url={}, branch={}, operator={}",
                id, normalizedUrl, request.normalizedRepoBranch(), LoginUser.currentUsernameOrNull());

        eventPublisher.publishEvent(new RepoSyncEvent(id, "BIND"));
        return detail(id);
    }

    /** 手动触发同步（拉取最新代码）。返回时状态可能已经变成 CLONING，前端应轮询详情。 */
    @Transactional(rollbackFor = Exception.class)
    public ProjectVO sync(Long id) {
        RepoProject project = requireProject(id);
        SecurityUtils.requireOwnerOrAdmin(project.getOwnerId());
        if (!project.isBound()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "项目尚未绑定仓库地址");
        }
        eventPublisher.publishEvent(new RepoSyncEvent(id, "MANUAL"));
        log.info("手动触发仓库同步: projectId={}, operator={}", id, LoginUser.currentUsernameOrNull());
        return detail(id);
    }

    // ---------------- 内部方法 ----------------

    private RepoProject requireProject(Long id) {
        if (id == null) {
            throw new BizException(ErrorCode.PROJECT_NOT_FOUND);
        }
        RepoProject project = projectMapper.selectById(id);
        if (project == null) {
            throw new BizException(ErrorCode.PROJECT_NOT_FOUND);
        }
        return project;
    }

    private void assertRepoUrlNotUsed(String normalizedUrl, Long excludeId) {
        List<RepoProject> existing = projectMapper.selectList(
                Wrappers.<RepoProject>lambdaQuery().eq(RepoProject::getRepoUrl, normalizedUrl));
        boolean conflict = existing.stream()
                .anyMatch(project -> !Objects.equals(project.getId(), excludeId));
        if (conflict) {
            throw new BizException(ErrorCode.REPO_ALREADY_EXISTS);
        }
    }

    /**
     * 校验凭据可用性与归属。
     *
     * <p>在<b>请求线程</b>里做这件事是必须的：异步同步线程没有登录上下文，
     * 无法再做归属判断，一旦放过就会变成"用他人凭据克隆私有仓库"的越权通道。
     */
    private void assertCredentialAccessible(Long credentialId) {
        if (credentialId == null) {
            return;
        }
        // resolve 内部会做 requireOwnerOrAdmin，这里只借用其校验语义
        credentialService.resolve(credentialId);
    }

    /**
     * 推导项目编码。
     *
     * <p>优先级：手填 > 仓库地址推导 > 名称清洗 > 随机兜底。
     * 自动推导时若发生冲突则追加序号，最多尝试 {@value #MAX_CODE_ATTEMPTS} 次，
     * 超过则要求用户手动指定——避免无限循环，也避免生成 `repo-1` … `repo-99` 这种不可读编码。
     */
    private String resolveCode(String requestedCode, String normalizedUrl, String name) {
        if (requestedCode != null) {
            if (projectMapper.countByCode(requestedCode) > 0) {
                throw new BizException(ErrorCode.CONFLICT, "项目编码 " + requestedCode + " 已被占用");
            }
            return requestedCode;
        }

        String base = normalizedUrl != null
                ? gitUrlValidator.deriveRepoKey(normalizedUrl)
                : sanitizeCode(name);
        if (base == null || base.isBlank()) {
            base = "proj-" + Integer.toString(ThreadLocalRandom.current().nextInt(1 << 20), 36);
        }

        String candidate = base;
        for (int i = 0; i < MAX_CODE_ATTEMPTS; i++) {
            if (projectMapper.countByCode(candidate) == 0) {
                return candidate;
            }
            candidate = base + "-" + (i + 2);
        }
        throw new BizException(ErrorCode.CONFLICT, "无法自动生成唯一的项目编码，请手动指定");
    }

    private String sanitizeCode(String name) {
        if (name == null) {
            return null;
        }
        String sanitized = name.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[^a-z0-9]+", "")
                .replaceAll("[^a-z0-9]+$", "");
        // 中文名称清洗后可能为空，交给调用方兜底
        if (sanitized.isBlank()) {
            return null;
        }
        return sanitized.length() > 48 ? sanitized.substring(0, 48) : sanitized;
    }

    /** 批量查凭据名称，避免列表页每行一次查询。 */
    private Map<Long, String> loadCredentialNames(List<RepoProject> projects) {
        Set<Long> credentialIds = new LinkedHashSet<>();
        projects.stream().map(RepoProject::getCredentialId).filter(Objects::nonNull).forEach(credentialIds::add);
        if (credentialIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        for (RepoCredential credential : credentialMapper.selectBatchIds(credentialIds)) {
            result.put(credential.getId(), credential.getName());
        }
        return result;
    }
}

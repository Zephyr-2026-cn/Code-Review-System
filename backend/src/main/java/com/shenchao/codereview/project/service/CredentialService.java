package com.shenchao.codereview.project.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shenchao.codereview.auth.security.LoginUser;
import com.shenchao.codereview.auth.security.SecurityUtils;
import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.exception.BizException;
import com.shenchao.codereview.project.dto.CredentialCreateRequest;
import com.shenchao.codereview.project.dto.CredentialVO;
import com.shenchao.codereview.project.entity.RepoCredential;
import com.shenchao.codereview.project.git.GitProperties;
import com.shenchao.codereview.project.git.GitRepositoryService;
import com.shenchao.codereview.project.git.SecretCipher;
import com.shenchao.codereview.project.mapper.RepoCredentialMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Git 凭据管理。
 *
 * <p>三条安全约定：
 * <ol>
 *   <li>令牌只在创建时传输一次，落库即密文，任何接口都不回显</li>
 *   <li>凭据按归属隔离：非管理员只能看到并使用自己创建的凭据</li>
 *   <li>被项目引用的凭据不可删除，否则项目会在下次同步时突然失去访问权限</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialService {

    private final RepoCredentialMapper credentialMapper;
    private final GitProperties gitProperties;

    /** 延迟到首次使用时构造，避免"没配密钥"导致应用启动失败。 */
    private volatile SecretCipher cipher;

    @PostConstruct
    void initCipher() {
        this.cipher = SecretCipher.of(gitProperties.credentialKey());
        if (this.cipher == null) {
            log.warn("Git 凭据功能未启用：请配置环境变量 APP_REPO_SECRET_KEY（Base64 编码的 32 字节密钥）");
        }
    }

    public boolean isEnabled() {
        return cipher != null;
    }

    @Transactional(rollbackFor = Exception.class)
    public CredentialVO create(CredentialCreateRequest request) {
        if (!isEnabled()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "服务端未配置凭据加密密钥，暂不支持私有仓库凭据");
        }
        String name = request.normalizedName();
        if (credentialMapper.countByName(name) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "凭据名称已存在");
        }

        RepoCredential credential = new RepoCredential();
        credential.setName(name);
        credential.setUsername(request.normalizedUsername());
        credential.setSecretCipher(cipher.encrypt(request.token()));
        credential.setOwnerId(SecurityUtils.requireUserId());
        credential.setDeleted(0);
        credentialMapper.insert(credential);

        // 只记名称与 ID，绝不记录令牌
        log.info("创建 Git 凭据: id={}, name={}, operator={}",
                credential.getId(), name, LoginUser.currentUsernameOrNull());
        return CredentialVO.from(credential);
    }

    @Transactional(readOnly = true)
    public List<CredentialVO> list() {
        Long currentUserId = SecurityUtils.requireUserId();
        var wrapper = Wrappers.<RepoCredential>lambdaQuery().orderByDesc(RepoCredential::getId);
        if (!SecurityUtils.isAdmin()) {
            // 非管理员只能看到自己的凭据，避免"借用"他人令牌访问私有仓库
            wrapper.eq(RepoCredential::getOwnerId, currentUserId);
        }
        return credentialMapper.selectList(wrapper).stream().map(CredentialVO::from).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        RepoCredential credential = requireCredential(id);
        SecurityUtils.requireOwnerOrAdmin(credential.getOwnerId());

        long referenced = credentialMapper.countProjectsByCredentialId(id);
        if (referenced > 0) {
            throw new BizException(ErrorCode.CONFLICT,
                    "该凭据仍被 " + referenced + " 个项目使用，请先解绑后再删除");
        }

        credentialMapper.deleteById(id);
        log.info("删除 Git 凭据: id={}, name={}, operator={}",
                id, credential.getName(), LoginUser.currentUsernameOrNull());
    }

    /**
     * 解析出可直接用于 JGit 的凭据。
     *
     * <p>顺带做归属校验：绑定项目时如果引用了别人的凭据，这里会直接拒绝，
     * 防止开发者 A 用管理员配置的高权限令牌去克隆私有仓库。
     *
     * @param credentialId 可为 null（公开仓库）
     * @return 无凭据时返回 null
     */
    @Transactional(readOnly = true)
    public GitRepositoryService.GitCredential resolve(Long credentialId) {
        if (credentialId == null) {
            return null;
        }
        if (!isEnabled()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "服务端未配置凭据加密密钥，无法使用私有仓库凭据");
        }
        RepoCredential credential = requireCredential(credentialId);
        SecurityUtils.requireOwnerOrAdmin(credential.getOwnerId());
        return new GitRepositoryService.GitCredential(
                credential.getUsername(), cipher.decrypt(credential.getSecretCipher()));
    }

    /** 仅用于展示：校验凭据存在并返回名称。 */
    @Transactional(readOnly = true)
    public String nameOf(Long credentialId) {
        if (credentialId == null) {
            return null;
        }
        RepoCredential credential = credentialMapper.selectById(credentialId);
        return credential == null ? null : credential.getName();
    }

    /**
     * <b>仅供后台同步线程使用</b>的凭据解析，跳过归属校验。
     *
     * <p>为什么需要单独一个方法：异步线程没有 SecurityContext，
     * 直接调用 {@link #resolve(Long)} 会因取不到登录用户而抛 401。
     * 安全性由调用链保证——绑定/同步接口在<b>请求线程</b>里已经完成过
     * {@code requireOwnerOrAdmin} 校验，异步线程只是执行已授权的动作。
     * 该方法不对外暴露，避免被 Controller 误用。
     */
    public GitRepositoryService.GitCredential resolveForSystem(Long credentialId) {
        if (credentialId == null) {
            return null;
        }
        if (!isEnabled()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "服务端未配置凭据加密密钥，无法使用私有仓库凭据");
        }
        RepoCredential credential = credentialMapper.selectById(credentialId);
        if (credential == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "凭据不存在或已被删除");
        }
        return new GitRepositoryService.GitCredential(
                credential.getUsername(), cipher.decrypt(credential.getSecretCipher()));
    }

    private RepoCredential requireCredential(Long id) {
        if (id == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "凭据不存在");
        }
        RepoCredential credential = credentialMapper.selectById(id);
        if (credential == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "凭据不存在");
        }
        return credential;
    }
}

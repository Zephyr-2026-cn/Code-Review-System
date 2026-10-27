package com.shenchao.codereview.auth.service;

import com.shenchao.codereview.auth.dto.AuthResult;
import com.shenchao.codereview.auth.dto.ChangePasswordRequest;
import com.shenchao.codereview.auth.dto.LoginRequest;
import com.shenchao.codereview.auth.dto.UserProfile;
import com.shenchao.codereview.auth.security.JwtProperties;
import com.shenchao.codereview.auth.security.JwtTokenProvider;
import com.shenchao.codereview.auth.security.LoginUser;
import com.shenchao.codereview.auth.security.SecurityProperties;
import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.exception.BizException;
import com.shenchao.codereview.common.util.PasswordPolicy;
import com.shenchao.codereview.system.entity.SysUser;
import com.shenchao.codereview.system.mapper.SysRoleMapper;
import com.shenchao.codereview.system.mapper.SysUserMapper;
import com.shenchao.codereview.system.service.PermissionCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 认证服务：登录、刷新、登出、改密。
 *
 * <p>安全要点（答辩常被追问，逐条都有实现支撑）：
 * <ol>
 *   <li><b>不泄漏用户是否存在</b>：用户不存在时也执行一次 BCrypt 校验（消耗同等时间），统一返回"用户名或密码错误"</li>
 *   <li><b>失败锁定</b>：连续失败达阈值锁定账号，抵御在线暴力破解</li>
 *   <li><b>刷新令牌一次一用</b>：消费即删除，重放直接失败</li>
 *   <li><b>登出即失效</b>：访问令牌 jti 进黑名单，不必等自然过期</li>
 * </ol>
 */
@Slf4j
@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final PermissionCacheService permissionCacheService;
    private final JwtTokenProvider tokenProvider;
    private final TokenStore tokenStore;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;
    private final SecurityProperties securityProperties;

    /**
     * 用于"用户不存在"时的等时校验，避免通过响应时间差推断账号是否存在。
     * 在构造时生成一个随机明文的摘要，明文不可知，无法被利用。
     */
    private final String dummyHash;

    public AuthService(SysUserMapper userMapper,
                       SysRoleMapper roleMapper,
                       PermissionCacheService permissionCacheService,
                       JwtTokenProvider tokenProvider,
                       TokenStore tokenStore,
                       PasswordEncoder passwordEncoder,
                       JwtProperties jwtProperties,
                       SecurityProperties securityProperties) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.permissionCacheService = permissionCacheService;
        this.tokenProvider = tokenProvider;
        this.tokenStore = tokenStore;
        this.passwordEncoder = passwordEncoder;
        this.jwtProperties = jwtProperties;
        this.securityProperties = securityProperties;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // ================= 登录 =================

    @Transactional(rollbackFor = Exception.class)
    public AuthResult login(LoginRequest request) {
        String username = request.normalizedUsername();

        if (tokenStore.isLoginLocked(username)) {
            throw new BizException(ErrorCode.ACCOUNT_LOCKED,
                    "登录失败次数过多，账号已锁定 " + securityProperties.loginLockDuration().toMinutes() + " 分钟");
        }

        SysUser user = userMapper.selectByUsername(username);
        if (user == null) {
            // 等时校验：即使账号不存在也跑一次 BCrypt，抹平响应时间差异
            passwordEncoder.matches(request.password(), dummyHash);
            registerFailure(username);
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            registerFailure(username);
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }

        if (!user.isEnabled()) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }

        tokenStore.clearLoginFailure(username);
        touchLastLoginAt(user.getId());
        passwordUpgradeIfNeeded(user, request.password());

        LoginUser loginUser = buildLoginUser(user);
        log.info("用户登录成功: username={}, userId={}, roles={}", user.getUsername(), user.getId(), loginUser.roles());
        return issueTokens(loginUser);
    }

    // ================= 刷新 =================

    @Transactional(rollbackFor = Exception.class)
    public AuthResult refresh(String refreshToken) {
        Long userId = tokenStore.consumeRefreshToken(refreshToken)
                .orElseThrow(() -> new BizException(ErrorCode.REFRESH_TOKEN_INVALID));

        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (!user.isEnabled()) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }

        return issueTokens(buildLoginUser(user));
    }

    // ================= 登出 =================

    /**
     * 登出：拉黑当前访问令牌 + 吊销刷新令牌。
     *
     * @param refreshToken 可为 null（例如改密后强制下线时前端尚未回传 Cookie）
     */
    public void logout(LoginUser loginUser, String refreshToken) {
        if (loginUser == null) {
            return;
        }
        if (loginUser.tokenId() != null && loginUser.tokenExpiresAt() != null) {
            java.time.Duration remaining = java.time.Duration.between(
                    java.time.Instant.now(), loginUser.tokenExpiresAt());
            tokenStore.blacklistAccessToken(loginUser.tokenId(), remaining);
        }
        tokenStore.revokeRefreshToken(refreshToken);
        log.info("用户登出: username={}", loginUser.username());
    }

    // ================= 当前用户 =================

    public UserProfile profileOf(LoginUser loginUser) {
        SysUser user = userMapper.selectById(loginUser.userId());
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return new UserProfile(user.getId(), user.getUsername(), user.getNickname(), user.getEmail(),
                List.copyOf(loginUser.roles()), loginUser.permissions());
    }

    // ================= 修改密码 =================

    @Transactional(rollbackFor = Exception.class)
    public void changePassword(LoginUser loginUser, ChangePasswordRequest request) {
        SysUser user = userMapper.selectById(loginUser.userId());
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (!passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS, "原密码不正确");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新密码不能与原密码相同");
        }
        PasswordPolicy.validate(request.newPassword());

        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(request.newPassword()));
        userMapper.updateById(update);

        // 改密后强制重新登录：拉黑当前令牌，前端收到后跳登录页
        logout(loginUser, null);
        log.info("用户修改密码成功: username={}", user.getUsername());
    }

    // ================= 内部方法 =================

    private AuthResult issueTokens(LoginUser loginUser) {
        JwtTokenProvider.IssuedToken accessToken = tokenProvider.issueAccessToken(loginUser);
        String refreshToken = tokenStore.createRefreshToken(loginUser.userId(), jwtProperties.refreshTokenTtl());

        UserProfile profile = new UserProfile(loginUser.userId(), loginUser.username(), loginUser.nickname(),
                null, List.copyOf(loginUser.roles()), loginUser.permissions());

        return new AuthResult(accessToken.token(), accessToken.expiresIn(), refreshToken, profile);
    }

    private LoginUser buildLoginUser(SysUser user) {
        Set<String> roles = new java.util.LinkedHashSet<>(roleMapper.selectRoleCodesByUserId(user.getId()));
        Set<String> permissions = permissionCacheService.permissionsOf(user.getId());
        return LoginUser.of(user.getId(), user.getUsername(), user.getNickname(), roles, permissions);
    }

    private void registerFailure(String username) {
        long failures = tokenStore.increaseLoginFailure(username, securityProperties.loginLockDuration());
        if (failures >= securityProperties.maxLoginFailures()) {
            tokenStore.lockLogin(username, securityProperties.loginLockDuration());
            tokenStore.clearLoginFailure(username);
            log.warn("账号因连续登录失败被锁定: username={}, failures={}", username, failures);
            throw new BizException(ErrorCode.ACCOUNT_LOCKED,
                    "登录失败次数过多，账号已锁定 " + securityProperties.loginLockDuration().toMinutes() + " 分钟");
        }
        log.warn("登录失败: username={}, 累计失败次数={}", username, failures);
    }

    private void touchLastLoginAt(Long userId) {
        SysUser update = new SysUser();
        update.setId(userId);
        update.setLastLoginAt(OffsetDateTime.now());
        userMapper.updateById(update);
    }

    /**
     * BCrypt 强度升级：若历史密码使用更低 cost 生成，登录成功时用当前 cost 重新加密。
     * 这是一种"无感迁移"，用户无需改密即可逐步提升存储安全性。
     */
    private void passwordUpgradeIfNeeded(SysUser user, String rawPassword) {
        try {
            if (passwordEncoder.upgradeEncoding(user.getPassword())) {
                SysUser update = new SysUser();
                update.setId(user.getId());
                update.setPassword(passwordEncoder.encode(rawPassword));
                userMapper.updateById(update);
                log.info("已升级用户密码哈希强度: userId={}", user.getId());
            }
        } catch (Exception ex) {
            // 升级失败不影响登录
            log.warn("密码哈希升级失败，忽略: userId={}, cause={}", user.getId(), ex.getMessage());
        }
    }
}

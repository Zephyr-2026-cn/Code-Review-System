package com.shenchao.codereview.auth.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 当前登录用户快照（作为 Spring Security 的 principal）。
 *
 * <p>刻意不实现 {@code UserDetails}：本项目不使用 Spring Security 的
 * {@code DaoAuthenticationProvider}，而是自建 JWT 校验链路，
 * 因此 principal 只需携带业务需要的身份信息与已展开的权限点。
 *
 * @param userId         用户主键
 * @param username       登录名
 * @param nickname       显示名
 * @param roles          角色编码集合，如 ADMIN / REVIEWER / DEVELOPER
 * @param permissions    权限点集合，如 system:user:list
 * @param tokenId        当前访问令牌的 jti，登出时用于拉黑
 * @param tokenExpiresAt 当前访问令牌过期时刻，决定黑名单 TTL
 */
public record LoginUser(
        Long userId,
        String username,
        String nickname,
        Set<String> roles,
        Set<String> permissions,
        String tokenId,
        Instant tokenExpiresAt
) {

    public static final String ROLE_PREFIX = "ROLE_";

    public LoginUser {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /** 非请求上下文构造（如定时任务、初始化器）。 */
    public static LoginUser of(Long userId, String username, String nickname,
                               Set<String> roles, Set<String> permissions) {
        return new LoginUser(userId, username, nickname, roles, permissions, null, null);
    }

    /** 展开为 Spring Security 权限：角色加 ROLE_ 前缀，权限点原样。 */
    public Collection<GrantedAuthority> authorities() {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        roles.forEach(role -> authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + role)));
        permissions.forEach(perm -> authorities.add(new SimpleGrantedAuthority(perm)));
        return authorities;
    }

    /** 超级管理员判定：拥有 ADMIN 角色即视为拥有全部权限点。 */
    public boolean isAdmin() {
        return roles.contains("ADMIN");
    }

    // ---------------- 静态上下文访问 ----------------

    public static Optional<LoginUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof LoginUser loginUser) {
            return Optional.of(loginUser);
        }
        return Optional.empty();
    }

    public static Long currentUserIdOrNull() {
        return currentUser().map(LoginUser::userId).orElse(null);
    }

    public static String currentUsernameOrNull() {
        return currentUser().map(LoginUser::username).orElse(null);
    }
}

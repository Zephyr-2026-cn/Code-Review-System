package com.shenchao.codereview.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchao.codereview.auth.service.TokenStore;
import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.api.Result;
import com.shenchao.codereview.common.exception.BizException;
import com.shenchao.codereview.system.service.PermissionCacheService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * JWT 认证过滤器。
 *
 * <p>行为约定：
 * <ul>
 *   <li>请求头<b>没有</b> Authorization：直接放行，由 {@code AuthorizationFilter} 决定是否 401</li>
 *   <li>请求头<b>有</b>但无效/过期：立即返回 401 并带精确业务码，让前端能区分"该刷新"与"该重新登录"</li>
 *   <li>Redis 不可用：返回 503 而不是放行（fail-closed）</li>
 * </ul>
 *
 * <p>为什么不用 {@code SecurityContextHolder} 保存到 Session：本项目 stateless，
 * 每个请求都要重新解析 Token；JWT 校验是纯 CPU 运算，成本远低于一次 Session 查库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final TokenStore tokenStore;
    private final PermissionCacheService permissionCacheService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String token = resolveToken(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = tokenProvider.parse(token);

            // 登出后令牌仍在有效期内，靠黑名单拦截
            if (tokenStore.isBlacklisted(claims.getId())) {
                log.debug("令牌已登出（命中黑名单）: jti={}", claims.getId());
                writeError(response, ErrorCode.TOKEN_INVALID);
                return;
            }

            Long userId = parseUserId(claims);

            // 账号被禁用/改密/调角色后，服务端会推高该用户的"吊销水位线"，此前签发的令牌全部作废
            if (tokenStore.isSessionRevoked(userId, claims.getIssuedAt() == null
                    ? null : claims.getIssuedAt().toInstant())) {
                log.debug("令牌已被用户级吊销: userId={}, jti={}", userId, claims.getId());
                writeError(response, ErrorCode.SESSION_REVOKED);
                return;
            }

            Set<String> permissions = permissionCacheService.permissionsOf(userId);
            LoginUser loginUser = tokenProvider.toLoginUser(claims, permissions);

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            chain.doFilter(request, response);
        } catch (BizException ex) {
            SecurityContextHolder.clearContext();
            writeError(response, ex.getErrorCode());
        } catch (NumberFormatException ex) {
            SecurityContextHolder.clearContext();
            writeError(response, ErrorCode.TOKEN_INVALID);
        }
    }

    private static Long parseUserId(Claims claims) {
        String subject = claims.getSubject();
        if (!StringUtils.hasText(subject)) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        return Long.valueOf(subject);
    }

    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (!StringUtils.hasText(header) || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        if (response.isCommitted()) {
            log.warn("响应已提交，无法写回认证错误: {}", errorCode.name());
            return;
        }
        response.setStatus(errorCode.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> body = Result.fail(errorCode);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /** 显式暴露 traceId 供子类日志使用（保留钩子，便于后续接入 OpenTelemetry）。 */
    protected String currentTraceId() {
        return MDC.get("traceId");
    }
}

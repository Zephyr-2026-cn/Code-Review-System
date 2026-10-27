package com.shenchao.codereview.auth.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 刷新令牌 Cookie 读写。
 *
 * <p>为什么放 Cookie 而不是 localStorage：
 * HttpOnly Cookie 无法被 JS 读取，XSS 攻击拿不到刷新令牌；
 * 访问令牌仍走内存（Pinia + sessionStorage），两者分工降低整体风险。
 *
 * <p>为什么 {@code Path=/api/auth}：
 * 刷新令牌只在认证相关接口发送，普通业务接口不会携带，缩小暴露面。
 *
 * <p>为什么 {@code SameSite=Lax}：
 * 阻止跨站 POST 携带 Cookie（CSRF 防护），同时保留"从外部链接点进来"的正常体验；
 * 生产环境开启 HTTPS 后 {@code secure=true}，开发环境为 false 否则浏览器不会保存。
 */
@Slf4j
@Component
public class RefreshTokenCookie {

    public static final String COOKIE_NAME = "cr_refresh";
    private static final String COOKIE_PATH = "/api/auth";

    private final JwtProperties jwtProperties;
    private final boolean secure;

    public RefreshTokenCookie(JwtProperties jwtProperties,
                              @Value("${app.cookie-secure:false}") boolean secure) {
        this.jwtProperties = jwtProperties;
        this.secure = secure;
    }

    /** 构造下发用的 Cookie。 */
    public ResponseCookie create(String token) {
        return baseBuilder(token)
                .maxAge(jwtProperties.refreshTokenTtl())
                .build();
    }

    /** 构造清除用的 Cookie（maxAge=0）。 */
    public ResponseCookie clear() {
        return baseBuilder("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseBuilder(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .path(COOKIE_PATH)
                .sameSite("Lax");
    }

    /**
     * 从请求中读取刷新令牌。
     *
     * @return 不存在时返回空，交由上层抛业务异常，不在这里区分"没带"与"带错了"
     */
    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request == null ? null : request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
            }
        }
        return Optional.empty();
    }
}

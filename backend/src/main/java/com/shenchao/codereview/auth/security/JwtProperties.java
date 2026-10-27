package com.shenchao.codereview.auth.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * JWT 配置。
 *
 * <p>{@code secret} 必须是 Base64 编码且解码后不少于 32 字节（HS256 要求），
 * 启动时校验，不满足直接启动失败——避免带着弱密钥上线。
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @NotBlank String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration clockSkew
) {

    public JwtProperties {
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofHours(2);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(7);
        }
        if (clockSkew == null) {
            clockSkew = Duration.ofSeconds(30);
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "code-review";
        }
    }
}

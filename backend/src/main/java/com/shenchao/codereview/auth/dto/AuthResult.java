package com.shenchao.codereview.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 内部认证结果：Service 层返回，Controller 负责把 {@code refreshToken}
 * 写入 HttpOnly Cookie（不允许出现在 JSON 响应体里）。
 */
public record AuthResult(
        String accessToken,
        long expiresIn,
        String refreshToken,
        UserProfile user
) {

    public LoginResponse toResponse() {
        return LoginResponse.of(accessToken, expiresIn, user);
    }

    @Schema(hidden = true)
    public String refreshToken() {
        return refreshToken;
    }
}

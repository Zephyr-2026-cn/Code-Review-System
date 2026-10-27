package com.shenchao.codereview.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 登录 / 刷新令牌响应。
 *
 * <p>刷新令牌<b>不在响应体中</b>，而是通过 HttpOnly Cookie 下发，
 * 前端 JS 无法读取，可显著降低 XSS 场景下令牌被窃取的风险。
 *
 * @param accessToken 访问令牌
 * @param tokenType   固定 Bearer
 * @param expiresIn   访问令牌剩余有效秒数，前端据此提前静默刷新
 * @param user        用户档案
 */
@Schema(description = "登录响应")
public record LoginResponse(
        @Schema(description = "访问令牌") String accessToken,
        @Schema(description = "令牌类型", example = "Bearer") String tokenType,
        @Schema(description = "有效期(秒)") long expiresIn,
        @Schema(description = "用户档案") UserProfile user
) {

    public static LoginResponse of(String accessToken, long expiresIn, UserProfile user) {
        return new LoginResponse(accessToken, "Bearer", expiresIn, user);
    }
}

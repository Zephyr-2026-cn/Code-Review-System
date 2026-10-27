package com.shenchao.codereview.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一次性凭证明文回显。
 *
 * <p>只在"创建用户"和"重置密码"两个接口出现，且<b>不会写入审计日志</b>
 * （审计切面对含 password 关键字的字段自动脱敏为 {@code ***}）。
 * 管理员刷新页面后无法再次获取，避免长期留存明文口令。
 *
 * @param userId   用户 ID
 * @param username 登录名
 * @param password 明文密码，仅此一次
 */
@Schema(description = "一次性凭证")
public record InitialCredentialVO(
        @Schema(description = "用户 ID") Long userId,
        @Schema(description = "登录名") String username,
        @Schema(description = "初始密码，仅返回一次，请妥善转交") String password
) {
}

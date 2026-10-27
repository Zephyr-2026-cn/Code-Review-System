package com.shenchao.codereview.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改用户请求。
 *
 * <p>刻意<b>不包含 username 字段</b>：登录名是审计日志、问题指派、评论引用的锚点，
 * 允许改名会让历史记录失去可读性。如确需换名，正确做法是停用旧账号 + 新建账号。
 */
@Schema(description = "修改用户请求")
public record UserUpdateRequest(

        @Schema(description = "显示名", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过 64")
        String nickname,

        @Schema(description = "邮箱")
        @Email(message = "格式不正确")
        @Size(max = 128, message = "长度不能超过 128")
        String email,

        @Schema(description = "状态：ENABLED / DISABLED", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "不能为空")
        @Pattern(regexp = "^(ENABLED|DISABLED)$", message = "只能是 ENABLED 或 DISABLED")
        String status
) {

    public String normalizedNickname() {
        return nickname == null ? null : nickname.trim();
    }

    public String normalizedEmail() {
        String trimmed = email == null ? null : email.trim();
        return (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
    }
}

package com.shenchao.codereview.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "登录请求")
public record LoginRequest(

        @Schema(description = "登录名", example = "admin", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过 64")
        String username,

        @Schema(description = "密码", example = "Admin@123456", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过 64")
        String password
) {

    /** 规范化登录名：去空格 + 转小写，避免用户因大小写/空格反复登录失败。 */
    public String normalizedUsername() {
        return username == null ? null : username.trim().toLowerCase(java.util.Locale.ROOT);
    }
}

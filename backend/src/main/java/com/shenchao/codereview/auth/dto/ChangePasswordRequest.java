package com.shenchao.codereview.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "修改密码请求")
public record ChangePasswordRequest(

        @Schema(description = "原密码", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过 64")
        String oldPassword,

        @Schema(description = "新密码，8-64 位且含字母与数字", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "不能为空")
        @Size(min = 8, max = 64, message = "长度需在 8-64 位之间")
        String newPassword
) {
}

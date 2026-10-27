package com.shenchao.codereview.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 管理员重置密码请求。
 *
 * @param newPassword 新密码，留空则由服务端生成随机密码；无论哪种方式都会置位"强制改密"
 */
@Schema(description = "重置密码请求")
public record ResetPasswordRequest(

        @Schema(description = "新密码，留空表示由服务端随机生成")
        @Size(max = 64, message = "长度不能超过 64")
        String newPassword
) {
}

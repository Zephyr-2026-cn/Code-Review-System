package com.shenchao.codereview.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建 Git 凭据。
 *
 * <p>令牌只在此处录入一次并以密文落库，之后任何接口都不会回显。
 */
@Schema(description = "新建 Git 凭据请求")
public record CredentialCreateRequest(

        @Schema(description = "凭据名称，全局唯一", example = "github-pat-shenchao",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过 64")
        String name,

        @Schema(description = "Git 用户名；使用 PAT 时可填任意非空值", example = "git",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过 128")
        String username,

        @Schema(description = "访问令牌（Personal Access Token）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "不能为空")
        @Size(min = 8, max = 512, message = "长度需在 8-512 之间")
        String token
) {

    public String normalizedName() {
        return name == null ? null : name.trim();
    }

    public String normalizedUsername() {
        return username == null ? null : username.trim();
    }
}

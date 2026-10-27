package com.shenchao.codereview.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 绑定 / 更新仓库地址。
 *
 * <p>绑定成功会立即触发一次克隆（异步），接口返回时项目状态为 {@code CLONING}，
 * 前端轮询项目详情直到 {@code READY} 或 {@code FAILED}。
 */
@Schema(description = "绑定仓库请求")
public record RepoBindRequest(

        @Schema(description = "Git 仓库地址（https）", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "https://github.com/Zephyr-2026-cn/Code-Review-System.git")
        @NotBlank(message = "不能为空")
        @Size(max = 512, message = "长度不能超过 512")
        String repoUrl,

        @Schema(description = "分支，留空使用远端默认分支", example = "main")
        @Size(max = 128, message = "长度不能超过 128")
        String repoBranch,

        @Schema(description = "Git 凭据 ID，公开仓库留空")
        Long credentialId
) {

    public String normalizedRepoBranch() {
        String trimmed = repoBranch == null ? null : repoBranch.trim();
        return (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
    }
}

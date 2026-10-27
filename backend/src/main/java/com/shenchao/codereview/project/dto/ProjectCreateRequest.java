package com.shenchao.codereview.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建项目请求。
 *
 * <p>仓库地址可以留空先建项目、之后再绑定；这样"先登记项目，再配仓库"和
 * "一次性建好"两种交互都能支持。
 *
 * @param code 项目编码，留空则由仓库地址自动推导（也可手动指定，用于中文项目名场景）
 */
@Schema(description = "创建项目请求")
public record ProjectCreateRequest(

        @Schema(description = "项目名称", example = "智能代码审查系统", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过 128")
        String name,

        @Schema(description = "项目编码，小写字母/数字/下划线/连字符，留空自动推导", example = "ai-codereview")
        @Pattern(regexp = "^$|^[a-z0-9][a-z0-9_-]{1,47}$",
                message = "需 2-48 位小写字母/数字/下划线/连字符，且以字母或数字开头")
        String code,

        @Schema(description = "项目描述")
        @Size(max = 500, message = "长度不能超过 500")
        String description,

        @Schema(description = "主语言", example = "JAVA")
        @Pattern(regexp = "^$|^[A-Z]{2,16}$", message = "需为大写语言标识，如 JAVA")
        String language,

        @Schema(description = "Git 仓库地址（https）", example = "https://github.com/Zephyr-2026-cn/Code-Review-System.git")
        @Size(max = 512, message = "长度不能超过 512")
        String repoUrl,

        @Schema(description = "分支，留空使用远端默认分支", example = "main")
        @Size(max = 128, message = "长度不能超过 128")
        String repoBranch,

        @Schema(description = "Git 凭据 ID，公开仓库留空")
        Long credentialId
) {

    public String normalizedName() {
        return name == null ? null : name.trim();
    }

    public String normalizedCode() {
        String trimmed = code == null ? null : code.trim().toLowerCase(java.util.Locale.ROOT);
        return (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
    }

    public String normalizedDescription() {
        String trimmed = description == null ? null : description.trim();
        return (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
    }

    public String normalizedLanguage() {
        String trimmed = language == null ? null : language.trim().toUpperCase(java.util.Locale.ROOT);
        return (trimmed == null || trimmed.isEmpty()) ? "JAVA" : trimmed;
    }

    public String normalizedRepoUrl() {
        String trimmed = repoUrl == null ? null : repoUrl.trim();
        return (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
    }

    public String normalizedRepoBranch() {
        String trimmed = repoBranch == null ? null : repoBranch.trim();
        return (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
    }
}

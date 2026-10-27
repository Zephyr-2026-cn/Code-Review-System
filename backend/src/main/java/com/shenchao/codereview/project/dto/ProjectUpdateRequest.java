package com.shenchao.codereview.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改项目请求。
 *
 * <p>不含 {@code code} 与 {@code repoUrl}：编码绑定本地目录，仓库地址涉及唯一约束，
 * 二者都通过专用接口（编码不可变、地址走 {@code POST /projects/{id}/repos}）修改。
 */
@Schema(description = "修改项目请求")
public record ProjectUpdateRequest(

        @Schema(description = "项目名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过 128")
        String name,

        @Schema(description = "项目描述")
        @Size(max = 500, message = "长度不能超过 500")
        String description,

        @Schema(description = "主语言", example = "JAVA")
        @Pattern(regexp = "^$|^[A-Z]{2,16}$", message = "需为大写语言标识，如 JAVA")
        String language
) {

    public String normalizedName() {
        return name == null ? null : name.trim();
    }

    public String normalizedDescription() {
        String trimmed = description == null ? null : description.trim();
        return (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
    }

    public String normalizedLanguage() {
        String trimmed = language == null ? null : language.trim().toUpperCase(java.util.Locale.ROOT);
        return (trimmed == null || trimmed.isEmpty()) ? "JAVA" : trimmed;
    }
}

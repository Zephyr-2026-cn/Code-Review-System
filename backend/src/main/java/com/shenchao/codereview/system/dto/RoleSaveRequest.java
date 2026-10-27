package com.shenchao.codereview.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 修改角色请求。
 *
 * <p>编码只允许大写字母、数字与下划线，且以字母开头：编码会被写进 JWT 的
 * {@code roles} 声明并与前端 {@code v-if="hasRole('ADMIN')"} 对比，
 * 放开大小写会造成"同一个角色两种写法"的隐性 bug。
 */
@Schema(description = "角色保存请求")
public record RoleSaveRequest(

        @Schema(description = "角色编码，2-32 位大写字母/数字/下划线，字母开头", example = "AUDITOR",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "不能为空")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$", message = "需 2-32 位大写字母/数字/下划线，且以字母开头")
        String code,

        @Schema(description = "角色名称", example = "质量审计员", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过 64")
        String name,

        @Schema(description = "描述")
        @Size(max = 255, message = "长度不能超过 255")
        String description
) {

    public String normalizedCode() {
        return code == null ? null : code.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public String normalizedName() {
        return name == null ? null : name.trim();
    }

    public String normalizedDescription() {
        String trimmed = description == null ? null : description.trim();
        return (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
    }
}

package com.shenchao.codereview.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 管理员创建用户请求。
 *
 * <p>本项目<b>不开放自助注册</b>：账号只能由拥有 {@code system:user:create} 的管理员创建，
 * 避免"任何人注册即可进入代码审查系统"带来的信息暴露面。
 *
 * @param username 登录名，创建后不可修改（审计追溯依赖于登录名稳定）
 * @param password 初始密码，可为空；为空时由服务端生成 12 位随机密码并在响应中返回一次
 * @param roleIds  初始角色，可为空（空表示"无角色"，登录后几乎看不到任何菜单）
 */
@Schema(description = "创建用户请求")
public record UserCreateRequest(

        @Schema(description = "登录名，3-32 位，字母开头，仅允许字母数字与 _ . -", example = "zhangsan",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "不能为空")
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{2,31}$",
                message = "需 3-32 位，字母开头，仅允许字母、数字、下划线、点、连字符")
        String username,

        @Schema(description = "初始密码；留空则自动生成", example = "")
        @Size(max = 64, message = "长度不能超过 64")
        String password,

        @Schema(description = "显示名", example = "张三", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过 64")
        String nickname,

        @Schema(description = "邮箱", example = "zhangsan@example.com")
        @Email(message = "格式不正确")
        @Size(max = 128, message = "长度不能超过 128")
        String email,

        @Schema(description = "角色 ID 列表")
        List<Long> roleIds
) {

    /** 规范化登录名：与登录链路保持一致（去空格 + 转小写），否则会出现"注册用大写、登录失败"的坑。 */
    public String normalizedUsername() {
        return username == null ? null : username.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public String normalizedNickname() {
        return nickname == null ? null : nickname.trim();
    }

    public String normalizedEmail() {
        String trimmed = email == null ? null : email.trim();
        return (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
    }
}

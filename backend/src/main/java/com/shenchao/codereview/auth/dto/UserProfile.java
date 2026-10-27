package com.shenchao.codereview.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Set;

/**
 * 当前用户档案（前端据此渲染菜单与按钮权限）。
 *
 * <p>注意：<b>不包含密码摘要</b>。任何返回用户的接口都必须经过本类型转换，
 * 这是防止"实体直出"泄漏敏感字段的最后一道闸。
 */
@Schema(description = "用户档案")
public record UserProfile(
        @Schema(description = "用户 ID") Long id,
        @Schema(description = "登录名") String username,
        @Schema(description = "显示名") String nickname,
        @Schema(description = "邮箱") String email,
        @Schema(description = "角色编码") List<String> roles,
        @Schema(description = "权限点") Set<String> permissions,
        @Schema(description = "是否必须先修改密码（管理员创建账号 / 重置密码后为 true）")
        boolean passwordResetRequired
) {

    /** 兼容既有调用点：默认不强制改密。 */
    public UserProfile(Long id, String username, String nickname, String email,
                       List<String> roles, Set<String> permissions) {
        this(id, username, nickname, email, roles, permissions, false);
    }
}

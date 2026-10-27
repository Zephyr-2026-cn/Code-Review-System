package com.shenchao.codereview.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 用户列表/详情视图。
 *
 * <p><b>为什么不直接用 {@code SysUser} 返回</b>：实体内含 BCrypt 摘要字段，
 * 一旦 Entity 直出，任何一个新加的敏感列都会自动泄漏到接口。
 * VO 是一道"白名单"闸门，字段必须显式声明才会出现在响应里。
 */
@Schema(description = "用户信息")
public record UserVO(
        @Schema(description = "用户 ID") Long id,
        @Schema(description = "登录名") String username,
        @Schema(description = "显示名") String nickname,
        @Schema(description = "邮箱") String email,
        @Schema(description = "状态：ENABLED / DISABLED") String status,
        @Schema(description = "所属角色（简要信息）") List<RoleBrief> roles,
        @Schema(description = "最近登录时间") OffsetDateTime lastLoginAt,
        @Schema(description = "创建时间") OffsetDateTime createdAt,
        @Schema(description = "是否处于待改密状态") boolean passwordResetRequired
) {

    /** 角色简要信息：用户列表里只需展示编码与名称，不必带权限集合。 */
    @Schema(description = "角色简要信息")
    public record RoleBrief(
            @Schema(description = "角色 ID") Long id,
            @Schema(description = "角色编码") String code,
            @Schema(description = "角色名称") String name
    ) {
    }
}

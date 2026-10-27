package com.shenchao.codereview.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 角色视图。
 *
 * @param builtin 内置角色，不可删除、不可改编码、不可调整权限（保证系统始终存在可用的管理员）
 * @param userCount 引用该角色的有效用户数，前端据此决定"删除"按钮是否禁用
 */
@Schema(description = "角色信息")
public record RoleVO(
        @Schema(description = "角色 ID") Long id,
        @Schema(description = "角色编码") String code,
        @Schema(description = "角色名称") String name,
        @Schema(description = "描述") String description,
        @Schema(description = "是否内置角色") boolean builtin,
        @Schema(description = "引用用户数") long userCount,
        @Schema(description = "权限点编码列表") List<String> permissions,
        @Schema(description = "创建时间") OffsetDateTime createdAt
) {

    /** 列表场景：不携带权限点，减少响应体积。 */
    public static RoleVO summary(Long id, String code, String name, String description,
                                 boolean builtin, long userCount, OffsetDateTime createdAt) {
        return new RoleVO(id, code, name, description, builtin, userCount, List.of(), createdAt);
    }
}

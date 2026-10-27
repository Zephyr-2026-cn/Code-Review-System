package com.shenchao.codereview.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 权限点树节点。
 *
 * <p>菜单（MENU）与接口/按钮（API/BUTTON）共处一棵树：
 * 前端分配权限时按树勾选，勾中菜单不等于自动获得其下接口权限，
 * 因此树里保留扁平结构，由前端自行决定是否做父子联动。
 *
 * @param type   MENU / API / BUTTON
 * @param path   菜单路由，仅 MENU 有值
 * @param children 子节点，叶子节点为空列表（不用 null，前端可直接 v-for）
 */
@Schema(description = "权限点")
public record PermissionVO(
        @Schema(description = "权限 ID") Long id,
        @Schema(description = "权限编码，如 task:create") String code,
        @Schema(description = "权限名称") String name,
        @Schema(description = "类型：MENU / API / BUTTON") String type,
        @Schema(description = "父权限 ID") Long parentId,
        @Schema(description = "菜单路由") String path,
        @Schema(description = "菜单图标") String icon,
        @Schema(description = "排序值") Integer sortOrder,
        @Schema(description = "子节点") List<PermissionVO> children
) {
}

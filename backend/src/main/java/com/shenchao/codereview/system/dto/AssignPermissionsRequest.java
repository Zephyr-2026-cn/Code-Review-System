package com.shenchao.codereview.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 分配角色权限（覆盖式）。
 *
 * @param permissionIds 目标权限点 ID 列表；空数组表示收回该角色全部权限
 */
@Schema(description = "分配角色权限请求")
public record AssignPermissionsRequest(

        @Schema(description = "权限点 ID 列表，空数组表示清空", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "不能为 null，清空权限请传空数组")
        List<Long> permissionIds
) {
}

package com.shenchao.codereview.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 重新分配用户角色（覆盖式，非增量）。
 *
 * <p>选覆盖而非增量，是为了让"勾选-保存"这种 UI 交互存在确定的状态终值，
 * 避免并发下出现"两个管理员各自追加一半角色"的脏数据。
 *
 * @param roleIds 目标角色 ID 列表；传空数组表示清空该用户的全部角色
 */
@Schema(description = "分配用户角色请求")
public record AssignRolesRequest(

        @Schema(description = "角色 ID 列表，空数组表示清空", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "不能为 null，清空角色请传空数组")
        List<Long> roleIds
) {
}

package com.shenchao.codereview.system.dto;

import com.shenchao.codereview.common.api.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户查询条件。
 *
 * <p>排序字段交由服务端白名单决定，<b>不接受前端任意列名</b>——
 * 否则 {@code sortBy=password} 之类的输入会变成信息探测通道。
 */
@Schema(description = "用户查询条件")
public class UserQuery extends PageQuery {

    @Schema(description = "关键字：登录名/显示名/邮箱模糊匹配")
    @Size(max = 64, message = "长度不能超过 64")
    private String keyword;

    @Schema(description = "状态：ENABLED / DISABLED")
    @Pattern(regexp = "^(ENABLED|DISABLED)$", message = "只能是 ENABLED 或 DISABLED")
    private String status;

    @Schema(description = "按角色过滤")
    private Long roleId;

    public String getKeyword() {
        return keyword == null ? null : keyword.trim();
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }
}

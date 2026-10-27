package com.shenchao.codereview.system.mapper;

import lombok.Getter;
import lombok.Setter;

/**
 * 用户-角色扁平行，用于"一次查询批量回填用户角色"，避免列表页 N+1。
 */
@Getter
@Setter
public class UserRoleRow {

    private Long userId;

    private Long roleId;

    private String code;

    private String name;
}

package com.shenchao.codereview.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shenchao.codereview.system.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /**
     * 查询用户实际拥有的权限点编码（角色未删除且权限点自身存在）。
     *
     * <p>DISTINCT 是必须的：一个用户可有多个角色，两个角色可能共享同一权限点。
     */
    @Select("""
            SELECT DISTINCT p.code
            FROM sys_permission p
                     JOIN sys_role_permission rp ON rp.permission_id = p.id
                     JOIN sys_user_role ur ON ur.role_id = rp.role_id
                     JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 0
            WHERE ur.user_id = #{userId}
            ORDER BY p.code
            """)
    List<String> selectCodesByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT DISTINCT p.code
            FROM sys_permission p
                     JOIN sys_role_permission rp ON rp.permission_id = p.id
            WHERE rp.role_id = #{roleId}
            ORDER BY p.code
            """)
    List<String> selectCodesByRoleId(@Param("roleId") Long roleId);
}

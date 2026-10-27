package com.shenchao.codereview.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shenchao.codereview.system.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /** 查询用户拥有的角色编码（仅未删除角色）。 */
    @Select("""
            SELECT r.code
            FROM sys_role r
                     JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
              AND r.deleted = 0
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    /** 统计角色下的有效用户数，用于"仍被引用则禁止删除"的约束判断。 */
    @Select("""
            SELECT count(DISTINCT ur.user_id)
            FROM sys_user_role ur
                     JOIN sys_user u ON u.id = ur.user_id AND u.deleted = 0
            WHERE ur.role_id = #{roleId}
            """)
    long countUsersByRoleId(@Param("roleId") Long roleId);
}

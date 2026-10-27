package com.shenchao.codereview.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shenchao.codereview.system.entity.SysUserRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户-角色关联。
 *
 * <p>该表<b>没有逻辑删除列</b>：关联关系本身就是"存在即生效"，
 * 保留已删除标记会让"重新授权"需要额外判重，收益远小于复杂度。
 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);

    @Delete("DELETE FROM sys_user_role WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);
}

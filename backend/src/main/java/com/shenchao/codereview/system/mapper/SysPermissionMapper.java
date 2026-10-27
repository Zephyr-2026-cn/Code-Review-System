package com.shenchao.codereview.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shenchao.codereview.system.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
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

    /** 全部权限点，按"父级在前、同级按 sortOrder"排序，供构树使用。 */
    @Select("""
            SELECT *
            FROM sys_permission
            ORDER BY COALESCE(parent_id, 0), sort_order, id
            """)
    List<SysPermission> selectAllOrdered();

    /**
     * 从给定 ID 集合中筛出真实存在的权限点 ID。
     *
     * <p>分配权限前必须做这个校验：前端传来的 ID 可能已被清理，
     * 直接批量 insert 会撞外键约束并抛出 500，属于"本可避免的服务端错误"。
     */
    @Select("""
            <script>
            SELECT id
            FROM sys_permission
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> selectExistingIds(@Param("ids") Collection<Long> ids);

    /** 统计权限点数量，用于启动自检与前端树构建的完整性校验。 */
    @Select("SELECT count(1) FROM sys_permission")
    long countAll();

    /** 按 ID 集合查询权限点编码，用于提权校验（授予的权限必须是操作者已有权限的子集）。 */
    @Select("""
            <script>
            SELECT code
            FROM sys_permission
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<String> selectCodesByIds(@Param("ids") Collection<Long> ids);
}

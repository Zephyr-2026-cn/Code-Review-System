package com.shenchao.codereview.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shenchao.codereview.system.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
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

    /**
     * 批量查询多个用户的角色（列表页专用，避免逐行查询）。
     *
     * <p>调用方必须保证 {@code userIds} 非空：空集合会生成非法 SQL {@code IN ()}。
     */
    @Select("""
            <script>
            SELECT ur.user_id AS userId, r.id AS roleId, r.code AS code, r.name AS name
            FROM sys_user_role ur
                     JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 0
            WHERE ur.user_id IN
            <foreach collection="userIds" item="uid" open="(" separator="," close=")">#{uid}</foreach>
            ORDER BY ur.user_id, r.id
            </script>
            """)
    List<UserRoleRow> selectRolesByUserIds(@Param("userIds") Collection<Long> userIds);

    /** 查询用户当前的角色 ID 列表（用于覆盖式分配的差异对比）。 */
    @Select("""
            SELECT ur.role_id
            FROM sys_user_role ur
                     JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 0
            WHERE ur.user_id = #{userId}
            """)
    List<Long> selectRoleIdsByUserId(@Param("userId") Long userId);

    /**
     * 统计"除指定用户外"仍处于启用状态的管理员数量。
     *
     * <p>最后管理员保护的核心查询：任何会让该值归零的操作（停用/删除/摘掉 ADMIN 角色）
     * 都必须被拒绝，否则系统会进入"无人可管理"的死锁状态，只能改库恢复。
     *
     * @param excludeUserId 需要排除的用户，传 null 表示不排除任何人
     */
    @Select("""
            <script>
            SELECT count(DISTINCT u.id)
            FROM sys_user u
                     JOIN sys_user_role ur ON ur.user_id = u.id
                     JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 0 AND r.code = 'ADMIN'
            WHERE u.deleted = 0
              AND u.status = 'ENABLED'
            <if test="excludeUserId != null">AND u.id &lt;&gt; #{excludeUserId}</if>
            </script>
            """)
    long countOtherEnabledAdmins(@Param("excludeUserId") Long excludeUserId);
}

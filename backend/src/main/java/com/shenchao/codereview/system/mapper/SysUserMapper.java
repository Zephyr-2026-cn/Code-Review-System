package com.shenchao.codereview.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shenchao.codereview.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按登录名查询（含密码摘要，仅登录链路使用）。
     *
     * <p>注意：自定义 SQL 不经过 MyBatis-Plus 的逻辑删除拦截器，必须手写 {@code deleted = 0}。
     */
    @Select("SELECT * FROM sys_user WHERE username = #{username} AND deleted = 0 LIMIT 1")
    SysUser selectByUsername(@Param("username") String username);

    @Select("SELECT count(1) FROM sys_user WHERE username = #{username} AND deleted = 0")
    long countByUsername(@Param("username") String username);
}

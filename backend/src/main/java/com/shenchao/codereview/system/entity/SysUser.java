package com.shenchao.codereview.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 系统用户。
 *
 * <p>安全约束：{@code password} 列只存 BCrypt 摘要；实体一旦离开 Service 层必须转 VO，
 * 严禁把该对象直接序列化给前端（会泄漏密码摘要）。
 */
@Getter
@Setter
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名，逻辑删除下仍要求全局唯一（见 V1 迁移里的部分唯一索引）。 */
    private String username;

    /** BCrypt 摘要，长度固定 60。 */
    private String password;

    private String nickname;

    private String email;

    /** ENABLED / DISABLED。 */
    private String status;

    private OffsetDateTime lastLoginAt;

    /** 为 true 时用户登录后必须先改密码（管理员创建账号或重置密码后置位）。 */
    private Boolean passwordResetRequired;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    public boolean isEnabled() {
        return STATUS_ENABLED.equals(status);
    }

    /** 是否需要强制改密。null 视为否（兼容升级前的历史数据）。 */
    public boolean mustResetPassword() {
        return Boolean.TRUE.equals(passwordResetRequired);
    }
}

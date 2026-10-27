package com.shenchao.codereview.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 权限点（菜单 / 接口 / 按钮三级统一建模）。
 *
 * <p>编码规范 {@code 域:资源:动作}，例如 {@code task:create}、{@code system:user:list}。
 * 前端菜单树由 {@code type=MENU} 的节点派生，后端鉴权只认 {@code code}。
 */
@Getter
@Setter
@TableName("sys_permission")
public class SysPermission {

    public static final String TYPE_MENU = "MENU";
    public static final String TYPE_API = "API";
    public static final String TYPE_BUTTON = "BUTTON";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    private String type;

    private Long parentId;

    /** 前端路由路径，仅 MENU 有值。 */
    private String path;

    private String icon;

    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}

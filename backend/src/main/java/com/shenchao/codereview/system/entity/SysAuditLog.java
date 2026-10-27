package com.shenchao.codereview.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 审计日志（只增不改，禁止提供删除接口）。
 *
 * <p>{@code detail} 为 JSONB 文本，写入时通过 JDBC 参数 {@code stringtype=unspecified}
 * 直接落库，避免 jsonb 类型转换异常。
 */
@Getter
@Setter
@TableName("sys_audit_log")
public class SysAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String username;

    /** 动作，例如 LOGIN / LOGOUT / USER_CREATE / GATE_OVERRIDE。 */
    private String action;

    /** 资源类型，例如 USER / TASK / ISSUE。 */
    private String resource;

    private String resourceId;

    /** JSON 文本，禁止写入密码、令牌等敏感字段。 */
    private String detail;

    private String ip;

    private String userAgent;

    private Boolean success;

    private String errorMsg;

    private Integer durationMs;

    private OffsetDateTime createdAt;
}

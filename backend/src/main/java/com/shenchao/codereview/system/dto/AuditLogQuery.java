package com.shenchao.codereview.system.dto;

import com.shenchao.codereview.common.api.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;

/**
 * 审计日志查询条件。
 *
 * <p>时间区间用 {@code OffsetDateTime} 接收：审计日志是跨时区排查问题的核心依据，
 * 用 {@code LocalDateTime} 会把部署机时区隐式带进来。
 */
@Schema(description = "审计日志查询条件")
public class AuditLogQuery extends PageQuery {

    @Schema(description = "操作人登录名（精确匹配）")
    @Size(max = 64, message = "长度不能超过 64")
    private String username;

    @Schema(description = "动作码，如 LOGIN / USER_CREATE（精确匹配）")
    @Size(max = 64, message = "长度不能超过 64")
    private String action;

    @Schema(description = "资源类型，如 USER / TASK")
    @Size(max = 64, message = "长度不能超过 64")
    private String resource;

    @Schema(description = "是否成功")
    private Boolean success;

    @Schema(description = "起始时间（含）")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime from;

    @Schema(description = "结束时间（含）")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime to;

    public String getUsername() {
        return username == null ? null : username.trim();
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAction() {
        return action == null ? null : action.trim();
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getResource() {
        return resource == null ? null : resource.trim();
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public OffsetDateTime getFrom() {
        return from;
    }

    public void setFrom(OffsetDateTime from) {
        this.from = from;
    }

    public OffsetDateTime getTo() {
        return to;
    }

    public void setTo(OffsetDateTime to) {
        this.to = to;
    }
}

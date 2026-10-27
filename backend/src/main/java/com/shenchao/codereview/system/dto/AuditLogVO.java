package com.shenchao.codereview.system.dto;

import com.shenchao.codereview.system.entity.SysAuditLog;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 审计日志视图。
 *
 * <p>{@code detail} 以原始 JSON 字符串下发，由前端按需格式化：
 * 后端若解析成 {@code Map} 再序列化，会丢失字段顺序，也不利于表格直接展示。
 */
@Schema(description = "审计日志")
public record AuditLogVO(
        @Schema(description = "日志 ID") Long id,
        @Schema(description = "操作人 ID") Long userId,
        @Schema(description = "操作人登录名") String username,
        @Schema(description = "动作") String action,
        @Schema(description = "资源类型") String resource,
        @Schema(description = "资源 ID") String resourceId,
        @Schema(description = "详情 JSON") String detail,
        @Schema(description = "客户端 IP") String ip,
        @Schema(description = "User-Agent") String userAgent,
        @Schema(description = "是否成功") boolean success,
        @Schema(description = "失败原因") String errorMsg,
        @Schema(description = "耗时(ms)") Integer durationMs,
        @Schema(description = "发生时间") OffsetDateTime createdAt
) {

    public static AuditLogVO from(SysAuditLog entity) {
        return new AuditLogVO(
                entity.getId(),
                entity.getUserId(),
                entity.getUsername(),
                entity.getAction(),
                entity.getResource(),
                entity.getResourceId(),
                entity.getDetail(),
                entity.getIp(),
                entity.getUserAgent(),
                Boolean.TRUE.equals(entity.getSuccess()),
                entity.getErrorMsg(),
                entity.getDurationMs(),
                entity.getCreatedAt());
    }
}

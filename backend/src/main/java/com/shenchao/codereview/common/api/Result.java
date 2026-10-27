package com.shenchao.codereview.common.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.MDC;

import java.time.Instant;

/**
 * 统一响应体。所有 Controller 一律返回该结构，不允许直接返回实体或 Map。
 *
 * @param code      业务码，0 表示成功，非 0 见 {@link ErrorCode}
 * @param message   提示文案（可直接展示给用户）
 * @param data      业务数据，失败时为 null
 * @param traceId   链路追踪号，用于给运维/答辩现场定位日志
 * @param timestamp 服务端时间戳（毫秒）
 */
@Schema(description = "统一响应体")
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Result<T>(
        @Schema(description = "业务码，0 为成功") int code,
        @Schema(description = "提示信息") String message,
        @Schema(description = "业务数据") T data,
        @Schema(description = "链路追踪号") String traceId,
        @Schema(description = "服务端时间戳(ms)") long timestamp
) {

    public static final String TRACE_ID_KEY = "traceId";

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.code(), ErrorCode.SUCCESS.message(), data, currentTraceId(),
                System.currentTimeMillis());
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.message());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.code(), message, null, currentTraceId(), System.currentTimeMillis());
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null, currentTraceId(), System.currentTimeMillis());
    }

    /**
     * 便捷判断，<b>不参与序列化</b>。
     *
     * <p>{@code @JsonIgnore} 不可省略：Jackson 会把 {@code isSuccess()} 当成
     * JavaBean 属性输出成 {@code "success": false}，污染对前端的契约。
     */
    @JsonIgnore
    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.code();
    }

    private static String currentTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }
}

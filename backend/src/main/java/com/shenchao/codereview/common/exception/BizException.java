package com.shenchao.codereview.common.exception;

import com.shenchao.codereview.common.api.ErrorCode;
import lombok.Getter;

import java.io.Serial;

/**
 * 业务异常：表示"可预期"的失败，由全局异常处理器翻译成统一响应。
 *
 * <p>使用约束：只在业务规则不满足时抛出；技术故障（数据库断连、第三方超时）请包装为
 * {@link ErrorCode#DEPENDENCY_UNAVAILABLE} 之类的语义错误再抛出，不要直接把 SQLException 冒泡到接口层。
 */
@Getter
public class BizException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /** 需要返回更精确信息时使用（例如"用户名 admin 已被占用"），不拼接内部细节。 */
    private final transient Object[] args;

    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
        this.args = null;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.args = null;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.args = null;
    }

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }

    public static BizException of(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message);
    }

    /** 断言为真，否则抛业务异常。 */
    public static void assertTrue(boolean expression, ErrorCode errorCode) {
        if (!expression) {
            throw new BizException(errorCode);
        }
    }

    public static void assertTrue(boolean expression, ErrorCode errorCode, String message) {
        if (!expression) {
            throw new BizException(errorCode, message);
        }
    }

    public static <T> T requireNonNull(T value, ErrorCode errorCode) {
        if (value == null) {
            throw new BizException(errorCode);
        }
        return value;
    }
}

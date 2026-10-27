package com.shenchao.codereview.common.exception;

import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：把各类异常收敛成统一响应，保证前端只需处理一种结构。
 *
 * <p>三条纪律：
 * <ol>
 *   <li>任何分支都不把堆栈、SQL、类名返回给调用方</li>
 *   <li>可预期异常（BizException / 校验失败）打 WARN，不可预期异常打 ERROR 并带 traceId</li>
 *   <li>HTTP 状态码与业务码同时表达语义，前端可按 HTTP 状态做统一兜底</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 单条错误提示最大长度，避免校验失败时响应体膨胀。 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    // ---------------- 业务异常 ----------------

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        log.warn("业务异常 [{}] {} {} -> {}: {}", code.code(), request.getMethod(), request.getRequestURI(),
                code.name(), ex.getMessage());
        return ResponseEntity.status(code.httpStatus()).body(Result.fail(code, ex.getMessage()));
    }

    // ---------------- 参数校验 ----------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .distinct()
                .collect(Collectors.joining("; "));
        return badRequest(detail);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .distinct()
                .collect(Collectors.joining("; "));
        return badRequest(detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .distinct()
                .collect(Collectors.joining("; "));
        return badRequest(detail);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return badRequest("缺少必填参数: " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("参数 " + ex.getName() + " 格式不正确");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return badRequest("请求体格式错误，请检查 JSON 结构与字段类型");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUpload(MaxUploadSizeExceededException ex) {
        log.warn("上传超限: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.PAYLOAD_TOO_LARGE.httpStatus())
                .body(Result.fail(ErrorCode.PAYLOAD_TOO_LARGE, "上传文件超过大小上限"));
    }

    // ---------------- 协议层 ----------------

    @ExceptionHandler({HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Result<Void>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(ErrorCode.UNSUPPORTED_MEDIA_TYPE.httpStatus())
                .body(Result.fail(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(ErrorCode.REQUEST_METHOD_NOT_SUPPORTED.httpStatus())
                .body(Result.fail(ErrorCode.REQUEST_METHOD_NOT_SUPPORTED));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> handleNoHandler(NoHandlerFoundException ex) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.httpStatus()).body(Result.fail(ErrorCode.NOT_FOUND));
    }

    // ---------------- 安全 ----------------

    /**
     * 方法级鉴权（{@code @PreAuthorize}）抛出的拒绝异常。
     * 注意：过滤器链层面抛出的同类异常由 {@code RestAuthenticationEntryPoint} 处理，不走这里。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("权限拒绝: {} {} - {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(ErrorCode.PERMISSION_DENIED.httpStatus())
                .body(Result.fail(ErrorCode.PERMISSION_DENIED));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuthentication(AuthenticationException ex) {
        log.warn("认证失败: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.UNAUTHORIZED.httpStatus())
                .body(Result.fail(ErrorCode.UNAUTHORIZED));
    }

    // ---------------- 数据层 ----------------

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException ex) {
        log.warn("唯一约束冲突: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(ErrorCode.CONFLICT.httpStatus())
                .body(Result.fail(ErrorCode.CONFLICT, "数据已存在，请勿重复提交"));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Result<Void>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("并发更新冲突: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.CONFLICT.httpStatus())
                .body(Result.fail(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.error("数据完整性异常", ex);
        return ResponseEntity.status(ErrorCode.DATA_INTEGRITY_VIOLATION.httpStatus())
                .body(Result.fail(ErrorCode.DATA_INTEGRITY_VIOLATION));
    }

    // ---------------- 兜底 ----------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未处理异常 {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(Result.fail(ErrorCode.INTERNAL_ERROR));
    }

    // ---------------- 工具方法 ----------------

    private static String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + " " + fieldError.getDefaultMessage();
    }

    private static ResponseEntity<Result<Void>> badRequest(String detail) {
        String message = (detail == null || detail.isBlank())
                ? ErrorCode.PARAM_INVALID.message()
                : truncate(detail);
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.httpStatus())
                .body(Result.fail(ErrorCode.PARAM_INVALID, message));
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR_MESSAGE_LENGTH ? text : text.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "...";
    }
}

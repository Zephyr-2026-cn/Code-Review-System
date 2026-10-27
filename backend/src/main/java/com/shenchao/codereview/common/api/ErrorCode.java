package com.shenchao.codereview.common.api;

import org.springframework.http.HttpStatus;

/**
 * 全局错误码。
 *
 * <p>编码规范（便于前端按段位做差异化处理）：
 * <ul>
 *   <li>0            成功</li>
 *   <li>40xxx        客户端错误（参数、状态）</li>
 *   <li>401xx        未认证 / 凭证失效</li>
 *   <li>403xx        已认证但无权限</li>
 *   <li>404xx        资源不存在</li>
 *   <li>409xx        资源冲突 / 并发</li>
 *   <li>429xx        限流 / 锁定</li>
 *   <li>50xxx        服务端错误</li>
 *   <li>60xxx        领域错误（审查引擎、门禁、知识库）</li>
 * </ul>
 *
 * <p>注意：{@code message} 是面向终端用户的兜底文案，禁止拼接堆栈或 SQL 等内部信息。
 */
public enum ErrorCode {

    SUCCESS(0, "成功", HttpStatus.OK),

    // ---------- 通用客户端错误 ----------
    BAD_REQUEST(40000, "请求参数不合法", HttpStatus.BAD_REQUEST),
    PARAM_INVALID(40001, "参数校验失败", HttpStatus.BAD_REQUEST),
    PAYLOAD_TOO_LARGE(40002, "请求体过大", HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE(40003, "不支持的请求格式", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    REQUEST_METHOD_NOT_SUPPORTED(40004, "请求方法不支持", HttpStatus.METHOD_NOT_ALLOWED),

    // ---------- 认证 ----------
    UNAUTHORIZED(40100, "未登录或登录已失效", HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID(40101, "凭证无效，请重新登录", HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED(40102, "凭证已过期，请重新登录", HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_INVALID(40103, "刷新凭证无效或已使用", HttpStatus.UNAUTHORIZED),
    BAD_CREDENTIALS(40104, "用户名或密码错误", HttpStatus.UNAUTHORIZED),
    ACCOUNT_DISABLED(40105, "账号已被禁用，请联系管理员", HttpStatus.UNAUTHORIZED),
    ACCOUNT_LOCKED(40106, "登录失败次数过多，账号已临时锁定", HttpStatus.LOCKED),

    // ---------- 授权 ----------
    FORBIDDEN(40300, "无权访问该资源", HttpStatus.FORBIDDEN),
    PERMISSION_DENIED(40301, "缺少操作权限", HttpStatus.FORBIDDEN),
    ROLE_DENIED(40302, "当前角色不允许该操作", HttpStatus.FORBIDDEN),

    // ---------- 资源 ----------
    NOT_FOUND(40400, "资源不存在", HttpStatus.NOT_FOUND),
    USER_NOT_FOUND(40401, "用户不存在", HttpStatus.NOT_FOUND),
    PROJECT_NOT_FOUND(40402, "项目不存在", HttpStatus.NOT_FOUND),

    // ---------- 冲突 ----------
    CONFLICT(40900, "资源状态冲突", HttpStatus.CONFLICT),
    USERNAME_EXISTS(40901, "用户名已存在", HttpStatus.CONFLICT),
    ROLE_CODE_EXISTS(40902, "角色编码已存在", HttpStatus.CONFLICT),
    TASK_STATE_CONFLICT(40903, "任务当前状态不允许该操作", HttpStatus.CONFLICT),
    IDEMPOTENT_CONFLICT(40904, "重复提交，已返回首次结果", HttpStatus.CONFLICT),

    // ---------- 限流 ----------
    TOO_MANY_REQUESTS(42900, "请求过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS),

    // ---------- 服务端 ----------
    INTERNAL_ERROR(50000, "系统繁忙，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR),
    DATA_INTEGRITY_VIOLATION(50001, "数据完整性校验失败", HttpStatus.INTERNAL_SERVER_ERROR),
    DEPENDENCY_UNAVAILABLE(50002, "依赖服务暂不可用", HttpStatus.SERVICE_UNAVAILABLE),

    // ---------- 领域：审查引擎 / 知识库 / 门禁 ----------
    ENGINE_UNAVAILABLE(60000, "审查引擎不可用", HttpStatus.SERVICE_UNAVAILABLE),
    LLM_CALL_FAILED(60001, "大模型调用失败", HttpStatus.BAD_GATEWAY),
    EMBEDDING_UNAVAILABLE(60002, "向量化服务不可用", HttpStatus.SERVICE_UNAVAILABLE),
    REPO_CLONE_FAILED(60003, "代码仓库拉取失败", HttpStatus.BAD_REQUEST),
    CODE_PARSE_FAILED(60004, "代码解析失败", HttpStatus.BAD_REQUEST),
    GATE_BLOCKED(60005, "门禁未通过，禁止放行", HttpStatus.CONFLICT);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}

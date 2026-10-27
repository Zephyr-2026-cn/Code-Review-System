package com.shenchao.codereview.auth.controller;

import com.shenchao.codereview.auth.dto.AuthResult;
import com.shenchao.codereview.auth.dto.ChangePasswordRequest;
import com.shenchao.codereview.auth.dto.LoginRequest;
import com.shenchao.codereview.auth.dto.LoginResponse;
import com.shenchao.codereview.auth.dto.UserProfile;
import com.shenchao.codereview.auth.security.LoginUser;
import com.shenchao.codereview.auth.security.RefreshTokenCookie;
import com.shenchao.codereview.auth.service.AuthService;
import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.api.Result;
import com.shenchao.codereview.common.exception.BizException;
import com.shenchao.codereview.system.audit.Audit;
import com.shenchao.codereview.system.audit.AuditAspect;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p>Cookie 操作只在 Controller 做，Service 层不感知 Web 容器类型，
 * 便于后续把认证逻辑复用到 CLI 或定时任务场景。
 */
@Slf4j
@Tag(name = "01-认证", description = "登录、刷新令牌、登出、当前用户、修改密码")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;

    @Operation(summary = "登录", description = "成功后返回访问令牌，刷新令牌通过 HttpOnly Cookie 下发")
    @PostMapping("/login")
    @Audit(action = "LOGIN", resource = "AUTH", logArgs = true, resourceIdIndex = -1)
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest,
                                       HttpServletResponse httpResponse) {
        // 供审计切面补偿用户名（此时上下文还没有登录用户）
        httpRequest.setAttribute(AuditAspect.AUDIT_USERNAME_ATTR, request.normalizedUsername());

        AuthResult result = authService.login(request);
        writeRefreshCookie(httpResponse, result.refreshToken());
        return Result.ok(result.toResponse());
    }

    @Operation(summary = "刷新令牌", description = "使用 Cookie 中的刷新令牌换取新的访问令牌，刷新令牌一次一用")
    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String refreshToken = refreshTokenCookie.read(httpRequest)
                .orElseThrow(() -> new BizException(ErrorCode.REFRESH_TOKEN_INVALID));

        try {
            AuthResult result = authService.refresh(refreshToken);
            writeRefreshCookie(httpResponse, result.refreshToken());
            return Result.ok(result.toResponse());
        } catch (BizException ex) {
            // 刷新失败说明凭证已失效，顺手清掉浏览器里的脏 Cookie，避免前端反复重试
            httpResponse.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie.clear().toString());
            throw ex;
        }
    }

    @Operation(summary = "登出", description = "拉黑当前访问令牌并吊销刷新令牌")
    @PostMapping("/logout")
    @Audit(action = "LOGOUT", resource = "AUTH")
    public Result<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        LoginUser loginUser = LoginUser.currentUser().orElse(null);
        authService.logout(loginUser, refreshTokenCookie.read(httpRequest).orElse(null));
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie.clear().toString());
        return Result.ok();
    }

    @Operation(summary = "当前用户", description = "返回身份、角色与权限点，前端据此渲染菜单与按钮")
    @GetMapping("/me")
    public Result<UserProfile> me() {
        LoginUser loginUser = LoginUser.currentUser()
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
        return Result.ok(authService.profileOf(loginUser));
    }

    @Operation(summary = "修改密码", description = "修改成功后当前令牌立即失效，需重新登录")
    @PostMapping("/password")
    @Audit(action = "CHANGE_PASSWORD", resource = "AUTH")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                       HttpServletResponse httpResponse) {
        LoginUser loginUser = LoginUser.currentUser()
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
        authService.changePassword(loginUser, request);
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie.clear().toString());
        return Result.ok();
    }

    private void writeRefreshCookie(HttpServletResponse response, String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie.create(refreshToken).toString());
    }
}

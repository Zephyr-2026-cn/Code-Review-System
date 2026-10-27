package com.shenchao.codereview.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 已认证但无权限：返回 403 统一 JSON，并记录"谁试图访问什么"。
 *
 * <p>该日志是发现越权探测的唯一线索，务必保留请求路径与用户名。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("越权访问被拦截: user={}, method={}, uri={}",
                LoginUser.currentUsernameOrNull(), request.getMethod(), request.getRequestURI());

        response.setStatus(ErrorCode.PERMISSION_DENIED.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(ErrorCode.PERMISSION_DENIED)));
    }
}

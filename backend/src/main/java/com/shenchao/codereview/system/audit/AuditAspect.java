package com.shenchao.codereview.system.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shenchao.codereview.auth.security.LoginUser;
import com.shenchao.codereview.common.util.RequestUtils;
import com.shenchao.codereview.system.entity.SysAuditLog;
import com.shenchao.codereview.system.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * 审计切面：记录"谁、在什么时候、从哪个 IP、对什么资源做了什么、结果如何"。
 *
 * <p>三条实现约束：
 * <ol>
 *   <li>入参序列化前必须脱敏，密码/令牌/密钥字段永远不进日志</li>
 *   <li>序列化异常不能影响主流程，降级为只记录参数类型</li>
 *   <li>落库走独立线程池，写日志的 RT 不计入接口耗时</li>
 * </ol>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    /** 命中这些关键字的字段一律脱敏（忽略大小写、包含匹配）。 */
    private static final Set<String> SENSITIVE_KEYWORDS =
            Set.of("password", "passwd", "token", "secret", "credential", "authorization", "key");

    /**
     * 登录场景的补偿：登录成功/失败时 SecurityContext 里还没有用户，
     * 由 Controller 把待审计的用户名写进请求属性，切面优先读取它。
     */
    public static final String AUDIT_USERNAME_ATTR = "AUDIT_USERNAME";

    private static final int MAX_DETAIL_LENGTH = 4000;

    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    @Around("@annotation(audit)")
    public Object around(ProceedingJoinPoint joinPoint, Audit audit) throws Throwable {
        long startNs = System.nanoTime();
        OffsetDateTime startedAt = OffsetDateTime.now();
        Throwable error = null;
        Object result = null;

        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable throwable) {
            error = throwable;
            throw throwable;
        } finally {
            try {
                record(joinPoint, audit, result, error, startedAt,
                        (int) ((System.nanoTime() - startNs) / 1_000_000));
            } catch (Exception ex) {
                // 审计自身问题绝不影响业务
                log.error("审计日志记录失败: action={}", audit.action(), ex);
            }
        }
    }

    private void record(ProceedingJoinPoint joinPoint, Audit audit, Object result, Throwable error,
                        OffsetDateTime startedAt, int durationMs) {

        SysAuditLog entity = new SysAuditLog();
        entity.setAction(audit.action());
        entity.setResource(audit.resource().isBlank() ? null : audit.resource());
        entity.setSuccess(error == null);
        entity.setDurationMs(durationMs);
        entity.setCreatedAt(startedAt);

        LoginUser.currentUser().ifPresent(user -> {
            entity.setUserId(user.userId());
            entity.setUsername(user.username());
        });

        HttpServletRequest request = currentRequest();
        if (request != null) {
            entity.setIp(RequestUtils.resolveClientIp(request));
            entity.setUserAgent(RequestUtils.resolveUserAgent(request));
            // 登录场景 SecurityContext 为空，用 Controller 预置的用户名补偿
            if (entity.getUsername() == null) {
                Object attr = request.getAttribute(AUDIT_USERNAME_ATTR);
                if (attr instanceof String attrUsername && !attrUsername.isBlank()) {
                    entity.setUsername(attrUsername);
                }
            }
        }

        if (error != null) {
            entity.setErrorMsg(truncate(error.getClass().getSimpleName() + ": " + error.getMessage(), 500));
        }

        String resourceId = extractResourceId(joinPoint, audit);
        if (resourceId != null) {
            entity.setResourceId(truncate(resourceId, 64));
        }

        entity.setDetail(buildDetail(joinPoint, audit, result));
        auditLogService.saveAsync(entity);
    }

    private String extractResourceId(ProceedingJoinPoint joinPoint, Audit audit) {
        int index = audit.resourceIdIndex();
        Object[] args = joinPoint.getArgs();
        if (index < 0 || index >= args.length) {
            return null;
        }
        Object arg = args[index];
        if (arg == null) {
            return null;
        }
        // 只接受标量类型，避免把整个 DTO 塞进 resource_id
        if (arg instanceof Number || arg instanceof CharSequence || arg instanceof java.util.UUID) {
            return String.valueOf(arg);
        }
        return null;
    }

    private String buildDetail(ProceedingJoinPoint joinPoint, Audit audit, Object result) {
        if (!audit.logArgs() && !audit.logResult()) {
            return null;
        }
        try {
            ObjectNode detail = objectMapper.createObjectNode();
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            if (audit.logArgs()) {
                String[] paramNames = signature.getParameterNames();
                Object[] args = joinPoint.getArgs();
                ObjectNode argsNode = objectMapper.createObjectNode();
                for (int i = 0; i < args.length; i++) {
                    String name = (paramNames != null && i < paramNames.length) ? paramNames[i] : ("arg" + i);
                    argsNode.set(name, mask(objectMapper.valueToTree(args[i])));
                }
                detail.set("args", argsNode);
            }
            if (audit.logResult() && result != null) {
                detail.set("result", mask(objectMapper.valueToTree(result)));
            }
            String json = objectMapper.writeValueAsString(detail);
            return truncate(json, MAX_DETAIL_LENGTH);
        } catch (Exception ex) {
            log.debug("审计参数序列化失败，已降级: {}", ex.getMessage());
            return null;
        }
    }

    /** 递归脱敏：把敏感字段值替换为 ***。 */
    private JsonNode mask(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<String> fieldNames = objectNode.fieldNames();
            java.util.List<String> toMask = new java.util.ArrayList<>();
            while (fieldNames.hasNext()) {
                String field = fieldNames.next();
                String lower = field.toLowerCase(Locale.ROOT);
                if (SENSITIVE_KEYWORDS.stream().anyMatch(lower::contains)) {
                    toMask.add(field);
                } else {
                    objectNode.set(field, mask(objectNode.get(field)));
                }
            }
            toMask.forEach(field -> objectNode.put(field, "***"));
            return objectNode;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) node).set(i, mask(node.get(i)));
            }
            return node;
        }
        return node;
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}

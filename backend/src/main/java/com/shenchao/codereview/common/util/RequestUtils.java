package com.shenchao.codereview.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * 请求元信息提取工具（用于审计日志与风险识别）。
 */
public final class RequestUtils {

    private static final String UNKNOWN = "unknown";

    private static final String[] IP_HEADERS = {
            "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP", "HTTP_CLIENT_IP"
    };

    private RequestUtils() {
    }

    /**
     * 解析真实客户端 IP。
     *
     * <p>注意：这些头都可被伪造，仅用于审计展示与限流分桶，<b>不得</b>作为安全边界。
     * {@code X-Forwarded-For} 取第一段（最靠近客户端的地址）。
     */
    public static String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        for (String header : IP_HEADERS) {
            String value = request.getHeader(header);
            if (StringUtils.hasText(value) && !UNKNOWN.equalsIgnoreCase(value)) {
                int comma = value.indexOf(',');
                String ip = comma > 0 ? value.substring(0, comma) : value;
                return normalize(ip.trim());
            }
        }
        return normalize(request.getRemoteAddr());
    }

    /** 截断 User-Agent，避免超长头写入数据库。 */
    public static String resolveUserAgent(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ua = request.getHeader("User-Agent");
        if (!StringUtils.hasText(ua)) {
            return null;
        }
        return ua.length() <= 255 ? ua : ua.substring(0, 255);
    }

    private static String normalize(String ip) {
        if (!StringUtils.hasText(ip)) {
            return UNKNOWN;
        }
        // IPv6 回环归一，便于统计
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip.length() <= 64 ? ip : ip.substring(0, 64);
    }
}

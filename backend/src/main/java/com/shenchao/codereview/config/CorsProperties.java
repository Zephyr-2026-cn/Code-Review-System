package com.shenchao.codereview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 跨域配置。
 *
 * <p>安全约束：因为刷新令牌走 Cookie，必须开启 {@code allowCredentials}，
 * 此时<b>禁止</b>使用通配符 {@code *}（浏览器会直接拒绝），所以这里强制白名单。
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}

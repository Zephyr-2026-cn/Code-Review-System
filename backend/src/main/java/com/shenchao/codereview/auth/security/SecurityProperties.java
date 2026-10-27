package com.shenchao.codereview.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 安全相关配置。
 *
 * @param permitAll         免认证路径（相对 context-path，支持 Ant 风格）
 * @param maxLoginFailures  触发锁定的连续失败次数
 * @param loginLockDuration 锁定时长
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        List<String> permitAll,
        int maxLoginFailures,
        Duration loginLockDuration
) {

    public SecurityProperties {
        permitAll = permitAll == null ? List.of() : List.copyOf(permitAll);
        if (maxLoginFailures <= 0) {
            maxLoginFailures = 5;
        }
        if (loginLockDuration == null) {
            loginLockDuration = Duration.ofMinutes(15);
        }
    }
}

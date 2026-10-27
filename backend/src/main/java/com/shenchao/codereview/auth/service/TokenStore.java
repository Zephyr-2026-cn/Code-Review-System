package com.shenchao.codereview.auth.service;

import com.shenchao.codereview.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 令牌存储：刷新令牌与访问令牌黑名单。
 *
 * <p>为什么用 Redis 而不是把状态放数据库：
 * <ul>
 *   <li>刷新令牌一次一用（轮换），需要原子 GETDEL，Redis 天然支持</li>
 *   <li>黑名单只需存活到 Token 自然过期，TTL 自动清理，不产生垃圾数据</li>
 * </ul>
 *
 * <p><b>可用性策略（fail-closed）</b>：Redis 不可用时认证链路直接拒绝请求，
 * 而不是"降级放行"。降级放行意味着已登出的令牌仍可访问，属于安全缺陷。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenStore {

    private static final String REFRESH_KEY_PREFIX = "auth:refresh:";
    private static final String BLACKLIST_KEY_PREFIX = "auth:blacklist:";
    private static final String LOGIN_FAIL_KEY_PREFIX = "auth:loginfail:";
    private static final String LOGIN_LOCK_KEY_PREFIX = "auth:loginlock:";

    private final StringRedisTemplate redisTemplate;

    // ---------------- 刷新令牌 ----------------

    /** 生成并保存刷新令牌，返回令牌明文（只此一次，服务端不存明文映射之外的信息）。 */
    public String createRefreshToken(Long userId, Duration ttl) {
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        try {
            redisTemplate.opsForValue().set(REFRESH_KEY_PREFIX + token, String.valueOf(userId), ttl);
        } catch (DataAccessException ex) {
            log.error("保存刷新令牌失败，Redis 不可用", ex);
            throw new com.shenchao.codereview.common.exception.BizException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return token;
    }

    /**
     * 消费刷新令牌（原子取出并删除），实现"一次一用"。
     *
     * @return 归属用户 ID；令牌不存在（已用/过期/伪造）返回空
     */
    public Optional<Long> consumeRefreshToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            String value = redisTemplate.opsForValue().getAndDelete(REFRESH_KEY_PREFIX + token);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(Long.valueOf(value));
        } catch (NumberFormatException ex) {
            log.warn("刷新令牌载荷异常，已忽略");
            return Optional.empty();
        } catch (DataAccessException ex) {
            log.error("读取刷新令牌失败，Redis 不可用", ex);
            throw new com.shenchao.codereview.common.exception.BizException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /** 吊销刷新令牌（登出）。令牌本就不存在时静默通过。 */
    public void revokeRefreshToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(REFRESH_KEY_PREFIX + token);
        } catch (DataAccessException ex) {
            log.warn("吊销刷新令牌失败: {}", ex.getMessage());
        }
    }

    // ---------------- 访问令牌黑名单 ----------------

    public void blacklistAccessToken(String jti, Duration ttl) {
        if (jti == null || jti.isBlank() || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(BLACKLIST_KEY_PREFIX + jti, "1", ttl);
        } catch (DataAccessException ex) {
            // 登出时黑名单写失败属于严重问题：令牌会被继续使用直到自然过期
            log.error("写入令牌黑名单失败，该令牌将在过期前保持有效: jti={}", jti, ex);
        }
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_KEY_PREFIX + jti));
        } catch (DataAccessException ex) {
            log.error("查询令牌黑名单失败，按 fail-closed 拒绝请求", ex);
            throw new com.shenchao.codereview.common.exception.BizException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    // ---------------- 登录失败计数 ----------------

    /** 记录一次登录失败，返回当前累计次数。首次失败时初始化窗口 TTL。 */
    public long increaseLoginFailure(String username, Duration window) {
        String key = LOGIN_FAIL_KEY_PREFIX + username;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, window);
            }
            return count == null ? 1L : count;
        } catch (DataAccessException ex) {
            log.warn("登录失败计数不可用（将退化为不限流）: {}", ex.getMessage());
            return 0L;
        }
    }

    public void clearLoginFailure(String username) {
        try {
            redisTemplate.delete(LOGIN_FAIL_KEY_PREFIX + username);
        } catch (DataAccessException ex) {
            log.warn("清理登录失败计数失败: {}", ex.getMessage());
        }
    }

    /** 锁定账号一段时间。 */
    public void lockLogin(String username, Duration duration) {
        try {
            redisTemplate.opsForValue().set(LOGIN_LOCK_KEY_PREFIX + username, "1", duration);
        } catch (DataAccessException ex) {
            log.warn("写入登录锁失败: {}", ex.getMessage());
        }
    }

    public boolean isLoginLocked(String username) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(LOGIN_LOCK_KEY_PREFIX + username));
        } catch (DataAccessException ex) {
            // 锁定检查不可用时放行，由密码校验兜底，避免 Redis 故障造成全员无法登录
            log.warn("查询登录锁失败，跳过锁定校验: {}", ex.getMessage());
            return false;
        }
    }
}

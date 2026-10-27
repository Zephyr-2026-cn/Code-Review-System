package com.shenchao.codereview.system.service;

import com.shenchao.codereview.system.mapper.SysPermissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 用户权限点查询（带 Redis 缓存）。
 *
 * <p>为什么要缓存：权限点在每次请求的认证阶段都要用到，直接查库会让
 * "一个页面 20 个接口"变成 20 次多表 JOIN。
 *
 * <p>为什么要可主动失效：管理员改完角色权限必须立刻生效。业务侧改动权限后
 * 调用 {@link #evict(Long)}，缓存键按用户粒度，命中率与一致性都够用。
 *
 * <p>缓存内容：权限点编码以换行分隔的纯文本（不用 JSON），解析成本最低且不会因
 * 序列化器升级导致历史缓存不可读。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionCacheService {

    private static final String CACHE_KEY_PREFIX = "auth:perm:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final SysPermissionMapper permissionMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 查询用户权限点。
     *
     * <p>Redis 故障时降级为直接查库：权限是读多写少的静态数据，
     * 查库的代价远低于"Redis 挂掉导致全员无法访问"。
     */
    public Set<String> permissionsOf(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        String key = CACHE_KEY_PREFIX + userId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return cached.isEmpty() ? Set.of() : new LinkedHashSet<>(Arrays.asList(cached.split("\n")));
            }
        } catch (DataAccessException ex) {
            log.warn("读取权限缓存失败，降级查库: userId={}, cause={}", userId, ex.getMessage());
            return loadFromDb(userId);
        }

        Set<String> permissions = loadFromDb(userId);
        try {
            redisTemplate.opsForValue().set(key, String.join("\n", permissions), CACHE_TTL);
        } catch (DataAccessException ex) {
            log.warn("写入权限缓存失败: userId={}, cause={}", userId, ex.getMessage());
        }
        return permissions;
    }

    /** 权限变更后失效单个用户的缓存。 */
    public void evict(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.delete(CACHE_KEY_PREFIX + userId);
        } catch (DataAccessException ex) {
            log.warn("失效权限缓存失败，用户 {} 的权限将在 {} 后生效: {}", userId, CACHE_TTL, ex.getMessage());
        }
    }

    /**
     * 角色权限变更后，失效该角色下所有用户的缓存。
     *
     * <p>用 SCAN 而非 KEYS：KEYS 会阻塞 Redis 单线程，生产环境在键多时会直接打挂实例。
     * 这里的做法是"宁可多失效一些"（清空全部权限缓存），换取逻辑简单且不会漏删。
     */
    public void evictByRole(Long roleId) {
        if (roleId == null) {
            return;
        }
        try {
            Set<String> keys = scanKeys(CACHE_KEY_PREFIX + "*");
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            log.info("角色 {} 权限变更，已清除 {} 个用户的权限缓存", roleId, keys.size());
        } catch (DataAccessException ex) {
            log.warn("按角色失效权限缓存失败: roleId={}, cause={}", roleId, ex.getMessage());
        }
    }

    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new LinkedHashSet<>();
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            org.springframework.data.redis.core.ScanOptions options =
                    org.springframework.data.redis.core.ScanOptions.scanOptions().match(pattern).count(500).build();
            try (org.springframework.data.redis.core.Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            return null;
        });
        return keys;
    }

    private Set<String> loadFromDb(Long userId) {
        return new LinkedHashSet<>(permissionMapper.selectCodesByUserId(userId));
    }
}

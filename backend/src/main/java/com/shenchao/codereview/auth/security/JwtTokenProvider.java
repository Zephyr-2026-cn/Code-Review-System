package com.shenchao.codereview.auth.security;

import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.exception.BizException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * JWT 签发与校验。
 *
 * <p>设计约束：
 * <ul>
 *   <li>Token 只承载身份与角色，<b>不承载权限点</b>——权限变更需要立即生效，放进 Token 会导致改权限必须等 Token 过期</li>
 *   <li>每个 Token 带 {@code jti}，登出时把 jti 加入 Redis 黑名单实现即时失效</li>
 *   <li>校验时固定校验 issuer 并容忍 {@code clockSkew} 的时钟偏移，避免多实例部署时误判过期</li>
 * </ul>
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_NICKNAME = "nickname";
    private static final String CLAIM_ROLES = "roles";
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties properties;
    private final SecretKey secretKey;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        byte[] keyBytes;
        try {
            // 优先按 Base64 解析；若不是合法 Base64 则按原始字符串处理，给使用者留容错空间
            keyBytes = Base64.getDecoder().decode(properties.secret());
        } catch (IllegalArgumentException ex) {
            keyBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        }
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret 解码后不足 32 字节，HS256 无法安全签名，请通过 APP_JWT_SECRET 注入");
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 签发访问令牌。
     *
     * @return 令牌字符串与其 jti、过期时间
     */
    public IssuedToken issueAccessToken(LoginUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .id(jti)
                .issuer(properties.issuer())
                .subject(String.valueOf(user.userId()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim(CLAIM_USERNAME, user.username())
                .claim(CLAIM_NICKNAME, user.nickname())
                .claim(CLAIM_ROLES, List.copyOf(user.roles()))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();

        return new IssuedToken(token, jti, expiresAt, properties.accessTokenTtl().toSeconds());
    }

    /**
     * 解析并校验令牌。
     *
     * @throws BizException 凭证过期 / 伪造 / 格式错误，分别对应不同错误码供前端区分处理
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(properties.issuer())
                    .clockSkewSeconds(properties.clockSkew().toSeconds())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw new BizException(ErrorCode.TOKEN_EXPIRED);
        } catch (SignatureException ex) {
            log.warn("JWT 签名校验失败，疑似伪造令牌");
            throw new BizException(ErrorCode.TOKEN_INVALID);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
    }

    /** 由 Claims 还原身份快照（权限点由权限缓存服务单独补齐）。 */
    public LoginUser toLoginUser(Claims claims, java.util.Set<String> permissions) {
        List<?> rawRoles = claims.get(CLAIM_ROLES, List.class);
        java.util.Set<String> roles = rawRoles == null
                ? java.util.Set.of()
                : rawRoles.stream().filter(String.class::isInstance).map(String.class::cast)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        java.time.Instant expiresAt = claims.getExpiration() == null
                ? null
                : claims.getExpiration().toInstant();
        return new LoginUser(
                Long.valueOf(claims.getSubject()),
                claims.get(CLAIM_USERNAME, String.class),
                claims.get(CLAIM_NICKNAME, String.class),
                roles,
                permissions,
                claims.getId(),
                expiresAt);
    }

    /**
     * 已签发令牌。
     *
     * @param token       令牌字符串
     * @param jti         唯一标识，用于黑名单
     * @param expiresAt   过期时刻
     * @param expiresIn   剩余有效秒数，供前端调度静默刷新
     */
    public record IssuedToken(String token, String jti, Instant expiresAt, long expiresIn) {
    }
}

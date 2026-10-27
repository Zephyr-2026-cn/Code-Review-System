package com.shenchao.codereview.auth;

import com.shenchao.codereview.auth.security.JwtProperties;
import com.shenchao.codereview.auth.security.JwtTokenProvider;
import com.shenchao.codereview.auth.security.LoginUser;
import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.exception.BizException;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 签发与校验单元测试（纯内存，不依赖 Spring 容器与数据库）。
 *
 * <p>覆盖点：正常签发解析、角色还原、过期判定、篡改判定、错误密钥判定、弱密钥拒绝启动。
 */
class JwtTokenProviderTest {

    private static final String SECRET_A =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String SECRET_B =
            Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));

    private JwtTokenProvider provider(String secret, Duration ttl) {
        JwtProperties properties = new JwtProperties(secret, "code-review-test", ttl, Duration.ofDays(7),
                Duration.ZERO);
        return new JwtTokenProvider(properties);
    }

    private LoginUser sampleUser() {
        return LoginUser.of(1L, "admin", "系统管理员", Set.of("ADMIN"), Set.of("task:create", "issue:list"));
    }

    @Test
    @DisplayName("正常签发后应能解析出用户身份与角色")
    void shouldIssueAndParseToken() {
        JwtTokenProvider provider = provider(SECRET_A, Duration.ofHours(1));

        JwtTokenProvider.IssuedToken issued = provider.issueAccessToken(sampleUser());
        Claims claims = provider.parse(issued.token());

        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.getIssuer()).isEqualTo("code-review-test");
        assertThat(claims.getId()).isEqualTo(issued.jti());
        assertThat(claims.get("username", String.class)).isEqualTo("admin");

        LoginUser restored = provider.toLoginUser(claims, Set.of("task:create"));
        assertThat(restored.userId()).isEqualTo(1L);
        assertThat(restored.roles()).containsExactly("ADMIN");
        assertThat(restored.permissions()).containsExactly("task:create");
        assertThat(restored.tokenId()).isEqualTo(issued.jti());
        assertThat(restored.tokenExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("已过期的令牌应抛出 TOKEN_EXPIRED")
    void shouldRejectExpiredToken() {
        JwtTokenProvider provider = provider(SECRET_A, Duration.ofSeconds(-10));
        String token = provider.issueAccessToken(sampleUser()).token();

        assertThatThrownBy(() -> provider.parse(token))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("被篡改的令牌应抛出 TOKEN_INVALID")
    void shouldRejectTamperedToken() {
        JwtTokenProvider provider = provider(SECRET_A, Duration.ofHours(1));
        String token = provider.issueAccessToken(sampleUser()).token();
        String tampered = token.substring(0, token.length() - 3) + "abc";

        assertThatThrownBy(() -> provider.parse(tampered))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("使用其他密钥签发的令牌应被拒绝")
    void shouldRejectTokenSignedByAnotherSecret() {
        String token = provider(SECRET_B, Duration.ofHours(1)).issueAccessToken(sampleUser()).token();

        assertThatThrownBy(() -> provider(SECRET_A, Duration.ofHours(1)).parse(token))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("弱密钥（不足 32 字节）应直接拒绝构造，避免带病上线")
    void shouldRejectWeakSecret() {
        String weak = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> provider(weak, Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("同一用户两次登录应产生不同的 jti，便于单独吊销")
    void shouldGenerateDistinctJtiPerIssue() {
        JwtTokenProvider provider = provider(SECRET_A, Duration.ofHours(1));

        String first = provider.issueAccessToken(sampleUser()).jti();
        String second = provider.issueAccessToken(sampleUser()).jti();

        assertThat(first).isNotEqualTo(second);
    }
}

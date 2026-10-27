package com.shenchao.codereview.common;

import com.shenchao.codereview.common.exception.BizException;
import com.shenchao.codereview.common.util.PasswordPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密码策略测试：边界值（7/8/64/65 位）与字符组成。
 */
class PasswordPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"abcd1234", "Zx9aBcDe", "a1b2c3d4e5"})
    @DisplayName("合法密码应通过校验")
    void shouldAcceptValidPassword(String password) {
        assertThatCode(() -> PasswordPolicy.validate(password)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("恰好 64 位应通过（上边界）")
    void shouldAcceptMaxLengthPassword() {
        String password = "a1".repeat(32);
        assertThatCode(() -> PasswordPolicy.validate(password)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc1234", "abcdefgh", "12345678", "password", "admin123"})
    @DisplayName("空值、过短、纯字母、纯数字、常见弱口令应被拒绝")
    void shouldRejectWeakPassword(String password) {
        assertThatThrownBy(() -> PasswordPolicy.validate(password))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("超过 64 位应被拒绝（BCrypt 只取前 72 字节，超长无效且浪费）")
    void shouldRejectTooLongPassword() {
        String password = "a1".repeat(33);
        assertThatThrownBy(() -> PasswordPolicy.validate(password))
                .isInstanceOf(BizException.class);
    }
}

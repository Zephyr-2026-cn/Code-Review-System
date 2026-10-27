package com.shenchao.codereview.common.util;

import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.exception.BizException;

import java.util.Set;

/**
 * 密码强度策略。
 *
 * <p>规则：长度 8-64，且至少包含"字母"与"数字"两类字符。
 * 不强制特殊字符——强制特殊字符会让用户把 {@code Passw0rd!} 写进桌面便签，反而更不安全。
 *
 * <p>上限 64 不是摆设：BCrypt 只取前 72 字节，超长密码不仅无效，还会带来
 * 每次登录多余的字符串处理开销。
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;

    /** 常见弱口令（小样本，用于挡住最离谱的几个）。 */
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "12345678", "123456789", "password", "password1", "admin123", "qwerty123", "abc12345", "11111111"
    );

    private PasswordPolicy() {
    }

    /**
     * 校验密码强度。
     *
     * @throws BizException 不满足策略时抛出，message 直接面向用户
     */
    public static void validate(String password) {
        if (password == null || password.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "密码不能为空");
        }
        int length = password.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "密码长度需在 " + MIN_LENGTH + "-" + MAX_LENGTH + " 位之间");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BizException(ErrorCode.PARAM_INVALID, "密码必须同时包含字母和数字");
        }
        if (WEAK_PASSWORDS.contains(password.toLowerCase(java.util.Locale.ROOT))) {
            throw new BizException(ErrorCode.PARAM_INVALID, "密码过于常见，请更换更复杂的密码");
        }
    }
}

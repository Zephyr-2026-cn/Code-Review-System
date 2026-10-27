package com.shenchao.codereview.common;

import com.shenchao.codereview.common.util.PasswordGenerator;
import com.shenchao.codereview.common.util.PasswordPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 初始密码生成器测试。
 *
 * <p>重点验证"生成结果一定能通过策略校验"——这是管理端最尴尬的故障模式：
 * 系统给了一个密码，用户拿着它改密却被策略拒绝。
 */
@DisplayName("初始密码生成器")
class PasswordGeneratorTest {

    @Test
    @DisplayName("生成的密码全部满足密码策略")
    void generatedPasswordsAlwaysPassPolicy() {
        for (int i = 0; i < 200; i++) {
            String password = PasswordGenerator.generate();
            // 不抛异常即为通过
            PasswordPolicy.validate(password);
            assertThat(password).hasSize(12);
        }
    }

    @Test
    @DisplayName("同时包含字母、数字与符号")
    void containsAllCharacterClasses() {
        for (int i = 0; i < 50; i++) {
            String password = PasswordGenerator.generate();
            assertThat(password.chars().anyMatch(Character::isLetter)).isTrue();
            assertThat(password.chars().anyMatch(Character::isDigit)).isTrue();
            assertThat(password.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch))).isTrue();
        }
    }

    @Test
    @DisplayName("长度参数被收敛到合法区间")
    void lengthIsClamped() {
        assertThat(PasswordGenerator.generate(1)).hasSize(PasswordPolicy.MIN_LENGTH);
        assertThat(PasswordGenerator.generate(1000)).hasSize(PasswordPolicy.MAX_LENGTH);
        assertThat(PasswordGenerator.generate(16)).hasSize(16);
    }

    @Test
    @DisplayName("1000 次生成无重复（SecureRandom 且空间足够大）")
    void noDuplicatesInBulkGeneration() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(PasswordGenerator.generate());
        }
        assertThat(seen).hasSize(1000);
    }

    @Test
    @DisplayName("不包含易混淆字符 0/O/1/l/I")
    void excludesAmbiguousCharacters() {
        for (int i = 0; i < 100; i++) {
            String password = PasswordGenerator.generate();
            assertThat(password).doesNotContain("0", "O", "1", "l", "I");
        }
    }
}

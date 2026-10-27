package com.shenchao.codereview.project;

import com.shenchao.codereview.common.exception.BizException;
import com.shenchao.codereview.project.git.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 凭据加解密测试。
 *
 * <p>覆盖点：
 * <ol>
 *   <li>往返一致性——加密后必须能解回原文</li>
 *   <li>IV 随机性——同一明文两次加密必须产生不同密文（GCM 复用 IV 会直接泄漏明文关系）</li>
 *   <li>完整性——密文被篡改必须解密失败，而不是静默返回垃圾数据</li>
 *   <li>密钥校验——长度不对时整体禁用凭据功能，而不是带着隐患运行</li>
 * </ol>
 */
@DisplayName("Git 凭据加解密")
class SecretCipherTest {

    /** 32 字节全零密钥，仅用于测试。 */
    private static final String VALID_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private SecretCipher cipher;

    @BeforeEach
    void setUp() {
        cipher = SecretCipher.of(VALID_KEY);
        assertThat(cipher).isNotNull();
    }

    @Test
    @DisplayName("加密后可正确解密")
    void roundTrip() {
        String token = "ghp_1234567890abcdefghijklmnopqrstuvwxyz";
        String encrypted = cipher.encrypt(token);

        assertThat(encrypted).isNotEqualTo(token);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(token);
    }

    @Test
    @DisplayName("同一明文两次加密得到不同密文（IV 随机）")
    void producesDifferentCiphertextForSamePlaintext() {
        String token = "ghp_same_token";
        String first = cipher.encrypt(token);
        String second = cipher.encrypt(token);

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(token);
        assertThat(cipher.decrypt(second)).isEqualTo(token);
    }

    @Test
    @DisplayName("密文被篡改时解密失败而不是返回脏数据")
    void tamperedCiphertextIsRejected() {
        String encrypted = cipher.encrypt("ghp_tamper_me");
        byte[] raw = Base64.getDecoder().decode(encrypted);
        // 翻转最后一个字节（落在 GCM Tag 上）
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("密钥不匹配时解密失败")
    void wrongKeyFails() {
        String encrypted = cipher.encrypt("ghp_secret");
        SecretCipher other = SecretCipher.of(
                Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8,
                        9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
                        25, 26, 27, 28, 29, 30, 31, 32}));
        assertThat(other).isNotNull();

        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("未配置密钥时返回 null，凭据功能整体禁用")
    void returnsNullWhenKeyMissing() {
        assertThat(SecretCipher.of(null)).isNull();
        assertThat(SecretCipher.of("")).isNull();
        assertThat(SecretCipher.of("   ")).isNull();
    }

    @Test
    @DisplayName("密钥长度不是 32 字节时拒绝启用")
    void rejectsWrongKeyLength() {
        assertThat(SecretCipher.of(Base64.getEncoder().encodeToString(new byte[16]))).isNull();
        assertThat(SecretCipher.of(Base64.getEncoder().encodeToString(new byte[48]))).isNull();
    }

    @Test
    @DisplayName("非 Base64 密钥不会导致启动失败，只是禁用功能")
    void invalidBase64DisablesFeature() {
        assertThat(SecretCipher.of("not-a-base64-!!!")).isNull();
    }

    @Test
    @DisplayName("空密文被拒绝")
    void rejectsEmptyCiphertext() {
        assertThatThrownBy(() -> cipher.decrypt("")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> cipher.decrypt(null)).isInstanceOf(BizException.class);
    }
}

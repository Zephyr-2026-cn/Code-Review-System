package com.shenchao.codereview.project.git;

import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Git 凭据加解密（AES-256-GCM）。
 *
 * <p>为什么用 GCM 而不是 CBC：
 * <ul>
 *   <li>GCM 自带完整性校验，密文被篡改会解密失败而不是解出垃圾数据</li>
 *   <li>CBC 需要额外做 HMAC 才能防篡改，实现成本更高、更容易写错</li>
 * </ul>
 *
 * <p>密文格式：{@code Base64(IV(12B) | CipherText | Tag(16B))}。
 * IV <b>每次加密都必须重新随机生成</b>——GCM 下重复 IV 会直接泄漏明文异或关系，
 * 这是该模式最致命的误用方式。
 *
 * <p>密钥来自环境变量 {@code APP_REPO_SECRET_KEY}，与数据库分离保存：
 * 只拿到数据库转储（dump）无法解出任何令牌。
 */
@Slf4j
public final class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec keySpec;

    private SecretCipher(SecretKeySpec keySpec) {
        this.keySpec = keySpec;
    }

    /**
     * 由配置构造；密钥不合法时返回 {@code null}（凭据功能整体禁用），而不是让应用启动失败。
     *
     * <p>权衡：凭据是可选能力，没有它系统仍可审查公开仓库；
     * 而"因为没配密钥导致整个服务起不来"会让人误以为代码有 bug。
     */
    public static SecretCipher of(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("未配置 app.git.credential-key，私有仓库凭据功能不可用");
            return null;
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException ex) {
            log.error("app.git.credential-key 不是合法 Base64，凭据功能不可用");
            return null;
        }
        if (keyBytes.length != KEY_BYTES) {
            log.error("app.git.credential-key 解码后必须为 {} 字节，当前 {} 字节，凭据功能不可用",
                    KEY_BYTES, keyBytes.length);
            return null;
        }
        return new SecretCipher(new SecretKeySpec(keyBytes, "AES"));
    }

    public boolean isAvailable() {
        return keySpec != null;
    }

    /** 加密令牌明文，返回可直接入库的 Base64 字符串。 */
    public String encrypt(String plaintext) {
        requireAvailable();
        if (plaintext == null) {
            throw new IllegalArgumentException("待加密内容不能为 null");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            // 不打印明文，也不把底层异常细节返回给调用方
            log.error("凭据加密失败", ex);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "凭据加密失败");
        }
    }

    /** 解密。密文被篡改或密钥不匹配时统一按"凭据不可用"处理，不区分具体原因。 */
    public String decrypt(String cipherBase64) {
        requireAvailable();
        if (cipherBase64 == null || cipherBase64.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "凭据密文为空");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(cipherBase64);
            if (combined.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文长度不足");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] cipherText = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.error("凭据解密失败，可能是密钥变更或密文损坏");
            throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE, "仓库凭据不可用，请重新录入");
        }
    }

    private void requireAvailable() {
        if (keySpec == null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "服务端未配置凭据加密密钥，无法使用私有仓库凭据");
        }
    }
}

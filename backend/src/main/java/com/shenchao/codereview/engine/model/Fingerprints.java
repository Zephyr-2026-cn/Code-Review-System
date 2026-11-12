package com.shenchao.codereview.engine.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 指纹工具：用于缺陷去重，稳定性优先于抗碰撞（不是安全场景）。 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String sha256Hex(String seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // JDK 必然内置 SHA-256，走到这里说明运行环境被裁剪，属于不可恢复错误
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}

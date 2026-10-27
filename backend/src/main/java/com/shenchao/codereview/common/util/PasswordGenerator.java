package com.shenchao.codereview.common.util;

import java.security.SecureRandom;

/**
 * 初始密码生成器。
 *
 * <p>约束：
 * <ol>
 *   <li>必须用 {@link SecureRandom}——{@code java.util.Random} 是可预测的线性同余序列，
 *       拿它生成口令等于把口令交给攻击者</li>
 *   <li>生成结果必须能通过 {@link PasswordPolicy}，否则会出现"系统给的密码登不进去、
 *       改密码又被策略拒绝"的死循环</li>
 *   <li>剔除易混淆字符（0/O、1/l/I），避免管理员抄错转达</li>
 * </ol>
 */
public final class PasswordGenerator {

    /** 去掉 0 O 1 l I o 之后的大小写字母与数字。 */
    private static final String LETTERS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "@#$%&*";

    private static final int DEFAULT_LENGTH = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordGenerator() {
    }

    /**
     * 生成指定长度的随机密码（至少 8 位，不足则取 8）。
     *
     * <p>保证至少各含一个字母、数字与符号，然后整体打乱——
     * 固定首字符类型的做法会让口令空间出现可预测的结构。
     */
    public static String generate(int length) {
        int size = Math.max(PasswordPolicy.MIN_LENGTH, Math.min(length, PasswordPolicy.MAX_LENGTH));
        char[] chars = new char[size];

        int index = 0;
        chars[index++] = pick(LETTERS);
        chars[index++] = pick(DIGITS);
        chars[index++] = pick(SYMBOLS);

        String pool = LETTERS + DIGITS + SYMBOLS;
        while (index < size) {
            chars[index++] = pick(pool);
        }

        shuffle(chars);
        return new String(chars);
    }

    public static String generate() {
        return generate(DEFAULT_LENGTH);
    }

    private static char pick(String source) {
        return source.charAt(RANDOM.nextInt(source.length()));
    }

    /** Fisher-Yates 洗牌，避免"前三位一定是字母数字符号"的固定模式。 */
    private static void shuffle(char[] chars) {
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
    }
}

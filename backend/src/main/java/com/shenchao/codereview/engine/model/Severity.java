package com.shenchao.codereview.engine.model;

/**
 * 缺陷严重度，从高到低。
 *
 * <p>{@link #rank()} 用于排序与门禁判定：合并门禁只拦 BLOCKER / CRITICAL，
 * 其余转 WARN，因此严重度必须可比较，不能只当字符串用。
 */
public enum Severity {

    /** 阻断级：必然导致事故或安全事故（硬编码密钥、必然空指针）。 */
    BLOCKER("阻断", 1),

    /** 严重：高概率出错或安全风险。 */
    CRITICAL("严重", 2),

    /** 主要：明确的不良实践，建议本次修复。 */
    MAJOR("主要", 3),

    /** 次要：可读性/维护性问题。 */
    MINOR("次要", 4),

    /** 提示：风格类建议。 */
    INFO("提示", 5);

    private final String label;
    private final int rank;

    Severity(String label, int rank) {
        this.label = label;
        this.rank = rank;
    }

    public String label() {
        return label;
    }

    /** 1 最高，5 最低；用于排序与门禁阈值比较。 */
    public int rank() {
        return rank;
    }

    /** 是否达到"阻断合并"的级别。 */
    public boolean blocking() {
        return this == BLOCKER || this == CRITICAL;
    }

    /**
     * PMD 优先级（1 最高 5 最低）到严重度的映射。
     *
     * <p>PMD 的 1 级不完全等于"阻断"：多数 1 级规则只是高概率出错，
     * 真正阻断级的由 {@code rule_category_map} 显式指定（如硬编码密钥），
     * 所以这里最高只到 CRITICAL，BLOCKER 留给人工规则与 LLM 高危判定。
     */
    public static Severity fromPmdPriority(int priority) {
        return switch (priority) {
            case 1 -> CRITICAL;
            case 2 -> MAJOR;
            case 3 -> MINOR;
            default -> INFO;
        };
    }

    public static Severity fromCode(String code) {
        if (code == null || code.isBlank()) {
            return MINOR;
        }
        for (Severity severity : values()) {
            if (severity.name().equalsIgnoreCase(code.trim())) {
                return severity;
            }
        }
        return MINOR;
    }

    /** 取两个严重度中更严重的那个，用于融合时以高者为准。 */
    public static Severity max(Severity left, Severity right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.rank <= right.rank ? left : right;
    }
}

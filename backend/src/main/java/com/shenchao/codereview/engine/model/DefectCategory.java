package com.shenchao.codereview.engine.model;

/**
 * 统一缺陷类别。
 *
 * <p>存在的意义：静态引擎（PMD）与语义引擎（LLM）各有自己的表述体系，
 * 若不先归一，融合、统计、门禁三处都要各写一套 if-else。
 * 这张枚举就是蓝皮书 6.1 里"两套体系对齐"的落点，
 * 具体规则到类别的映射配置在 {@code rule_category_map} 表。
 */
public enum DefectCategory {

    /** 空指针风险：可能对 null 解引用、空判断本身写错。 */
    NULL_POINTER("空指针风险"),

    /** 资源泄漏：流、连接、锁未在异常路径释放。 */
    RESOURCE_LEAK("资源泄漏"),

    /** 并发缺陷：竞态、错误的同步、非线程安全对象共享。 */
    CONCURRENCY("并发缺陷"),

    /** 安全风险：硬编码密钥、注入、不安全的加密用法。 */
    INJECTION("安全风险"),

    /** 性能问题：循环内重复创建对象、低效字符串拼接。 */
    PERFORMANCE("性能问题"),

    /** 可维护性：复杂度过高、死代码、命名与结构问题。 */
    MAINTAINABILITY("可维护性"),

    /** 逻辑错误：异常吞掉、错误的相等比较、switch 缺分支。 */
    LOGIC_ERROR("逻辑错误"),

    /** 代码风格：不影响正确性的规范问题。 */
    CODE_STYLE("代码风格");

    private final String label;

    DefectCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 解析失败时回退到 {@link #MAINTAINABILITY}，避免一条脏数据让整个任务失败。 */
    public static DefectCategory fromCode(String code) {
        if (code == null || code.isBlank()) {
            return MAINTAINABILITY;
        }
        for (DefectCategory category : values()) {
            if (category.name().equalsIgnoreCase(code.trim())) {
                return category;
            }
        }
        return MAINTAINABILITY;
    }
}

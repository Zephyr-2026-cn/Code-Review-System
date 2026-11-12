package com.shenchao.codereview.engine.model;

import com.shenchao.codereview.engine.spi.RawDefect;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一缺陷模型。
 *
 * <p>字段与蓝皮书 6.1 一致，是静态引擎、语义引擎与融合层的公共契约。
 * 引擎侧产出 {@link RawDefect}，由 {@link #fromRaw} 补齐来源、置信度与指纹后进入管道。
 *
 * @param filePath    仓库相对路径（统一用 {@code /} 分隔，跨平台可比对）
 * @param startLine   起始行（1 起）
 * @param endLine     结束行，单行缺陷时等于 startLine
 * @param category    统一类别
 * @param severity    严重度
 * @param title       一句话结论，列表页直接展示
 * @param description 详细说明
 * @param suggestion  修复建议，静态引擎可为空（由 LLM 补）
 * @param fixSnippet  修复代码片段，可为空
 * @param source      来源
 * @param ruleId      规则标识：PMD 规则名或 prompt 模板 id
 * @param confidence  置信度 0~1
 * @param fingerprint 任务内去重指纹
 * @param evidence    各引擎原始证据，落库前序列化为 JSONB，供人工复核
 */
public record Defect(
        String filePath,
        int startLine,
        int endLine,
        DefectCategory category,
        Severity severity,
        String title,
        String description,
        String suggestion,
        String fixSnippet,
        DefectSource source,
        String ruleId,
        double confidence,
        String fingerprint,
        Map<String, String> evidence
) {

    /**
     * 由引擎原始输出构造统一缺陷。
     *
     * @param confidence 该规则的历史可信度（静态引擎取规则权重，LLM 取模型自评）
     */
    public static Defect fromRaw(RawDefect raw, DefectSource source, double confidence) {
        int begin = Math.max(raw.startLine(), 1);
        int end = Math.max(raw.endLine(), begin);
        DefectCategory category = raw.category() == null ? DefectCategory.MAINTAINABILITY : raw.category();
        String ruleId = raw.ruleId() == null ? "unknown" : raw.ruleId();

        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("source", source.name());
        if (raw.evidence() != null) {
            evidence.putAll(raw.evidence());
        }

        return new Defect(
                normalizePath(raw.filePath()),
                begin,
                end,
                category,
                raw.severity() == null ? Severity.MINOR : raw.severity(),
                truncate(raw.title(), 256),
                raw.description(),
                null,
                null,
                source,
                ruleId,
                clamp(confidence),
                fingerprintOf(raw.filePath(), ruleId, begin, category.name()),
                evidence
        );
    }

    /** 同一文件、同一规则、同一行、同一类别视为同一条缺陷。 */
    public static String fingerprintOf(String filePath, String ruleId, int startLine, String category) {
        String seed = normalizePath(filePath) + "|" + ruleId + "|" + startLine + "|" + category;
        return Fingerprints.sha256Hex(seed);
    }

    /** 路径统一：反斜杠转正斜杠，去掉开头的 {@code ./}，保证 Windows 与 Linux 一致。 */
    public static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "unknown";
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0d;
        }
        return Math.max(0d, Math.min(1d, value));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
    }
}

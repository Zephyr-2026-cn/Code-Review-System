package com.shenchao.codereview.engine.spi;

import com.shenchao.codereview.engine.model.DefectCategory;
import com.shenchao.codereview.engine.model.Severity;

import java.util.Map;

/**
 * 引擎原始缺陷输出。
 *
 * <p>各静态分析器只负责"把自家结果翻译成这个结构"，不关心来源、置信度与指纹，
 * 那些由 {@link com.shenchao.codereview.engine.model.Defect#fromRaw} 统一补齐。
 * 这样新增一个引擎实现时，不需要重复处理融合层的约定。
 *
 * @param evidence   原始证据（规则、优先级、规则集、原始报文），落库前序列化为 JSONB
 * @param confidence 引擎自评置信度 0~1；{@code null} 表示引擎不做自评，
 *                   由管道按规则历史权重补齐（静态引擎属于这一类，语义引擎会自评）
 */
public record RawDefect(
        String filePath,
        int startLine,
        int endLine,
        String ruleId,
        DefectCategory category,
        Severity severity,
        String title,
        String description,
        Map<String, String> evidence,
        Double confidence
) {

    public RawDefect {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        confidence = confidence == null ? null : Math.max(0d, Math.min(1d, confidence));
    }

    /** 兼容不做置信度自评的引擎。 */
    public RawDefect(String filePath, int startLine, int endLine, String ruleId,
                     DefectCategory category, Severity severity, String title,
                     String description, Map<String, String> evidence) {
        this(filePath, startLine, endLine, ruleId, category, severity, title, description, evidence, null);
    }
}

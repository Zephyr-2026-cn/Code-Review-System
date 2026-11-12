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
 * @param evidence 原始证据（规则、优先级、规则集、原始报文），落库前序列化为 JSONB
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
        Map<String, String> evidence
) {
}

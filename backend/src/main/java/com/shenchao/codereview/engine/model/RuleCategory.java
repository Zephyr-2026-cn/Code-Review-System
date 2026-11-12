package com.shenchao.codereview.engine.model;

/**
 * 规则的统一元信息：类别、严重度与权重。
 *
 * @param category 统一缺陷类别
 * @param severity 默认严重度（可被 rule_config 覆盖）
 * @param weight   规则权重 0~1，作为静态缺陷的初始置信度
 */
public record RuleCategory(DefectCategory category, Severity severity, double weight) {
}

package com.shenchao.codereview.engine.spi;

import com.shenchao.codereview.engine.model.RuleCategory;

import java.util.Optional;

/**
 * 规则元信息查询入口。
 *
 * <p>接口放在引擎层、实现放在业务层（读 {@code rule_category_map} 表并缓存），
 * 是为了守住"引擎不碰数据库"的红线：引擎只声明"我需要规则元信息"，
 * 由谁提供、从哪读取，由外层决定。
 */
public interface RuleCategoryMapper {

    /** 查询规则映射；未配置时返回空，由调用方按 PMD 分类做兜底推断。 */
    Optional<RuleCategory> find(String ruleId);
}

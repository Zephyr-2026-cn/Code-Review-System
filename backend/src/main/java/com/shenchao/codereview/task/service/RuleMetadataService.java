package com.shenchao.codereview.task.service;

import com.shenchao.codereview.engine.model.DefectCategory;
import com.shenchao.codereview.engine.model.RuleCategory;
import com.shenchao.codereview.engine.model.Severity;
import com.shenchao.codereview.engine.spi.RuleCategoryMapper;
import com.shenchao.codereview.task.entity.RuleCategoryMapRow;
import com.shenchao.codereview.task.mapper.RuleCategoryMapMapper;
import com.shenchao.codereview.task.mapper.RuleConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 规则元信息与开关（{@link RuleCategoryMapper} 的数据库实现）。
 *
 * <p>为什么要缓存：一次任务要处理上千条告警，每条都查一次映射表会产生上千次 SQL，
 * 而映射表本身几乎不变。这里用"整表加载 + 5 分钟过期"，
 * 代价是配置改动最多延迟 5 分钟生效——对规则配置这种低频操作完全可以接受。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleMetadataService implements RuleCategoryMapper {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final RuleCategoryMapMapper categoryMapMapper;
    private final RuleConfigMapper ruleConfigMapper;

    private volatile Map<String, RuleCategory> categoryCache = Map.of();
    private volatile Set<String> disabledCache = Set.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    @Override
    public Optional<RuleCategory> find(String ruleId) {
        if (ruleId == null) {
            return Optional.empty();
        }
        refreshIfStale();
        return Optional.ofNullable(categoryCache.get(ruleId));
    }

    /** 本次任务需要过滤掉的规则编码。 */
    public Set<String> disabledRules() {
        refreshIfStale();
        return disabledCache;
    }

    /** 配置变更后立即生效（管理接口调用）。 */
    public void invalidate() {
        loadedAt = Instant.EPOCH;
    }

    private void refreshIfStale() {
        if (Instant.now().isBefore(loadedAt.plus(CACHE_TTL))) {
            return;
        }
        synchronized (this) {
            if (Instant.now().isBefore(loadedAt.plus(CACHE_TTL))) {
                return;
            }
            try {
                categoryCache = loadCategories();
                disabledCache = Set.copyOf(ruleConfigMapper.selectDisabledRuleIds());
                loadedAt = Instant.now();
                log.debug("规则元信息已刷新: 映射 {} 条, 关闭 {} 条", categoryCache.size(), disabledCache.size());
            } catch (RuntimeException ex) {
                // 刷新失败沿用旧缓存：规则元信息不是强一致数据，宁可旧一点也不让任务失败
                log.warn("刷新规则元信息失败，沿用旧缓存: {}", ex.getMessage());
                loadedAt = Instant.now();
            }
        }
    }

    private Map<String, RuleCategory> loadCategories() {
        List<RuleCategoryMapRow> rows = categoryMapMapper.selectList(null);
        Map<String, RuleCategory> map = new HashMap<>(rows.size() * 2);
        for (RuleCategoryMapRow row : rows) {
            map.put(row.getRuleId(), new RuleCategory(
                    DefectCategory.fromCode(row.getUnifiedCategory()),
                    Severity.fromCode(row.getDefaultSeverity()),
                    weightOf(row)));
        }
        return Map.copyOf(map);
    }

    /**
     * 静态规则的初始置信度。
     *
     * <p>PMD 规则误报率与其类别强相关：空指针/资源泄漏这类结构化规则几乎不误报，
     * 复杂度类规则则偏主观。这里按类别给一个先验，后续可用缺陷回流（E2）修正。
     */
    private double weightOf(RuleCategoryMapRow row) {
        return switch (DefectCategory.fromCode(row.getUnifiedCategory())) {
            case NULL_POINTER, RESOURCE_LEAK, INJECTION -> 0.9;
            case CONCURRENCY, LOGIC_ERROR -> 0.85;
            case PERFORMANCE -> 0.75;
            case MAINTAINABILITY -> 0.65;
            case CODE_STYLE -> 0.55;
        };
    }
}

package com.shenchao.codereview.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 规则 → 统一缺陷类别的映射行（只读配置，由迁移脚本维护）。 */
@Getter
@Setter
@TableName("rule_category_map")
public class RuleCategoryMapRow {

    @TableId(value = "rule_id", type = IdType.INPUT)
    private String ruleId;

    private String pmdCategory;

    private String unifiedCategory;

    private String defaultSeverity;

    private String note;
}

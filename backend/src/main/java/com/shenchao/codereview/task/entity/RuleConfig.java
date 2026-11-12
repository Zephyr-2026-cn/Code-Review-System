package com.shenchao.codereview.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 规则开关。
 *
 * <p>只存"与默认不同的配置"：没有记录的规则按默认开启，
 * 这样升级规则集时不需要补数据。
 */
@Getter
@Setter
@TableName("rule_config")
public class RuleConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ruleId;

    private String ruleset;

    private String name;

    private Boolean enabled;

    private String severityOverride;

    private Double weight;

    /** JSONB：规则参数覆盖。 */
    private String params;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}

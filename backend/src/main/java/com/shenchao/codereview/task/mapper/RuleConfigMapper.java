package com.shenchao.codereview.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shenchao.codereview.task.entity.RuleConfig;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 规则开关数据访问。 */
public interface RuleConfigMapper extends BaseMapper<RuleConfig> {

    /**
     * 被显式关闭的规则编码。
     *
     * <p>只取关闭项：没有记录 = 默认开启，升级规则集时无需补数据。
     */
    @Select("SELECT rule_id FROM rule_config WHERE enabled = FALSE")
    List<String> selectDisabledRuleIds();
}

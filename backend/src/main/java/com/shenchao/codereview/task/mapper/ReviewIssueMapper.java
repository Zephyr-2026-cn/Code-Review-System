package com.shenchao.codereview.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shenchao.codereview.task.entity.ReviewIssue;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 缺陷数据访问。批量插入是为了让"一次任务几百条缺陷"只走一次网络往返。 */
public interface ReviewIssueMapper extends BaseMapper<ReviewIssue> {

    @Insert("""
            <script>
            INSERT INTO review_issue (task_id, project_id, file_path, start_line, end_line, category, severity,
                                      title, description, suggestion, fix_snippet, source, rule_id, confidence,
                                      flag, status, evidence, fingerprint, created_at)
            VALUES
            <foreach collection="issues" item="it" separator=",">
                (#{it.taskId}, #{it.projectId}, #{it.filePath}, #{it.startLine}, #{it.endLine}, #{it.category},
                 #{it.severity}, #{it.title}, #{it.description}, #{it.suggestion}, #{it.fixSnippet}, #{it.source},
                 #{it.ruleId}, #{it.confidence}, #{it.flag}, #{it.status},
                 #{it.evidence}, #{it.fingerprint}, now())
            </foreach>
            </script>
            """)
    int insertBatch(@Param("issues") List<ReviewIssue> issues);

    // 三个统计维度写成固定 SQL，而不是拼接 ${dimension}：
    // MyBatis 的 ${} 是字符串直拼，即使调用方传常量也留下了注入面，
    // 评审时这是必被挑出来的写法。

    @Select("SELECT severity AS key, count(*) AS cnt FROM review_issue WHERE task_id = #{taskId} GROUP BY severity")
    List<Map<String, Object>> countBySeverity(@Param("taskId") Long taskId);

    @Select("SELECT category AS key, count(*) AS cnt FROM review_issue WHERE task_id = #{taskId} GROUP BY category")
    List<Map<String, Object>> countByCategory(@Param("taskId") Long taskId);

    @Select("SELECT source AS key, count(*) AS cnt FROM review_issue WHERE task_id = #{taskId} GROUP BY source")
    List<Map<String, Object>> countBySource(@Param("taskId") Long taskId);
}

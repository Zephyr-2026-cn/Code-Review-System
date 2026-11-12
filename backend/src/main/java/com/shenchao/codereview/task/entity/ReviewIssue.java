package com.shenchao.codereview.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 缺陷/问题。字段对齐蓝皮书 6.1 的统一缺陷模型。 */
@Getter
@Setter
@TableName("review_issue")
public class ReviewIssue {

    public static final String STATUS_OPEN = "OPEN";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Long projectId;

    private String filePath;

    private Integer startLine;

    private Integer endLine;

    private String category;

    private String severity;

    private String title;

    private String description;

    private String suggestion;

    private String fixSnippet;

    /** STATIC / LLM / FUSED。 */
    private String source;

    private String ruleId;

    private Double confidence;

    /** CROSS_VALIDATED / STATIC_ONLY / LLM_ONLY / LLM_RAG / CONFLICT。 */
    private String flag;

    private String status;

    /** JSONB：各引擎原始证据 + RAG 命中。 */
    private String evidence;

    private String fingerprint;

    private Long assignedTo;

    private OffsetDateTime createdAt;
}

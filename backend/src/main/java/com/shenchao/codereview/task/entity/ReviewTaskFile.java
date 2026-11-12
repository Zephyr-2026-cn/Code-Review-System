package com.shenchao.codereview.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 任务扫描的文件清单。
 *
 * <p>记录"哪些文件进了分析、哪些被跳过、跳过的原因"，
 * 是为了回答答辩时最容易追问的一句："你说扫了全量，凭什么证明？"
 */
@Getter
@Setter
@TableName("review_task_file")
public class ReviewTaskFile {

    public static final String CHANGE_SCANNED = "SCANNED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private String filePath;

    /** SCANNED / ADDED / MODIFIED / DELETED / RENAMED。 */
    private String changeType;

    private String language;

    private Integer additions;

    private Integer deletions;

    private Boolean analyzed;

    private String skipReason;

    private Integer defectCount;

    private OffsetDateTime createdAt;
}

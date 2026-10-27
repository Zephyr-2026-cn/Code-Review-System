package com.shenchao.codereview.project.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 被审查项目（一个项目对应一份仓库绑定）。
 *
 * <p>{@code code} 同时是本地工作目录名，只允许 {@code [a-z0-9_-]}，
 * 由 {@code GitUrlValidator#deriveRepoKey} 从仓库地址推导并做白名单过滤。
 */
@Getter
@Setter
@TableName("repo_project")
public class RepoProject {

    public static final String SYNC_UNBOUND = "UNBOUND";
    public static final String SYNC_CLONING = "CLONING";
    public static final String SYNC_READY = "READY";
    public static final String SYNC_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 项目编码，兼作本地目录名，全局唯一（逻辑删除维度）。 */
    private String code;

    private String description;

    /** 主语言，当前仅 JAVA 走完整双引擎，其余语言由 StaticAnalyzer SPI 决定能力。 */
    private String language;

    private String repoUrl;

    private String repoBranch;

    private Long credentialId;

    /** UNBOUND / CLONING / READY / FAILED。 */
    private String syncStatus;

    private OffsetDateTime lastSyncedAt;

    private String lastCommitId;

    private String lastCommitMessage;

    private OffsetDateTime lastCommitAt;

    /** 最近一次同步失败原因，成功后清空（长度上限 512）。 */
    private String lastError;

    /** 项目归属人，用于"仅所有者可改"的水平权限判断。 */
    private Long ownerId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    public boolean isBound() {
        return repoUrl != null && !repoUrl.isBlank();
    }
}

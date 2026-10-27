package com.shenchao.codereview.project.dto;

import com.shenchao.codereview.project.entity.RepoProject;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 项目视图。
 *
 * <p>{@code credentialName} 只给名称，不给用户名与密文：
 * 前端在绑定表单里只需要展示"用的是哪份凭据"。
 */
@Schema(description = "项目信息")
public record ProjectVO(
        @Schema(description = "项目 ID") Long id,
        @Schema(description = "项目名称") String name,
        @Schema(description = "项目编码（兼本地目录名）") String code,
        @Schema(description = "描述") String description,
        @Schema(description = "主语言") String language,
        @Schema(description = "仓库地址") String repoUrl,
        @Schema(description = "分支") String repoBranch,
        @Schema(description = "凭据 ID") Long credentialId,
        @Schema(description = "凭据名称") String credentialName,
        @Schema(description = "同步状态：UNBOUND / CLONING / READY / FAILED") String syncStatus,
        @Schema(description = "最近同步时间") OffsetDateTime lastSyncedAt,
        @Schema(description = "当前提交短 ID") String lastCommitId,
        @Schema(description = "当前提交说明") String lastCommitMessage,
        @Schema(description = "提交时间") OffsetDateTime lastCommitAt,
        @Schema(description = "最近失败原因") String lastError,
        @Schema(description = "归属人 ID") Long ownerId,
        @Schema(description = "创建时间") OffsetDateTime createdAt
) {

    public static ProjectVO from(RepoProject project, String credentialName) {
        return new ProjectVO(
                project.getId(),
                project.getName(),
                project.getCode(),
                project.getDescription(),
                project.getLanguage(),
                project.getRepoUrl(),
                project.getRepoBranch(),
                project.getCredentialId(),
                credentialName,
                project.getSyncStatus(),
                project.getLastSyncedAt(),
                project.getLastCommitId(),
                project.getLastCommitMessage(),
                project.getLastCommitAt(),
                project.getLastError(),
                project.getOwnerId(),
                project.getCreatedAt());
    }
}

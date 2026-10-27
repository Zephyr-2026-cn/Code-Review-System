package com.shenchao.codereview.project.dto;

import com.shenchao.codereview.project.entity.RepoCredential;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 凭据视图。
 *
 * <p><b>刻意不含 secretCipher 与任何摘要</b>：即使密文不可逆，
 * 暴露给前端也会扩大泄漏面（拿到密文即可离线爆破弱令牌）。
 */
@Schema(description = "Git 凭据（不含令牌）")
public record CredentialVO(
        @Schema(description = "凭据 ID") Long id,
        @Schema(description = "名称") String name,
        @Schema(description = "用户名") String username,
        @Schema(description = "归属人 ID") Long ownerId,
        @Schema(description = "创建时间") OffsetDateTime createdAt
) {

    public static CredentialVO from(RepoCredential credential) {
        return new CredentialVO(credential.getId(), credential.getName(), credential.getUsername(),
                credential.getOwnerId(), credential.getCreatedAt());
    }
}

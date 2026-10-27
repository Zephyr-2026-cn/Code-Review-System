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
 * Git 访问凭据。
 *
 * <p>{@code secretCipher} 是 AES-256-GCM 密文，<b>永远不出现在任何响应体中</b>：
 * 列表接口只返回名称与用户名，编辑时也必须重新录入令牌（不做"回显占位符"这种
 * 看似方便、实则等于把密文暴露给前端的设计）。
 */
@Getter
@Setter
@TableName("repo_credential")
public class RepoCredential {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** Git 用户名；GitHub 使用 Personal Access Token 时通常填任意非空字符串。 */
    private String username;

    /** AES-256-GCM 密文，禁止直出。 */
    private String secretCipher;

    private Long ownerId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

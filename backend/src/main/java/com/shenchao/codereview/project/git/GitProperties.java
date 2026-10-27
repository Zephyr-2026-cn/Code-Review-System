package com.shenchao.codereview.project.git;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Git 接入配置。
 *
 * @param workspace            本地克隆根目录，每个项目一个子目录（目录名 = 项目编码）
 * @param cloneTimeout         单次克隆/拉取超时，防止大仓库把线程池占满
 * @param maxRepoSizeMb        克隆后工作目录体积上限，超限直接判失败并清理
 * @param allowHttp            是否允许 http:// 明文仓库地址；默认禁止，避免凭据在网络上裸奔
 * @param allowPrivateNetwork  是否允许克隆内网地址（SSRF 防护开关，仅本地演示时打开）
 * @param credentialKey        凭据加密密钥（Base64，解码后必须 32 字节）；未配置则凭据功能不可用
 */
@ConfigurationProperties(prefix = "app.git")
public record GitProperties(
        Path workspace,
        Duration cloneTimeout,
        Integer maxRepoSizeMb,
        Boolean allowHttp,
        Boolean allowPrivateNetwork,
        String credentialKey
) {

    public GitProperties {
        workspace = workspace == null ? Path.of("./data/repos") : workspace;
        cloneTimeout = cloneTimeout == null ? Duration.ofSeconds(120) : cloneTimeout;
        maxRepoSizeMb = maxRepoSizeMb == null ? 500 : maxRepoSizeMb;
        allowHttp = allowHttp != null && allowHttp;
        allowPrivateNetwork = allowPrivateNetwork != null && allowPrivateNetwork;
        credentialKey = credentialKey == null ? "" : credentialKey;
    }

    /** 单次克隆允许的最大字节数。 */
    public long maxRepoSizeBytes() {
        return (long) maxRepoSizeMb * 1024 * 1024;
    }
}

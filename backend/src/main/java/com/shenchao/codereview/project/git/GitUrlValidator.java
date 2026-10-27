package com.shenchao.codereview.project.git;

import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 仓库地址校验与规范化。
 *
 * <p>服务端要替用户去访问任意 URL，因此这里本质上是一道 <b>SSRF 防线</b>。四类拦截：
 * <ol>
 *   <li><b>协议白名单</b>：只允许 https（可选 http）。{@code file://} 会让攻击者读取服务器任意文件，
 *       {@code ssh://}/{@code git://} 无法用统一凭据方式管理</li>
 *   <li><b>禁止 URL 内嵌凭据</b>：{@code https://user:token@host/...} 这种写法会被完整写进日志、
 *       审计记录与异常堆栈，等于主动泄漏令牌</li>
 *   <li><b>禁止内网 / 回环 / 云元数据地址</b>：{@code 169.254.169.254} 是各云厂商的实例元数据端点，
 *       一旦被当作"仓库地址"克隆，等于把服务器凭证交给调用方</li>
 *   <li><b>长度与字符约束</b>：超长 URL 与奇怪的转义序列通常是探测行为</li>
 * </ol>
 *
 * <p><b>已知残余风险</b>：这里只校验字面量 IP，攻击者可用域名解析到内网地址（DNS Rebinding）绕过。
 * 生产环境的正确做法是在 TCP 连接建立时校验目标地址（自定义 SocketFactory），
 * 本项目作为毕设保留该限制并在文档中标注，答辩时可作为"安全设计的边界意识"来讲。
 */
@Slf4j
@Component
public class GitUrlValidator {

    private static final int MAX_URL_LENGTH = 512;

    /** 允许的主机名格式：域名、IPv4 字面量、[IPv6]。 */
    private static final Pattern HOST_PATTERN =
            Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$");

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "localhost.localdomain", "ip6-localhost", "metadata",
            "metadata.google.internal", "instance-data");

    private final GitProperties properties;

    public GitUrlValidator(GitProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验并返回规范化后的地址。
     *
     * <p>规范化内容：去空白、去末尾斜杠、去 fragment、去 query、主机名小写。
     * 规范化是为了让"同一仓库的两种写法"落到同一条唯一索引上，避免重复接入。
     *
     * @throws BizException 不合法时抛出，message 直接面向用户且不暴露内部判断细节
     */
    public String normalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BizException(ErrorCode.REPO_URL_INVALID, "仓库地址不能为空");
        }
        String trimmed = rawUrl.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new BizException(ErrorCode.REPO_URL_INVALID, "仓库地址长度不能超过 " + MAX_URL_LENGTH);
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException ex) {
            throw new BizException(ErrorCode.REPO_URL_INVALID, "仓库地址格式不正确");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new BizException(ErrorCode.REPO_URL_INVALID,
                    "仅支持 https 协议（如需 http，请由管理员开启 app.git.allow-http）");
        }
        if ("http".equals(scheme) && !properties.allowHttp()) {
            throw new BizException(ErrorCode.REPO_URL_INVALID,
                    "当前未开启明文 http 协议，请使用 https 地址");
        }
        if (uri.getUserInfo() != null) {
            // 凭据必须走凭据管理功能加密存储，不能出现在 URL 里
            throw new BizException(ErrorCode.REPO_URL_INVALID,
                    "仓库地址不能包含用户名或密码，请使用「凭据」功能单独配置");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BizException(ErrorCode.REPO_URL_INVALID, "仓库地址缺少主机名");
        }
        host = host.toLowerCase(Locale.ROOT);
        assertHostAllowed(host);

        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.isBlank() || "/".equals(path)) {
            throw new BizException(ErrorCode.REPO_URL_INVALID, "仓库地址缺少仓库路径，例如 /owner/repo.git");
        }
        if (path.contains("..")) {
            throw new BizException(ErrorCode.REPO_URL_INVALID, "仓库地址包含非法路径片段");
        }

        String normalizedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int port = uri.getPort();
        String portPart = port > 0 ? ":" + port : "";
        return scheme + "://" + host + portPart + normalizedPath;
    }

    /**
     * 由仓库地址推导本地目录名。
     *
     * <p>这是<b>路径穿越防护</b>的关键位置：目录名最终会参与文件系统路径拼接，
     * 若直接把 URL 末段拿来做目录名，{@code ../../etc} 这类输入会导致越权写入。
     * 处理方式是白名单字符过滤 + 长度截断 + 空值兜底，任何非法字符一律替换为下划线。
     */
    public String deriveRepoKey(String normalizedUrl) {
        String path = URI.create(normalizedUrl).getPath();
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        if (lastSegment.endsWith(".git")) {
            lastSegment = lastSegment.substring(0, lastSegment.length() - 4);
        }
        String sanitized = lastSegment.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "-")
                // 连续点必须收敛：目录名里出现 ".." 就等于把路径穿越的原材料写进了文件系统
                .replaceAll("\\.{2,}", ".")
                .replaceAll("^[.-]+", "")
                .replaceAll("-{2,}", "-")
                .replaceAll("[.-]+$", "");
        if (sanitized.isBlank()) {
            sanitized = "repo";
        }
        return sanitized.length() > 48 ? sanitized.substring(0, 48) : sanitized;
    }

    private void assertHostAllowed(String host) {
        // IPv6 字面量在 URI 里带方括号，判定前必须剥掉，否则 "[::1]" 会被当成普通域名放行
        String bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;

        if (BLOCKED_HOSTS.contains(bare) || isPrivateOrReserved(bare)) {
            rejectPrivate(host);
            return;
        }
        // 域名解析到内网无法在字符串层面发现，这里只拦字面量 IP
        boolean ipv6Literal = bare.contains(":");
        if (!ipv6Literal && !HOST_PATTERN.matcher(bare).matches()) {
            throw new BizException(ErrorCode.REPO_URL_INVALID, "仓库地址主机名格式不正确");
        }
    }

    /**
     * 命中内网/保留地址时的处理。
     *
     * <p>配置了 {@code allow-private-network=true} 时只告警放行（本地演示需要），
     * 否则统一拒绝——注意这里必须真正抛出异常，而不是返回一个异常对象再 {@code throw}，
     * 后者在放行分支会抛出 {@code null} 变成 NPE。
     */
    private void rejectPrivate(String host) {
        if (properties.allowPrivateNetwork()) {
            log.warn("允许访问内网仓库地址（app.git.allow-private-network=true）: host={}", host);
            return;
        }
        log.warn("拦截内网/保留地址的仓库接入请求: host={}", host);
        throw new BizException(ErrorCode.REPO_URL_INVALID, "不允许访问内网或回环地址");
    }

    /** 判断是否为私有/保留/回环地址（仅字面量判断，不做 DNS 解析）。 */
    static boolean isPrivateOrReserved(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if ("::1".equals(lower) || "0:0:0:0:0:0:0:1".equals(lower)) {
            return true;
        }
        if (lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80")) {
            return true;
        }
        String[] parts = lower.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) {
                return false;
            }
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ex) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }
        int first = octets[0];
        int second = octets[1];
        if (first == 0 || first == 10 || first == 127) {
            return true;
        }
        if (first == 169 && second == 254) {
            // 链路本地，含 169.254.169.254 云元数据端点
            return true;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return true;
        }
        if (first == 192 && second == 168) {
            return true;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            // 运营商级 NAT
            return true;
        }
        return first >= 224;
    }
}

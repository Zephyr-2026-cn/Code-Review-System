package com.shenchao.codereview.project;

import com.shenchao.codereview.common.exception.BizException;
import com.shenchao.codereview.project.git.GitProperties;
import com.shenchao.codereview.project.git.GitUrlValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 仓库地址校验测试。
 *
 * <p>这个类是 SSRF 防线的单元测试，重点覆盖三类攻击输入：
 * 内网/回环地址、云元数据地址、URL 内嵌凭据。
 */
@DisplayName("Git 仓库地址校验")
class GitUrlValidatorTest {

    private static GitUrlValidator validator(boolean allowHttp, boolean allowPrivate) {
        return new GitUrlValidator(new GitProperties(
                Path.of("./data/repos"), Duration.ofSeconds(30), 100, allowHttp, allowPrivate, ""));
    }

    private final GitUrlValidator strict = validator(false, false);

    // ---------------- 合法地址 ----------------

    @Test
    @DisplayName("https 地址被接受并规范化")
    void acceptsHttpsAndNormalizes() {
        String normalized = strict.normalize("  https://GitHub.com/Zephyr-2026-cn/Code-Review-System.git/  ");
        assertThat(normalized).isEqualTo("https://github.com/Zephyr-2026-cn/Code-Review-System.git");
    }

    @Test
    @DisplayName("去掉 query 与 fragment，避免同仓库被当成两条记录")
    void stripsQueryAndFragment() {
        String normalized = strict.normalize("https://github.com/owner/repo.git?ref=main#readme");
        assertThat(normalized).isEqualTo("https://github.com/owner/repo.git");
    }

    @Test
    @DisplayName("保留自定义端口")
    void keepsCustomPort() {
        assertThat(strict.normalize("https://git.example.com:8443/owner/repo.git"))
                .isEqualTo("https://git.example.com:8443/owner/repo.git");
    }

    // ---------------- 协议白名单 ----------------

    @ParameterizedTest
    @DisplayName("非 https/http 协议一律拒绝（file/ssh/git）")
    @ValueSource(strings = {
            "file:///etc/passwd",
            "ssh://git@github.com/owner/repo.git",
            "git://github.com/owner/repo.git",
            "ftp://github.com/owner/repo.git"
    })
    void rejectsNonHttpSchemes(String url) {
        assertThatThrownBy(() -> strict.normalize(url)).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("默认拒绝 http 明文地址，开启后放行")
    void httpDependsOnConfig() {
        assertThatThrownBy(() -> strict.normalize("http://github.com/owner/repo.git"))
                .isInstanceOf(BizException.class);
        assertThat(validator(true, false).normalize("http://github.com/owner/repo.git"))
                .isEqualTo("http://github.com/owner/repo.git");
    }

    // ---------------- 凭据泄漏 ----------------

    @Test
    @DisplayName("URL 内嵌用户名密码被拒绝，避免令牌进日志")
    void rejectsUserInfoInUrl() {
        assertThatThrownBy(() -> strict.normalize("https://user:ghp_secret@github.com/owner/repo.git"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("凭据");
    }

    // ---------------- SSRF ----------------

    @ParameterizedTest
    @DisplayName("内网、回环、云元数据地址被拒绝")
    @ValueSource(strings = {
            "https://localhost/owner/repo.git",
            "https://127.0.0.1/owner/repo.git",
            "https://10.0.0.8/owner/repo.git",
            "https://192.168.1.10/owner/repo.git",
            "https://172.16.0.5/owner/repo.git",
            "https://169.254.169.254/latest/meta-data.git",
            "https://0.0.0.0/owner/repo.git",
            "https://[::1]/owner/repo.git"
    })
    void rejectsInternalAddresses(String url) {
        assertThatThrownBy(() -> strict.normalize(url))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("内网");
    }

    @Test
    @DisplayName("显式开启 allow-private-network 后放行内网地址（仅本地演示）")
    void allowsPrivateWhenConfigured() {
        assertThat(validator(false, true).normalize("https://127.0.0.1/owner/repo.git"))
                .isEqualTo("https://127.0.0.1/owner/repo.git");
    }

    // ---------------- 结构校验 ----------------

    @Test
    @DisplayName("缺少仓库路径时拒绝")
    void rejectsMissingPath() {
        assertThatThrownBy(() -> strict.normalize("https://github.com")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> strict.normalize("https://github.com/")).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("包含 .. 的路径被拒绝")
    void rejectsDotDotPath() {
        assertThatThrownBy(() -> strict.normalize("https://github.com/owner/../../etc/passwd"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("超长地址被拒绝")
    void rejectsTooLongUrl() {
        String longUrl = "https://github.com/owner/" + "a".repeat(600) + ".git";
        assertThatThrownBy(() -> strict.normalize(longUrl)).isInstanceOf(BizException.class);
    }

    // ---------------- 目录名推导 ----------------

    @Test
    @DisplayName("从地址推导目录名，去掉 .git 后缀")
    void derivesRepoKey() {
        assertThat(strict.deriveRepoKey("https://github.com/owner/Code-Review-System.git"))
                .isEqualTo("code-review-system");
    }

    @Test
    @DisplayName("含空格等非法字符被替换为连字符")
    void sanitizesIllegalCharacters() {
        assertThat(strict.deriveRepoKey("https://github.com/owner/my%20repo.git"))
                .isEqualTo("my-repo");
    }

    @Test
    @DisplayName("前导点与连续连字符被清理，防止生成隐藏目录或越界路径")
    void stripsLeadingDotsAndRepeatedDashes() {
        String key = strict.deriveRepoKey("https://github.com/owner/--Weird..Name.git");
        assertThat(key).isEqualTo("weird.name");
        assertThat(key).doesNotContain("/", "\\");
    }

    @Test
    @DisplayName("无法推导时回退为 repo，绝不返回空串")
    void fallsBackToDefaultKey() {
        assertThat(strict.deriveRepoKey("https://github.com/owner/...")).isEqualTo("repo");
    }
}

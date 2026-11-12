package com.shenchao.codereview.task.pipeline;

import com.shenchao.codereview.task.entity.ReviewTaskFile;
import com.shenchao.codereview.task.queue.ReviewProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作目录扫描测试。
 *
 * <p>重点验证三类边界：构建产物目录必须剪枝、测试目录默认排除、大文件跳过而不是读进内存。
 */
class WorkspaceScannerTest {

    private WorkspaceScanner scanner(int maxFiles, int maxFileSizeKb, boolean includeTests) {
        return new WorkspaceScanner(new ReviewProperties(
                true, 2, "review:queue", "review-workers",
                Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofSeconds(30),
                maxFiles, maxFileSizeKb, includeTests, 500));
    }

    @Test
    @DisplayName("跳过 .git/target 与测试目录，只保留主源码")
    void skipsBuildAndTestDirectories(@TempDir Path workspace) throws IOException {
        write(workspace.resolve("src/main/java/demo/Service.java"), "class Service {}");
        write(workspace.resolve("src/test/java/demo/ServiceTest.java"), "class ServiceTest {}");
        write(workspace.resolve("target/generated/Gen.java"), "class Gen {}");
        write(workspace.resolve(".git/objects/x.java"), "class X {}");
        write(workspace.resolve("README.md"), "# readme");

        WorkspaceScanner.ScanResult result = scanner(100, 1024, false).scan(workspace, 1L);

        assertThat(result.files()).extracting(WorkspaceScanner.ScannedFile::relativePath)
                .containsExactly("src/main/java/demo/Service.java");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("includeTests 打开后测试源码也纳入扫描")
    void includesTestsWhenEnabled(@TempDir Path workspace) throws IOException {
        write(workspace.resolve("src/main/java/demo/Service.java"), "class Service {}");
        write(workspace.resolve("src/test/java/demo/ServiceTest.java"), "class ServiceTest {}");

        WorkspaceScanner.ScanResult result = scanner(100, 1024, true).scan(workspace, 1L);

        assertThat(result.files()).hasSize(2);
    }

    @Test
    @DisplayName("超过体积上限的文件被跳过并记录原因")
    void skipsOversizedFiles(@TempDir Path workspace) throws IOException {
        write(workspace.resolve("Big.java"), "x".repeat(4 * 1024));
        write(workspace.resolve("Small.java"), "class Small {}");

        WorkspaceScanner.ScanResult result = scanner(100, 1, false).scan(workspace, 1L);

        assertThat(result.files()).extracting(WorkspaceScanner.ScannedFile::relativePath)
                .containsExactly("Small.java");
        assertThat(result.skipped()).extracting(ReviewTaskFile::getFilePath).containsExactly("Big.java");
        assertThat(result.skipped().get(0).getSkipReason()).contains("KB");
    }

    @Test
    @DisplayName("文件数达到上限即截断，并返回 truncated 标记")
    void truncatesAtMaxFiles(@TempDir Path workspace) throws IOException {
        write(workspace.resolve("A.java"), "class A {}");
        write(workspace.resolve("B.java"), "class B {}");

        WorkspaceScanner.ScanResult result = scanner(1, 1024, false).scan(workspace, 1L);

        assertThat(result.truncated()).isTrue();
        assertThat(result.files()).hasSize(1);
    }

    @Test
    @DisplayName("扫描结果按路径排序，保证两次运行顺序一致")
    void sortsDeterministically(@TempDir Path workspace) throws IOException {
        write(workspace.resolve("src/main/java/demo/B.java"), "class B {}");
        write(workspace.resolve("src/main/java/demo/A.java"), "class A {}");

        WorkspaceScanner.ScanResult result = scanner(100, 1024, false).scan(workspace, 1L);

        List<String> paths = result.files().stream().map(WorkspaceScanner.ScannedFile::relativePath).toList();
        assertThat(paths).isSorted();
    }

    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}

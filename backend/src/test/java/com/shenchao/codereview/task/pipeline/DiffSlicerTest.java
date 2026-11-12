package com.shenchao.codereview.task.pipeline;

import com.shenchao.codereview.common.exception.BizException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("增量切片")
class DiffSlicerTest {

    @TempDir
    Path workspace;

    private final DiffSlicer slicer = new DiffSlicer();
    private Git git;
    private RevCommit first;
    private RevCommit second;

    private static PersonIdent identity() {
        return new PersonIdent("tester", "tester@example.com");
    }

    @BeforeEach
    void setUp() throws Exception {
        git = Git.init().setDirectory(workspace.toFile()).call();
        Files.writeString(workspace.resolve("A.java"), "class A {}\n");
        Files.writeString(workspace.resolve("B.java"), "class B {}\n");
        Files.writeString(workspace.resolve("README.md"), "# doc\n");
        first = commit("first");

        Files.writeString(workspace.resolve("A.java"), "class A {\n    int x;\n}\n");
        Files.writeString(workspace.resolve("C.java"), "class C {}\n");
        Files.delete(workspace.resolve("B.java"));
        git.rm().addFilepattern("B.java").call();
        second = commit("second");
    }

    @AfterEach
    void tearDown() {
        if (git != null) {
            git.close();
        }
    }

    private RevCommit commit(String message) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(message).setAuthor(identity()).setCommitter(identity()).call();
    }

    @Test
    @DisplayName("识别新增与修改，忽略删除与非源码文件")
    void detectsAddedAndModified() {
        DiffSlicer.DiffResult result = slicer.slice(workspace, first.getName(), second.getName(), 100);

        List<String> paths = result.changes().stream().map(DiffSlicer.FileChange::path).toList();
        assertThat(paths).containsExactlyInAnyOrder("A.java", "C.java");
        assertThat(result.changes()).noneMatch(change -> change.path().equals("B.java"));

        DiffSlicer.FileChange added = result.changes().stream()
                .filter(change -> change.path().equals("C.java")).findFirst().orElseThrow();
        assertThat(added.changeType()).isEqualTo(DiffSlicer.TYPE_ADDED);

        DiffSlicer.FileChange modified = result.changes().stream()
                .filter(change -> change.path().equals("A.java")).findFirst().orElseThrow();
        assertThat(modified.changeType()).isEqualTo(DiffSlicer.TYPE_MODIFIED);
        // 原来是单行 class A {}，改成三行方法体：删 1 行、增 3 行
        assertThat(modified.additions()).isEqualTo(3);
        assertThat(modified.deletions()).isEqualTo(1);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("同一提交比较没有变更")
    void sameCommitHasNoChanges() {
        DiffSlicer.DiffResult result = slicer.slice(workspace, second.getName(), second.getName(), 100);

        assertThat(result.changes()).isEmpty();
        assertThat(result.additions()).isZero();
    }

    @Test
    @DisplayName("变更文件超过上限时截断并置标记")
    void truncatesWhenTooManyFiles() {
        DiffSlicer.DiffResult result = slicer.slice(workspace, first.getName(), second.getName(), 1);

        assertThat(result.changes()).hasSize(1);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    @DisplayName("不存在的提交给出可读错误，而不是 NPE")
    void unknownRevisionFailsClearly() {
        assertThatThrownBy(() -> slicer.slice(workspace, "deadbeefdeadbeef", second.getName(), 100))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("基线提交");
    }

    @Test
    @DisplayName("工作目录不是 git 仓库时明确报错")
    void missingGitDirFailsClearly() throws IOException {
        Path empty = Files.createTempDirectory("no-git");
        assertThatThrownBy(() -> slicer.slice(empty, first.getName(), second.getName(), 100))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("工作目录");
        Files.deleteIfExists(empty);
    }

    @Test
    @DisplayName("单文件差异带行号与增删类型")
    void fileDiffCarriesLineNumbersAndTypes() {
        DiffSlicer.FileDiff diff = slicer.fileDiff(workspace, first.getName(), second.getName(), "A.java", 1, 100, 0);

        assertThat(diff).isNotNull();
        assertThat(diff.hunks()).isNotEmpty();
        assertThat(diff.truncated()).isFalse();

        List<DiffSlicer.DiffLine> lines = diff.hunks().get(0).lines();
        // 旧文件只有一行 class A {}，被替换成三行
        assertThat(lines).anyMatch(line -> DiffSlicer.LINE_DELETE.equals(line.type())
                && "class A {}".equals(line.text()) && line.oldLine() == 1 && line.newLine() == 0);
        assertThat(lines).anyMatch(line -> DiffSlicer.LINE_ADD.equals(line.type())
                && "    int x;".equals(line.text()) && line.oldLine() == 0);
        // 新增行必须带新文件行号，报告页靠它做"滚到行"
        DiffSlicer.DiffLine added = lines.stream()
                .filter(line -> DiffSlicer.LINE_ADD.equals(line.type()) && "    int x;".equals(line.text()))
                .findFirst().orElseThrow();
        assertThat(added.newLine()).isEqualTo(2);
    }

    @Test
    @DisplayName("新增文件在差异里全部标为 ADD")
    void addedFileIsAllAdditions() {
        DiffSlicer.FileDiff diff = slicer.fileDiff(workspace, first.getName(), second.getName(), "C.java", 2, 100, 0);

        assertThat(diff).isNotNull();
        assertThat(diff.hunks()).hasSize(1);
        assertThat(diff.hunks().get(0).lines())
                .allMatch(line -> DiffSlicer.LINE_ADD.equals(line.type()))
                .anyMatch(line -> "class C {}".equals(line.text()) && line.newLine() == 1);
    }

    @Test
    @DisplayName("未变更的文件返回 null，由调用方退回只看源码")
    void unchangedFileReturnsNull() {
        assertThat(slicer.fileDiff(workspace, first.getName(), second.getName(), "README.md", 3, 100, 0))
                .isNull();
        assertThat(slicer.fileDiff(workspace, first.getName(), second.getName(), "Nope.java", 3, 100, 0))
                .isNull();
    }

    @Test
    @DisplayName("行数上限命中时置 truncated，不无限输出")
    void diffRespectsLineLimit() {
        DiffSlicer.FileDiff diff = slicer.fileDiff(workspace, first.getName(), second.getName(), "A.java", 3, 1, 0);

        assertThat(diff).isNotNull();
        assertThat(diff.truncated()).isTrue();
        int total = diff.hunks().stream().mapToInt(hunk -> hunk.lines().size()).sum();
        assertThat(total).isEqualTo(1);
    }

    @Test
    @DisplayName("聚焦行只返回相关差异块，避免缺陷位置落在输出之外")
    void focusKeepsOnlyRelevantHunk() throws Exception {
        String baseContent = lines(1, 200);
        Files.writeString(workspace.resolve("Long.java"), baseContent);
        RevCommit base = commit("long-base");

        String headContent = baseContent
                .replace("line 5\n", "line 5 changed\n")
                .replace("line 150\n", "line 150 changed\n");
        Files.writeString(workspace.resolve("Long.java"), headContent);
        RevCommit head = commit("long-head");

        DiffSlicer.FileDiff all = slicer.fileDiff(workspace, base.getName(), head.getName(),
                "Long.java", 2, 500, 0);
        assertThat(all.hunks()).hasSize(2);

        DiffSlicer.FileDiff focused = slicer.fileDiff(workspace, base.getName(), head.getName(),
                "Long.java", 2, 500, 151);
        assertThat(focused.hunks()).hasSize(1);
        assertThat(focused.hunks().get(0).lines())
                .anyMatch(line -> "line 150 changed".equals(line.text()));
    }

    @Test
    @DisplayName("整文件新增时以缺陷行为中心开窗，缺陷不会落在窗口外")
    void focusWindowIncludesIssueLineInLargeHunk() throws Exception {
        Files.writeString(workspace.resolve("Big.java"), lines(1, 500));
        RevCommit big = commit("big");

        // 相对 first 提交，Big.java 是新增文件：整个 500 行落在同一个差异块里
        DiffSlicer.FileDiff diff = slicer.fileDiff(workspace, first.getName(), big.getName(),
                "Big.java", 3, 100, 400);

        int total = diff.hunks().stream().mapToInt(hunk -> hunk.lines().size()).sum();
        assertThat(total).isEqualTo(100);
        assertThat(diff.truncated()).isTrue();
        assertThat(diff.hunks().get(0).lines())
                .anyMatch(line -> line.newLine() == 400 && "line 400".equals(line.text()));
    }

    private static String lines(int from, int to) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i <= to; i++) {
            builder.append("line ").append(i).append('\n');
        }
        return builder.toString();
    }
}

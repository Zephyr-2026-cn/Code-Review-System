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
}

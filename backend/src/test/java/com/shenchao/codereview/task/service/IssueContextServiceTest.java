package com.shenchao.codereview.task.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchao.codereview.common.exception.BizException;
import com.shenchao.codereview.project.entity.RepoProject;
import com.shenchao.codereview.project.git.GitRepositoryService;
import com.shenchao.codereview.project.mapper.RepoProjectMapper;
import com.shenchao.codereview.task.dto.IssueContextVO;
import com.shenchao.codereview.task.entity.ReviewIssue;
import com.shenchao.codereview.task.entity.ReviewTask;
import com.shenchao.codereview.task.mapper.ReviewIssueMapper;
import com.shenchao.codereview.task.mapper.ReviewTaskMapper;
import com.shenchao.codereview.task.pipeline.DiffSlicer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("缺陷上下文")
class IssueContextServiceTest {

    @TempDir
    Path workspace;

    private ReviewIssueMapper issueMapper;
    private ReviewTaskMapper taskMapper;
    private RepoProjectMapper projectMapper;
    private GitRepositoryService gitService;
    private DiffSlicer diffSlicer;
    private IssueContextService service;

    private ReviewIssue issue;
    private ReviewTask task;

    @BeforeEach
    void setUp() throws IOException {
        issueMapper = mock(ReviewIssueMapper.class);
        taskMapper = mock(ReviewTaskMapper.class);
        projectMapper = mock(RepoProjectMapper.class);
        gitService = mock(GitRepositoryService.class);
        diffSlicer = mock(DiffSlicer.class);
        service = new IssueContextService(issueMapper, taskMapper, projectMapper,
                gitService, diffSlicer, new ObjectMapper());

        // 服务只检查 .git 目录是否存在，不需要真仓库
        Files.createDirectory(workspace.resolve(".git"));

        issue = new ReviewIssue();
        issue.setId(1L);
        issue.setTaskId(10L);
        issue.setProjectId(20L);
        issue.setFilePath("src/A.java");
        issue.setStartLine(100);
        issue.setEndLine(101);
        issue.setSeverity("MAJOR");
        issue.setCategory("LOGIC_ERROR");
        issue.setStatus("OPEN");
        issue.setEvidence("{\"fusion\":\"CROSS_VALIDATED\",\"staticRule\":\"NullAssignment\"}");

        task = new ReviewTask();
        task.setId(10L);
        task.setProjectId(20L);
        task.setReviewMode("FULL");
        task.setHeadCommit("head123");
    }

    private void stubProject() {
        RepoProject project = new RepoProject();
        project.setId(20L);
        project.setCode("demo");
        when(projectMapper.selectById(20L)).thenReturn(project);
        when(gitService.resolveWorkDir("demo")).thenReturn(workspace);
    }

    private void writeSource(int lines) throws IOException {
        Path dir = Files.createDirectories(workspace.resolve("src"));
        String content = IntStream.rangeClosed(1, lines)
                .mapToObj(i -> "line-" + i)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        Files.writeString(dir.resolve("A.java"), content + "\n");
    }

    @Test
    @DisplayName("缺陷不存在时抛 404，而不是返回空壳")
    void missingIssueFails() {
        when(issueMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.context(99L, 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("问题不存在");
    }

    @Test
    @DisplayName("按缺陷行号取上下文窗口，行号与内容对齐")
    void windowIsCenteredOnIssueLine() throws IOException {
        writeSource(200);
        when(issueMapper.selectById(1L)).thenReturn(issue);
        when(taskMapper.selectById(10L)).thenReturn(task);
        stubProject();

        IssueContextVO context = service.context(1L, 5);

        assertThat(context.totalLines()).isEqualTo(200);
        assertThat(context.windowStart()).isEqualTo(95);
        assertThat(context.windowEnd()).isEqualTo(106);
        assertThat(context.lines()).hasSize(12);
        // 第 100 行必须真的是文件里的第 100 行，差一会让"滚到行"落错位置
        assertThat(context.lines().stream()
                .filter(line -> line.no() == 100).findFirst().orElseThrow().text())
                .isEqualTo("line-100");
        assertThat(context.language()).isEqualTo("java");
        assertThat(context.notice()).isNull();
    }

    @Test
    @DisplayName("缺陷行号超出文件时降级提示，不抛异常")
    void lineBeyondFileDegrades() throws IOException {
        writeSource(50);
        issue.setStartLine(500);
        issue.setEndLine(500);
        when(issueMapper.selectById(1L)).thenReturn(issue);
        when(taskMapper.selectById(10L)).thenReturn(task);
        stubProject();

        IssueContextVO context = service.context(1L, 5);

        assertThat(context.lines()).isNotEmpty();
        assertThat(context.notice()).contains("超出文件当前行数");
        assertThat(context.windowEnd()).isEqualTo(50);
    }

    @Test
    @DisplayName("路径穿越被拒绝，不读取仓库外文件")
    void pathTraversalIsRejected() throws IOException {
        Files.writeString(workspace.getParent().resolve("secret.txt"), "top-secret");
        issue.setFilePath("../../secret.txt");
        when(issueMapper.selectById(1L)).thenReturn(issue);
        when(taskMapper.selectById(10L)).thenReturn(task);
        stubProject();

        IssueContextVO context = service.context(1L, 5);

        assertThat(context.lines()).isEmpty();
        assertThat(context.notice()).contains("不合法");
        assertThat(context.issue().filePath()).isEqualTo("../../secret.txt");
    }

    @Test
    @DisplayName("文件不存在时不报错，给出可读提示")
    void missingFileDegrades() throws IOException {
        issue.setFilePath("src/NotExist.java");
        when(issueMapper.selectById(1L)).thenReturn(issue);
        when(taskMapper.selectById(10L)).thenReturn(task);
        stubProject();

        IssueContextVO context = service.context(1L, 5);

        assertThat(context.lines()).isEmpty();
        assertThat(context.notice()).contains("已不在工作目录");
    }

    @Test
    @DisplayName("全量任务不计算 diff，增量任务才带上差异")
    void diffOnlyForIncrementalTask() throws IOException {
        writeSource(200);
        when(issueMapper.selectById(1L)).thenReturn(issue);
        when(taskMapper.selectById(10L)).thenReturn(task);
        stubProject();

        service.context(1L, 5);
        verify(diffSlicer, never()).fileDiff(any(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());

        task.setReviewMode("INCREMENTAL");
        task.setBaseCommit("base123");
        when(diffSlicer.fileDiff(workspace, "base123", "head123", "src/A.java", 6, 300, 100))
                .thenReturn(new DiffSlicer.FileDiff("src/A.java", java.util.List.of(), false));

        IssueContextVO context = service.context(1L, 5);

        assertThat(context.diff()).isNotNull();
        assertThat(context.diff().path()).isEqualTo("src/A.java");
    }

    @Test
    @DisplayName("项目已删除时降级为提示，不抛异常")
    void deletedProjectDegrades() {
        when(issueMapper.selectById(1L)).thenReturn(issue);
        when(taskMapper.selectById(10L)).thenReturn(task);
        when(projectMapper.selectById(20L)).thenReturn(null);

        IssueContextVO context = service.context(1L, 5);

        assertThat(context.lines()).isEmpty();
        assertThat(context.notice()).contains("项目已被删除");
    }

    @Test
    @DisplayName("证据 JSON 损坏时返回空证据而不是 500")
    void brokenEvidenceDegrades() throws IOException {
        writeSource(200);
        issue.setEvidence("{not-json");
        when(issueMapper.selectById(1L)).thenReturn(issue);
        when(taskMapper.selectById(10L)).thenReturn(task);
        stubProject();

        IssueContextVO context = service.context(1L, 5);

        assertThat(context.evidence()).isEmpty();
        assertThat(context.lines()).isNotEmpty();
    }
}

package com.shenchao.codereview.task.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.exception.BizException;
import com.shenchao.codereview.project.entity.RepoProject;
import com.shenchao.codereview.project.git.GitRepositoryService;
import com.shenchao.codereview.project.mapper.RepoProjectMapper;
import com.shenchao.codereview.task.dto.IssueContextVO;
import com.shenchao.codereview.task.dto.IssueVO;
import com.shenchao.codereview.task.entity.ReviewIssue;
import com.shenchao.codereview.task.entity.ReviewTask;
import com.shenchao.codereview.task.mapper.ReviewIssueMapper;
import com.shenchao.codereview.task.mapper.ReviewTaskMapper;
import com.shenchao.codereview.task.pipeline.DiffSlicer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 缺陷上下文服务：把"点一条缺陷看代码"这件事所需的数据一次备齐。
 *
 * <p><b>为什么不在前端拼</b>：仓库工作目录在服务端，前端拿不到文件；
 * 而且 diff 需要读 Git 对象库，只有后端能算。
 *
 * <p><b>降级优先于报错</b>：仓库被清理、文件被删除、路径穿越——
 * 这些情况下报告页仍然应该能展示缺陷描述与融合证据，只是代码区留空并给出说明。
 * 直接抛异常会让用户彻底看不到问题，这是更差的体验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueContextService {

    /** 缺陷行上下各保留的默认上下文行数。 */
    private static final int DEFAULT_CONTEXT = 12;

    /** 片段上限：再多前端也看不过来，反而拖慢渲染。 */
    private static final int MAX_WINDOW_LINES = 300;

    /** 单文件读取上限，超过直接降级而不是把整个文件塞进响应。 */
    private static final long MAX_FILE_BYTES = 512 * 1024L;

    /** diff 单文件输出行数上限。 */
    private static final int MAX_DIFF_LINES = 300;

    private final ReviewIssueMapper issueMapper;
    private final ReviewTaskMapper taskMapper;
    private final RepoProjectMapper projectMapper;
    private final GitRepositoryService gitRepositoryService;
    private final DiffSlicer diffSlicer;
    private final ObjectMapper objectMapper;

    /**
     * @param issueId      缺陷 ID
     * @param contextLines 上下文行数，{@code null} 或非正数取默认值
     */
    public IssueContextVO context(Long issueId, Integer contextLines) {
        ReviewIssue issue = issueMapper.selectById(issueId);
        if (issue == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "问题不存在");
        }
        IssueVO issueVO = IssueVO.from(issue);
        Map<String, String> evidence = parseEvidence(issue.getEvidence());
        int ctx = contextLines == null || contextLines <= 0 ? DEFAULT_CONTEXT : Math.min(contextLines, 60);

        ReviewTask task = taskMapper.selectById(issue.getTaskId());
        RepoProject project = task == null ? null : projectMapper.selectById(task.getProjectId());
        if (project == null) {
            return empty(issueVO, "项目已被删除，无法读取代码");
        }

        Path workspace;
        try {
            workspace = gitRepositoryService.resolveWorkDir(project.getCode());
        } catch (BizException ex) {
            return empty(issueVO, "项目编码不合法，无法定位代码目录");
        }
        if (!Files.isDirectory(workspace.resolve(".git"))) {
            return empty(issueVO, "本地工作目录不存在，请先同步仓库后再查看代码");
        }

        Path file = resolveSafely(workspace, issue.getFilePath());
        if (file == null) {
            log.warn("缺陷路径越界，已拒绝读取: issueId={}, path={}", issueId, issue.getFilePath());
            return empty(issueVO, "缺陷记录的文件路径不合法，已拒绝读取");
        }
        if (!Files.isRegularFile(file)) {
            return empty(issueVO, "文件已不在工作目录中（可能已被删除或重命名）");
        }

        String notice = null;
        List<String> rawLines;
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                return empty(issueVO, "文件超过 " + (MAX_FILE_BYTES / 1024) + "KB，不在页面内展示，请下载后查看");
            }
            // 按行切分而不做编码猜测：仓库里源码统一 UTF-8，遇到非法字节用替换符兜底
            rawLines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("读取缺陷所在文件失败: issueId={}, path={}, cause={}", issueId, issue.getFilePath(), ex.getMessage());
            return empty(issueVO, "读取文件失败：" + ex.getMessage());
        }

        int totalLines = rawLines.size();
        int anchor = issue.getStartLine() == null ? 1 : Math.max(1, issue.getStartLine());
        int anchorEnd = issue.getEndLine() == null ? anchor : Math.max(anchor, issue.getEndLine());

        int windowStart = Math.max(1, anchor - ctx);
        int windowEnd = Math.min(totalLines, Math.min(anchorEnd + ctx, windowStart + MAX_WINDOW_LINES - 1));
        if (windowEnd < windowStart) {
            // 缺陷行号超出文件范围（文件被改短了），退回文件尾部，至少能看到上下文
            windowEnd = totalLines;
            windowStart = Math.max(1, totalLines - MAX_WINDOW_LINES + 1);
            notice = "缺陷行号 " + anchor + " 超出文件当前行数 " + totalLines + "，已展示文件末尾";
        } else if (anchor > totalLines) {
            notice = "缺陷行号 " + anchor + " 超出文件当前行数 " + totalLines + "，文件可能已被修改";
        }

        List<IssueContextVO.CodeLine> lines = new ArrayList<>(Math.max(0, windowEnd - windowStart + 1));
        for (int no = windowStart; no <= windowEnd; no++) {
            lines.add(new IssueContextVO.CodeLine(no, rawLines.get(no - 1)));
        }

        DiffSlicer.FileDiff diff = null;
        if (task != null && "INCREMENTAL".equals(task.getReviewMode())
                && task.getBaseCommit() != null && !task.getBaseCommit().isBlank()) {
            diff = diffSlicer.fileDiff(workspace, task.getBaseCommit(), task.getHeadCommit(),
                    issue.getFilePath(), 6, MAX_DIFF_LINES, anchor);
        }

        return new IssueContextVO(issueVO, issue.getFilePath(), languageOf(issue.getFilePath()),
                totalLines, windowStart, windowEnd, lines, evidence, diff, notice);
    }

    // ---------------- 内部方法 ----------------

    /**
     * 路径穿越防护。
     *
     * <p>{@code file_path} 来自分析引擎的输出，PMD 与模型都可能给出奇怪的值。
     * 规范化之后必须仍在工作目录内，否则一律拒绝——
     * 报告页是只读展示，没有理由让它能读到仓库外的任意文件。
     */
    private Path resolveSafely(Path workspace, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        Path root = workspace.toAbsolutePath().normalize();
        Path target = root.resolve(filePath.replace('\\', '/')).normalize();
        return target.startsWith(root) ? target : null;
    }

    private Map<String, String> parseEvidence(String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(evidence, new TypeReference<>() {
            });
        } catch (Exception ex) {
            // 证据是 JSONB 文本，历史数据格式变化不该让接口报错
            log.debug("解析缺陷证据失败: cause={}", ex.getMessage());
            return Map.of();
        }
    }

    private IssueContextVO empty(IssueVO issue, String notice) {
        return new IssueContextVO(issue, issue.filePath(), languageOf(issue.filePath()),
                0, 0, 0, List.of(), Map.of(), null, notice);
    }

    /** 由后缀推断语言，前端据此选着色规则；识别不了就按纯文本展示。 */
    private String languageOf(String path) {
        if (path == null) {
            return "text";
        }
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return "text";
        }
        return switch (lower.substring(dot + 1)) {
            case "java" -> "java";
            case "kt" -> "kotlin";
            case "js", "jsx", "mjs", "cjs" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "py" -> "python";
            case "go" -> "go";
            case "xml", "html", "htm" -> "html";
            case "yml", "yaml" -> "yaml";
            case "sql" -> "sql";
            case "json" -> "json";
            default -> "text";
        };
    }
}

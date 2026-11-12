package com.shenchao.codereview.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchao.codereview.auth.security.SecurityUtils;
import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.api.PageResult;
import com.shenchao.codereview.common.exception.BizException;
import com.shenchao.codereview.project.entity.RepoProject;
import com.shenchao.codereview.project.mapper.RepoProjectMapper;
import com.shenchao.codereview.system.entity.SysUser;
import com.shenchao.codereview.system.mapper.SysUserMapper;
import com.shenchao.codereview.task.dto.IssueQuery;
import com.shenchao.codereview.task.dto.IssueVO;
import com.shenchao.codereview.task.dto.TaskCreateRequest;
import com.shenchao.codereview.task.dto.TaskQuery;
import com.shenchao.codereview.task.dto.TaskReportVO;
import com.shenchao.codereview.task.dto.TaskVO;
import com.shenchao.codereview.task.entity.ReviewIssue;
import com.shenchao.codereview.task.entity.ReviewTask;
import com.shenchao.codereview.task.mapper.ReviewIssueMapper;
import com.shenchao.codereview.task.mapper.ReviewTaskMapper;
import com.shenchao.codereview.task.model.TaskStatus;
import com.shenchao.codereview.task.queue.ReviewProperties;
import com.shenchao.codereview.task.queue.ReviewQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 审查任务服务。
 *
 * <p>创建任务刻意<b>不加事务</b>：先落库、再改状态、最后入队，三步各自提交。
 * 如果包在一个事务里，就会出现"消息已经发给 worker，但任务记录还没提交"的竞态——
 * worker 抢占失败、消息被 ack 掉，任务永远停在 CREATED。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewTaskService {

    private final ReviewTaskMapper taskMapper;
    private final ReviewIssueMapper issueMapper;
    private final RepoProjectMapper projectMapper;
    private final SysUserMapper userMapper;
    private final ReviewQueue queue;
    private final ReviewProperties properties;
    private final ObjectMapper objectMapper;

    // ---------------- 创建 ----------------

    public TaskVO create(TaskCreateRequest request) {
        Long userId = SecurityUtils.requireUserId();
        RepoProject project = projectMapper.selectById(request.projectId());
        if (project == null) {
            throw new BizException(ErrorCode.PROJECT_NOT_FOUND);
        }
        if (!RepoProject.SYNC_READY.equals(project.getSyncStatus()) || project.getLastCommitId() == null) {
            throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "项目仓库尚未同步完成，请先在项目页触发同步");
        }
        boolean incremental = "INCREMENTAL".equals(request.reviewMode());
        if (incremental) {
            // 增量必须有基线：没有基线就无从计算 diff，与其猜一个不如明确拒绝
            String baseCommit = request.baseCommit();
            if (baseCommit == null || baseCommit.isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "增量审查必须指定基线提交 baseCommit");
            }
            if (baseCommit.equals(project.getLastCommitId())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "基线提交与当前 HEAD 相同，没有可审查的变更");
            }
        } else if (request.baseCommit() != null && !request.baseCommit().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "全量审查不需要基线提交");
        }
        String headCommit = project.getLastCommitId();
        if (request.headCommit() != null && !request.headCommit().isBlank()
                && !request.headCommit().equals(headCommit)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "当前只支持审查项目已同步的提交，最新提交为 " + shortCommit(headCommit));
        }

        ReviewTask task = new ReviewTask();
        task.setProjectId(project.getId());
        task.setTriggerType("MANUAL");
        task.setHeadCommit(headCommit);
        task.setReviewMode(incremental ? "INCREMENTAL" : "FULL");
        task.setBaseCommit(incremental ? request.baseCommit().trim() : null);
        task.setStatus(TaskStatus.CREATED.name());
        task.setCancelRequested(false);
        task.setEngineFlags(writeJson(engineFlagsOf(request.engineFlags())));
        task.setStatsJson("{}");
        task.setIssueCount(0);
        task.setCreatedBy(userId);

        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException ex) {
            // 命中 uk_task_active：同一项目同一提交已有在途任务
            throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "该提交已有正在进行的审查任务，请等待其完成");
        }

        // 先迁移状态再入队：若入队失败，任务停在 QUEUED，由巡检任务在 30 秒后重投
        taskMapper.compareAndSetStatus(task.getId(), TaskStatus.CREATED.name(), TaskStatus.QUEUED.name());
        submit(task.getId());
        log.info("创建审查任务: taskId={}, projectId={}, commit={}, engineFlags={}",
                task.getId(), project.getId(), shortCommit(headCommit), task.getEngineFlags());
        return toVO(taskMapper.selectById(task.getId()));
    }

    /** 触发入队；失败只记日志，任务留给巡检重投。 */
    private void submit(Long taskId) {
        if (!queue.enqueue(taskId)) {
            log.warn("任务入队失败，等待巡检重投: taskId={}", taskId);
        }
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public PageResult<TaskVO> page(TaskQuery query) {
        LambdaQueryWrapper<ReviewTask> wrapper = Wrappers.lambdaQuery();
        if (query.getProjectId() != null) {
            wrapper.eq(ReviewTask::getProjectId, query.getProjectId());
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(ReviewTask::getStatus, query.getStatus());
        }
        if (query.getReviewMode() != null && !query.getReviewMode().isBlank()) {
            wrapper.eq(ReviewTask::getReviewMode, query.getReviewMode());
        }
        if (Boolean.TRUE.equals(query.getActiveOnly())) {
            wrapper.in(ReviewTask::getStatus, TaskStatus.activeNames());
        }
        if (Boolean.TRUE.equals(query.getMineOnly())) {
            wrapper.eq(ReviewTask::getCreatedBy, SecurityUtils.requireUserId());
        }
        if (query.getCommit() != null && !query.getCommit().isBlank()) {
            wrapper.likeRight(ReviewTask::getHeadCommit, query.getCommit());
        }
        wrapper.orderByDesc(ReviewTask::getId);

        Page<ReviewTask> page = taskMapper.selectPage(new Page<>(query.getPage(), query.getPageSize()), wrapper);
        Map<Long, RepoProject> projects = loadProjects(page.getRecords());
        Map<Long, String> users = loadUserNames(page.getRecords());

        List<TaskVO> records = page.getRecords().stream()
                .map(task -> assemble(task, projects.get(task.getProjectId()), users))
                .toList();
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Transactional(readOnly = true)
    public TaskVO detail(Long id) {
        return toVO(requireTask(id));
    }

    /**
     * 审查报告：任务快照 + 三个维度的聚合 + 缺陷明细。
     *
     * <p>聚合结果同时来自两处：{@code stats_json}（执行时的快照，含耗时与截断标记）
     * 与数据库实时 group by（用户在页面上改了缺陷状态后仍准确）。
     * 两者不一致时以数据库为准，快照只用于补充耗时类信息。
     */
    @Transactional(readOnly = true)
    public TaskReportVO report(Long id) {
        ReviewTask task = requireTask(id);
        TaskVO vo = toVO(task);

        List<ReviewIssue> issues = issueMapper.selectList(Wrappers.<ReviewIssue>lambdaQuery()
                .eq(ReviewIssue::getTaskId, id)
                .orderByAsc(ReviewIssue::getFilePath)
                .orderByAsc(ReviewIssue::getStartLine));
        issues.sort(Comparator
                .comparingInt((ReviewIssue issue) -> com.shenchao.codereview.engine.model.Severity
                        .fromCode(issue.getSeverity()).rank())
                .thenComparing(ReviewIssue::getFilePath)
                .thenComparingInt(issue -> issue.getStartLine() == null ? 0 : issue.getStartLine()));

        List<IssueVO> issueVOs = issues.stream()
                .limit(properties.reporterMaxIssues())
                .map(IssueVO::from)
                .toList();

        Map<String, Long> bySeverity = toCountMap(issueMapper.countBySeverity(id));
        Map<String, Long> byCategory = toCountMap(issueMapper.countByCategory(id));
        Map<String, Long> bySource = toCountMap(issueMapper.countBySource(id));
        Map<String, Long> byFlag = toCountMap(issueMapper.countByFlag(id));

        Map<String, Object> stats = vo.stats() == null ? Map.of() : vo.stats();
        TaskReportVO.ReportSummary summary = new TaskReportVO.ReportSummary(
                issues.size(),
                bySeverity,
                byCategory,
                bySource,
                byFlag,
                intOf(stats.get("filesTotal")),
                intOf(stats.get("filesAnalyzed")),
                Boolean.TRUE.equals(stats.get("truncated")),
                longOf(stats.get("totalMs")),
                stringList(stats.get("warnings")));

        return new TaskReportVO(vo, summary, issueVOs);
    }

    @Transactional(readOnly = true)
    public PageResult<IssueVO> pageIssues(IssueQuery query) {
        LambdaQueryWrapper<ReviewIssue> wrapper = Wrappers.lambdaQuery();
        if (query.getTaskId() != null) {
            wrapper.eq(ReviewIssue::getTaskId, query.getTaskId());
        }
        if (query.getProjectId() != null) {
            wrapper.eq(ReviewIssue::getProjectId, query.getProjectId());
        }
        if (query.getSeverity() != null && !query.getSeverity().isBlank()) {
            wrapper.eq(ReviewIssue::getSeverity, query.getSeverity());
        }
        if (query.getCategory() != null && !query.getCategory().isBlank()) {
            wrapper.eq(ReviewIssue::getCategory, query.getCategory());
        }
        if (query.getSource() != null && !query.getSource().isBlank()) {
            wrapper.eq(ReviewIssue::getSource, query.getSource());
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(ReviewIssue::getStatus, query.getStatus());
        }
        if (query.getFilePath() != null && !query.getFilePath().isBlank()) {
            wrapper.like(ReviewIssue::getFilePath, query.getFilePath());
        }
        wrapper.orderByDesc(ReviewIssue::getId);

        Page<ReviewIssue> page = issueMapper.selectPage(new Page<>(query.getPage(), query.getPageSize()), wrapper);
        return PageResult.of(page.getRecords().stream().map(IssueVO::from).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Transactional(readOnly = true)
    public IssueVO issueDetail(Long id) {
        ReviewIssue issue = issueMapper.selectById(id);
        if (issue == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "问题不存在");
        }
        return IssueVO.from(issue);
    }

    // ---------------- 取消 ----------------

    /**
     * 取消任务。
     *
     * <p>两种语义：
     * <ul>
     *   <li>还没开始跑（CREATED/QUEUED）：直接置为 CANCELED</li>
     *   <li>正在跑：只置取消标记，worker 在阶段边界退出，返回的仍是"已请求取消"</li>
     * </ul>
     * 不做线程中断：PMD 内部是多线程的，中断会留下不确定状态。
     */
    public TaskVO cancel(Long id) {
        ReviewTask task = requireTask(id);
        TaskStatus status = TaskStatus.fromCode(task.getStatus());
        if (status.terminal()) {
            throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "任务已结束，无法取消");
        }

        if (status == TaskStatus.CREATED || status == TaskStatus.QUEUED) {
            int updated = taskMapper.finish(id, TaskStatus.CANCELED.name(), null, "用户取消", 0,
                    task.getStatsJson() == null ? "{}" : task.getStatsJson());
            if (updated == 0) {
                throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "任务状态已变化，请刷新后重试");
            }
            log.info("任务已取消: taskId={}", id);
        } else {
            int updated = taskMapper.requestCancel(id);
            if (updated == 0) {
                throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "任务状态已变化，请刷新后重试");
            }
            log.info("已请求取消任务，将在当前阶段结束后停止: taskId={}", id);
        }
        return toVO(taskMapper.selectById(id));
    }

    // ---------------- 组装 ----------------

    private ReviewTask requireTask(Long id) {
        if (id == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        ReviewTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return task;
    }

    private TaskVO toVO(ReviewTask task) {
        RepoProject project = projectMapper.selectById(task.getProjectId());
        Map<Long, String> users = loadUserNames(List.of(task));
        return assemble(task, project, users);
    }

    private TaskVO assemble(ReviewTask task, RepoProject project, Map<Long, String> userNames) {
        return TaskVO.of(
                task,
                project == null ? null : project.getName(),
                project == null ? null : project.getCode(),
                userNames.get(task.getCreatedBy()),
                parseJson(task.getEngineFlags()),
                parseJson(task.getStatsJson()));
    }

    private Map<Long, RepoProject> loadProjects(List<ReviewTask> tasks) {
        Set<Long> ids = tasks.stream().map(ReviewTask::getProjectId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return projectMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(RepoProject::getId, Function.identity()));
    }

    private Map<Long, String> loadUserNames(List<ReviewTask> tasks) {
        Set<Long> ids = tasks.stream().map(ReviewTask::getCreatedBy).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId,
                        user -> user.getNickname() == null ? user.getUsername() : user.getNickname()));
    }

    private Map<String, Object> engineFlagsOf(TaskCreateRequest.EngineFlags flags) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pmd", flags == null || flags.pmdEnabled());
        map.put("spotbugs", flags != null && flags.spotbugsEnabled());
        map.put("llm", flags != null && flags.llmEnabled());
        // RAG 依赖向量库，M3 只做语义审查，召回留到下一阶段：置 false 而不是让前端以为开了
        if (flags != null && flags.ragEnabled()) {
            log.info("请求开启 RAG 召回，但向量检索尚未接入，已按关闭处理");
        }
        map.put("rag", false);
        return map;
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            log.warn("解析 JSON 字段失败，返回空对象: {}", ex.getMessage());
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private Map<String, Long> toCountMap(List<Map<String, Object>> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get("key");
            Object cnt = row.get("cnt");
            if (key != null) {
                result.put(String.valueOf(key), cnt instanceof Number number ? number.longValue() : 0L);
            }
        }
        return result;
    }

    private int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return List.of();
    }

    private String shortCommit(String commit) {
        return commit == null ? "-" : (commit.length() <= 8 ? commit : commit.substring(0, 8));
    }
}

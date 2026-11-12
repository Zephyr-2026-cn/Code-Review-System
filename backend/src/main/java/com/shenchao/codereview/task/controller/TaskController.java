package com.shenchao.codereview.task.controller;

import com.shenchao.codereview.common.api.PageResult;
import com.shenchao.codereview.common.api.Result;
import com.shenchao.codereview.system.audit.Audit;
import com.shenchao.codereview.task.dto.IssueQuery;
import com.shenchao.codereview.task.dto.IssueVO;
import com.shenchao.codereview.task.dto.TaskCreateRequest;
import com.shenchao.codereview.task.dto.TaskQuery;
import com.shenchao.codereview.task.dto.TaskReportVO;
import com.shenchao.codereview.task.dto.TaskVO;
import com.shenchao.codereview.task.service.ReviewTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审查任务接口。
 *
 * <p>创建任务后执行是异步的：接口返回时状态通常是 {@code QUEUED}，
 * 前端应轮询 {@code GET /tasks/{id}}（间隔 1~2 秒）直到终态，
 * 或等 SSE 通道（M5）推送。
 */
@Tag(name = "07-审查任务", description = "发起审查、查询进度与报告")
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final ReviewTaskService taskService;

    @Operation(summary = "发起审查",
            description = "项目必须已同步完成；同一项目同一提交同时只允许一个在途任务")
    @PostMapping
    @PreAuthorize("hasAuthority('task:create')")
    @Audit(action = "TASK_CREATE", resource = "TASK", logArgs = true, resourceIdIndex = -1)
    public Result<TaskVO> create(@Valid @RequestBody TaskCreateRequest request) {
        return Result.ok(taskService.create(request));
    }

    @Operation(summary = "分页查询任务")
    @GetMapping
    @PreAuthorize("hasAuthority('task:list')")
    public Result<PageResult<TaskVO>> page(@Valid TaskQuery query) {
        return Result.ok(taskService.page(query));
    }

    @Operation(summary = "任务详情（含进度与统计）")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('task:detail')")
    public Result<TaskVO> detail(@PathVariable Long id) {
        return Result.ok(taskService.detail(id));
    }

    @Operation(summary = "审查报告", description = "按严重度/类别/来源聚合，附缺陷明细")
    @GetMapping("/{id}/report")
    @PreAuthorize("hasAuthority('task:detail')")
    public Result<TaskReportVO> report(@PathVariable Long id) {
        return Result.ok(taskService.report(id));
    }

    @Operation(summary = "取消任务",
            description = "未开始的任务直接取消；执行中的任务在当前阶段结束后停止")
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('task:cancel')")
    @Audit(action = "TASK_CANCEL", resource = "TASK")
    public Result<TaskVO> cancel(@PathVariable Long id) {
        return Result.ok(taskService.cancel(id));
    }

    @Operation(summary = "任务下的缺陷列表")
    @GetMapping("/{id}/issues")
    @PreAuthorize("hasAuthority('issue:list')")
    public Result<PageResult<IssueVO>> issues(@PathVariable Long id, @Valid IssueQuery query) {
        query.setTaskId(id);
        return Result.ok(taskService.pageIssues(query));
    }
}

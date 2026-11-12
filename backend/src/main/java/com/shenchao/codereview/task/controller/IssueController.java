package com.shenchao.codereview.task.controller;

import com.shenchao.codereview.common.api.PageResult;
import com.shenchao.codereview.common.api.Result;
import com.shenchao.codereview.task.dto.IssueContextVO;
import com.shenchao.codereview.task.dto.IssueQuery;
import com.shenchao.codereview.task.dto.IssueVO;
import com.shenchao.codereview.task.service.IssueContextService;
import com.shenchao.codereview.task.service.ReviewTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问题中心接口。
 *
 * <p>与 {@code /tasks/{id}/issues} 的区别：这里跨任务查询，
 * 用于"整个项目还有哪些未处理的高危问题"这类视图。
 */
@Tag(name = "08-问题中心", description = "跨任务查询缺陷")
@RestController
@RequestMapping("/issues")
@RequiredArgsConstructor
public class IssueController {

    private final ReviewTaskService taskService;
    private final IssueContextService contextService;

    @Operation(summary = "分页查询缺陷")
    @GetMapping
    @PreAuthorize("hasAuthority('issue:list')")
    public Result<PageResult<IssueVO>> page(@Valid IssueQuery query) {
        return Result.ok(taskService.pageIssues(query));
    }

    @Operation(summary = "缺陷详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('issue:detail')")
    public Result<IssueVO> detail(@PathVariable Long id) {
        return Result.ok(taskService.issueDetail(id));
    }

    @Operation(summary = "缺陷上下文",
            description = "返回缺陷所在文件的代码片段、融合证据；增量任务的缺陷还带 base..head 的差异块")
    @GetMapping("/{id}/context")
    @PreAuthorize("hasAuthority('issue:detail')")
    public Result<IssueContextVO> context(@PathVariable Long id,
                                          @RequestParam(required = false) Integer contextLines) {
        return Result.ok(contextService.context(id, contextLines));
    }
}

package com.shenchao.codereview.project.controller;

import com.shenchao.codereview.common.api.PageResult;
import com.shenchao.codereview.common.api.Result;
import com.shenchao.codereview.project.dto.ProjectCreateRequest;
import com.shenchao.codereview.project.dto.ProjectQuery;
import com.shenchao.codereview.project.dto.ProjectUpdateRequest;
import com.shenchao.codereview.project.dto.ProjectVO;
import com.shenchao.codereview.project.dto.RepoBindRequest;
import com.shenchao.codereview.project.service.ProjectService;
import com.shenchao.codereview.system.audit.Audit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目管理接口。
 *
 * <p>仓库绑定是异步的：{@code POST /projects/{id}/repos} 返回时项目状态可能仍为
 * {@code UNBOUND}，前端应轮询 {@code GET /projects/{id}} 直到 {@code READY} / {@code FAILED}。
 */
@Tag(name = "06-项目", description = "项目增删改查、仓库绑定与同步")
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @Operation(summary = "分页查询项目")
    @GetMapping
    @PreAuthorize("hasAuthority('project:repo:list')")
    public Result<PageResult<ProjectVO>> page(@Valid ProjectQuery query) {
        return Result.ok(projectService.page(query));
    }

    @Operation(summary = "项目详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('project:repo:detail') or hasAuthority('project:repo:list')")
    public Result<ProjectVO> detail(@PathVariable Long id) {
        return Result.ok(projectService.detail(id));
    }

    @Operation(summary = "创建项目", description = "携带仓库地址时会在事务提交后自动触发一次克隆")
    @PostMapping
    @PreAuthorize("hasAuthority('project:repo:create')")
    @Audit(action = "PROJECT_CREATE", resource = "PROJECT", logArgs = true, resourceIdIndex = -1)
    public Result<ProjectVO> create(@Valid @RequestBody ProjectCreateRequest request) {
        return Result.ok(projectService.create(request));
    }

    @Operation(summary = "修改项目", description = "仅归属人或管理员可改")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('project:repo:update')")
    @Audit(action = "PROJECT_UPDATE", resource = "PROJECT", logArgs = true)
    public Result<ProjectVO> update(@PathVariable Long id, @Valid @RequestBody ProjectUpdateRequest request) {
        return Result.ok(projectService.update(id, request));
    }

    @Operation(summary = "删除项目", description = "逻辑删除记录并清理本地工作目录")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('project:repo:delete')")
    @Audit(action = "PROJECT_DELETE", resource = "PROJECT")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "绑定仓库", description = "绑定后异步克隆，返回后请轮询项目详情")
    @PostMapping("/{id}/repos")
    @PreAuthorize("hasAuthority('project:repo:update')")
    @Audit(action = "PROJECT_BIND_REPO", resource = "PROJECT", logArgs = true)
    public Result<ProjectVO> bindRepo(@PathVariable Long id, @Valid @RequestBody RepoBindRequest request) {
        return Result.ok(projectService.bindRepo(id, request));
    }

    @Operation(summary = "同步仓库", description = "拉取最新代码，异步执行")
    @PostMapping("/{id}/sync")
    @PreAuthorize("hasAuthority('project:repo:update')")
    @Audit(action = "PROJECT_SYNC", resource = "PROJECT")
    public Result<ProjectVO> sync(@PathVariable Long id) {
        return Result.ok(projectService.sync(id));
    }

    @Operation(summary = "远端分支列表", description = "供绑定表单下拉；查询失败返回空列表")
    @GetMapping("/{id}/branches")
    @PreAuthorize("hasAuthority('project:repo:list')")
    public Result<List<String>> branches(@PathVariable Long id) {
        return Result.ok(projectService.listRemoteBranches(id));
    }
}

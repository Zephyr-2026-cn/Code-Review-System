package com.shenchao.codereview.system.controller;

import com.shenchao.codereview.common.api.Result;
import com.shenchao.codereview.system.audit.Audit;
import com.shenchao.codereview.system.dto.AssignPermissionsRequest;
import com.shenchao.codereview.system.dto.RoleSaveRequest;
import com.shenchao.codereview.system.dto.RoleVO;
import com.shenchao.codereview.system.service.RoleService;
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
 * 角色与权限分配接口（仅管理员可用）。
 *
 * <p>内置角色（ADMIN/REVIEWER/DEVELOPER）不可删除、编码不可修改、权限不可调整，
 * 从接口层到 Service 层双重拦截，保证系统永远不会出现"没有任何管理员"的状态。
 */
@Tag(name = "03-系统-角色", description = "角色查询、创建、修改、删除、分配权限")
@RestController
@RequestMapping("/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @Operation(summary = "角色列表", description = "附带每个角色的引用用户数，供前端禁用删除按钮")
    @GetMapping
    @PreAuthorize("hasAuthority('system:role:list')")
    public Result<List<RoleVO>> list() {
        return Result.ok(roleService.list());
    }

    @Operation(summary = "角色详情", description = "包含该角色已授予的权限点编码")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('system:role:list')")
    public Result<RoleVO> detail(@PathVariable Long id) {
        return Result.ok(roleService.detail(id));
    }

    @Operation(summary = "创建角色")
    @PostMapping
    @PreAuthorize("hasAuthority('system:role:create')")
    @Audit(action = "ROLE_CREATE", resource = "ROLE", logArgs = true, resourceIdIndex = -1)
    public Result<RoleVO> create(@Valid @RequestBody RoleSaveRequest request) {
        return Result.ok(roleService.create(request));
    }

    @Operation(summary = "修改角色", description = "角色编码不可修改")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:role:update')")
    @Audit(action = "ROLE_UPDATE", resource = "ROLE", logArgs = true)
    public Result<RoleVO> update(@PathVariable Long id, @Valid @RequestBody RoleSaveRequest request) {
        return Result.ok(roleService.update(id, request));
    }

    @Operation(summary = "删除角色", description = "内置角色或仍被用户引用的角色不允许删除")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:role:delete')")
    @Audit(action = "ROLE_DELETE", resource = "ROLE")
    public Result<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "分配角色权限", description = "覆盖式；变更后该角色下所有用户的权限缓存立即失效")
    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('system:role:assign')")
    @Audit(action = "ROLE_ASSIGN_PERMISSION", resource = "ROLE", logArgs = true)
    public Result<RoleVO> assignPermissions(@PathVariable Long id,
                                            @Valid @RequestBody AssignPermissionsRequest request) {
        return Result.ok(roleService.assignPermissions(id, request));
    }
}

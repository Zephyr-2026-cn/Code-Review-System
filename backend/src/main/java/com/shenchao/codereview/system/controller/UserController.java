package com.shenchao.codereview.system.controller;

import com.shenchao.codereview.common.api.PageResult;
import com.shenchao.codereview.common.api.Result;
import com.shenchao.codereview.system.audit.Audit;
import com.shenchao.codereview.system.dto.AssignRolesRequest;
import com.shenchao.codereview.system.dto.InitialCredentialVO;
import com.shenchao.codereview.system.dto.ResetPasswordRequest;
import com.shenchao.codereview.system.dto.UserCreateRequest;
import com.shenchao.codereview.system.dto.UserQuery;
import com.shenchao.codereview.system.dto.UserUpdateRequest;
import com.shenchao.codereview.system.dto.UserVO;
import com.shenchao.codereview.system.service.UserAdminService;
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

/**
 * 用户管理接口（仅管理员可用）。
 *
 * <p>本项目<b>没有注册接口</b>：账号只能由管理员通过 {@code POST /system/users} 创建。
 */
@Tag(name = "02-系统-用户", description = "用户查询、创建、修改、删除、重置密码、分配角色")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserAdminService userAdminService;

    @Operation(summary = "分页查询用户")
    @GetMapping
    @PreAuthorize("hasAuthority('system:user:list')")
    public Result<PageResult<UserVO>> page(@Valid UserQuery query) {
        return Result.ok(userAdminService.page(query));
    }

    @Operation(summary = "查询用户详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:list')")
    public Result<UserVO> detail(@PathVariable Long id) {
        return Result.ok(userAdminService.detail(id));
    }

    @Operation(summary = "创建用户",
            description = "不传密码则由系统生成随机初始密码，明文仅在本次响应返回一次")
    @PostMapping
    @PreAuthorize("hasAuthority('system:user:create')")
    @Audit(action = "USER_CREATE", resource = "USER", logArgs = true, resourceIdIndex = -1)
    public Result<InitialCredentialVO> create(@Valid @RequestBody UserCreateRequest request) {
        return Result.ok(userAdminService.create(request));
    }

    @Operation(summary = "修改用户", description = "仅可改显示名、邮箱与状态；登录名不可变")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:update')")
    @Audit(action = "USER_UPDATE", resource = "USER", logArgs = true)
    public Result<UserVO> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        return Result.ok(userAdminService.update(id, request));
    }

    @Operation(summary = "删除用户", description = "逻辑删除；不能删除自己，也不能删除最后一个可用管理员")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:delete')")
    @Audit(action = "USER_DELETE", resource = "USER")
    public Result<Void> delete(@PathVariable Long id) {
        userAdminService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "重置密码", description = "重置后该用户全部会话立即失效，并在下次登录后强制改密")
    @PostMapping("/{id}/password")
    @PreAuthorize("hasAuthority('system:user:reset-password')")
    @Audit(action = "USER_RESET_PASSWORD", resource = "USER")
    public Result<InitialCredentialVO> resetPassword(@PathVariable Long id,
                                                     @Valid @RequestBody ResetPasswordRequest request) {
        return Result.ok(userAdminService.resetPassword(id, request));
    }

    @Operation(summary = "分配角色", description = "覆盖式分配；变更后该用户全部会话立即失效")
    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('system:user:update')")
    @Audit(action = "USER_ASSIGN_ROLES", resource = "USER", logArgs = true)
    public Result<UserVO> assignRoles(@PathVariable Long id, @Valid @RequestBody AssignRolesRequest request) {
        return Result.ok(userAdminService.assignRoles(id, request));
    }
}

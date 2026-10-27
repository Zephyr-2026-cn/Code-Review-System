package com.shenchao.codereview.system.controller;

import com.shenchao.codereview.common.api.Result;
import com.shenchao.codereview.system.dto.PermissionVO;
import com.shenchao.codereview.system.service.PermissionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限点查询接口。
 *
 * <p>只读：权限点由数据库迁移脚本维护（版本化、可评审、可回滚），
 * 不开放运行时增删，避免"随手加一个权限点"导致前端菜单与后端校验脱节。
 */
@Tag(name = "04-系统-权限", description = "权限点树查询")
@RestController
@RequestMapping("/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionQueryService permissionQueryService;

    @Operation(summary = "权限点树", description = "MENU / API / BUTTON 三类权限点组成的树，供角色授权页勾选")
    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('system:role:list')")
    public Result<List<PermissionVO>> tree() {
        return Result.ok(permissionQueryService.tree());
    }
}

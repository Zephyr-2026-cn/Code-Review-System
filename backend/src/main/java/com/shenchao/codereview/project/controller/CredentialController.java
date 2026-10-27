package com.shenchao.codereview.project.controller;

import com.shenchao.codereview.common.api.Result;
import com.shenchao.codereview.project.dto.CredentialCreateRequest;
import com.shenchao.codereview.project.dto.CredentialVO;
import com.shenchao.codereview.project.service.CredentialService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Git 凭据管理接口。
 *
 * <p>没有"查看令牌"接口，也没有"更新令牌"接口：
 * 需要换令牌时删除后重建。看似不便，但对 PAT 这类高权限凭证来说，
 * 减少一次读取就少一个泄漏面。
 */
@Tag(name = "07-项目-Git 凭据", description = "私有仓库访问凭据（加密存储，不回显）")
@RestController
@RequestMapping("/credentials")
@RequiredArgsConstructor
public class CredentialController {

    private final CredentialService credentialService;

    @Operation(summary = "凭据列表", description = "非管理员只能看到自己创建的凭据，且不含令牌")
    @GetMapping
    @PreAuthorize("hasAuthority('project:repo:list')")
    public Result<List<CredentialVO>> list() {
        return Result.ok(credentialService.list());
    }

    @Operation(summary = "新建凭据", description = "令牌 AES-256-GCM 加密后落库，仅此一次传输")
    @PostMapping
    @PreAuthorize("hasAuthority('project:repo:credential')")
    @Audit(action = "CREDENTIAL_CREATE", resource = "CREDENTIAL", logArgs = true, resourceIdIndex = -1)
    public Result<CredentialVO> create(@Valid @RequestBody CredentialCreateRequest request) {
        return Result.ok(credentialService.create(request));
    }

    @Operation(summary = "删除凭据", description = "仍被项目引用时不允许删除")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('project:repo:credential')")
    @Audit(action = "CREDENTIAL_DELETE", resource = "CREDENTIAL")
    public Result<Void> delete(@PathVariable Long id) {
        credentialService.delete(id);
        return Result.ok();
    }
}

package com.shenchao.codereview.system.controller;

import com.shenchao.codereview.common.api.PageResult;
import com.shenchao.codereview.common.api.Result;
import com.shenchao.codereview.system.dto.AuditLogQuery;
import com.shenchao.codereview.system.dto.AuditLogVO;
import com.shenchao.codereview.system.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志查询接口。
 *
 * <p>只提供查询，<b>不提供任何写/删接口</b>：审计日志一旦可被业务侧修改或删除，
 * 就不再具备取证价值。保留策略由数据库侧定时任务负责，应用层无权干预。
 */
@Tag(name = "05-系统-审计日志", description = "操作审计日志查询（只读）")
@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @Operation(summary = "分页查询审计日志", description = "时间跨度上限 90 天")
    @GetMapping
    @PreAuthorize("hasAuthority('system:audit:list')")
    public Result<PageResult<AuditLogVO>> page(@Valid AuditLogQuery query) {
        return Result.ok(auditLogService.page(query));
    }
}

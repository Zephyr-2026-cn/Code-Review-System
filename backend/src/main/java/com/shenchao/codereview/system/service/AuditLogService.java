package com.shenchao.codereview.system.service;

import com.shenchao.codereview.config.AsyncConfig;
import com.shenchao.codereview.system.dto.AuditLogQuery;
import com.shenchao.codereview.system.dto.AuditLogVO;
import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.api.PageResult;
import com.shenchao.codereview.common.exception.BizException;
import com.shenchao.codereview.system.entity.SysAuditLog;
import com.shenchao.codereview.system.mapper.SysAuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 审计日志写入。
 *
 * <p>用 {@code REQUIRES_NEW} 独立事务：业务事务回滚时审计仍要留痕
 * （"操作失败"本身就是需要记录的事件）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    /** 单次查询允许的最大时间跨度，防止"查全表"式请求拖垮数据库。 */
    private static final int MAX_QUERY_SPAN_DAYS = 90;

    private final SysAuditLogMapper auditLogMapper;

    @Async(AsyncConfig.AUDIT_EXECUTOR)
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void saveAsync(SysAuditLog auditLog) {
        try {
            if (auditLog.getCreatedAt() == null) {
                auditLog.setCreatedAt(java.time.OffsetDateTime.now());
            }
            auditLogMapper.insert(auditLog);
        } catch (Exception ex) {
            // 审计写入失败只告警，不抛出：异步线程里抛出也不会有人捕获
            log.error("审计日志写入失败: action={}, user={}", auditLog.getAction(), auditLog.getUsername(), ex);
        }
    }

    /**
     * 分页查询审计日志。
     *
     * <p>三条边界：
     * <ol>
     *   <li>时间区间跨度上限 90 天：无上限的区间查询会退化成全表扫描</li>
     *   <li>起止时间颠倒时直接纠正而不是报错，属于前端常见的传参失误</li>
     *   <li>只支持按时间/耗时排序，不接受任意列名</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public PageResult<AuditLogVO> page(AuditLogQuery query) {
        OffsetDateTime to = query.getTo() == null ? OffsetDateTime.now() : query.getTo();
        OffsetDateTime from = query.getFrom();
        if (from != null && from.isAfter(to)) {
            OffsetDateTime tmp = from;
            from = to;
            to = tmp;
        }
        if (from != null && Duration.between(from, to).toDays() > MAX_QUERY_SPAN_DAYS) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "查询时间跨度不能超过 " + MAX_QUERY_SPAN_DAYS + " 天");
        }

        LambdaQueryWrapper<SysAuditLog> wrapper = Wrappers.<SysAuditLog>lambdaQuery()
                .eq(query.getUsername() != null && !query.getUsername().isBlank(),
                        SysAuditLog::getUsername, query.getUsername())
                .eq(query.getAction() != null && !query.getAction().isBlank(),
                        SysAuditLog::getAction, query.getAction())
                .eq(query.getResource() != null && !query.getResource().isBlank(),
                        SysAuditLog::getResource, query.getResource())
                .eq(query.getSuccess() != null, SysAuditLog::getSuccess, query.getSuccess())
                .ge(from != null, SysAuditLog::getCreatedAt, from)
                .le(SysAuditLog::getCreatedAt, to);

        if ("durationMs".equals(query.getSortBy())) {
            wrapper.orderBy(true, !Boolean.TRUE.equals(query.getDesc()), SysAuditLog::getDurationMs);
        } else {
            wrapper.orderBy(true, !Boolean.TRUE.equals(query.getDesc()), SysAuditLog::getCreatedAt);
        }

        Page<SysAuditLog> page = new Page<>(query.getPage(), query.getPageSize());
        auditLogMapper.selectPage(page, wrapper);

        return PageResult.of(page.getRecords().stream().map(AuditLogVO::from).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }
}

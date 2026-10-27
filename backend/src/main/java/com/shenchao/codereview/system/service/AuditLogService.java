package com.shenchao.codereview.system.service;

import com.shenchao.codereview.config.AsyncConfig;
import com.shenchao.codereview.system.entity.SysAuditLog;
import com.shenchao.codereview.system.mapper.SysAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
}

package com.shenchao.codereview.project.event;

import com.shenchao.codereview.config.AsyncConfig;
import com.shenchao.codereview.project.service.RepoSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 仓库同步事件监听器。
 *
 * <p>{@code AFTER_COMMIT} 是不可省略的：
 * <ul>
 *   <li>事务回滚时不能触发克隆（否则会下载一个已被回滚的项目）</li>
 *   <li>克隆前必须能读到已提交的数据</li>
 * </ul>
 *
 * <p>{@code fallbackExecution = true} 让"没有事务的场景"（例如测试或运维脚本直接发事件）
 * 也能正常执行，属于工程上的容错。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepoSyncListener {

    private final RepoSyncService repoSyncService;

    @Async(AsyncConfig.REVIEW_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRepoSync(RepoSyncEvent event) {
        log.debug("开始处理仓库同步事件: projectId={}, reason={}", event.projectId(), event.reason());
        repoSyncService.sync(event.projectId(), event.reason());
    }
}

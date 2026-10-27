package com.shenchao.codereview.project.event;

/**
 * 请求仓库同步事件。
 *
 * <p>为什么用事件而不是直接在 Service 里调异步方法：
 * 异步任务必须在<b>事务提交后</b>才能读到新数据（否则可能读到未提交的旧快照，
 * 表现为"绑定成功但同步时找不到仓库地址"）。事件 + {@code @TransactionalEventListener}
 * 天然满足"提交后执行"的时序，且把"写库"和"重活"解耦。
 *
 * @param projectId 项目 ID
 * @param reason    触发原因，仅用于日志（CREATE / BIND / MANUAL）
 */
public record RepoSyncEvent(Long projectId, String reason) {
}

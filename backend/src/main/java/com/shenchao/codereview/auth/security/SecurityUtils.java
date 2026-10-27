package com.shenchao.codereview.auth.security;

import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.exception.BizException;

/**
 * 安全上下文工具：供 Service 层做数据级权限判断。
 *
 * <p>为什么需要：{@code @PreAuthorize} 只能拦截"功能入口"，
 * 无法阻止"开发者 A 查询开发者 B 的任务详情"这类 <b>水平越权</b>。
 * 处理方式见 {@link #requireSelfOrPermission(Long, String)}。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static LoginUser requireUser() {
        return LoginUser.currentUser()
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
    }

    public static Long requireUserId() {
        return requireUser().userId();
    }

    public static boolean hasPermission(String permission) {
        LoginUser user = requireUser();
        return user.isAdmin() || user.permissions().contains(permission);
    }

    public static boolean hasRole(String role) {
        return requireUser().roles().contains(role);
    }

    /**
     * 水平越权防护：资源归属人本人，或拥有指定管理权限时才放行。
     *
     * @param ownerId    资源归属人 ID，可为 null（表示无归属，仅管理员可操作）
     * @param permission 管理类权限点，例如 issue:verdict:any
     */
    public static void requireSelfOrPermission(Long ownerId, String permission) {
        LoginUser user = requireUser();
        if (user.isAdmin() || user.permissions().contains(permission)) {
            return;
        }
        if (ownerId != null && ownerId.equals(user.userId())) {
            return;
        }
        throw new BizException(ErrorCode.PERMISSION_DENIED);
    }
}

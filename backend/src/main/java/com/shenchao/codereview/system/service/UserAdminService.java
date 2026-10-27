package com.shenchao.codereview.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shenchao.codereview.auth.security.LoginUser;
import com.shenchao.codereview.auth.security.SecurityUtils;
import com.shenchao.codereview.auth.service.TokenStore;
import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.api.PageResult;
import com.shenchao.codereview.common.exception.BizException;
import com.shenchao.codereview.common.util.PasswordGenerator;
import com.shenchao.codereview.common.util.PasswordPolicy;
import com.shenchao.codereview.system.dto.AssignRolesRequest;
import com.shenchao.codereview.system.dto.InitialCredentialVO;
import com.shenchao.codereview.system.dto.ResetPasswordRequest;
import com.shenchao.codereview.system.dto.UserCreateRequest;
import com.shenchao.codereview.system.dto.UserQuery;
import com.shenchao.codereview.system.dto.UserUpdateRequest;
import com.shenchao.codereview.system.dto.UserVO;
import com.shenchao.codereview.system.entity.SysUser;
import com.shenchao.codereview.system.entity.SysUserRole;
import com.shenchao.codereview.system.mapper.SysRoleMapper;
import com.shenchao.codereview.system.mapper.SysUserMapper;
import com.shenchao.codereview.system.mapper.SysUserRoleMapper;
import com.shenchao.codereview.system.mapper.UserRoleRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户管理（管理员侧）。
 *
 * <p><b>本项目不开放自助注册</b>：账号只能由管理员创建，初始密码随机生成并只回显一次，
 * 同时置位"强制改密"标记 —— 否则管理员会永久掌握该账号的可用凭证。
 *
 * <p>集中在这里的五个安全约束：
 * <ol>
 *   <li>不能删除自己、不能停用自己（避免误操作把自己锁在门外）</li>
 *   <li>不能把"最后一个启用状态的管理员"降级/停用/删除，否则系统失去管理入口</li>
 *   <li>非超级管理员不能创建/授予 ADMIN 角色（横向提权）</li>
 *   <li>停用、删除、重置密码、调整角色后，立即吊销该用户全部在途会话</li>
 *   <li>响应体绝不包含密码摘要，统一走 {@link UserVO}</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    /** 排序白名单：只允许按这三列排序，杜绝 {@code sortBy=password} 之类的探测。 */
    private static final Set<String> SORTABLE_COLUMNS = Set.of("id", "username", "createdAt", "lastLoginAt");

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final RoleService roleService;
    private final PermissionCacheService permissionCacheService;
    private final TokenStore tokenStore;
    private final PasswordEncoder passwordEncoder;

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public PageResult<UserVO> page(UserQuery query) {
        LambdaQueryWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaQuery();

        String keyword = query.getKeyword();
        if (keyword != null && !keyword.isBlank()) {
            // 参数化模糊匹配，不使用字符串拼接，避免 LIKE 注入
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getNickname, keyword)
                    .or().like(SysUser::getEmail, keyword));
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(SysUser::getStatus, query.getStatus());
        }
        if (query.getRoleId() != null) {
            // 用参数化子查询过滤角色，避免 join 后分页条数被关联行放大
            wrapper.apply("id IN (SELECT user_id FROM sys_user_role WHERE role_id = {0})", query.getRoleId());
        }
        applySort(wrapper, query.getSortBy(), Boolean.TRUE.equals(query.getDesc()));

        Page<SysUser> page = new Page<>(query.getPage(), query.getPageSize());
        userMapper.selectPage(page, wrapper);

        List<UserVO> records = toUserVOs(page.getRecords());
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Transactional(readOnly = true)
    public UserVO detail(Long id) {
        SysUser user = requireUser(id);
        return toUserVOs(List.of(user)).get(0);
    }

    // ---------------- 创建 / 修改 / 删除 ----------------

    @Transactional(rollbackFor = Exception.class)
    public InitialCredentialVO create(UserCreateRequest request) {
        String username = request.normalizedUsername();

        // 先查一次给出友好提示；真正的并发唯一性由 uk_sys_user_username 保证
        if (userMapper.countByUsername(username) > 0) {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }

        String rawPassword = resolvePassword(request.password());
        Set<Long> roleIds = roleService.requireExistingRoleIds(request.roleIds());
        assertCanAssignRoles(roleIds);

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setNickname(request.normalizedNickname());
        user.setEmail(request.normalizedEmail());
        user.setStatus(SysUser.STATUS_ENABLED);
        user.setPasswordResetRequired(Boolean.TRUE);
        user.setDeleted(0);
        userMapper.insert(user);

        replaceUserRoles(user.getId(), roleIds);
        permissionCacheService.evict(user.getId());

        log.info("管理员创建用户: username={}, userId={}, roles={}, operator={}",
                username, user.getId(), roleIds, LoginUser.currentUsernameOrNull());

        // 明文密码只在本次响应中出现，不写日志、不落库
        return new InitialCredentialVO(user.getId(), username, rawPassword);
    }

    @Transactional(rollbackFor = Exception.class)
    public UserVO update(Long id, UserUpdateRequest request) {
        SysUser user = requireUser(id);
        Long operatorId = SecurityUtils.requireUserId();

        boolean disabling = SysUser.STATUS_DISABLED.equals(request.status());
        boolean currentlyEnabled = user.isEnabled();

        if (disabling && id.equals(operatorId)) {
            throw new BizException(ErrorCode.SELF_OPERATION_FORBIDDEN, "不能停用当前登录的账号");
        }

        // 停用一个管理员前，确认停用后系统里还有别的可用管理员
        if (disabling && currentlyEnabled && isAdminUser(id)
                && roleMapper.countOtherEnabledAdmins(id) == 0) {
            throw new BizException(ErrorCode.LAST_ADMIN_PROTECTED);
        }

        SysUser update = new SysUser();
        update.setId(id);
        update.setNickname(request.normalizedNickname());
        update.setEmail(request.normalizedEmail());
        update.setStatus(request.status());
        userMapper.updateById(update);

        if (disabling && currentlyEnabled) {
            // 停用后必须让在途令牌立即失效，否则"禁用"只是挡住了下次登录
            tokenStore.revokeUserSessions(id);
            permissionCacheService.evict(id);
            log.info("管理员停用用户并吊销其会话: userId={}, operator={}", id, LoginUser.currentUsernameOrNull());
        }

        return detail(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysUser user = requireUser(id);
        Long operatorId = SecurityUtils.requireUserId();

        if (id.equals(operatorId)) {
            throw new BizException(ErrorCode.SELF_OPERATION_FORBIDDEN, "不能删除当前登录的账号");
        }
        if (user.isEnabled() && isAdminUser(id) && roleMapper.countOtherEnabledAdmins(id) == 0) {
            throw new BizException(ErrorCode.LAST_ADMIN_PROTECTED, "不能删除系统中最后一个可用管理员");
        }

        // 先断关联：逻辑删除不会触发外键级联，残留关联会让"重建同名账号"意外继承旧角色
        userRoleMapper.deleteByUserId(id);
        userMapper.deleteById(id);

        tokenStore.revokeUserSessions(id);
        permissionCacheService.evict(id);
        log.info("管理员删除用户: username={}, userId={}, operator={}",
                user.getUsername(), id, LoginUser.currentUsernameOrNull());
    }

    /**
     * 重置密码。
     *
     * <p>无论管理员是否指定新密码，都会置位"强制改密"：
     * 管理员知道密码本身就是一个风险点，必须由用户登录后立即更换。
     */
    @Transactional(rollbackFor = Exception.class)
    public InitialCredentialVO resetPassword(Long id, ResetPasswordRequest request) {
        SysUser user = requireUser(id);
        String rawPassword = resolvePassword(request.newPassword());

        SysUser update = new SysUser();
        update.setId(id);
        update.setPassword(passwordEncoder.encode(rawPassword));
        update.setPasswordResetRequired(Boolean.TRUE);
        userMapper.updateById(update);

        // 旧密码对应的会话必须全部作废
        tokenStore.revokeUserSessions(id);
        log.info("管理员重置用户密码: username={}, userId={}, operator={}",
                user.getUsername(), id, LoginUser.currentUsernameOrNull());

        return new InitialCredentialVO(id, user.getUsername(), rawPassword);
    }

    /**
     * 覆盖式分配角色。
     *
     * <p>角色变化会改变权限集合与 JWT 里的 {@code roles} 声明，
     * 因此必须同时清缓存 + 吊销会话，让新权限在下次登录后才生效，
     * 避免"用旧令牌访问新取消的接口"这种时间窗。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserVO assignRoles(Long id, AssignRolesRequest request) {
        SysUser user = requireUser(id);
        Set<Long> targetRoleIds = roleService.requireExistingRoleIds(request.roleIds());
        assertCanAssignRoles(targetRoleIds);

        List<Long> currentRoleIds = roleMapper.selectRoleIdsByUserId(id);
        boolean losingAdmin = containsAdminRole(currentRoleIds)
                && !containsAdminRole(targetRoleIds);

        if (losingAdmin && user.isEnabled() && roleMapper.countOtherEnabledAdmins(id) == 0) {
            throw new BizException(ErrorCode.LAST_ADMIN_PROTECTED,
                    "不能移除最后一个可用管理员的管理员角色");
        }

        replaceUserRoles(id, targetRoleIds);
        permissionCacheService.evict(id);
        tokenStore.revokeUserSessions(id);

        log.info("管理员调整用户角色: userId={}, before={}, after={}, operator={}",
                id, currentRoleIds, targetRoleIds, LoginUser.currentUsernameOrNull());
        return detail(id);
    }

    // ---------------- 内部方法 ----------------

    private SysUser requireUser(Long id) {
        if (id == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    /** 密码未指定时随机生成；指定时必须通过强度策略（否则会出现"管理员设的密码不合规"）。 */
    private String resolvePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return PasswordGenerator.generate();
        }
        PasswordPolicy.validate(rawPassword);
        return rawPassword;
    }

    /**
     * 提权防护：非超级管理员不得创建或授予 ADMIN 角色。
     *
     * <p>ADMIN 在 {@code LoginUser#isAdmin()} 中被特判为"拥有全部权限点"，
     * 因此授予 ADMIN 等价于授予系统最高权限。
     */
    private void assertCanAssignRoles(Set<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return;
        }
        LoginUser operator = SecurityUtils.requireUser();
        if (operator.isAdmin()) {
            return;
        }
        boolean containsAdmin = roleMapper.selectBatchIds(roleIds).stream()
                .anyMatch(role -> "ADMIN".equals(role.getCode()));
        if (containsAdmin) {
            log.warn("阻止越权授予 ADMIN 角色: operator={}", operator.username());
            throw new BizException(ErrorCode.PRIVILEGE_ESCALATION, "只有管理员可以授予管理员角色");
        }
    }

    private void replaceUserRoles(Long userId, Set<Long> roleIds) {
        userRoleMapper.deleteByUserId(userId);
        for (Long roleId : roleIds) {
            userRoleMapper.insert(SysUserRole.of(userId, roleId));
        }
    }

    private boolean isAdminUser(Long userId) {
        return containsAdminRole(roleMapper.selectRoleIdsByUserId(userId));
    }

    /** 一次查库判断角色集合里是否含 ADMIN，避免逐项查询。 */
    private boolean containsAdminRole(java.util.Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return false;
        }
        return roleMapper.selectBatchIds(roleIds).stream()
                .anyMatch(role -> "ADMIN".equals(role.getCode()));
    }

    /**
     * 批量转 VO：角色信息一次查出后按 userId 分组，避免列表页 N+1。
     */
    private List<UserVO> toUserVOs(List<SysUser> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = users.stream().map(SysUser::getId).toList();
        Map<Long, List<UserVO.RoleBrief>> rolesByUser = new LinkedHashMap<>();
        for (UserRoleRow row : roleMapper.selectRolesByUserIds(userIds)) {
            rolesByUser.computeIfAbsent(row.getUserId(), key -> new ArrayList<>())
                    .add(new UserVO.RoleBrief(row.getRoleId(), row.getCode(), row.getName()));
        }

        List<UserVO> result = new ArrayList<>(users.size());
        for (SysUser user : users) {
            result.add(new UserVO(
                    user.getId(),
                    user.getUsername(),
                    user.getNickname(),
                    user.getEmail(),
                    user.getStatus(),
                    rolesByUser.getOrDefault(user.getId(), List.of()),
                    user.getLastLoginAt(),
                    user.getCreatedAt(),
                    user.mustResetPassword()));
        }
        return result;
    }

    /**
     * 排序：非白名单字段一律回退到 {@code id DESC}。
     *
     * <p>不抛异常而是静默回退，是因为排序参数通常由前端表格组件自动带上，
     * 为一个"不支持的排序字段"直接报错会让整个列表页白屏。
     */
    private void applySort(LambdaQueryWrapper<SysUser> wrapper, String sortBy, boolean desc) {
        String column = (sortBy == null || sortBy.isBlank()) ? "id" : sortBy;
        if (!SORTABLE_COLUMNS.contains(column)) {
            column = "id";
        }
        boolean asc = !desc;
        switch (column) {
            case "username" -> wrapper.orderBy(true, asc, SysUser::getUsername);
            case "createdAt" -> wrapper.orderBy(true, asc, SysUser::getCreatedAt);
            case "lastLoginAt" -> wrapper.orderBy(true, asc, SysUser::getLastLoginAt);
            default -> wrapper.orderBy(true, asc, SysUser::getId);
        }
    }
}

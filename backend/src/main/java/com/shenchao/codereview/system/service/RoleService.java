package com.shenchao.codereview.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shenchao.codereview.auth.security.LoginUser;
import com.shenchao.codereview.auth.security.SecurityUtils;
import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.exception.BizException;
import com.shenchao.codereview.system.dto.AssignPermissionsRequest;
import com.shenchao.codereview.system.dto.RoleSaveRequest;
import com.shenchao.codereview.system.dto.RoleVO;
import com.shenchao.codereview.system.entity.SysRole;
import com.shenchao.codereview.system.entity.SysRolePermission;
import com.shenchao.codereview.system.mapper.SysRoleMapper;
import com.shenchao.codereview.system.mapper.SysPermissionMapper;
import com.shenchao.codereview.system.mapper.SysRolePermissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 角色管理。
 *
 * <p>三条不可妥协的边界（答辩时被问到"权限系统怎么防止把自己锁死"就答这些）：
 * <ol>
 *   <li><b>内置角色不可删、不可改编码、不可改权限</b>：ADMIN 若被清空权限，
 *       系统将没有任何人可以重新授权，只能改数据库</li>
 *   <li><b>被用户引用的角色不可删</b>：级联删关联会让这些用户"静默失去全部权限"，
 *       排查成本极高；正确做法是先摘人再删角色</li>
 *   <li><b>不可授予超出自身的权限</b>：防止"有分配权限的人"把自己升格为超级管理员
 *       （横向提权）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private static final String ADMIN_ROLE_CODE = "ADMIN";

    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;
    private final PermissionQueryService permissionQueryService;
    private final PermissionCacheService permissionCacheService;

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<RoleVO> list() {
        List<SysRole> roles = roleMapper.selectList(
                Wrappers.<SysRole>lambdaQuery().orderByAsc(SysRole::getId));
        List<RoleVO> result = new ArrayList<>(roles.size());
        for (SysRole role : roles) {
            result.add(RoleVO.summary(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                    Boolean.TRUE.equals(role.getBuiltin()), roleMapper.countUsersByRoleId(role.getId()),
                    role.getCreatedAt()));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public RoleVO detail(Long id) {
        SysRole role = requireRole(id);
        return new RoleVO(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                Boolean.TRUE.equals(role.getBuiltin()), roleMapper.countUsersByRoleId(role.getId()),
                permissionQueryService.codesOfRole(role.getId()), role.getCreatedAt());
    }

    // ---------------- 写操作 ----------------

    @Transactional(rollbackFor = Exception.class)
    public RoleVO create(RoleSaveRequest request) {
        String code = request.normalizedCode();
        if (countByCode(code, null) > 0) {
            throw new BizException(ErrorCode.ROLE_CODE_EXISTS, "角色编码 " + code + " 已存在");
        }

        SysRole role = new SysRole();
        role.setCode(code);
        role.setName(request.normalizedName());
        role.setDescription(request.normalizedDescription());
        // 任何通过接口创建的角色都不是内置角色，因此永远可删可改
        role.setBuiltin(false);
        role.setDeleted(0);
        roleMapper.insert(role);

        log.info("创建角色: code={}, id={}, operator={}", code, role.getId(),
                LoginUser.currentUsernameOrNull());
        return detail(role.getId());
    }

    /**
     * 修改角色。
     *
     * <p>编码不接受修改：角色编码会写进 JWT 的 {@code roles} 声明，一旦变更，
     * 所有在途令牌里的旧编码会立刻失效（表现为"用户突然看不到菜单"）。
     * 需要改编码的正确姿势是新建角色 + 迁移用户。
     */
    @Transactional(rollbackFor = Exception.class)
    public RoleVO update(Long id, RoleSaveRequest request) {
        SysRole role = requireRole(id);

        String requestedCode = request.normalizedCode();
        if (!role.getCode().equals(requestedCode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "角色编码不允许修改，如需调整请新建角色并迁移用户");
        }

        SysRole update = new SysRole();
        update.setId(id);
        update.setName(request.normalizedName());
        update.setDescription(request.normalizedDescription());
        roleMapper.updateById(update);

        log.info("修改角色: id={}, code={}, operator={}", id, role.getCode(), LoginUser.currentUsernameOrNull());
        return detail(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysRole role = requireRole(id);
        if (Boolean.TRUE.equals(role.getBuiltin())) {
            throw new BizException(ErrorCode.BUILTIN_ROLE_PROTECTED, "内置角色 " + role.getCode() + " 不允许删除");
        }

        long users = roleMapper.countUsersByRoleId(id);
        if (users > 0) {
            throw new BizException(ErrorCode.ROLE_IN_USE,
                    "该角色仍被 " + users + " 个用户使用，请先解除关联后再删除");
        }

        // 先断关联再删角色：外键虽然是 ON DELETE CASCADE，但逻辑删除不触发物理级联，
        // 残留的 role_permission 会让"重建同编码角色"意外继承旧权限
        rolePermissionMapper.deleteByRoleId(id);
        roleMapper.deleteById(id);

        permissionCacheService.evictByRole(id);
        log.info("删除角色: id={}, code={}, operator={}", id, role.getCode(), LoginUser.currentUsernameOrNull());
    }

    /**
     * 覆盖式分配权限。
     *
     * <p>先全删再全插而不是做差集：差集逻辑在并发下容易出现"两个管理员同时保存，
     * 最终结果是谁后提交谁生效"的错觉，而全删全插在同一事务里语义确定。
     */
    @Transactional(rollbackFor = Exception.class)
    public RoleVO assignPermissions(Long id, AssignPermissionsRequest request) {
        SysRole role = requireRole(id);
        if (Boolean.TRUE.equals(role.getBuiltin())) {
            throw new BizException(ErrorCode.BUILTIN_ROLE_PROTECTED,
                    "内置角色 " + role.getCode() + " 的权限由系统固定，不允许调整");
        }

        Set<Long> targetPermissionIds = permissionQueryService.requireExistingIds(request.permissionIds());
        assertNoPrivilegeEscalation(targetPermissionIds);

        rolePermissionMapper.deleteByRoleId(id);
        for (Long permissionId : targetPermissionIds) {
            SysRolePermission relation = new SysRolePermission();
            relation.setRoleId(id);
            relation.setPermissionId(permissionId);
            rolePermissionMapper.insert(relation);
        }

        // 角色权限变了，该角色下所有用户的权限缓存都必须失效，否则最长要等 30 分钟才生效
        permissionCacheService.evictByRole(id);
        log.info("角色权限已更新: roleId={}, count={}, operator={}", id, targetPermissionIds.size(),
                LoginUser.currentUsernameOrNull());
        return detail(id);
    }

    // ---------------- 内部方法 ----------------

    public SysRole requireRole(Long id) {
        if (id == null) {
            throw new BizException(ErrorCode.ROLE_NOT_FOUND);
        }
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException(ErrorCode.ROLE_NOT_FOUND);
        }
        return role;
    }

    /** 统计同编码角色数量（逻辑删除的自动排除），{@code excludeId} 用于修改场景排除自身。 */
    private long countByCode(String code, Long excludeId) {
        return roleMapper.selectCount(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getCode, code)
                .ne(excludeId != null, SysRole::getId, excludeId));
    }

    /**
     * 校验角色 ID 集合全部存在，返回去重结果。
     *
     * <p>不做这层校验直接写关联表会撞外键，最终以 500 的形式返回给前端，
     * 而实际原因是"传了一个已删除的角色 ID"，属于本可避免的服务端错误。
     */
    public Set<Long> requireExistingRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> distinct = new LinkedHashSet<>();
        roleIds.stream().filter(java.util.Objects::nonNull).forEach(distinct::add);
        if (distinct.isEmpty()) {
            return Set.of();
        }
        List<SysRole> roles = roleMapper.selectBatchIds(distinct);
        if (roles.size() != distinct.size()) {
            Set<Long> missing = new LinkedHashSet<>(distinct);
            roles.forEach(role -> missing.remove(role.getId()));
            log.warn("发现不存在的角色: {}", missing);
            throw new BizException(ErrorCode.ROLE_NOT_FOUND, "存在无效的角色: " + missing);
        }
        return distinct;
    }

    public boolean isAdminRole(Set<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return false;
        }
        return roleMapper.selectBatchIds(roleIds).stream()
                .anyMatch(role -> ADMIN_ROLE_CODE.equals(role.getCode()));
    }

    /**
     * 提权防护：非超级管理员分配权限时，授予的权限点必须是自身权限的子集。
     *
     * <p>没有这道闸门，"拥有 system:role:assign 的普通管理员"可以给自己所在的角色
     * 加上任意权限点，一次调用完成自我升格。
     */
    private void assertNoPrivilegeEscalation(Set<Long> targetPermissionIds) {
        LoginUser operator = SecurityUtils.requireUser();
        if (operator.isAdmin()) {
            return;
        }
        if (targetPermissionIds.isEmpty()) {
            return;
        }
        List<String> targetCodes = permissionMapper.selectCodesByIds(targetPermissionIds);
        Set<String> notOwned = new LinkedHashSet<>(targetCodes);
        notOwned.removeAll(operator.permissions());
        if (!notOwned.isEmpty()) {
            log.warn("阻止权限提升尝试: operator={}, 越权权限点={}", operator.username(), notOwned);
            throw new BizException(ErrorCode.PRIVILEGE_ESCALATION,
                    "不能授予自身不具备的权限点: " + notOwned);
        }
    }
}

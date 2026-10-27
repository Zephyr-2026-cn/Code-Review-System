package com.shenchao.codereview.system.service;

import com.shenchao.codereview.system.dto.PermissionVO;
import com.shenchao.codereview.system.entity.SysPermission;
import com.shenchao.codereview.system.mapper.SysPermissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限点查询与构树。
 *
 * <p>为什么在服务端构树而不是让前端组装：权限点数量会随功能迭代增长，
 * 前端各自实现构树逻辑容易在"父节点缺失"等脏数据上出现空指针或丢节点。
 * 服务端统一保证：任何 parentId 指向不存在节点（或已被清理）的权限点，都会被挂到根级，
 * 宁可多显示一个顶级菜单，也不让权限点凭空消失。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionQueryService {

    private final SysPermissionMapper permissionMapper;

    /**
     * 查询完整权限树。
     *
     * <p>一次全表查询后在内存里组树：权限点是静态元数据（本项目约 60 条），
     * 递归查库反而会带来 60 次往返。
     */
    @Transactional(readOnly = true)
    public List<PermissionVO> tree() {
        List<SysPermission> all = permissionMapper.selectAllOrdered();
        if (all.isEmpty()) {
            return List.of();
        }

        Map<Long, List<PermissionVO>> childrenByParent = new LinkedHashMap<>();
        Map<Long, SysPermission> byId = new LinkedHashMap<>();
        all.forEach(permission -> byId.put(permission.getId(), permission));

        for (SysPermission permission : all) {
            PermissionVO node = toNode(permission);
            childrenByParent.computeIfAbsent(permission.getParentId(), key -> new ArrayList<>()).add(node);
        }

        // 根节点 = parentId 为空，或父节点已不存在（脏数据兜底）
        List<PermissionVO> roots = new ArrayList<>();
        for (SysPermission permission : all) {
            Long parentId = permission.getParentId();
            if (parentId == null || !byId.containsKey(parentId)) {
                roots.add(attachChildren(toNode(permission), childrenByParent));
            }
        }
        return roots;
    }

    /** 角色当前拥有的权限点编码，用于角色详情页回显。 */
    @Transactional(readOnly = true)
    public List<String> codesOfRole(Long roleId) {
        return permissionMapper.selectCodesByRoleId(roleId);
    }

    /**
     * 校验权限点 ID 是否全部存在，返回去重后的合法集合。
     *
     * @throws com.shenchao.codereview.common.exception.BizException 存在非法 ID 时抛出
     */
    public Set<Long> requireExistingIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        Set<Long> distinct = new LinkedHashSet<>();
        ids.stream().filter(java.util.Objects::nonNull).forEach(distinct::add);
        if (distinct.isEmpty()) {
            return Set.of();
        }
        List<Long> existing = permissionMapper.selectExistingIds(distinct);
        if (existing.size() != distinct.size()) {
            Set<Long> missing = new LinkedHashSet<>(distinct);
            missing.removeAll(existing);
            log.warn("分配权限时发现不存在的权限点: {}", missing);
            throw new com.shenchao.codereview.common.exception.BizException(
                    com.shenchao.codereview.common.api.ErrorCode.PERMISSION_NOT_FOUND,
                    "存在无效的权限点: " + missing);
        }
        return new LinkedHashSet<>(existing);
    }

    private PermissionVO attachChildren(PermissionVO node, Map<Long, List<PermissionVO>> childrenByParent) {
        List<PermissionVO> children = childrenByParent.get(node.id());
        if (children == null || children.isEmpty()) {
            return new PermissionVO(node.id(), node.code(), node.name(), node.type(), node.parentId(),
                    node.path(), node.icon(), node.sortOrder(), List.of());
        }
        List<PermissionVO> resolved = new ArrayList<>(children.size());
        for (PermissionVO child : children) {
            resolved.add(attachChildren(child, childrenByParent));
        }
        return new PermissionVO(node.id(), node.code(), node.name(), node.type(), node.parentId(),
                node.path(), node.icon(), node.sortOrder(), resolved);
    }

    private PermissionVO toNode(SysPermission permission) {
        return new PermissionVO(permission.getId(), permission.getCode(), permission.getName(),
                permission.getType(), permission.getParentId(), permission.getPath(), permission.getIcon(),
                permission.getSortOrder(), List.of());
    }
}

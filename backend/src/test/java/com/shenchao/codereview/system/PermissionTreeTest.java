package com.shenchao.codereview.system;

import com.shenchao.codereview.system.dto.PermissionVO;
import com.shenchao.codereview.system.entity.SysPermission;
import com.shenchao.codereview.system.mapper.SysPermissionMapper;
import com.shenchao.codereview.system.service.PermissionQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 权限树组装测试。
 *
 * <p>核心场景是"脏数据兜底"：某个子权限点的 parent_id 指向了已被清理的父节点。
 * 此时若直接丢弃，管理员在授权页会看不到这个权限点，于是"永远给不出这个权限"，
 * 属于极难排查的问题。正确行为是把它提到根级显示。
 */
@DisplayName("权限点构树")
class PermissionTreeTest {

    private final SysPermissionMapper mapper = mock(SysPermissionMapper.class);
    private final PermissionQueryService service = new PermissionQueryService(mapper);

    private static SysPermission permission(Long id, String code, Long parentId, int sort) {
        SysPermission permission = new SysPermission();
        permission.setId(id);
        permission.setCode(code);
        permission.setName(code);
        permission.setType(SysPermission.TYPE_MENU);
        permission.setParentId(parentId);
        permission.setSortOrder(sort);
        return permission;
    }

    @Test
    @DisplayName("两级菜单正确挂载")
    void buildsTwoLevelTree() {
        when(mapper.selectAllOrdered()).thenReturn(List.of(
                permission(1L, "menu:dashboard", null, 10),
                permission(2L, "menu:project", null, 20),
                permission(3L, "menu:project:list", 2L, 10)));

        List<PermissionVO> roots = service.tree();

        assertThat(roots).hasSize(2);
        PermissionVO project = roots.stream().filter(r -> r.id().equals(2L)).findFirst().orElseThrow();
        assertThat(project.children()).hasSize(1);
        assertThat(project.children().get(0).code()).isEqualTo("menu:project:list");
    }

    @Test
    @DisplayName("父节点缺失的子权限被提升到根级而不是丢弃")
    void orphanNodeBecomesRoot() {
        when(mapper.selectAllOrdered()).thenReturn(List.of(
                permission(1L, "menu:dashboard", null, 10),
                permission(9L, "menu:orphan", 999L, 20)));

        List<PermissionVO> roots = service.tree();

        assertThat(roots).extracting(PermissionVO::code)
                .containsExactlyInAnyOrder("menu:dashboard", "menu:orphan");
    }

    @Test
    @DisplayName("叶子节点 children 为空列表而不是 null，前端可直接遍历")
    void leavesHaveEmptyChildren() {
        when(mapper.selectAllOrdered()).thenReturn(List.of(permission(1L, "menu:dashboard", null, 10)));

        List<PermissionVO> roots = service.tree();

        assertThat(roots.get(0).children()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("无权限点时返回空列表")
    void emptyWhenNoPermission() {
        when(mapper.selectAllOrdered()).thenReturn(List.of());
        assertThat(service.tree()).isEmpty();
    }

    @Test
    @DisplayName("三层嵌套可正确展开")
    void buildsNestedTree() {
        when(mapper.selectAllOrdered()).thenReturn(List.of(
                permission(1L, "menu:system", null, 10),
                permission(2L, "menu:system:user", 1L, 10),
                permission(3L, "system:user:create", 2L, 10)));

        List<PermissionVO> roots = service.tree();

        assertThat(roots).hasSize(1);
        PermissionVO level2 = roots.get(0).children().get(0);
        assertThat(level2.code()).isEqualTo("menu:system:user");
        assertThat(level2.children()).hasSize(1);
        assertThat(level2.children().get(0).code()).isEqualTo("system:user:create");
    }
}

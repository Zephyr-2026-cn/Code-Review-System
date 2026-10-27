<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import type { ElTree } from 'element-plus'
import { permissionApi, roleApi, type PermissionNode, type RoleItem } from '@/api/system'
import { useUserStore } from '@/stores/user'

/**
 * 角色与权限页。
 *
 * 关键设计：权限树使用 `check-strictly`（父子不联动）。
 * 原因：MENU 只是"菜单可见"，API/BUTTON 才是真正的鉴权点，
 * 若做父子联动，勾一个菜单就会顺带授予它下面全部写操作，属于隐性提权。
 * 这里让管理员显式勾选每一个权限点，宁多点几下，也不要模糊授权。
 */
const userStore = useUserStore()

const loading = ref(false)
const rows = ref<RoleItem[]>([])
const permissionTree = ref<PermissionNode[]>([])
const treeRef = ref<InstanceType<typeof ElTree>>()

/** code -> id，用于把角色已有的权限编码回填成树选中项。 */
const codeToId = ref<Map<string, number>>(new Map())

async function loadRoles(): Promise<void> {
  loading.value = true
  try {
    rows.value = await roleApi.list()
  } finally {
    loading.value = false
  }
}

function indexPermission(nodes: PermissionNode[], target: Map<string, number>): void {
  for (const node of nodes) {
    target.set(node.code, node.id)
    indexPermission(node.children, target)
  }
}

async function loadPermissions(): Promise<void> {
  const tree = await permissionApi.tree()
  permissionTree.value = tree
  const index = new Map<string, number>()
  indexPermission(tree, index)
  codeToId.value = index
}

onMounted(async () => {
  await Promise.all([loadRoles(), loadPermissions()])
})

// ---------------------------------------------------------------- 新建 / 编辑

const editVisible = ref(false)
const editMode = ref<'create' | 'edit'>('create')
const editForm = reactive({ id: 0, code: '', name: '', description: '' })

function openCreate(): void {
  editMode.value = 'create'
  editForm.id = 0
  editForm.code = ''
  editForm.name = ''
  editForm.description = ''
  editVisible.value = true
}

function openEdit(row: RoleItem): void {
  editMode.value = 'edit'
  editForm.id = row.id
  editForm.code = row.code
  editForm.name = row.name
  editForm.description = row.description ?? ''
  editVisible.value = true
}

async function submitEdit(): Promise<void> {
  const payload = { code: editForm.code, name: editForm.name, description: editForm.description || undefined }
  if (editMode.value === 'create') {
    await roleApi.create(payload)
    ElMessage.success('角色已创建')
  } else {
    await roleApi.update(editForm.id, payload)
    ElMessage.success('已保存')
  }
  editVisible.value = false
  await loadRoles()
}

async function removeRole(row: RoleItem): Promise<void> {
  await ElMessageBox.confirm(
    `删除角色「${row.name}」后不可恢复。若仍有用户绑定该角色，系统会拒绝删除。是否继续？`,
    '删除角色',
    { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
  )
  await roleApi.remove(row.id)
  ElMessage.success('已删除')
  await loadRoles()
}

// ---------------------------------------------------------------- 权限分配

const permVisible = ref(false)
const permLoading = ref(false)
const permTarget = reactive({ id: 0, name: '', code: '' })

async function openPermission(row: RoleItem): Promise<void> {
  if (row.builtin) {
    ElMessage.warning('内置角色的权限由系统固定，不允许调整')
    return
  }
  permTarget.id = row.id
  permTarget.name = row.name
  permTarget.code = row.code
  permVisible.value = true
  permLoading.value = true
  try {
    const detail = await roleApi.detail(row.id)
    const ids = detail.permissions
      .map((code) => codeToId.value.get(code))
      .filter((id): id is number => typeof id === 'number')
    // 树组件要等 dialog 渲染完才能 setCheckedKeys
    await nextTick()
    treeRef.value?.setCheckedKeys(ids, false)
  } finally {
    permLoading.value = false
  }
}

async function submitPermission(): Promise<void> {
  const checked = (treeRef.value?.getCheckedKeys(false) ?? []) as number[]
  await roleApi.assignPermissions(permTarget.id, checked)
  permVisible.value = false
  ElMessage.success(`已保存 ${checked.length} 个权限点，相关用户缓存已失效`)
  await loadRoles()
}

function expandAll(): void {
  const nodes = treeRef.value?.store.nodesMap ?? {}
  Object.values(nodes).forEach((node) => {
    ;(node as { expanded: boolean }).expanded = true
  })
}

// ---------------------------------------------------------------- 展示辅助

const canCreate = computed(() => userStore.hasPermission('system:role:create'))
const canUpdate = computed(() => userStore.hasPermission('system:role:update'))
const canDelete = computed(() => userStore.hasPermission('system:role:delete'))
const canAssign = computed(() => userStore.hasPermission('system:role:assign'))

const typeLabel = (type: string) => {
  if (type === 'MENU') return '菜单'
  if (type === 'API') return '接口'
  return '按钮'
}

const typeTag = (type: string) => {
  if (type === 'MENU') return 'primary'
  if (type === 'API') return 'success'
  return 'warning'
}
</script>

<template>
  <div class="cr-page">
    <el-card shadow="never" class="filter-card">
      <div class="filter-bar">
        <span class="cr-text-muted">
          内置角色不可删除、编码不可修改、权限不可调整 —— 保证系统始终存在可用的管理员。
        </span>
        <div class="filter-spacer" />
        <el-button :icon="Refresh" @click="loadRoles">刷新</el-button>
        <el-button v-if="canCreate" type="primary" :icon="Plus" @click="openCreate">新增角色</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column prop="code" label="角色编码" width="140">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="角色名称" width="140" />
        <el-table-column prop="description" label="描述" min-width="240">
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.builtin ? 'info' : 'success'" effect="plain">
              {{ row.builtin ? '内置' : '自定义' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="userCount" label="引用用户" width="100" align="center" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canUpdate" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="canAssign" link type="primary" :disabled="row.builtin" @click="openPermission(row)">
              权限
            </el-button>
            <el-button v-if="canDelete" link type="danger" :disabled="row.builtin || row.userCount > 0" @click="removeRole(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建 / 编辑角色 -->
    <el-dialog
      v-model="editVisible"
      :title="editMode === 'create' ? '新增角色' : '编辑角色'"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form label-width="90px">
        <el-form-item label="角色编码" required>
          <el-input
            v-model="editForm.code"
            :disabled="editMode === 'edit'"
            placeholder="2-32 位大写字母/数字/下划线，如 AUDITOR"
          />
          <div class="form-hint">
            {{ editMode === 'edit' ? '编码是稳定标识，写入令牌后不可变更；如需调整请新建角色' : '创建后不可修改' }}
          </div>
        </el-form-item>
        <el-form-item label="角色名称" required>
          <el-input v-model="editForm.name" placeholder="如 质量审计员" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 权限分配 -->
    <el-dialog v-model="permVisible" :title="`分配权限 · ${permTarget.name}`" width="620px" top="6vh">
      <div class="perm-toolbar">
        <el-button link type="primary" @click="expandAll">展开全部</el-button>
        <span class="cr-text-muted">父子不联动：勾选菜单不会自动授予其下的接口权限</span>
      </div>
      <div v-loading="permLoading" class="perm-tree">
        <el-tree
          ref="treeRef"
          :data="permissionTree"
          node-key="id"
          show-checkbox
          check-strictly
          default-expand-all
          :props="{ label: 'name', children: 'children' }"
        >
          <template #default="{ data }">
            <span class="perm-node">
              <el-tag size="small" :type="typeTag(data.type)" effect="plain">{{ typeLabel(data.type) }}</el-tag>
              <span class="perm-name">{{ data.name }}</span>
              <span class="perm-code mono">{{ data.code }}</span>
            </span>
          </template>
        </el-tree>
      </div>
      <template #footer>
        <el-button @click="permVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPermission">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.filter-spacer {
  flex: 1;
}

.mono {
  font-family: var(--cr-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}

.form-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  margin-top: 2px;
}

.perm-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  font-size: 12px;
}

.perm-tree {
  max-height: 58vh;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px;
}

.perm-node {
  display: flex;
  align-items: center;
  gap: 8px;
}

.perm-name {
  font-size: 13px;
}

.perm-code {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>

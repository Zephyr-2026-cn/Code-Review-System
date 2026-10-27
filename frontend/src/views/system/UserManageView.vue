<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { roleApi, userApi, type RoleItem, type UserItem, type UserStatus } from '@/api/system'
import { useUserStore } from '@/stores/user'

/**
 * 用户管理页。
 *
 * 三条交互约束（与后端安全约束一一对应）：
 *   1. 没有自助注册入口：账号只能在这里创建，初始密码由后端随机生成并只显示一次
 *   2. 不能停用/删除自己：开关与按钮对当前账号置灰，避免"点一下把自己锁在门外"
 *   3. 权限变更会立刻踢下线：调整角色或重置密码后提示管理员转告用户重新登录
 */
const userStore = useUserStore()

const loading = ref(false)
const rows = ref<UserItem[]>([])
const roles = ref<RoleItem[]>([])
const total = ref(0)

const query = reactive({
  page: 1,
  pageSize: 20,
  keyword: '',
  status: '' as UserStatus | '',
  roleId: null as number | null
})

const isSelf = (row: UserItem) => row.id === userStore.profile?.id

async function loadRoles(): Promise<void> {
  roles.value = await roleApi.list()
}

async function loadUsers(): Promise<void> {
  loading.value = true
  try {
    const page = await userApi.page({
      page: query.page,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      roleId: query.roleId ?? undefined
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function resetQuery(): void {
  query.keyword = ''
  query.status = ''
  query.roleId = null
  query.page = 1
  loadUsers()
}

onMounted(async () => {
  await Promise.all([loadRoles(), loadUsers()])
})

// ---------------------------------------------------------------- 创建

const createVisible = ref(false)
const createForm = reactive({
  username: '',
  nickname: '',
  email: '',
  password: '',
  roleIds: [] as number[]
})

function openCreate(): void {
  createForm.username = ''
  createForm.nickname = ''
  createForm.email = ''
  createForm.password = ''
  createForm.roleIds = []
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  const credential = await userApi.create({
    username: createForm.username,
    nickname: createForm.nickname,
    email: createForm.email || undefined,
    password: createForm.password || undefined,
    roleIds: createForm.roleIds
  })
  createVisible.value = false
  showCredential(credential.username, credential.password)
  await loadUsers()
}

// ---------------------------------------------------------------- 初始密码展示

const credentialVisible = ref(false)
const credential = reactive({ username: '', password: '' })

function showCredential(username: string, password: string): void {
  credential.username = username
  credential.password = password
  credentialVisible.value = true
}

async function copyCredential(): Promise<void> {
  try {
    await navigator.clipboard.writeText(
      `登录名：${credential.username}\n初始密码：${credential.password}`
    )
    ElMessage.success('已复制到剪贴板，请通过安全渠道转交')
  } catch {
    // 非 HTTPS 或浏览器拒绝授权时会走到这里，提示手动复制
    ElMessage.warning('浏览器不允许自动复制，请手动选中复制')
  }
}

// ---------------------------------------------------------------- 编辑

const editVisible = ref(false)
const editForm = reactive({ id: 0, nickname: '', email: '', status: 'ENABLED' as UserStatus })

function openEdit(row: UserItem): void {
  editForm.id = row.id
  editForm.nickname = row.nickname
  editForm.email = row.email ?? ''
  editForm.status = row.status
  editVisible.value = true
}

async function submitEdit(): Promise<void> {
  await userApi.update(editForm.id, {
    nickname: editForm.nickname,
    email: editForm.email || undefined,
    status: editForm.status
  })
  editVisible.value = false
  ElMessage.success('已保存')
  await loadUsers()
}

/** 表格内的启用/禁用开关：直接走 update，失败时回滚显示。 */
async function toggleStatus(row: UserItem, next: boolean): Promise<void> {
  const target: UserStatus = next ? 'ENABLED' : 'DISABLED'
  try {
    await userApi.update(row.id, {
      nickname: row.nickname,
      email: row.email ?? undefined,
      status: target
    })
    row.status = target
    ElMessage.success(next ? '已启用' : '已禁用，该用户会话已失效')
  } catch {
    // 后端拒绝（例如"最后一个管理员"）时把开关拨回去，避免界面与真实状态不一致
    row.status = next ? 'DISABLED' : 'ENABLED'
  }
}

// ---------------------------------------------------------------- 角色分配

const roleVisible = ref(false)
const roleForm = reactive({ id: 0, username: '', roleIds: [] as number[] })

function openRole(row: UserItem): void {
  roleForm.id = row.id
  roleForm.username = row.username
  roleForm.roleIds = row.roles.map((role) => role.id)
  roleVisible.value = true
}

async function submitRole(): Promise<void> {
  await userApi.assignRoles(roleForm.id, roleForm.roleIds)
  roleVisible.value = false
  ElMessage.success('角色已更新，该用户的会话已失效，需重新登录')
  await loadUsers()
}

// ---------------------------------------------------------------- 重置密码 / 删除

async function resetPassword(row: UserItem): Promise<void> {
  await ElMessageBox.confirm(
    `将为「${row.username}」生成新的随机密码，该用户当前登录状态会立即失效。是否继续？`,
    '重置密码',
    { type: 'warning', confirmButtonText: '确认重置', cancelButtonText: '取消' }
  )
  const result = await userApi.resetPassword(row.id)
  showCredential(result.username, result.password)
}

async function removeUser(row: UserItem): Promise<void> {
  await ElMessageBox.confirm(
    `删除后「${row.username}」将无法登录，其历史操作记录仍保留在审计日志中。是否继续？`,
    '删除用户',
    { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
  )
  await userApi.remove(row.id)
  ElMessage.success('已删除')
  await loadUsers()
}

// ---------------------------------------------------------------- 展示辅助

const roleTagType = (code: string) => {
  if (code === 'ADMIN') return 'danger'
  if (code === 'REVIEWER') return 'warning'
  return 'primary'
}

const canCreate = computed(() => userStore.hasPermission('system:user:create'))
const canUpdate = computed(() => userStore.hasPermission('system:user:update'))
const canDelete = computed(() => userStore.hasPermission('system:user:delete'))
const canResetPassword = computed(() => userStore.hasPermission('system:user:reset-password'))
</script>

<template>
  <div class="cr-page">
    <el-card shadow="never" class="filter-card">
      <div class="filter-bar">
        <el-input
          v-model="query.keyword"
          placeholder="登录名 / 显示名 / 邮箱"
          clearable
          class="filter-input"
          @keyup.enter="((query.page = 1), loadUsers())"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <el-select v-model="query.status" placeholder="全部状态" clearable class="filter-select">
          <el-option label="启用" value="ENABLED" />
          <el-option label="禁用" value="DISABLED" />
        </el-select>

        <el-select v-model="query.roleId" placeholder="全部角色" clearable class="filter-select">
          <el-option v-for="role in roles" :key="role.id" :label="role.name" :value="role.id" />
        </el-select>

        <el-button type="primary" @click="((query.page = 1), loadUsers())">查询</el-button>
        <el-button :icon="Refresh" @click="resetQuery">重置</el-button>

        <div class="filter-spacer" />

        <el-button v-if="canCreate" type="primary" :icon="Plus" @click="openCreate">新增用户</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column prop="username" label="登录名" min-width="140">
          <template #default="{ row }">
            <span class="mono">{{ row.username }}</span>
            <el-tag v-if="row.passwordResetRequired" size="small" type="warning" effect="plain" class="ml-6">
              待改密
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="nickname" label="显示名" min-width="120" />
        <el-table-column prop="email" label="邮箱" min-width="180">
          <template #default="{ row }">{{ row.email || '—' }}</template>
        </el-table-column>
        <el-table-column label="角色" min-width="200">
          <template #default="{ row }">
            <template v-if="row.roles.length">
              <el-tag
                v-for="role in row.roles"
                :key="role.id"
                size="small"
                effect="plain"
                :type="roleTagType(role.code)"
                class="mr-4"
              >
                {{ role.name }}
              </el-tag>
            </template>
            <span v-else class="cr-text-muted">未分配</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status === 'ENABLED'"
              :disabled="!canUpdate || isSelf(row)"
              @update:model-value="(value: boolean) => toggleStatus(row, value)"
            />
          </template>
        </el-table-column>
        <el-table-column label="最近登录" width="170">
          <template #default="{ row }">
            <span class="cr-text-muted">{{ row.lastLoginAt ? row.lastLoginAt.replace('T', ' ').slice(0, 19) : '从未登录' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canUpdate" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="canUpdate" link type="primary" @click="openRole(row)">角色</el-button>
            <el-button v-if="canResetPassword" link type="warning" @click="resetPassword(row)">重置密码</el-button>
            <el-tooltip v-if="canDelete" :disabled="!isSelf(row)" content="不能删除当前登录账号">
              <span>
                <el-button link type="danger" :disabled="isSelf(row)" @click="removeUser(row)">删除</el-button>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="loadUsers"
          @size-change="((query.page = 1), loadUsers())"
        />
      </div>
    </el-card>

    <!-- 创建用户 -->
    <el-dialog v-model="createVisible" title="新增用户" width="520px" :close-on-click-modal="false">
      <el-form label-width="90px" label-position="right">
        <el-form-item label="登录名" required>
          <el-input v-model="createForm.username" placeholder="3-32 位，字母开头，如 zhangsan" />
          <div class="form-hint">创建后不可修改，将用于登录与审计追溯</div>
        </el-form-item>
        <el-form-item label="显示名" required>
          <el-input v-model="createForm.nickname" placeholder="如 张三" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="createForm.email" placeholder="选填" />
        </el-form-item>
        <el-form-item label="初始密码">
          <el-input v-model="createForm.password" placeholder="留空则由系统生成 12 位随机密码" show-password />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="createForm.roleIds" multiple placeholder="可稍后分配" style="width: 100%">
            <el-option v-for="role in roles" :key="role.id" :label="`${role.name}（${role.code}）`" :value="role.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 编辑用户 -->
    <el-dialog v-model="editVisible" title="编辑用户" width="480px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <el-form-item label="显示名" required>
          <el-input v-model="editForm.nickname" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="editForm.email" placeholder="选填" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="editForm.status">
            <el-radio value="ENABLED">启用</el-radio>
            <el-radio value="DISABLED">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 分配角色 -->
    <el-dialog v-model="roleVisible" :title="`分配角色 · ${roleForm.username}`" width="480px">
      <el-checkbox-group v-model="roleForm.roleIds" class="role-group">
        <el-checkbox v-for="role in roles" :key="role.id" :value="role.id" :disabled="role.code === 'ADMIN' && !userStore.isAdmin">
          <span>{{ role.name }}</span>
          <span class="cr-text-muted role-desc">{{ role.description || role.code }}</span>
        </el-checkbox>
      </el-checkbox-group>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="保存后该用户全部会话立即失效，需要重新登录才能获得新权限"
        class="mt-8"
      />
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRole">保存</el-button>
      </template>
    </el-dialog>

    <!-- 初始密码（一次性展示） -->
    <el-dialog v-model="credentialVisible" title="初始密码（仅显示一次）" width="480px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" show-icon title="关闭后无法再次查看，请立即转交用户" class="mb-12" />
      <div class="credential-box">
        <div class="credential-row">
          <span class="credential-label">登录名</span>
          <span class="mono">{{ credential.username }}</span>
        </div>
        <div class="credential-row">
          <span class="credential-label">初始密码</span>
          <span class="mono credential-password">{{ credential.password }}</span>
        </div>
      </div>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="该用户首次登录后会被要求修改密码"
        class="mt-12"
      />
      <template #footer>
        <el-button @click="credentialVisible = false">我已记录</el-button>
        <el-button type="primary" @click="copyCredential">复制</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.filter-input {
  width: 240px;
}

.filter-select {
  width: 150px;
}

.filter-spacer {
  flex: 1;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.mono {
  font-family: var(--cr-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}

.ml-6 {
  margin-left: 6px;
}

.mr-4 {
  margin-right: 4px;
}

.mt-8 {
  margin-top: 8px;
}

.mt-12 {
  margin-top: 12px;
}

.mb-12 {
  margin-bottom: 12px;
}

.form-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  margin-top: 2px;
}

.role-group {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.role-desc {
  margin-left: 8px;
  font-size: 12px;
}

.credential-box {
  background: var(--el-fill-color-light);
  border-radius: 6px;
  padding: 16px;
}

.credential-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 6px 0;
}

.credential-label {
  width: 72px;
  color: var(--el-text-color-secondary);
}

.credential-password {
  font-size: 18px;
  letter-spacing: 1px;
  font-weight: 600;
}
</style>

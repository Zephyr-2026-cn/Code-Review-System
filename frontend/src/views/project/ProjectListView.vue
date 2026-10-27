<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Key, Link, Plus, Refresh, Search, VideoPlay } from '@element-plus/icons-vue'
import {
  credentialApi,
  projectApi,
  type CredentialItem,
  type ProjectItem,
  type SyncStatus
} from '@/api/project'
import { useUserStore } from '@/stores/user'

/**
 * 项目管理页（仓库接入）。
 *
 * 同步是异步的：绑定 / 触发同步后接口立刻返回，真正克隆在后台线程执行。
 * 页面用轮询把状态推到终态（READY / FAILED），超时上限 3 分钟 ——
 * 后端克隆超时是 120s，留一分钟余量。轮询比"长时间挂起的请求"体验更好，
 * 也不会因为反向代理超时导致前端看到假的失败。
 */
const userStore = useUserStore()

const loading = ref(false)
const rows = ref<ProjectItem[]>([])
const total = ref(0)
const credentials = ref<CredentialItem[]>([])

const query = reactive({
  page: 1,
  pageSize: 20,
  keyword: '',
  syncStatus: '' as SyncStatus | '',
  mineOnly: false
})

/** 正在轮询的项目 ID，用于按钮 loading 与防重复点击。 */
const syncing = ref<Set<number>>(new Set())
const pollTimers = new Map<number, number>()

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await projectApi.page({
      page: query.page,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      syncStatus: query.syncStatus || undefined,
      mineOnly: query.mineOnly || undefined
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function loadCredentials(): Promise<void> {
  try {
    credentials.value = await credentialApi.list()
  } catch {
    // 未配置加密密钥时后端会拒绝，凭证非必需，不影响项目列表使用
    credentials.value = []
  }
}

function search(): void {
  query.page = 1
  load()
}

function resetQuery(): void {
  query.keyword = ''
  query.syncStatus = ''
  query.mineOnly = false
  query.page = 1
  load()
}

onMounted(async () => {
  await Promise.all([load(), loadCredentials()])
})

onBeforeUnmount(() => {
  pollTimers.forEach((timer) => window.clearTimeout(timer))
  pollTimers.clear()
})

// ---------------------------------------------------------------- 同步轮询

const TERMINAL: SyncStatus[] = ['READY', 'FAILED', 'UNBOUND']

function stopPoll(id: number): void {
  const timer = pollTimers.get(id)
  if (timer) {
    window.clearTimeout(timer)
    pollTimers.delete(id)
  }
  syncing.value.delete(id)
}

/**
 * 轮询项目状态直到终态。
 *
 * 用递归 setTimeout 而不是 setInterval：下一次请求在上一次返回之后才发出，
 * 后端慢的时候不会堆积请求；同时便于在组件卸载时精确清理。
 */
function pollUntilSettled(id: number, attempt = 0): void {
  const MAX_ATTEMPTS = 90 // 90 次 × 2s ≈ 3 分钟
  if (attempt > MAX_ATTEMPTS) {
    stopPoll(id)
    ElMessage.warning('同步耗时超出预期，请稍后刷新查看结果')
    return
  }

  const timer = window.setTimeout(async () => {
    try {
      const project = await projectApi.detail(id)
      const index = rows.value.findIndex((item) => item.id === id)
      if (index >= 0) rows.value[index] = project

      if (TERMINAL.includes(project.syncStatus)) {
        stopPoll(id)
        if (project.syncStatus === 'READY') {
          ElMessage.success(`同步完成：${project.lastCommitId ?? ''}`)
        } else if (project.syncStatus === 'FAILED') {
          ElMessage.error(project.lastError || '同步失败')
        }
        return
      }
      pollUntilSettled(id, attempt + 1)
    } catch {
      // 4xx（例如无权限/项目被删）没有必要继续轮询
      stopPoll(id)
    }
  }, 2000)

  pollTimers.set(id, timer)
}

function startPoll(id: number): void {
  if (syncing.value.has(id)) return
  syncing.value.add(id)
  pollUntilSettled(id)
}

// ---------------------------------------------------------------- 新建项目

const createVisible = ref(false)
const createForm = reactive({
  name: '',
  code: '',
  description: '',
  language: 'JAVA',
  repoUrl: '',
  repoBranch: '',
  credentialId: null as number | null
})

function openCreate(): void {
  createForm.name = ''
  createForm.code = ''
  createForm.description = ''
  createForm.language = 'JAVA'
  createForm.repoUrl = ''
  createForm.repoBranch = ''
  createForm.credentialId = null
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  const project = await projectApi.create({
    name: createForm.name,
    code: createForm.code || undefined,
    description: createForm.description || undefined,
    language: createForm.language || 'JAVA',
    repoUrl: createForm.repoUrl || undefined,
    repoBranch: createForm.repoBranch || undefined,
    credentialId: createForm.credentialId
  })
  createVisible.value = false
  ElMessage.success('项目已创建')
  await load()
  if (project.repoUrl) {
    startPoll(project.id)
  }
}

// ---------------------------------------------------------------- 绑定仓库

const bindVisible = ref(false)
const bindForm = reactive({
  id: 0,
  name: '',
  repoUrl: '',
  repoBranch: '',
  credentialId: null as number | null
})
const branchOptions = ref<string[]>([])
const branchLoading = ref(false)

function openBind(row: ProjectItem): void {
  bindForm.id = row.id
  bindForm.name = row.name
  bindForm.repoUrl = row.repoUrl ?? ''
  bindForm.repoBranch = row.repoBranch ?? ''
  bindForm.credentialId = row.credentialId ?? null
  branchOptions.value = []
  bindVisible.value = true
}

async function fetchBranches(): Promise<void> {
  if (!bindForm.repoUrl) {
    ElMessage.warning('请先填写仓库地址')
    return
  }
  branchLoading.value = true
  try {
    // 先落一次绑定再拉分支会改动数据，这里直接复用项目上的地址；
    // 用户改了地址时分支列表可能对不上，因此提示需先保存
    const current = rows.value.find((item) => item.id === bindForm.id)
    if (current?.repoUrl !== bindForm.repoUrl) {
      ElMessage.info('地址已修改，请先保存绑定，再查询分支')
      return
    }
    branchOptions.value = await projectApi.branches(bindForm.id)
    if (!branchOptions.value.length) {
      ElMessage.info('未取到分支列表，可手动输入')
    }
  } finally {
    branchLoading.value = false
  }
}

async function submitBind(): Promise<void> {
  await projectApi.bindRepo(bindForm.id, {
    repoUrl: bindForm.repoUrl,
    repoBranch: bindForm.repoBranch || undefined,
    credentialId: bindForm.credentialId
  })
  bindVisible.value = false
  ElMessage.success('已保存绑定，正在后台克隆')
  await load()
  startPoll(bindForm.id)
}

// ---------------------------------------------------------------- 手动同步 / 删除

async function syncProject(row: ProjectItem): Promise<void> {
  await projectApi.sync(row.id)
  ElMessage.info('已触发同步')
  startPoll(row.id)
  await load()
}

async function removeProject(row: ProjectItem): Promise<void> {
  await ElMessageBox.confirm(
    `删除项目「${row.name}」会同时清理本地代码副本，历史审查任务不受影响。是否继续？`,
    '删除项目',
    { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
  )
  await projectApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

// ---------------------------------------------------------------- 凭据管理

const credentialVisible = ref(false)
const credentialForm = reactive({ name: '', username: 'git', token: '' })

async function openCredentials(): Promise<void> {
  credentialVisible.value = true
  await loadCredentials()
}

async function submitCredential(): Promise<void> {
  await credentialApi.create({
    name: credentialForm.name,
    username: credentialForm.username,
    token: credentialForm.token
  })
  credentialForm.name = ''
  credentialForm.token = ''
  ElMessage.success('凭据已保存（令牌加密存储，不会再次显示）')
  await loadCredentials()
}

async function removeCredential(item: CredentialItem): Promise<void> {
  await ElMessageBox.confirm(`删除凭据「${item.name}」？被项目引用时会被拒绝。`, '删除凭据', {
    type: 'warning',
    confirmButtonText: '确认删除',
    cancelButtonText: '取消'
  })
  await credentialApi.remove(item.id)
  ElMessage.success('已删除')
  await loadCredentials()
}

// ---------------------------------------------------------------- 展示辅助

const statusMeta: Record<SyncStatus, { label: string; type: 'info' | 'warning' | 'success' | 'danger' }> = {
  UNBOUND: { label: '未绑定', type: 'info' },
  CLONING: { label: '克隆中', type: 'warning' },
  READY: { label: '就绪', type: 'success' },
  FAILED: { label: '失败', type: 'danger' }
}

const formatTime = (value?: string | null) => (value ? value.replace('T', ' ').slice(0, 19) : '—')

const canCreate = computed(() => userStore.hasPermission('project:repo:create'))
const canUpdate = computed(() => userStore.hasPermission('project:repo:update'))
const canDelete = computed(() => userStore.hasPermission('project:repo:delete'))
const canManageCredential = computed(() => userStore.hasPermission('project:repo:credential'))
</script>

<template>
  <div class="cr-page">
    <el-card shadow="never" class="filter-card">
      <div class="filter-bar">
        <el-input v-model="query.keyword" placeholder="名称 / 编码 / 仓库地址" clearable class="w-240" @keyup.enter="search">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <el-select v-model="query.syncStatus" placeholder="全部状态" clearable class="w-140">
          <el-option label="未绑定" value="UNBOUND" />
          <el-option label="克隆中" value="CLONING" />
          <el-option label="就绪" value="READY" />
          <el-option label="失败" value="FAILED" />
        </el-select>

        <el-checkbox v-model="query.mineOnly" @change="search">只看我接入的</el-checkbox>

        <el-button type="primary" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="resetQuery">重置</el-button>

        <div class="filter-spacer" />

        <el-button v-if="canManageCredential" :icon="Key" @click="openCredentials">Git 凭据</el-button>
        <el-button v-if="canCreate" type="primary" :icon="Plus" @click="openCreate">接入项目</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column label="项目" min-width="220">
          <template #default="{ row }">
            <div class="project-name">{{ row.name }}</div>
            <div class="project-code mono">{{ row.code }}</div>
          </template>
        </el-table-column>
        <el-table-column label="仓库" min-width="260">
          <template #default="{ row }">
            <div v-if="row.repoUrl" class="repo-url mono" :title="row.repoUrl">{{ row.repoUrl }}</div>
            <span v-else class="cr-text-muted">未绑定</span>
            <div v-if="row.repoBranch" class="repo-branch">分支 {{ row.repoBranch }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusMeta[row.syncStatus as SyncStatus].type" effect="plain">
              {{ statusMeta[row.syncStatus as SyncStatus].label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前提交" min-width="220">
          <template #default="{ row }">
            <template v-if="row.lastCommitId">
              <div class="mono commit-id">{{ row.lastCommitId }}</div>
              <div class="commit-msg" :title="row.lastCommitMessage">{{ row.lastCommitMessage }}</div>
            </template>
            <span v-else class="cr-text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="最近同步" width="170">
          <template #default="{ row }">
            <span class="cr-text-muted">{{ formatTime(row.lastSyncedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canUpdate" link type="primary" :icon="Link" @click="openBind(row)">绑定</el-button>
            <el-button
              v-if="canUpdate"
              link
              type="primary"
              :icon="VideoPlay"
              :loading="syncing.has(row.id)"
              :disabled="!row.repoUrl"
              @click="syncProject(row)"
            >
              同步
            </el-button>
            <el-button v-if="canDelete" link type="danger" :icon="Delete" @click="removeProject(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-alert
        v-for="row in rows.filter((item) => item.syncStatus === 'FAILED')"
        :key="row.id"
        type="error"
        :closable="false"
        show-icon
        class="sync-error"
        :title="`「${row.name}」同步失败：${row.lastError ?? '未知原因'}`"
      />

      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="load"
          @size-change="search"
        />
      </div>
    </el-card>

    <!-- 接入项目 -->
    <el-dialog v-model="createVisible" title="接入项目" width="600px" :close-on-click-modal="false">
      <el-form label-width="96px">
        <el-form-item label="项目名称" required>
          <el-input v-model="createForm.name" placeholder="如 智能代码审查系统" />
        </el-form-item>
        <el-form-item label="项目编码">
          <el-input v-model="createForm.code" placeholder="留空则由仓库地址自动推导" />
          <div class="form-hint">小写字母/数字/下划线/连字符，同时作为本地目录名，创建后不可修改</div>
        </el-form-item>
        <el-form-item label="主语言">
          <el-select v-model="createForm.language" style="width: 100%">
            <el-option label="Java（双引擎完整能力）" value="JAVA" />
            <el-option label="Python（插件化静态分析）" value="PYTHON" />
            <el-option label="TypeScript" value="TYPESCRIPT" />
            <el-option label="Go" value="GO" />
          </el-select>
        </el-form-item>
        <el-form-item label="仓库地址">
          <el-input v-model="createForm.repoUrl" placeholder="https://github.com/owner/repo.git（留空可稍后绑定）" />
          <div class="form-hint">仅支持 https；不允许包含用户名密码，私有仓库请使用凭据</div>
        </el-form-item>
        <el-form-item label="分支">
          <el-input v-model="createForm.repoBranch" placeholder="留空使用远端默认分支" />
        </el-form-item>
        <el-form-item label="访问凭据">
          <el-select v-model="createForm.credentialId" placeholder="公开仓库可留空" clearable style="width: 100%">
            <el-option v-for="item in credentials" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 绑定仓库 -->
    <el-dialog v-model="bindVisible" :title="`绑定仓库 · ${bindForm.name}`" width="560px" :close-on-click-modal="false">
      <el-form label-width="96px">
        <el-form-item label="仓库地址" required>
          <el-input v-model="bindForm.repoUrl" placeholder="https://github.com/owner/repo.git" />
        </el-form-item>
        <el-form-item label="分支">
          <el-select
            v-model="bindForm.repoBranch"
            filterable
            allow-create
            clearable
            placeholder="留空使用远端默认分支"
            :loading="branchLoading"
            style="width: 100%"
          >
            <el-option v-for="branch in branchOptions" :key="branch" :label="branch" :value="branch" />
          </el-select>
          <el-button link type="primary" :loading="branchLoading" @click="fetchBranches">从远端拉取分支列表</el-button>
        </el-form-item>
        <el-form-item label="访问凭据">
          <el-select v-model="bindForm.credentialId" placeholder="公开仓库可留空" clearable style="width: 100%">
            <el-option v-for="item in credentials" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="保存后会在后台浅克隆（depth=1）当前分支，可在列表中查看进度"
      />
      <template #footer>
        <el-button @click="bindVisible = false">取消</el-button>
        <el-button type="primary" @click="submitBind">保存并克隆</el-button>
      </template>
    </el-dialog>

    <!-- 凭据管理 -->
    <el-dialog v-model="credentialVisible" title="Git 凭据" width="600px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="令牌使用 AES-256-GCM 加密存储，创建后不可查看，需要更换请删除后重建"
        class="mb-12"
      />
      <el-table :data="credentials" size="small" max-height="220">
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">
            <span class="cr-text-muted">{{ formatTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeCredential(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-divider content-position="left">新增凭据</el-divider>
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="credentialForm.name" placeholder="如 github-pat" />
        </el-form-item>
        <el-form-item label="用户名" required>
          <el-input v-model="credentialForm.username" placeholder="使用 PAT 时可填 git" />
        </el-form-item>
        <el-form-item label="令牌" required>
          <el-input v-model="credentialForm.token" type="password" show-password placeholder="Personal Access Token" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="credentialVisible = false">关闭</el-button>
        <el-button type="primary" @click="submitCredential">保存凭据</el-button>
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

.filter-spacer {
  flex: 1;
}

.w-140 {
  width: 140px;
}
.w-240 {
  width: 240px;
}

.mono {
  font-family: var(--cr-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}

.project-name {
  font-weight: 600;
  font-size: 14px;
}

.project-code {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.repo-url {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.repo-branch {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.commit-id {
  font-size: 12px;
}

.commit-msg {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sync-error {
  margin-top: 12px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.form-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  margin-top: 2px;
}

.mb-12 {
  margin-bottom: 12px;
}
</style>

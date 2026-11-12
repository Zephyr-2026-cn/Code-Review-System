<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search, View } from '@element-plus/icons-vue'
import { projectApi, type CommitInfo, type ProjectItem } from '@/api/project'
import { isTerminal, taskApi, type ReviewMode, type TaskItem, type TaskStatus } from '@/api/task'
import { useUserStore } from '@/stores/user'

/**
 * 审查任务列表。
 *
 * 轮询策略：只要当前页存在未完成任务，就每 2.5 秒刷新一次；全部进入终态后自动停止。
 * 不做逐行轮询——一个列表页最多 20 行，整页刷新比分页维护 20 个定时器更不容易出错。
 */
const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const rows = ref<TaskItem[]>([])
const total = ref(0)
const projects = ref<ProjectItem[]>([])

const query = reactive({
  page: 1,
  pageSize: 20,
  projectId: undefined as number | undefined,
  status: '' as TaskStatus | '',
  activeOnly: false,
  mineOnly: false,
  commit: ''
})

let pollTimer: number | null = null

const canCreate = computed(() => userStore.hasPermission('task:create'))
const canCancel = computed(() => userStore.hasPermission('task:cancel'))

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await taskApi.page({
      page: query.page,
      pageSize: query.pageSize,
      projectId: query.projectId,
      status: query.status || undefined,
      activeOnly: query.activeOnly || undefined,
      mineOnly: query.mineOnly || undefined,
      commit: query.commit || undefined
    })
    rows.value = page.records
    total.value = page.total
    schedulePoll()
  } finally {
    loading.value = false
  }
}

async function loadProjects(): Promise<void> {
  const page = await projectApi.page({ page: 1, pageSize: 100 })
  projects.value = page.records
}

/** 存在未完成任务时安排下一次刷新；否则清掉定时器。 */
function schedulePoll(): void {
  const hasActive = rows.value.some((row) => !isTerminal(row.status))
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
    pollTimer = null
  }
  if (!hasActive) {
    return
  }
  pollTimer = window.setTimeout(async () => {
    try {
      const page = await taskApi.page({
        page: query.page,
        pageSize: query.pageSize,
        projectId: query.projectId,
        status: query.status || undefined,
        activeOnly: query.activeOnly || undefined,
        mineOnly: query.mineOnly || undefined,
        commit: query.commit || undefined
      })
      rows.value = page.records
      total.value = page.total
      schedulePoll()
    } catch {
      // 轮询失败不弹提示，避免网络抖动时连续打扰
      pollTimer = window.setTimeout(schedulePoll, 5000)
    }
  }, 2500)
}

function search(): void {
  query.page = 1
  load()
}

function resetQuery(): void {
  query.projectId = undefined
  query.status = ''
  query.activeOnly = false
  query.mineOnly = false
  query.commit = ''
  query.page = 1
  load()
}

onMounted(async () => {
  await Promise.all([load(), loadProjects()])
})

onBeforeUnmount(() => {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
  }
})

// ---------------------------------------------------------------- 发起审查

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({
  projectId: undefined as number | undefined,
  reviewMode: 'FULL' as ReviewMode,
  baseCommit: '' as string,
  spotbugs: false,
  // 默认关闭：语义审查按 token 计费，应该由使用者主动勾选
  llm: false,
  /** 只在增量模式下加载，全量模式不需要 */
  commits: [] as CommitInfo[],
  commitsLoading: false
})

const readyProjects = computed(() => projects.value.filter((item) => item.syncStatus === 'READY'))

const canSubmit = computed(() => {
  if (!createForm.projectId) return false
  return createForm.reviewMode === 'FULL' || Boolean(createForm.baseCommit)
})

function openCreate(): void {
  createForm.projectId = readyProjects.value[0]?.id
  createForm.reviewMode = 'FULL'
  createForm.baseCommit = ''
  createForm.spotbugs = false
  createForm.llm = false
  createForm.commits = []
  createVisible.value = true
}

/** 切到增量模式时才去拉历史，避免无谓请求 */
async function onModeChange(mode: ReviewMode): Promise<void> {
  createForm.baseCommit = ''
  if (mode !== 'INCREMENTAL' || !createForm.projectId) {
    createForm.commits = []
    return
  }
  createForm.commitsLoading = true
  try {
    const list = await projectApi.commits(createForm.projectId, 30)
    // 第一条是当前 HEAD，把它排除：基线必须早于 HEAD 才有 diff 可算
    createForm.commits = list.slice(1)
  } finally {
    createForm.commitsLoading = false
  }
}

async function submitCreate(): Promise<void> {
  if (!createForm.projectId) {
    ElMessage.warning('请选择要审查的项目')
    return
  }
  if (createForm.reviewMode === 'INCREMENTAL' && !createForm.baseCommit) {
    ElMessage.warning('增量审查需要选择基线提交')
    return
  }
  creating.value = true
  try {
    const task = await taskApi.create({
      projectId: createForm.projectId,
      reviewMode: createForm.reviewMode,
      baseCommit: createForm.reviewMode === 'INCREMENTAL' ? createForm.baseCommit : undefined,
      engineFlags: { pmd: true, spotbugs: createForm.spotbugs, llm: createForm.llm, rag: false }
    })
    createVisible.value = false
    ElMessage.success(`已提交审查任务 #${task.id}`)
    await load()
  } finally {
    creating.value = false
  }
}

// ---------------------------------------------------------------- 取消

async function cancelTask(row: TaskItem): Promise<void> {
  await ElMessageBox.confirm(
    `取消任务 #${row.id}？未开始的任务会立即取消，执行中的任务会在当前阶段结束后停止。`,
    '取消任务',
    { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '返回' }
  )
  await taskApi.cancel(row.id)
  ElMessage.success('已提交取消请求')
  await load()
}

function openReport(row: TaskItem): void {
  router.push(`/tasks/${row.id}`)
}

// ---------------------------------------------------------------- 展示辅助

const statusType: Record<TaskStatus, 'info' | 'primary' | 'warning' | 'success' | 'danger'> = {
  CREATED: 'info',
  QUEUED: 'info',
  FETCHING: 'warning',
  SLICING: 'warning',
  ANALYZING: 'primary',
  FUSING: 'primary',
  DONE: 'success',
  PARTIAL: 'warning',
  FAILED: 'danger',
  CANCELED: 'info'
}

const formatTime = (value?: string | null) => (value ? value.replace('T', ' ').slice(0, 19) : '—')

const formatDuration = (ms?: number | null) => {
  if (!ms || ms < 0) return '—'
  if (ms < 1000) return `${ms}ms`
  const seconds = ms / 1000
  return seconds < 60 ? `${seconds.toFixed(1)}s` : `${(seconds / 60).toFixed(1)}min`
}
</script>

<template>
  <div class="cr-page">
    <el-card shadow="never" class="filter-card">
      <div class="filter-bar">
        <el-select
          v-model="query.projectId"
          placeholder="全部项目"
          clearable
          style="width: 200px"
          @change="search"
        >
          <el-option v-for="item in projects" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>

        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 130px" @change="search">
          <el-option label="排队中" value="QUEUED" />
          <el-option label="分析中" value="ANALYZING" />
          <el-option label="已完成" value="DONE" />
          <el-option label="部分完成" value="PARTIAL" />
          <el-option label="失败" value="FAILED" />
          <el-option label="已取消" value="CANCELED" />
        </el-select>

        <el-input
          v-model="query.commit"
          placeholder="提交号前缀"
          clearable
          style="width: 160px"
          @keyup.enter="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <el-checkbox v-model="query.activeOnly" @change="search">只看未完成</el-checkbox>
        <el-checkbox v-model="query.mineOnly" @change="search">只看我发起的</el-checkbox>

        <div class="filter-actions">
          <el-button @click="resetQuery">重置</el-button>
          <el-button :icon="Refresh" @click="load">刷新</el-button>
          <el-button v-if="canCreate" type="primary" :icon="Plus" @click="openCreate">发起审查</el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="rows" border stripe size="small">
        <el-table-column label="任务" width="180">
          <template #default="{ row }">
            <div class="task-cell">
              <span class="task-id">#{{ row.id }}</span>
              <span class="task-commit">{{ row.shortCommit ?? '—' }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="项目" min-width="160">
          <template #default="{ row }">{{ row.projectName ?? '—' }}</template>
        </el-table-column>

        <el-table-column label="模式" width="90">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.reviewMode === 'FULL' ? '全量' : '增量' }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="200">
          <template #default="{ row }">
            <div class="status-cell">
              <el-tag size="small" :type="statusType[row.status as TaskStatus]">{{ row.statusLabel }}</el-tag>
              <el-progress
                v-if="!isTerminal(row.status)"
                :percentage="row.progress"
                :stroke-width="6"
                :show-text="false"
                class="status-progress"
              />
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="issueCount" label="缺陷数" width="90" align="right">
          <template #default="{ row }">
            <span :class="{ 'has-issue': row.issueCount > 0 }">{{ row.issueCount }}</span>
          </template>
        </el-table-column>

        <el-table-column label="耗时" width="100" align="right">
          <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
        </el-table-column>

        <el-table-column label="发起人" width="110">
          <template #default="{ row }">{{ row.createdByName ?? '—' }}</template>
        </el-table-column>

        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="View" @click="openReport(row)">报告</el-button>
            <el-button
              v-if="canCancel && !isTerminal(row.status)"
              link
              type="danger"
              @click="cancelTask(row)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="还没有审查任务，先从「发起审查」开始" :image-size="80" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="load"
          @size-change="search"
        />
      </div>
    </el-card>

    <el-dialog v-model="createVisible" title="发起审查" width="520px">
      <el-form label-width="96px">
        <el-form-item label="项目" required>
          <el-select v-model="createForm.projectId" placeholder="选择已同步的项目" style="width: 100%">
            <el-option v-for="item in readyProjects" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" />
          </el-select>
          <div v-if="!readyProjects.length" class="form-tip">
            没有已同步的项目，请先到「项目管理」完成仓库接入与同步
          </div>
        </el-form-item>

        <el-form-item label="审查模式">
          <el-radio-group v-model="createForm.reviewMode" @change="onModeChange(createForm.reviewMode)">
            <el-radio value="FULL">全量</el-radio>
            <el-radio value="INCREMENTAL">增量</el-radio>
          </el-radio-group>
          <div class="form-tip">
            全量扫描当前提交的全部源码；增量只审查基线到当前 HEAD 之间的变更文件，速度快、省 token
          </div>
        </el-form-item>

        <el-form-item v-if="createForm.reviewMode === 'INCREMENTAL'" label="基线提交" required>
          <el-select
            v-model="createForm.baseCommit"
            :loading="createForm.commitsLoading"
            placeholder="选择上线的那个提交"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="commit in createForm.commits"
              :key="commit.commitId"
              :label="`${commit.shortCommitId} ${commit.message}`"
              :value="commit.commitId"
            />
          </el-select>
          <div v-if="!createForm.commitsLoading && !createForm.commits.length" class="form-tip">
            本地没有更早的历史提交（浅克隆深度限制了可选范围），请先同步仓库或改用全量审查
          </div>
        </el-form-item>

        <el-form-item label="引擎">
          <div class="engine-list">
            <div class="engine-item">
              <el-switch :model-value="true" disabled />
              <span>PMD 静态分析</span>
              <span class="cr-text-muted">源码级，零编译依赖</span>
            </div>
            <div class="engine-item">
              <el-switch v-model="createForm.llm" />
              <span>大模型语义审查</span>
              <span class="cr-text-muted">按 token 计费，只送审前 40 个文件</span>
            </div>
            <div class="engine-item">
              <el-switch v-model="createForm.spotbugs" />
              <span>SpotBugs</span>
              <span class="cr-text-muted">需项目可编译，演示环境建议关闭</span>
            </div>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" :disabled="!canSubmit" @click="submitCreate">
          开始审查
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-card {
  margin-bottom: 12px;
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.filter-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}

.table-card {
  border-radius: var(--cr-radius);
}

.task-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

.task-id {
  font-weight: 600;
}

.task-commit {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.status-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.status-progress {
  width: 100%;
}

.has-issue {
  color: var(--el-color-danger);
  font-weight: 600;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.engine-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.engine-item {
  display: grid;
  grid-template-columns: 44px 130px 1fr;
  align-items: center;
  gap: 8px;
}
</style>

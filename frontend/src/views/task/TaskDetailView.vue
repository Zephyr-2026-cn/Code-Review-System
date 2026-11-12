<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Refresh, Warning } from '@element-plus/icons-vue'
import {
  isTerminal,
  taskApi,
  type IssueItem,
  type Severity,
  type TaskItem,
  type TaskReport,
  type TaskStatus
} from '@/api/task'
import { useUserStore } from '@/stores/user'

/**
 * 任务详情 / 审查报告页。
 *
 * 一个页面承担两个角色：任务未完成时看进度，完成后看报告。
 * 这样用户点进来之后不需要再跳一次页面，也避免"报告还没生成"的空态。
 */
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const taskId = computed(() => Number(route.params.id))
const task = ref<TaskItem | null>(null)
const report = ref<TaskReport | null>(null)
const loading = ref(false)

let pollTimer: number | null = null

const canCancel = computed(() => userStore.hasPermission('task:cancel'))

async function load(): Promise<void> {
  loading.value = true
  try {
    const detail = await taskApi.detail(taskId.value)
    task.value = detail
    if (isTerminal(detail.status)) {
      report.value = await taskApi.report(taskId.value)
      await loadIssues()
    }
    schedulePoll()
  } finally {
    loading.value = false
  }
}

/** 未完成时每 2 秒刷新一次，进入终态后停表并拉一次报告。 */
function schedulePoll(): void {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
    pollTimer = null
  }
  if (!task.value || isTerminal(task.value.status)) {
    return
  }
  pollTimer = window.setTimeout(async () => {
    try {
      const detail = await taskApi.detail(taskId.value)
      task.value = detail
      if (isTerminal(detail.status)) {
        report.value = await taskApi.report(taskId.value)
        await loadIssues()
      }
      schedulePoll()
    } catch {
      pollTimer = window.setTimeout(schedulePoll, 5000)
    }
  }, 2000)
}

onMounted(load)

onBeforeUnmount(() => {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
  }
})

// ---------------------------------------------------------------- 缺陷列表

const issues = ref<IssueItem[]>([])
const issueTotal = ref(0)
const issueLoading = ref(false)
const issueQuery = reactive({
  page: 1,
  pageSize: 20,
  severity: '' as Severity | '',
  category: ''
})

async function loadIssues(): Promise<void> {
  issueLoading.value = true
  try {
    const page = await taskApi.issues(taskId.value, {
      page: issueQuery.page,
      pageSize: issueQuery.pageSize,
      severity: issueQuery.severity || undefined,
      category: issueQuery.category || undefined
    })
    issues.value = page.records
    issueTotal.value = page.total
  } finally {
    issueLoading.value = false
  }
}

function filterIssues(): void {
  issueQuery.page = 1
  loadIssues()
}

// ---------------------------------------------------------------- 缺陷详情

const drawerVisible = ref(false)
const currentIssue = ref<IssueItem | null>(null)

function openIssue(row: IssueItem): void {
  currentIssue.value = row
  drawerVisible.value = true
}

// ---------------------------------------------------------------- 取消

async function cancelTask(): Promise<void> {
  if (!task.value) return
  await ElMessageBox.confirm('取消后已产生的部分结果会被丢弃，是否继续？', '取消任务', {
    type: 'warning',
    confirmButtonText: '确认取消',
    cancelButtonText: '返回'
  })
  await taskApi.cancel(task.value.id)
  ElMessage.success('已提交取消请求')
  await load()
}

// ---------------------------------------------------------------- 展示辅助

const STAGES = ['排队', '拉取代码', '代码切片', '引擎分析', '结果融合']

/** 与后端状态机一致的阶段顺序（不含 CREATED，它与排队同一步）。 */
const STAGE_ORDER: TaskStatus[] = ['QUEUED', 'FETCHING', 'SLICING', 'ANALYZING', 'FUSING']

const stepIndex = computed(() => {
  const status = task.value?.status
  if (!status) return 0
  if (status === 'DONE' || status === 'PARTIAL') return STAGE_ORDER.length
  if (status === 'FAILED') {
    // 失败时把进度停在出错的阶段上，用户一眼能看出卡在哪
    const failStage = task.value?.failStage as TaskStatus | null
    const index = failStage ? STAGE_ORDER.indexOf(failStage) : -1
    return index >= 0 ? index : 0
  }
  if (status === 'CREATED' || status === 'CANCELED') return 0
  const index = STAGE_ORDER.indexOf(status)
  return index >= 0 ? index : 0
})

const stepStatus = computed<'wait' | 'process' | 'finish' | 'error'>(() => {
  const status = task.value?.status
  if (status === 'FAILED') return 'error'
  if (status === 'CANCELED') return 'wait'
  if (status === 'DONE' || status === 'PARTIAL') return 'finish'
  return 'process'
})

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

const severityMeta: Record<Severity, { label: string; color: string; tag: 'danger' | 'warning' | 'info' }> = {
  BLOCKER: { label: '阻断', color: '#c45656', tag: 'danger' },
  CRITICAL: { label: '严重', color: '#e6a23c', tag: 'danger' },
  MAJOR: { label: '主要', color: '#f0a020', tag: 'warning' },
  MINOR: { label: '次要', color: '#909399', tag: 'info' },
  INFO: { label: '提示', color: '#a8abb2', tag: 'info' }
}

const severityRows = computed(() => {
  const map = report.value?.summary.bySeverity ?? {}
  const total = report.value?.summary.total ?? 0
  return (['BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR', 'INFO'] as Severity[]).map((severity) => {
    const count = map[severity] ?? 0
    return {
      severity,
      label: severityMeta[severity].label,
      color: severityMeta[severity].color,
      count,
      percent: total > 0 ? Math.round((count / total) * 100) : 0
    }
  })
})

const categoryRows = computed(() =>
  Object.entries(report.value?.summary.byCategory ?? {})
    .map(([key, value]) => ({ key, value }))
    .sort((a, b) => b.value - a.value)
)

const formatTime = (value?: string | null) => (value ? value.replace('T', ' ').slice(0, 19) : '—')

const formatDuration = (ms?: number | null) => {
  if (!ms || ms < 0) return '—'
  if (ms < 1000) return `${ms}ms`
  const seconds = ms / 1000
  return seconds < 60 ? `${seconds.toFixed(1)}s` : `${(seconds / 60).toFixed(1)}min`
}
</script>

<template>
  <div v-loading="loading" class="cr-page">
    <el-card shadow="never" class="head-card">
      <div class="head">
        <div class="head-left">
          <el-button :icon="ArrowLeft" link @click="router.push('/tasks')">返回列表</el-button>
          <span class="head-title">审查任务 #{{ task?.id }}</span>
          <el-tag v-if="task" :type="statusType[task.status]" size="small">{{ task.statusLabel }}</el-tag>
        </div>
        <div class="head-right">
          <el-button :icon="Refresh" @click="load">刷新</el-button>
          <el-button
            v-if="task && canCancel && !isTerminal(task.status)"
            type="danger"
            plain
            @click="cancelTask"
          >
            取消任务
          </el-button>
        </div>
      </div>

      <el-descriptions v-if="task" :column="4" size="small" border class="head-desc">
        <el-descriptions-item label="项目">{{ task.projectName ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="提交">
          <span class="mono">{{ task.shortCommit ?? '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="模式">{{ task.reviewMode === 'FULL' ? '全量' : '增量' }}</el-descriptions-item>
        <el-descriptions-item label="发起人">{{ task.createdByName ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatTime(task.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ formatTime(task.startedAt) }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ formatTime(task.finishedAt) }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ formatDuration(task.durationMs) }}</el-descriptions-item>
      </el-descriptions>

      <el-steps
        v-if="task && !isTerminal(task.status)"
        :active="stepIndex"
        :process-status="stepStatus === 'error' ? 'error' : 'process'"
        align-center
        class="steps"
      >
        <el-step v-for="stage in STAGES" :key="stage" :title="stage" />
      </el-steps>

      <el-alert
        v-if="task?.status === 'FAILED'"
        type="error"
        :closable="false"
        show-icon
        class="alert"
        :title="`执行失败于「${task.failStage ?? '未知阶段'}」`"
        :description="task.failReason ?? '未提供失败原因'"
      />

      <el-alert
        v-if="task?.status === 'PARTIAL'"
        type="warning"
        :closable="false"
        show-icon
        class="alert"
        title="结果不完整"
        description="任务因超时或文件数上限被截断，以下缺陷为已完成部分的结果"
      />
    </el-card>

    <!-- 统计 -->
    <div v-if="report" class="stat-grid">
      <el-card shadow="never" class="stat-card">
        <div class="stat-value">{{ report.summary.total }}</div>
        <div class="stat-label">缺陷总数</div>
      </el-card>
      <el-card shadow="never" class="stat-card">
        <div class="stat-value">{{ report.summary.analyzedFiles }}<span class="unit">/{{ report.summary.scannedFiles }}</span></div>
        <div class="stat-label">已分析 / 扫描文件</div>
      </el-card>
      <el-card shadow="never" class="stat-card">
        <div class="stat-value">{{ formatDuration(report.summary.durationMs) }}</div>
        <div class="stat-label">总耗时</div>
      </el-card>
      <el-card shadow="never" class="stat-card">
        <div class="stat-value">{{ (report.task.stats.engines ?? []).join(' / ') || '—' }}</div>
        <div class="stat-label">参与引擎</div>
      </el-card>
    </div>

    <!-- 分布 -->
    <div v-if="report" class="dist-grid">
      <el-card shadow="never">
        <template #header><span class="card-title">严重度分布</span></template>
        <div v-for="row in severityRows" :key="row.severity" class="dist-row">
          <span class="dist-label">{{ row.label }}</span>
          <el-progress
            :percentage="row.percent"
            :color="row.color"
            :stroke-width="10"
            :format="() => String(row.count)"
            class="dist-bar"
          />
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header><span class="card-title">类别分布</span></template>
        <div v-if="!categoryRows.length" class="cr-text-muted">暂无缺陷</div>
        <div v-for="row in categoryRows" :key="row.key" class="category-row">
          <span>{{ row.key }}</span>
          <el-tag size="small" effect="plain">{{ row.value }}</el-tag>
        </div>
      </el-card>
    </div>

    <!-- 明细 -->
    <el-card v-if="report" shadow="never" class="detail-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">缺陷明细</span>
          <div class="card-actions">
            <el-select v-model="issueQuery.severity" placeholder="全部严重度" clearable style="width: 130px" @change="filterIssues">
              <el-option v-for="(meta, key) in severityMeta" :key="key" :label="meta.label" :value="key" />
            </el-select>
            <el-select v-model="issueQuery.category" placeholder="全部类别" clearable style="width: 150px" @change="filterIssues">
              <el-option v-for="row in categoryRows" :key="row.key" :label="row.key" :value="row.key" />
            </el-select>
          </div>
        </div>
      </template>

      <el-table v-loading="issueLoading" :data="issues" border stripe size="small" @row-click="openIssue">
        <el-table-column label="严重度" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="severityMeta[row.severity as Severity].tag" effect="dark">
              {{ row.severityLabel }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="categoryLabel" label="类别" width="110" />

        <el-table-column label="位置" min-width="300">
          <template #default="{ row }">
            <span class="mono">{{ row.filePath }}:{{ row.startLine }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="title" label="结论" min-width="200" show-overflow-tooltip />
        <el-table-column prop="ruleId" label="规则" width="180" show-overflow-tooltip />

        <el-table-column label="置信度" width="90" align="right">
          <template #default="{ row }">
            {{ row.confidence != null ? Math.round(row.confidence * 100) + '%' : '—' }}
          </template>
        </el-table-column>

        <el-table-column prop="source" label="来源" width="90" />

        <template #empty>
          <el-empty description="没有匹配的缺陷" :image-size="72" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="issueQuery.page"
          v-model:page-size="issueQuery.pageSize"
          :total="issueTotal"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadIssues"
          @size-change="filterIssues"
        />
      </div>
    </el-card>

    <el-empty
      v-if="!report && task && !isTerminal(task.status)"
      description="审查进行中，完成后会自动展示报告"
      :image-size="90"
    />

    <el-drawer v-model="drawerVisible" title="缺陷详情" size="480px">
      <template v-if="currentIssue">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="结论">{{ currentIssue.title }}</el-descriptions-item>
          <el-descriptions-item label="严重度">
            <el-tag size="small" :type="severityMeta[currentIssue.severity].tag" effect="dark">
              {{ currentIssue.severityLabel }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="类别">{{ currentIssue.categoryLabel }}</el-descriptions-item>
          <el-descriptions-item label="位置">
            <span class="mono">{{ currentIssue.filePath }}:{{ currentIssue.startLine }}-{{ currentIssue.endLine }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="规则">{{ currentIssue.ruleId }}</el-descriptions-item>
          <el-descriptions-item label="来源">{{ currentIssue.source }}</el-descriptions-item>
          <el-descriptions-item label="置信度">
            {{ currentIssue.confidence != null ? Math.round(currentIssue.confidence * 100) + '%' : '—' }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="drawer-section">
          <div class="drawer-label"><el-icon><Warning /></el-icon> 详细说明</div>
          <p class="drawer-text">{{ currentIssue.description || '—' }}</p>
        </div>

        <div class="drawer-section">
          <div class="drawer-label">修复建议</div>
          <p class="drawer-text">{{ currentIssue.suggestion || '静态规则未提供修复建议，将在语义审查阶段补充' }}</p>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.head-card {
  margin-bottom: 12px;
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.head-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.head-title {
  font-size: 16px;
  font-weight: 600;
}

.head-right {
  display: flex;
  gap: 8px;
}

.head-desc {
  margin-bottom: 12px;
}

.steps {
  margin: 16px 0 8px;
}

.alert {
  margin-top: 12px;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.stat-card {
  text-align: center;
}

.stat-value {
  font-size: 22px;
  font-weight: 600;
}

.unit {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  font-weight: 400;
}

.stat-label {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.dist-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.dist-row {
  display: grid;
  grid-template-columns: 48px 1fr;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.dist-label {
  font-size: 13px;
}

.dist-bar {
  width: 100%;
}

.category-row {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
  font-size: 13px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-weight: 600;
}

.card-actions {
  display: flex;
  gap: 8px;
}

.detail-card {
  margin-bottom: 12px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}

.drawer-section {
  margin-top: 16px;
}

.drawer-label {
  display: flex;
  align-items: center;
  gap: 4px;
  font-weight: 600;
  margin-bottom: 6px;
}

.drawer-text {
  margin: 0;
  line-height: 1.7;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
}
</style>

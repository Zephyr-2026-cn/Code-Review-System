<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  CopyDocument,
  Refresh,
  Search,
  Top,
  Bottom
} from '@element-plus/icons-vue'
import {
  isTerminal,
  issueApi,
  taskApi,
  type IssueContext,
  type IssueItem,
  type Severity,
  type TaskItem,
  type TaskReport,
  type TaskStatus
} from '@/api/task'
import { useUserStore } from '@/stores/user'
import CodeViewer from '@/components/CodeViewer.vue'
import DiffViewer from '@/components/DiffViewer.vue'

/**
 * 审查报告页。
 *
 * <p>布局按"先看结论、再定位问题、最后看证据"的顺序组织：
 * 顶部是任务与结论徽标，左侧是按文件分组的缺陷树，右侧是代码与双引擎对照。
 * 这是答辩时唯一需要长时间停留的页面，所以交互密度刻意做高：
 * 点一条缺陷就能同时看到代码位置、两个引擎各自的说法、以及修复建议。
 *
 * <p>任务未完成时同一页面切换为进度视图，避免"报告未生成"的空页。
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
      syncSelectionAfterLoad()
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
        syncSelectionAfterLoad()
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

// ---------------------------------------------------------------- 缺陷树

const allIssues = computed<IssueItem[]>(() => report.value?.issues ?? [])

/** 报告接口最多返回 500 条，超出时页面要如实说明，而不是假装这就是全部。 */
const issuesTruncated = computed(() => {
  const total = report.value?.summary.total ?? 0
  return total > allIssues.value.length
})

const filter = reactive({ keyword: '', severity: '' as Severity | '' })

const filteredIssues = computed(() => {
  const keyword = filter.keyword.trim().toLowerCase()
  return allIssues.value.filter((issue) => {
    if (filter.severity && issue.severity !== filter.severity) return false
    if (!keyword) return true
    return (
      issue.title.toLowerCase().includes(keyword) ||
      issue.filePath.toLowerCase().includes(keyword) ||
      (issue.ruleId ?? '').toLowerCase().includes(keyword)
    )
  })
})

interface FileGroup {
  path: string
  name: string
  dir: string
  issues: IssueItem[]
  worst: Severity
}

const SEVERITY_ORDER: Severity[] = ['BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR', 'INFO']

const fileGroups = computed<FileGroup[]>(() => {
  const map = new Map<string, IssueItem[]>()
  for (const issue of filteredIssues.value) {
    const list = map.get(issue.filePath)
    if (list) {
      list.push(issue)
    } else {
      map.set(issue.filePath, [issue])
    }
  }
  const groups: FileGroup[] = []
  for (const [path, issues] of map) {
    const segments = path.split('/')
    const name = segments[segments.length - 1]
    const worst = SEVERITY_ORDER.find((severity) => issues.some((issue) => issue.severity === severity))
    groups.push({
      path,
      name,
      dir: segments.length > 1 ? segments.slice(0, -1).join('/') : '',
      issues,
      worst: worst ?? 'INFO'
    })
  }
  // 缺陷多的文件排前面：报告页最常见的动作是"先看问题最集中的文件"
  return groups.sort((a, b) => b.issues.length - a.issues.length || a.path.localeCompare(b.path))
})

const expanded = ref<Set<string>>(new Set())

function toggleGroup(path: string): void {
  const next = new Set(expanded.value)
  if (next.has(path)) {
    next.delete(path)
  } else {
    next.add(path)
  }
  expanded.value = next
}

const severityCounts = computed(() => {
  const map: Record<string, number> = {}
  for (const issue of allIssues.value) {
    map[issue.severity] = (map[issue.severity] ?? 0) + 1
  }
  return map
})

const flagCounts = computed(() => report.value?.summary.byFlag ?? {})

function filterBySeverity(severity: Severity): void {
  filter.severity = filter.severity === severity ? '' : severity
}

// ---------------------------------------------------------------- 选中与上下文

const selectedId = ref<number | null>(null)
const context = ref<IssueContext | null>(null)
const contextLoading = ref(false)
const viewMode = ref<'code' | 'diff'>('code')

const selectedIssue = computed(() => allIssues.value.find((issue) => issue.id === selectedId.value) ?? null)

/** 首次进入自动选中第一条高优缺陷，避免右侧留白 */
function syncSelectionAfterLoad(): void {
  if (selectedId.value && allIssues.value.some((issue) => issue.id === selectedId.value)) {
    return
  }
  const first = allIssues.value[0]
  if (first) {
    selectIssue(first)
  }
  // 默认展开缺陷最多的两个文件，让人一眼看到分布
  const initial = new Set(fileGroups.value.slice(0, 2).map((group) => group.path))
  expanded.value = initial
}

async function selectIssue(issue: IssueItem): Promise<void> {
  selectedId.value = issue.id
  viewMode.value = 'code'
  if (!expanded.value.has(issue.filePath)) {
    const next = new Set(expanded.value)
    next.add(issue.filePath)
    expanded.value = next
  }
  await loadContext(issue.id)
}

async function loadContext(issueId: number): Promise<void> {
  contextLoading.value = true
  context.value = null
  try {
    context.value = await issueApi.context(issueId, 20)
  } catch {
    // 接口异常已在拦截器统一提示，这里只需保证面板回到空态
    context.value = null
  } finally {
    contextLoading.value = false
  }
}

// 增量任务才有差异视图；切到没有差异的任务时自动回到源码
watch(
  () => context.value?.diff,
  (diff) => {
    if (!diff && viewMode.value === 'diff') {
      viewMode.value = 'code'
    }
  }
)

const currentIndex = computed(() => {
  if (!selectedId.value) return -1
  return filteredIssues.value.findIndex((issue) => issue.id === selectedId.value)
})

/**
 * 缺陷所在行是否落在本次改动范围内。
 *
 * 为 false 时差异视图展示的是"文件里离它最近的改动"——这在语义上没错
 * （缺陷往往指向没被改动的历史代码），但不说清楚会让人以为高亮丢了。
 */
const diffHasIssueLine = computed(() => {
  const diff = context.value?.diff
  const line = selectedIssue.value?.startLine ?? 0
  if (!diff || !line) return false
  return diff.hunks.some((hunk) =>
    hunk.lines.some((item) => item.newLine === line || item.oldLine === line)
  )
})

function step(delta: number): void {
  const list = filteredIssues.value
  const next = currentIndex.value + delta
  if (next < 0 || next >= list.length) return
  selectIssue(list[next])
}

async function copyLocation(): Promise<void> {
  if (!selectedIssue.value) return
  const text = `${selectedIssue.value.filePath}:${selectedIssue.value.startLine}`
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制 ' + text)
  } catch {
    ElMessage.warning('浏览器拒绝访问剪贴板')
  }
}

// ---------------------------------------------------------------- 双引擎对照

interface EngineSide {
  available: boolean
  name: string
  rule: string
  confidence: string
  title: string
  description: string
  suggestion: string
}

const evidence = computed<Record<string, string>>(() => context.value?.evidence ?? {})

/**
 * 静态引擎一侧的表述。
 *
 * M2 时期的数据没有拆分证据，只能退回合并后的描述；
 * 此时 `available` 依然为真，但只展示规则与结论，不硬凑"原始表述"。
 */
const staticSide = computed<EngineSide>(() => {
  const issue = selectedIssue.value
  const ev = evidence.value
  const flag = issue?.flag
  const available = flag === 'CROSS_VALIDATED' || flag === 'STATIC_ONLY' || Boolean(ev.staticRule)
  return {
    available,
    name: '静态规则',
    rule: ev.staticRule ?? issue?.ruleId ?? '—',
    confidence: ev.staticConfidence ?? (issue?.source === 'STATIC' ? formatConfidence(issue.confidence) : '—'),
    // 规则名已经在上一行单独展示，标题再重复一遍只是浪费版面；
    // M2 时期的数据没有拆分的原始表述，此时只给描述更干净
    title: ev.staticTitle || '',
    description: ev.staticDescription || (available ? issue?.description ?? '' : ''),
    suggestion: ''
  }
})

const llmSide = computed<EngineSide>(() => {
  const issue = selectedIssue.value
  const ev = evidence.value
  const flag = issue?.flag
  const available = flag === 'CROSS_VALIDATED' || flag === 'LLM_ONLY' || Boolean(ev.llmRule)
  return {
    available,
    name: '大模型',
    // 单侧命中时没有融合层补的 llmRule，退回模型名——比显示一个占位符更有信息量
    rule: ev.llmRule ?? ev.model ?? '—',
    confidence: ev.llmConfidence ?? (issue?.source === 'LLM' ? formatConfidence(issue.confidence) : '—'),
    title: ev.llmTitle || (available ? issue?.title ?? '' : ''),
    description: ev.llmDescription || (available ? issue?.description ?? '' : ''),
    // 单侧命中的记录里，模型建议存在通用的 suggestion 键上（融合时才会拆成 llmSuggestion）
    suggestion: ev.llmSuggestion || ev.suggestion || ''
  }
})

/** 融合后的建议优先用模型给的（它通常带具体改法），其次才是缺陷本身。 */
const suggestionText = computed(() => {
  const issue = selectedIssue.value
  if (!issue) return ''
  return llmSide.value.suggestion || issue.suggestion || ''
})

/**
 * 是否在结论条下方再贴一遍描述。
 *
 * 侧边卡片已经各自展示引擎的原始表述，而缺陷的 description 在融合时就是两侧拼接的结果，
 * 再显示一遍等于同一段文字出现两次，纯粹占版面。
 */
const showIssueDesc = computed(() => {
  const description = selectedIssue.value?.description?.trim()
  if (!description) return false
  const staticDesc = staticSide.value.available ? staticSide.value.description.trim() : ''
  const llmDesc = llmSide.value.available ? llmSide.value.description.trim() : ''
  if (staticDesc === description || llmDesc === description) return false
  return !(staticDesc && llmDesc && description.includes(staticDesc) && description.includes(llmDesc))
})

const fixSnippet = computed(() => selectedIssue.value?.fixSnippet ?? '')

// ---------------------------------------------------------------- 结论徽标

/**
 * 结论判定。
 *
 * <p>当前用内置固定阈值（有阻断/严重即高风险）。M6 会换成服务端门禁策略驱动，
 * 那时前端只需把这里替换成 `/tasks/{id}/gate` 的返回值，页面结构不用动。
 */
const verdict = computed(() => {
  if (!report.value) return null
  const counts = report.value.summary.bySeverity
  const blocker = counts.BLOCKER ?? 0
  const critical = counts.CRITICAL ?? 0
  const major = counts.MAJOR ?? 0
  if (blocker > 0) {
    return { label: '阻断', type: 'danger' as const, reason: `${blocker} 个阻断级缺陷`, hint: '存在阻断级缺陷，按默认阈值不建议合并' }
  }
  if (critical > 0) {
    return { label: '高风险', type: 'danger' as const, reason: `${critical} 个严重缺陷`, hint: '存在严重缺陷，按默认阈值建议修复后再合并' }
  }
  if (major > 0) {
    return { label: '需关注', type: 'warning' as const, reason: `${major} 个主要缺陷`, hint: '存在主要缺陷，可合并但建议排期修复' }
  }
  return { label: '通过', type: 'success' as const, reason: '无阻断级缺陷', hint: '未发现阻断级或严重缺陷' }
})

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

const formatTime = (value?: string | null) => (value ? value.replace('T', ' ').slice(0, 19) : '—')

const formatConfidence = (value?: number | null): string =>
  value == null ? '—' : `${Math.round(value * 100)}%`

const formatDuration = (ms?: number | null) => {
  if (!ms || ms < 0) return '—'
  if (ms < 1000) return `${ms}ms`
  const seconds = ms / 1000
  return seconds < 60 ? `${seconds.toFixed(1)}s` : `${(seconds / 60).toFixed(1)}min`
}

function flagLabel(flag?: string | null): string {
  switch (flag) {
    case 'CROSS_VALIDATED':
      return '双引擎印证'
    case 'LLM_ONLY':
      return '语义独有'
    case 'STATIC_ONLY':
      return '静态独有'
    default:
      return flag || '—'
  }
}

function flagTagType(flag?: string | null): 'success' | 'warning' | 'info' | 'primary' {
  switch (flag) {
    case 'CROSS_VALIDATED':
      return 'success'
    case 'LLM_ONLY':
      return 'warning'
    case 'STATIC_ONLY':
      return 'info'
    default:
      return 'primary'
  }
}
</script>

<template>
  <div v-loading="loading" class="cr-page">
    <!-- 任务头 + 结论 -->
    <el-card shadow="never" class="head-card">
      <div class="head">
        <div class="head-left">
          <el-button :icon="ArrowLeft" link @click="router.push('/tasks')">返回列表</el-button>
          <span class="head-title">审查任务 #{{ task?.id }}</span>
          <el-tag v-if="task" :type="statusType[task.status]" size="small">{{ task.statusLabel }}</el-tag>
          <el-tag v-if="task?.reviewMode === 'INCREMENTAL'" size="small" effect="plain">增量</el-tag>
        </div>
        <div class="head-right">
          <el-tooltip v-if="verdict" :content="verdict.hint" placement="bottom">
            <div class="verdict" :class="`verdict-${verdict.type}`">
              <span class="verdict-label">{{ verdict.label }}</span>
              <span class="verdict-reason">{{ verdict.reason }}</span>
            </div>
          </el-tooltip>
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

      <div class="meta-line">
        <span class="meta-item">{{ task?.projectName ?? '—' }}</span>
        <span class="meta-item mono">{{ task?.shortCommit ?? '—' }}</span>
        <span class="meta-item">{{ formatTime(task?.createdAt) }}</span>
        <span class="meta-item">耗时 {{ formatDuration(task?.durationMs) }}</span>
        <span v-if="report" class="meta-item">
          已分析 {{ report.summary.analyzedFiles }}/{{ report.summary.scannedFiles }} 文件
        </span>
        <span v-if="report?.task.stats.engines?.length" class="meta-item">
          引擎 {{ (report.task.stats.engines ?? []).join(' + ') }}
        </span>
      </div>

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
        description="任务因超时或预算上限被截断，以下缺陷为已完成部分的结果"
      />
    </el-card>

    <el-empty
      v-if="!report && task && !isTerminal(task.status)"
      description="审查进行中，完成后会自动展示报告"
      :image-size="90"
    />

    <!-- 报告主体 -->
    <div v-if="report" class="report-layout">
      <!-- 左：问题树 -->
      <el-card shadow="never" class="tree-card">
        <template #header>
          <div class="tree-head">
            <span class="card-title">缺陷（{{ report.summary.total }}）</span>
            <el-input
              v-model="filter.keyword"
              :prefix-icon="Search"
              placeholder="筛选标题 / 文件 / 规则"
              size="small"
              clearable
              class="tree-search"
            />
          </div>
        </template>

        <div class="chips">
          <span
            v-for="severity in SEVERITY_ORDER"
            :key="severity"
            class="chip"
            :class="{ active: filter.severity === severity, dim: !severityCounts[severity] }"
            :style="{ '--chip-color': severityMeta[severity].color }"
            @click="filterBySeverity(severity)"
          >
            {{ severityMeta[severity].label }}
            <b>{{ severityCounts[severity] ?? 0 }}</b>
          </span>
        </div>

        <div v-if="flagCounts.CROSS_VALIDATED !== undefined || flagCounts.LLM_ONLY !== undefined" class="flag-line">
          <el-tooltip
            content="同文件、同类别、行距 5 行内被两个引擎独立命中，才会标记为印证；0 表示本次语义审查补充的都是规则没覆盖到的问题"
            placement="bottom"
          >
            <span class="flag-item">
              双引擎印证 <b>{{ flagCounts.CROSS_VALIDATED ?? 0 }}</b>
            </span>
          </el-tooltip>
          <span class="flag-item" v-if="flagCounts.LLM_ONLY">
            语义独有 <b>{{ flagCounts.LLM_ONLY }}</b>
          </span>
          <span class="flag-item" v-if="flagCounts.STATIC_ONLY">
            静态独有 <b>{{ flagCounts.STATIC_ONLY }}</b>
          </span>
        </div>

        <div class="tree-body">
          <div v-if="!fileGroups.length" class="tree-empty">
            {{ allIssues.length ? '没有匹配的缺陷' : '本次审查未发现缺陷' }}
          </div>

          <div v-for="group in fileGroups" :key="group.path" class="file-group">
            <div class="file-head" @click="toggleGroup(group.path)">
              <el-icon class="caret">
                <ArrowDown v-if="expanded.has(group.path)" />
                <ArrowRight v-else />
              </el-icon>
              <span class="file-dot" :style="{ background: severityMeta[group.worst].color }" />
              <span class="file-name">{{ group.name }}</span>
              <span class="file-dir">{{ group.dir }}</span>
              <span class="file-count">{{ group.issues.length }}</span>
            </div>

            <template v-if="expanded.has(group.path)">
              <div
                v-for="issue in group.issues"
                :key="issue.id"
                class="issue-item"
                :class="{ active: issue.id === selectedId }"
                @click="selectIssue(issue)"
              >
                <span class="issue-bar" :style="{ background: severityMeta[issue.severity].color }" />
                <div class="issue-body">
                  <div class="issue-title">{{ issue.title }}</div>
                  <div class="issue-meta">
                    <span class="mono">L{{ issue.startLine }}</span>
                    <span class="issue-category">{{ issue.categoryLabel }}</span>
                    <el-tag v-if="issue.flag === 'CROSS_VALIDATED'" size="small" type="success" effect="plain">
                      印证
                    </el-tag>
                    <el-tag v-else-if="issue.flag === 'LLM_ONLY'" size="small" type="warning" effect="plain">
                      语义
                    </el-tag>
                  </div>
                </div>
              </div>
            </template>
          </div>

          <div v-if="issuesTruncated" class="tree-note">
            共 {{ report.summary.total }} 条，此处仅展示前 {{ allIssues.length }} 条
          </div>
        </div>
      </el-card>

      <!-- 右：代码 + 证据 -->
      <el-card shadow="never" class="detail-card" v-loading="contextLoading">
        <template #header>
          <div class="detail-head">
            <div class="detail-path">
              <template v-if="selectedIssue">
                <span class="mono">{{ selectedIssue.filePath }}</span>
                <span class="mono line-no">:{{ selectedIssue.startLine }}</span>
                <el-button :icon="CopyDocument" link size="small" @click="copyLocation" />
              </template>
              <span v-else class="cr-text-muted">
                {{ allIssues.length ? '从左侧选择一条缺陷' : '本次审查未发现缺陷' }}
              </span>
            </div>
            <div class="detail-actions">
              <el-radio-group v-model="viewMode" size="small">
                <el-radio-button value="code">源码</el-radio-button>
                <el-radio-button value="diff" :disabled="!context?.diff?.hunks?.length">
                  差异
                </el-radio-button>
              </el-radio-group>
              <el-button-group>
                <el-button :icon="Top" size="small" :disabled="currentIndex <= 0" @click="step(-1)" />
                <el-button
                  :icon="Bottom"
                  size="small"
                  :disabled="currentIndex < 0 || currentIndex >= filteredIssues.length - 1"
                  @click="step(1)"
                />
              </el-button-group>
            </div>
          </div>
        </template>

        <div v-if="!selectedIssue" class="detail-placeholder">
          <el-empty
            :description="allIssues.length
              ? '选择左侧缺陷后，这里会显示代码位置与双引擎证据'
              : '本次审查未发现缺陷'"
            :image-size="80"
          />
        </div>

        <template v-else>
          <el-alert
            v-if="context?.notice"
            type="info"
            :closable="false"
            show-icon
            class="notice"
            :title="context.notice"
          />

          <div class="code-block">
            <div v-if="viewMode === 'diff' && context?.diff && !diffHasIssueLine" class="diff-hint">
              该文件本次提交未修改缺陷所在行，下面显示的是距离它最近的改动
            </div>
            <DiffViewer
              v-if="viewMode === 'diff' && context?.diff"
              :hunks="context.diff.hunks"
              :highlight-start="selectedIssue.startLine"
              :highlight-end="selectedIssue.endLine"
              :truncated="context.diff.truncated"
            />
            <CodeViewer
              v-else
              :lines="context?.lines ?? []"
              :highlight-start="selectedIssue.startLine"
              :highlight-end="selectedIssue.endLine"
              :language="context?.language ?? 'java'"
              :scroll-to="selectedIssue.startLine"
            />
            <div v-if="context && context.totalLines" class="code-foot">
              共 {{ context.totalLines }} 行，显示 {{ context.windowStart }}–{{ context.windowEnd }}
            </div>
          </div>

          <!-- 结论 -->
          <div class="issue-head">
            <el-tag :type="severityMeta[selectedIssue.severity].tag" effect="dark" size="small">
              {{ selectedIssue.severityLabel }}
            </el-tag>
            <span class="issue-headline">{{ selectedIssue.title }}</span>
            <el-tag size="small" effect="plain">{{ selectedIssue.categoryLabel }}</el-tag>
            <el-tag :type="flagTagType(selectedIssue.flag)" size="small" effect="plain">
              {{ flagLabel(selectedIssue.flag) }}
            </el-tag>
            <span class="issue-confidence">综合置信度 {{ formatConfidence(selectedIssue.confidence) }}</span>
          </div>

          <p v-if="showIssueDesc" class="issue-desc">{{ selectedIssue.description }}</p>

          <!-- 双引擎对照 -->
          <div class="engine-grid">
            <div class="engine-card" :class="{ muted: !staticSide.available }">
              <div class="engine-head">
                <span class="engine-name">静态规则引擎</span>
                <span v-if="staticSide.available" class="engine-conf">{{ staticSide.confidence }}</span>
              </div>
              <template v-if="staticSide.available">
                <div class="engine-rule mono">{{ staticSide.rule }}</div>
                <div v-if="staticSide.title" class="engine-title">{{ staticSide.title }}</div>
                <div v-if="staticSide.description" class="engine-desc">{{ staticSide.description }}</div>
              </template>
              <div v-else class="engine-none">本次未命中</div>
            </div>

            <div class="engine-card" :class="{ muted: !llmSide.available }">
              <div class="engine-head">
                <span class="engine-name">大模型语义审查</span>
                <span v-if="llmSide.available" class="engine-conf">{{ llmSide.confidence }}</span>
              </div>
              <template v-if="llmSide.available">
                <div class="engine-rule mono">{{ llmSide.rule }}</div>
                <div v-if="llmSide.title" class="engine-title">{{ llmSide.title }}</div>
                <div v-if="llmSide.description" class="engine-desc">{{ llmSide.description }}</div>
              </template>
              <div v-else class="engine-none">本次未命中</div>
            </div>
          </div>

          <!-- 修复建议 -->
          <div v-if="suggestionText || fixSnippet" class="advice">
            <div class="advice-label">修复建议</div>
            <p v-if="suggestionText" class="advice-text">{{ suggestionText }}</p>
            <pre v-if="fixSnippet" class="advice-code">{{ fixSnippet }}</pre>
          </div>
        </template>
      </el-card>
    </div>
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
  margin-bottom: 10px;
  gap: 12px;
  flex-wrap: wrap;
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
  align-items: center;
  gap: 8px;
}

/* 结论徽标：一眼给出"这次审查能不能过" */
.verdict {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 4px 12px;
  border-radius: 14px;
  border: 1px solid transparent;
  cursor: default;
}

.verdict-label {
  font-size: 15px;
  font-weight: 700;
}

.verdict-reason {
  font-size: 12px;
  opacity: 0.85;
}

.verdict-danger {
  color: #c45656;
  background: #fef0f0;
  border-color: #fbc4c4;
}

.verdict-warning {
  color: #b88230;
  background: #fdf6ec;
  border-color: #f5dab1;
}

.verdict-success {
  color: #4e9a5e;
  background: #f0f9eb;
  border-color: #c2e7b0;
}

.meta-line {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}

.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}

.steps {
  margin: 16px 0 8px;
}

.alert {
  margin-top: 12px;
}

/* 主体：左树右详情 */
.report-layout {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.tree-card {
  position: sticky;
  top: 12px;
}

.tree-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-title {
  font-weight: 600;
  white-space: nowrap;
}

.tree-search {
  margin-left: auto;
  width: 180px;
}

.chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 9px;
  border-radius: 11px;
  border: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
  cursor: pointer;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-blank);
  transition: all 0.15s;
}

.chip b {
  color: var(--chip-color);
}

.chip.dim {
  opacity: 0.45;
}

.chip.active {
  border-color: var(--chip-color);
  background: var(--el-fill-color-light);
  color: var(--chip-color);
}

.flag-line {
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
  margin-bottom: 6px;
}

.flag-item b {
  color: var(--el-text-color-primary);
}

.tree-body {
  max-height: calc(100vh - 260px);
  overflow: auto;
}

.tree-empty,
.tree-note {
  padding: 20px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 12.5px;
}

.tree-note {
  padding: 10px 0 2px;
}

.file-group {
  margin-bottom: 2px;
}

.file-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 4px;
  cursor: pointer;
  border-radius: 4px;
  font-size: 13px;
}

.file-head:hover {
  background: var(--el-fill-color-light);
}

.caret {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.file-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: 0 0 auto;
}

.file-name {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12.5px;
  font-weight: 600;
  white-space: nowrap;
}

.file-dir {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  direction: rtl;
  text-align: left;
}

.file-count {
  font-size: 11.5px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color);
  border-radius: 8px;
  padding: 0 7px;
  flex: 0 0 auto;
}

.issue-item {
  display: flex;
  gap: 8px;
  padding: 6px 6px 6px 12px;
  cursor: pointer;
  border-radius: 4px;
  margin-left: 12px;
}

.issue-item:hover {
  background: var(--el-fill-color-light);
}

.issue-item.active {
  background: var(--el-color-primary-light-9);
}

.issue-bar {
  width: 3px;
  border-radius: 2px;
  flex: 0 0 auto;
}

.issue-body {
  min-width: 0;
  flex: 1;
}

.issue-title {
  font-size: 12.5px;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.issue-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 3px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.issue-category {
  white-space: nowrap;
}

/* 右侧详情 */
.detail-card {
  min-height: 320px;
}

.detail-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.detail-path {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
}

.line-no {
  color: var(--el-color-primary);
  font-weight: 600;
}

.detail-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.detail-placeholder {
  padding: 40px 0;
}

.notice {
  margin-bottom: 10px;
}

.code-block {
  margin-bottom: 14px;
}

.code-foot {
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--el-text-color-secondary);
  text-align: right;
}

.diff-hint {
  margin-bottom: 6px;
  padding: 5px 10px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.issue-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.issue-headline {
  font-size: 14px;
  font-weight: 600;
}

.issue-confidence {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.issue-desc {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
}

.engine-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  /* 两侧内容长短差异大，按各自内容高度展示，避免短的一侧被拉出大片空白 */
  align-items: start;
}

.engine-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 10px 12px;
  background: var(--el-fill-color-blank);
}

.engine-card.muted {
  opacity: 0.55;
}

.engine-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.engine-name {
  font-size: 12.5px;
  font-weight: 600;
}

.engine-conf {
  font-size: 11.5px;
  color: var(--el-color-success);
  font-family: 'JetBrains Mono', Consolas, monospace;
}

.engine-rule {
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
  word-break: break-all;
}

.engine-title {
  font-size: 12.5px;
  line-height: 1.55;
  margin-bottom: 4px;
}

.engine-desc {
  font-size: 12px;
  line-height: 1.65;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
  max-height: 140px;
  overflow: auto;
}

.engine-none {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.advice {
  margin-top: 12px;
  border-left: 3px solid var(--el-color-primary-light-5);
  padding-left: 12px;
}

.advice-label {
  font-size: 12.5px;
  font-weight: 600;
  margin-bottom: 4px;
}

.advice-text {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.advice-code {
  margin: 0;
  padding: 10px 12px;
  background: #fbfbfc;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  overflow: auto;
  white-space: pre;
}

/* 窄屏（答辩时用手机打开链接）切单列：问题在上、代码在下 */
@media (max-width: 1100px) {
  .report-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .tree-card {
    position: static;
  }

  .tree-body {
    max-height: 360px;
  }

  .engine-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>

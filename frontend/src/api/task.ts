import { http } from '@/utils/request'
import type { PageResult } from './types'

/**
 * 审查任务与缺陷接口。
 *
 * 任务执行是异步的：创建接口返回时通常还是 QUEUED，
 * 页面用短轮询把状态推到终态（DONE / PARTIAL / FAILED / CANCELED）。
 */

export type TaskStatus =
  | 'CREATED'
  | 'QUEUED'
  | 'FETCHING'
  | 'SLICING'
  | 'ANALYZING'
  | 'FUSING'
  | 'DONE'
  | 'PARTIAL'
  | 'FAILED'
  | 'CANCELED'

export type ReviewMode = 'FULL' | 'INCREMENTAL'

export type Severity = 'BLOCKER' | 'CRITICAL' | 'MAJOR' | 'MINOR' | 'INFO'

export interface TaskItem {
  id: number
  projectId: number
  projectName?: string | null
  projectCode?: string | null
  status: TaskStatus
  statusLabel: string
  progress: number
  reviewMode: ReviewMode
  triggerType: string
  baseCommit?: string | null
  headCommit: string
  shortCommit?: string | null
  failStage?: string | null
  failReason?: string | null
  issueCount: number
  engineFlags: Record<string, unknown>
  stats: TaskStats
  createdBy: number
  createdByName?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  durationMs?: number | null
  createdAt: string
}

export interface TaskStats {
  filesTotal?: number
  filesAnalyzed?: number
  filesSkipped?: number
  filesWithDefects?: number
  defects?: number
  truncated?: boolean
  fetchMs?: number
  sliceMs?: number
  analyzeMs?: number
  fuseMs?: number
  totalMs?: number
  engines?: string[]
  enginesDetail?: Record<string, unknown>
  bySeverity?: Record<string, number>
  byCategory?: Record<string, number>
  bySource?: Record<string, number>
  warnings?: string[]
  error?: string
  [key: string]: unknown
}

export interface TaskQuery {
  page?: number
  pageSize?: number
  projectId?: number
  status?: TaskStatus | ''
  reviewMode?: ReviewMode | ''
  activeOnly?: boolean
  mineOnly?: boolean
  commit?: string
}

export interface TaskCreatePayload {
  projectId: number
  reviewMode?: ReviewMode
  /** 增量审查的基线提交，增量模式必填 */
  baseCommit?: string
  headCommit?: string
  engineFlags?: {
    pmd?: boolean
    spotbugs?: boolean
    llm?: boolean
    rag?: boolean
  }
}

export interface IssueItem {
  id: number
  taskId: number
  projectId: number
  filePath: string
  startLine: number
  endLine: number
  category: string
  categoryLabel: string
  severity: Severity
  severityLabel: string
  title: string
  description?: string | null
  suggestion?: string | null
  fixSnippet?: string | null
  source: string
  ruleId?: string | null
  confidence?: number | null
  flag?: string | null
  status: string
  createdAt: string
}

export interface IssueQuery {
  page?: number
  pageSize?: number
  taskId?: number
  projectId?: number
  severity?: Severity | ''
  category?: string
  source?: string
  status?: string
  filePath?: string
}

/** 代码行：行号由后端算好，前端不再做偏移运算。 */
export interface CodeLine {
  no: number
  text: string
}

export type DiffLineType = 'CONTEXT' | 'ADD' | 'DELETE'

export interface DiffLine {
  type: DiffLineType
  /** 旧文件行号，新增行为 0 */
  oldLine: number
  /** 新文件行号，删除行为 0 */
  newLine: number
  text: string
}

export interface DiffHunk {
  oldStart: number
  newStart: number
  lines: DiffLine[]
}

export interface FileDiff {
  path: string
  hunks: DiffHunk[]
  truncated: boolean
}

/**
 * 缺陷上下文。
 *
 * `notice` 非空表示代码没能正常加载（文件已删除、路径越界、文件过大），
 * 此时 `lines` 为空但缺陷描述与证据仍然可用——报告页据此降级而不是整页报错。
 */
export interface IssueContext {
  issue: IssueItem
  filePath: string
  language: string
  totalLines: number
  windowStart: number
  windowEnd: number
  lines: CodeLine[]
  evidence: Record<string, string>
  diff?: FileDiff | null
  notice?: string | null
}

export interface TaskReport {
  task: TaskItem
  summary: {
    total: number
    bySeverity: Record<string, number>
    byCategory: Record<string, number>
    bySource: Record<string, number>
    /** 融合标记分布：CROSS_VALIDATED / STATIC_ONLY / LLM_ONLY */
    byFlag: Record<string, number>
    scannedFiles: number
    analyzedFiles: number
    truncated: boolean
    durationMs: number
    warnings: string[]
  }
  issues: IssueItem[]
}

export const taskApi = {
  page(query: TaskQuery): Promise<PageResult<TaskItem>> {
    return http.get<PageResult<TaskItem>>('/tasks', { params: query })
  },

  detail(id: number): Promise<TaskItem> {
    return http.get<TaskItem>(`/tasks/${id}`)
  },

  report(id: number): Promise<TaskReport> {
    return http.get<TaskReport>(`/tasks/${id}/report`)
  },

  create(payload: TaskCreatePayload): Promise<TaskItem> {
    return http.post<TaskItem>('/tasks', payload)
  },

  cancel(id: number): Promise<TaskItem> {
    return http.post<TaskItem>(`/tasks/${id}/cancel`)
  },

  issues(taskId: number, query: IssueQuery): Promise<PageResult<IssueItem>> {
    return http.get<PageResult<IssueItem>>(`/tasks/${taskId}/issues`, { params: query })
  }
}

export const issueApi = {
  page(query: IssueQuery): Promise<PageResult<IssueItem>> {
    return http.get<PageResult<IssueItem>>('/issues', { params: query })
  },

  detail(id: number): Promise<IssueItem> {
    return http.get<IssueItem>(`/issues/${id}`)
  },

  /** 代码片段 + 融合证据 + （增量任务的）差异块，报告页右侧面板一次取全。 */
  context(id: number, contextLines?: number): Promise<IssueContext> {
    return http.get<IssueContext>(`/issues/${id}/context`, { params: { contextLines } })
  }
}

/** 终态判定，供轮询使用。 */
export const TERMINAL_STATUS: TaskStatus[] = ['DONE', 'PARTIAL', 'FAILED', 'CANCELED']

export const isTerminal = (status: TaskStatus): boolean => TERMINAL_STATUS.includes(status)

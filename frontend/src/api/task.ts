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

export interface TaskReport {
  task: TaskItem
  summary: {
    total: number
    bySeverity: Record<string, number>
    byCategory: Record<string, number>
    bySource: Record<string, number>
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
  }
}

/** 终态判定，供轮询使用。 */
export const TERMINAL_STATUS: TaskStatus[] = ['DONE', 'PARTIAL', 'FAILED', 'CANCELED']

export const isTerminal = (status: TaskStatus): boolean => TERMINAL_STATUS.includes(status)

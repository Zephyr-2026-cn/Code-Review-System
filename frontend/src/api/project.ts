import { http } from '@/utils/request'
import type { PageResult } from './types'

/**
 * 项目域接口（项目 / 仓库绑定 / Git 凭据）。
 *
 * 注意同步是异步的：绑定或触发同步后，状态会经历
 * UNBOUND → CLONING → READY/FAILED，页面需要轮询详情。
 */

export type SyncStatus = 'UNBOUND' | 'CLONING' | 'READY' | 'FAILED'

export interface ProjectItem {
  id: number
  name: string
  code: string
  description?: string | null
  language: string
  repoUrl?: string | null
  repoBranch?: string | null
  credentialId?: number | null
  credentialName?: string | null
  syncStatus: SyncStatus
  lastSyncedAt?: string | null
  lastCommitId?: string | null
  lastCommitMessage?: string | null
  lastCommitAt?: string | null
  lastError?: string | null
  ownerId: number
  createdAt: string
}

/** 历史提交摘要（增量审查的基线候选） */
export interface CommitInfo {
  commitId: string
  shortCommitId: string
  message: string
  author?: string | null
  commitAt?: string | null
}

export interface ProjectQuery {  page?: number
  pageSize?: number
  keyword?: string
  syncStatus?: SyncStatus | ''
  mineOnly?: boolean
}

export interface ProjectCreatePayload {
  name: string
  code?: string
  description?: string
  language?: string
  repoUrl?: string
  repoBranch?: string
  credentialId?: number | null
}

export interface ProjectUpdatePayload {
  name: string
  description?: string
  language?: string
}

export interface RepoBindPayload {
  repoUrl: string
  repoBranch?: string
  credentialId?: number | null
}

export interface CredentialItem {
  id: number
  name: string
  username: string
  ownerId: number
  createdAt: string
}

export interface CredentialCreatePayload {
  name: string
  username: string
  token: string
}

export const projectApi = {
  page(query: ProjectQuery): Promise<PageResult<ProjectItem>> {
    return http.get<PageResult<ProjectItem>>('/projects', { params: query })
  },

  detail(id: number): Promise<ProjectItem> {
    return http.get<ProjectItem>(`/projects/${id}`)
  },

  create(payload: ProjectCreatePayload): Promise<ProjectItem> {
    return http.post<ProjectItem>('/projects', payload)
  },

  update(id: number, payload: ProjectUpdatePayload): Promise<ProjectItem> {
    return http.put<ProjectItem>(`/projects/${id}`, payload)
  },

  remove(id: number): Promise<void> {
    return http.delete<void>(`/projects/${id}`)
  },

  bindRepo(id: number, payload: RepoBindPayload): Promise<ProjectItem> {
    return http.post<ProjectItem>(`/projects/${id}/repos`, payload)
  },

  sync(id: number): Promise<ProjectItem> {
    return http.post<ProjectItem>(`/projects/${id}/sync`)
  },

  branches(id: number): Promise<string[]> {
    return http.get<string[]>(`/projects/${id}/branches`)
  },

  /** 最近提交，供增量审查选择基线（只返回本地已有的历史） */
  commits(id: number, limit = 30): Promise<CommitInfo[]> {
    return http.get<CommitInfo[]>(`/projects/${id}/commits`, { params: { limit } })
  }
}

export const credentialApi = {
  list(): Promise<CredentialItem[]> {
    return http.get<CredentialItem[]>('/credentials')
  },

  create(payload: CredentialCreatePayload): Promise<CredentialItem> {
    return http.post<CredentialItem>('/credentials', payload)
  },

  remove(id: number): Promise<void> {
    return http.delete<void>(`/credentials/${id}`)
  }
}

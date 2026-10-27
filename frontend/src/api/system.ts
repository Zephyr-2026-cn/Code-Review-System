import { http } from '@/utils/request'
import type { PageResult } from './types'

/**
 * 系统域接口（用户 / 角色 / 权限 / 审计日志）。
 *
 * 约定：本文件只做「请求 + 类型」，不做任何 UI 提示与状态管理，
 * 便于后续被多个页面复用（例如角色选择器既在用户页用，也在角色页用）。
 */

// ---------------------------------------------------------------- 类型

export type UserStatus = 'ENABLED' | 'DISABLED'

export interface RoleBrief {
  id: number
  code: string
  name: string
}

export interface UserItem {
  id: number
  username: string
  nickname: string
  email?: string | null
  status: UserStatus
  roles: RoleBrief[]
  lastLoginAt?: string | null
  createdAt: string
  passwordResetRequired: boolean
}

export interface UserQuery {
  page?: number
  pageSize?: number
  keyword?: string
  status?: UserStatus | ''
  roleId?: number | null
}

export interface UserCreatePayload {
  username: string
  password?: string
  nickname: string
  email?: string
  roleIds: number[]
}

export interface UserUpdatePayload {
  nickname: string
  email?: string
  status: UserStatus
}

export interface InitialCredential {
  userId: number
  username: string
  password: string
}

export type PermissionType = 'MENU' | 'API' | 'BUTTON'

export interface PermissionNode {
  id: number
  code: string
  name: string
  type: PermissionType
  parentId: number | null
  path?: string | null
  icon?: string | null
  sortOrder: number
  children: PermissionNode[]
}

export interface RoleItem {
  id: number
  code: string
  name: string
  description?: string | null
  builtin: boolean
  userCount: number
  permissions: string[]
  createdAt: string
}

export interface RoleSavePayload {
  code: string
  name: string
  description?: string
}

export interface AuditLogItem {
  id: number
  userId?: number | null
  username?: string | null
  action: string
  resource?: string | null
  resourceId?: string | null
  detail?: string | null
  ip?: string | null
  userAgent?: string | null
  success: boolean
  errorMsg?: string | null
  durationMs?: number | null
  createdAt: string
}

export interface AuditLogQuery {
  page?: number
  pageSize?: number
  username?: string
  action?: string
  resource?: string
  success?: boolean | ''
  from?: string
  to?: string
}

// ---------------------------------------------------------------- 用户

export const userApi = {
  page(query: UserQuery): Promise<PageResult<UserItem>> {
    return http.get<PageResult<UserItem>>('/users', { params: query })
  },

  detail(id: number): Promise<UserItem> {
    return http.get<UserItem>(`/users/${id}`)
  },

  /** 创建：不传 password 时由后端生成随机初始密码，明文只在响应中出现一次 */
  create(payload: UserCreatePayload): Promise<InitialCredential> {
    return http.post<InitialCredential>('/users', payload)
  },

  update(id: number, payload: UserUpdatePayload): Promise<UserItem> {
    return http.put<UserItem>(`/users/${id}`, payload)
  },

  remove(id: number): Promise<void> {
    return http.delete<void>(`/users/${id}`)
  },

  resetPassword(id: number, newPassword?: string): Promise<InitialCredential> {
    return http.post<InitialCredential>(`/users/${id}/password`, { newPassword })
  },

  assignRoles(id: number, roleIds: number[]): Promise<UserItem> {
    return http.put<UserItem>(`/users/${id}/roles`, { roleIds })
  }
}

// ---------------------------------------------------------------- 角色

export const roleApi = {
  list(): Promise<RoleItem[]> {
    return http.get<RoleItem[]>('/roles')
  },

  detail(id: number): Promise<RoleItem> {
    return http.get<RoleItem>(`/roles/${id}`)
  },

  create(payload: RoleSavePayload): Promise<RoleItem> {
    return http.post<RoleItem>('/roles', payload)
  },

  update(id: number, payload: RoleSavePayload): Promise<RoleItem> {
    return http.put<RoleItem>(`/roles/${id}`, payload)
  },

  remove(id: number): Promise<void> {
    return http.delete<void>(`/roles/${id}`)
  },

  assignPermissions(id: number, permissionIds: number[]): Promise<RoleItem> {
    return http.put<RoleItem>(`/roles/${id}/permissions`, { permissionIds })
  }
}

// ---------------------------------------------------------------- 权限

export const permissionApi = {
  tree(): Promise<PermissionNode[]> {
    return http.get<PermissionNode[]>('/permissions/tree')
  }
}

// ---------------------------------------------------------------- 审计日志

export const auditLogApi = {
  page(query: AuditLogQuery): Promise<PageResult<AuditLogItem>> {
    return http.get<PageResult<AuditLogItem>>('/audit-logs', { params: query })
  }
}

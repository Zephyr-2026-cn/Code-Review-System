/**
 * 后端统一响应体结构，与 Java 端 Result<T> 严格对应。
 * 任何接口返回都必须先过这一层，页面里不允许出现裸 fetch。
 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
  traceId?: string
  timestamp: number
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  pageSize: number
  pages: number
}

export interface UserProfile {
  id: number
  username: string
  nickname: string
  email?: string | null
  roles: string[]
  permissions: string[]
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: UserProfile
}

/** 业务错误码，与后端 ErrorCode 保持一致（前端只关心需要特殊处理的部分）。 */
export const ErrorCodes = {
  SUCCESS: 0,
  UNAUTHORIZED: 40100,
  TOKEN_INVALID: 40101,
  TOKEN_EXPIRED: 40102,
  REFRESH_TOKEN_INVALID: 40103,
  BAD_CREDENTIALS: 40104,
  ACCOUNT_DISABLED: 40105,
  ACCOUNT_LOCKED: 40106,
  FORBIDDEN: 40300,
  PERMISSION_DENIED: 40301
} as const

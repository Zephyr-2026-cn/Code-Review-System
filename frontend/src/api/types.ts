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
  /** 为 true 表示管理员创建/重置过密码，前端应强制跳转改密 */
  passwordResetRequired?: boolean
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
  PARAM_INVALID: 40001,
  UNAUTHORIZED: 40100,
  TOKEN_INVALID: 40101,
  TOKEN_EXPIRED: 40102,
  REFRESH_TOKEN_INVALID: 40103,
  BAD_CREDENTIALS: 40104,
  ACCOUNT_DISABLED: 40105,
  ACCOUNT_LOCKED: 40106,
  FORBIDDEN: 40300,
  PERMISSION_DENIED: 40301,
  SELF_OPERATION_FORBIDDEN: 40303,
  BUILTIN_ROLE_PROTECTED: 40304,
  PRIVILEGE_ESCALATION: 40305,
  /** 账号权限被管理员变更，旧令牌已吊销，必须重新登录 */
  SESSION_REVOKED: 40306,
  CONFLICT: 40900,
  LAST_ADMIN_PROTECTED: 40905,
  ROLE_IN_USE: 40906
} as const

import axios, {
  type AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig
} from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from '@/api/types'
import { ErrorCodes } from '@/api/types'
import { clearAuthStorage, tokenStorage } from '@/utils/storage'

/** 带业务语义的异常，页面可据此做差异化处理（例如表单字段级报错）。 */
export class ApiError extends Error {
  readonly code: number
  readonly httpStatus: number
  readonly traceId?: string

  constructor(message: string, code: number, httpStatus: number, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
    this.traceId = traceId
  }
}

interface RetriableConfig extends InternalAxiosRequestConfig {
  /** 标记已重试过，避免刷新令牌后再次 401 造成死循环 */
  _retried?: boolean
  /** 静默模式：不弹全局错误提示，由调用方自行处理 */
  silent?: boolean
}

/** 这些接口的 401 不应该触发"刷新令牌"逻辑，否则会递归调用。 */
const NO_REFRESH_URLS = ['/auth/login', '/auth/refresh', '/auth/logout']

const instance: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30_000,
  // 刷新令牌走 HttpOnly Cookie，必须允许携带凭证
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' }
})

// ---------------------------------------------------------------- 刷新令牌（单飞）
// 多个请求同时 401 时只发起一次刷新，其余请求等待同一个 Promise，
// 否则并发刷新会因"刷新令牌一次一用"而互相把对方踢下线。
let refreshPromise: Promise<string | null> | null = null

async function refreshAccessToken(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = axios
      .post<ApiResult<{ accessToken: string }>>('/api/auth/refresh', null, { withCredentials: true })
      .then((response) => {
        const body = response.data
        if (body?.code === ErrorCodes.SUCCESS && body.data?.accessToken) {
          tokenStorage.set(body.data.accessToken)
          return body.data.accessToken
        }
        return null
      })
      .catch(() => null)
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

let redirecting = false

/** 认证彻底失效：清理本地状态并跳转登录页，保留原始目标地址。 */
async function forceLogout(): Promise<void> {
  clearAuthStorage()
  if (redirecting) {
    return
  }
  redirecting = true
  const { default: router } = await import('@/router')
  const current = router.currentRoute.value
  const redirect = current.fullPath && current.path !== '/login' ? current.fullPath : undefined
  await router.replace({ path: '/login', query: redirect ? { redirect } : undefined })
  redirecting = false
}

// ---------------------------------------------------------------- 请求拦截
instance.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = tokenStorage.get()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// ---------------------------------------------------------------- 响应拦截
instance.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult<unknown>
    // 兼容"HTTP 200 + 业务失败"的返回（部分网关会这样返回）
    if (body && typeof body.code === 'number' && body.code !== ErrorCodes.SUCCESS) {
      return Promise.reject(
        new ApiError(body.message || '请求失败', body.code, response.status, body.traceId)
      )
    }
    return body?.data as never
  },
  async (error: AxiosError<ApiResult<unknown>>) => {
    const config = error.config as RetriableConfig | undefined
    const httpStatus = error.response?.status ?? 0
    const body = error.response?.data
    const businessCode = body?.code ?? httpStatus

    // ---- 网络层错误 ----
    if (!error.response) {
      const message =
        error.code === 'ECONNABORTED' ? '请求超时，请检查网络后重试' : '无法连接服务器，请确认后端已启动'
      if (!config?.silent) {
        ElMessage.error(message)
      }
      return Promise.reject(new ApiError(message, -1, 0))
    }

    // ---- 401：尝试静默刷新一次 ----
    const isAuthUrl = config?.url ? NO_REFRESH_URLS.some((url) => config.url!.includes(url)) : false
    if (httpStatus === 401 && config && !config._retried && !isAuthUrl) {
      config._retried = true
      const newToken = await refreshAccessToken()
      if (newToken) {
        config.headers.Authorization = `Bearer ${newToken}`
        return instance.request(config)
      }
      // 刷新失败：只有"确实曾登录过"才提示，避免未登录访问公开页时弹出刺眼提示
      if (tokenStorage.get()) {
        ElMessage.warning('登录状态已失效，请重新登录')
      }
      await forceLogout()
      return Promise.reject(new ApiError(body?.message ?? '登录状态已失效', businessCode, httpStatus))
    }

    if (httpStatus === 401) {
      await forceLogout()
    }

    // ---- 403 + 会话被吊销：管理员改了角色或禁用了账号，重试无意义，直接回登录页 ----
    if (businessCode === ErrorCodes.SESSION_REVOKED) {
      ElMessage.warning(body?.message ?? '账号权限已变更，请重新登录')
      await forceLogout()
      return Promise.reject(new ApiError(body?.message ?? '账号权限已变更', businessCode, httpStatus))
    }

    // ---- 其余错误：统一提示 ----
    const message = buildErrorMessage(httpStatus, businessCode, body?.message, body?.traceId)
    if (!config?.silent && httpStatus !== 401) {
      ElMessage.error(message)
    }
    return Promise.reject(new ApiError(message, businessCode, httpStatus, body?.traceId))
  }
)

function buildErrorMessage(
  httpStatus: number,
  businessCode: number,
  serverMessage?: string,
  traceId?: string
): string {
  if (businessCode === ErrorCodes.ACCOUNT_LOCKED) {
    return serverMessage || '账号已临时锁定'
  }
  if (httpStatus === 403) {
    return '没有操作权限，请联系管理员'
  }
  if (httpStatus === 413) {
    return '上传内容超过大小限制'
  }
  if (httpStatus === 429) {
    return '操作过于频繁，请稍后再试'
  }
  if (httpStatus >= 500) {
    // 排障信息：把 traceId 暴露给用户，便于对照后端日志定位
    return `服务异常，请稍后重试${traceId ? `（追踪号 ${traceId}）` : ''}`
  }
  return serverMessage || `请求失败（${httpStatus}）`
}

/** 类型安全的请求封装：直接返回业务数据，失败抛 ApiError。 */
export const http = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config) as unknown as Promise<T>
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, data, config) as unknown as Promise<T>
  },
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.put(url, data, config) as unknown as Promise<T>
  },
  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.patch(url, data, config) as unknown as Promise<T>
  },
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.delete(url, config) as unknown as Promise<T>
  }
}

export default instance

import type { UserProfile } from '@/api/types'

const ACCESS_TOKEN_KEY = 'cr_at'
const USER_KEY = 'cr_user'

/**
 * 令牌与用户档案的本地存储。
 *
 * 选型说明：
 *   - 访问令牌放 sessionStorage 而不是 localStorage —— 关闭标签页即失效，
 *     暴露窗口更短；刷新令牌在 HttpOnly Cookie 里，JS 完全读不到。
 *   - 用户档案放 sessionStorage 只用于刷新页面时避免界面闪烁，
 *     权限判断一律以后端返回为准，前端隐藏按钮只是体验优化，不是安全边界。
 */
export const tokenStorage = {
  get(): string | null {
    return sessionStorage.getItem(ACCESS_TOKEN_KEY)
  },
  set(token: string): void {
    sessionStorage.setItem(ACCESS_TOKEN_KEY, token)
  },
  clear(): void {
    sessionStorage.removeItem(ACCESS_TOKEN_KEY)
  }
}

export const userStorage = {
  get(): UserProfile | null {
    const raw = sessionStorage.getItem(USER_KEY)
    if (!raw) {
      return null
    }
    try {
      return JSON.parse(raw) as UserProfile
    } catch {
      // 数据损坏时直接丢弃，避免整个应用因解析异常白屏
      sessionStorage.removeItem(USER_KEY)
      return null
    }
  },
  set(user: UserProfile): void {
    sessionStorage.setItem(USER_KEY, JSON.stringify(user))
  },
  clear(): void {
    sessionStorage.removeItem(USER_KEY)
  }
}

export function clearAuthStorage(): void {
  tokenStorage.clear()
  userStorage.clear()
}

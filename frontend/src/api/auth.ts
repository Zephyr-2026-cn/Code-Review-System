import { http } from '@/utils/request'
import type { LoginResponse, UserProfile } from './types'

export interface LoginPayload {
  username: string
  password: string
}

export interface ChangePasswordPayload {
  oldPassword: string
  newPassword: string
}

export const authApi = {
  /** 登录：成功后刷新令牌由后端写入 HttpOnly Cookie，前端只保存访问令牌 */
  login(payload: LoginPayload): Promise<LoginResponse> {
    return http.post<LoginResponse>('/auth/login', payload)
  },

  /** 刷新令牌：由请求拦截器在 401 时自动调用，业务代码一般不直接使用 */
  refresh(): Promise<LoginResponse> {
    return http.post<LoginResponse>('/auth/refresh')
  },

  logout(): Promise<void> {
    return http.post<void>('/auth/logout')
  },

  me(): Promise<UserProfile> {
    return http.get<UserProfile>('/auth/me')
  },

  changePassword(payload: ChangePasswordPayload): Promise<void> {
    return http.post<void>('/auth/password', payload)
  }
}

import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi, type ChangePasswordPayload, type LoginPayload } from '@/api/auth'
import type { UserProfile } from '@/api/types'
import { clearAuthStorage, tokenStorage, userStorage } from '@/utils/storage'

/**
 * 用户状态。
 *
 * 安全边界说明：前端的 `hasPermission` 只用于「隐藏不该出现的按钮」，
 * 真正的鉴权在后端每个接口上。隐藏按钮是为了体验，不是安全措施。
 */
export const useUserStore = defineStore('user', () => {
  const profile = ref<UserProfile | null>(userStorage.get())
  const permissionSet = ref<Set<string>>(new Set(profile.value?.permissions ?? []))
  const loading = ref(false)

  const isLoggedIn = computed(() => Boolean(profile.value && tokenStorage.get()))
  const displayName = computed(() => profile.value?.nickname || profile.value?.username || '未登录')
  const roles = computed(() => profile.value?.roles ?? [])
  const isAdmin = computed(() => roles.value.includes('ADMIN'))

  function applyProfile(next: UserProfile): void {
    profile.value = next
    permissionSet.value = new Set(next.permissions ?? [])
    userStorage.set(next)
  }

  /** 拥有指定权限点（管理员视为拥有全部）。 */
  function hasPermission(code: string): boolean {
    return isAdmin.value || permissionSet.value.has(code)
  }

  /** 拥有任意一个权限点即可。 */
  function hasAnyPermission(codes: string[]): boolean {
    return codes.some((code) => hasPermission(code))
  }

  async function login(payload: LoginPayload): Promise<void> {
    loading.value = true
    try {
      const result = await authApi.login(payload)
      tokenStorage.set(result.accessToken)
      applyProfile(result.user)
    } finally {
      loading.value = false
    }
  }

  async function loadProfile(): Promise<void> {
    const next = await authApi.me()
    applyProfile(next)
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 主动登出失败不应阻塞前端清理：令牌最终会自然过期
    } finally {
      clearAuthStorage()
      profile.value = null
      permissionSet.value = new Set()
    }
  }

  async function changePassword(payload: ChangePasswordPayload): Promise<void> {
    await authApi.changePassword(payload)
    clearAuthStorage()
    profile.value = null
    permissionSet.value = new Set()
  }

  /** 401 兜底清理（由请求层调用）。 */
  function reset(): void {
    clearAuthStorage()
    profile.value = null
    permissionSet.value = new Set()
  }

  return {
    profile,
    loading,
    isLoggedIn,
    displayName,
    roles,
    isAdmin,
    hasPermission,
    hasAnyPermission,
    login,
    loadProfile,
    logout,
    changePassword,
    reset
  }
})

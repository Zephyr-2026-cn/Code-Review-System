import { ref, watch } from 'vue'
import { defineStore } from 'pinia'

export type ThemeMode = 'light' | 'dark'

const THEME_KEY = 'cr_theme'
const SIDEBAR_KEY = 'cr_sidebar_collapsed'

/**
 * 应用级 UI 状态：主题、侧边栏折叠。
 *
 * 暗色主题实现方式：Element Plus 官方的 `html.dark` + CSS 变量，
 * 不额外维护一套样式，避免升级组件库时配色错乱。
 */
export const useAppStore = defineStore('app', () => {
  const theme = ref<ThemeMode>((localStorage.getItem(THEME_KEY) as ThemeMode) || 'light')
  const sidebarCollapsed = ref(localStorage.getItem(SIDEBAR_KEY) === '1')

  function applyTheme(mode: ThemeMode): void {
    document.documentElement.classList.toggle('dark', mode === 'dark')
  }

  function setTheme(mode: ThemeMode): void {
    theme.value = mode
  }

  function toggleTheme(): void {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
  }

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  watch(
    theme,
    (mode) => {
      applyTheme(mode)
      localStorage.setItem(THEME_KEY, mode)
    },
    { immediate: true }
  )

  watch(sidebarCollapsed, (collapsed) => {
    localStorage.setItem(SIDEBAR_KEY, collapsed ? '1' : '0')
  })

  return { theme, sidebarCollapsed, setTheme, toggleTheme, toggleSidebar, applyTheme }
})

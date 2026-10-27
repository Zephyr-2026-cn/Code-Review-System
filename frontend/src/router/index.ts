import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { tokenStorage } from '@/utils/storage'

/**
 * 路由表。
 *
 * 约定：
 *   - `meta.public`   免登录页面
 *   - `meta.permission` 进入该页所需的权限点，缺失则跳 403
 *   - `meta.title`    面包屑与浏览器标题
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: () => import('@/layout/DefaultLayout.vue'),
    children: [
      {
        path: '',
        redirect: '/dashboard'
      },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '质量看板', icon: 'DataBoard', permission: 'stats:dashboard:view' }
      },
      {
        path: 'projects',
        name: 'projects',
        component: () => import('@/views/project/ProjectListView.vue'),
        meta: { title: '项目管理', icon: 'Folder', permission: 'project:repo:list' }
      },
      {
        path: 'tasks',
        name: 'tasks',
        component: () => import('@/views/ComingSoonView.vue'),
        meta: { title: '审查任务', icon: 'Tickets', permission: 'task:list' }
      },
      {
        path: 'issues',
        name: 'issues',
        component: () => import('@/views/ComingSoonView.vue'),
        meta: { title: '问题中心', icon: 'Warning', permission: 'issue:list' }
      },
      {
        path: 'lab/compare',
        name: 'lab-compare',
        component: () => import('@/views/ComingSoonView.vue'),
        meta: { title: '三方案沙盘', icon: 'Histogram', permission: 'lab:compare:run' }
      },
      {
        path: 'config/gate',
        name: 'config-gate',
        component: () => import('@/views/ComingSoonView.vue'),
        meta: { title: '合并门禁', icon: 'Lock', permission: 'config:gate:list' }
      },
      {
        path: 'system/users',
        name: 'system-users',
        component: () => import('@/views/system/UserManageView.vue'),
        meta: { title: '用户管理', icon: 'UserFilled', permission: 'system:user:list' }
      },
      {
        path: 'system/roles',
        name: 'system-roles',
        component: () => import('@/views/system/RoleManageView.vue'),
        meta: { title: '角色权限', icon: 'Key', permission: 'system:role:list' }
      },
      {
        path: 'system/audit',
        name: 'system-audit',
        component: () => import('@/views/system/AuditLogView.vue'),
        meta: { title: '审计日志', icon: 'DocumentCopy', permission: 'system:audit:list' }
      }
    ]
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/ErrorView.vue'),
    props: { code: 403 },
    meta: { public: true, title: '无权限' }
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/ErrorView.vue'),
    props: { code: 404 },
    meta: { public: true, title: '页面不存在' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach(async (to) => {
  const userStore = useUserStore()
  document.title = to.meta.title ? `${to.meta.title} · 智能代码审查系统` : '智能代码审查系统'

  const isPublic = to.meta.public === true
  const hasToken = Boolean(tokenStorage.get())

  if (isPublic) {
    // 已登录访问登录页：直接回首页，避免重复登录
    if (to.path === '/login' && hasToken && userStore.profile) {
      return { path: '/dashboard' }
    }
    return true
  }

  if (!hasToken) {
    return { path: '/login', query: to.fullPath === '/' ? undefined : { redirect: to.fullPath } }
  }

  // 刷新页面后内存里没有用户信息，需要补拉一次（失败说明凭证已失效）
  if (!userStore.profile) {
    try {
      await userStore.loadProfile()
    } catch {
      userStore.reset()
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  const required = to.meta.permission as string | undefined
  if (required && !userStore.hasPermission(required)) {
    return { path: '/403' }
  }

  return true
})

export default router

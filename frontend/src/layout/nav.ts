/**
 * 侧边导航配置。
 *
 * 为什么手写而不是后端下发菜单：
 *   前端路由与组件是编译期确定的，后端只能"允许/禁止"访问，无法动态生成组件。
 *   因此采用「前端声明菜单 + 后端给权限点」的方式：
 *   后端只决定某个权限点是否存在，菜单是否显示由本文件 + 权限判断共同决定。
 */
export interface NavItem {
  path: string
  title: string
  /** Element Plus 图标组件名 */
  icon: string
  /** 缺失该权限点则不显示（后端仍会二次校验） */
  permission?: string
}

export interface NavGroup {
  title: string
  items: NavItem[]
}

export const navGroups: NavGroup[] = [
  {
    title: '总览',
    items: [{ path: '/dashboard', title: '质量看板', icon: 'DataBoard', permission: 'stats:dashboard:view' }]
  },
  {
    title: '审查',
    items: [
      { path: '/projects', title: '项目管理', icon: 'Folder', permission: 'project:repo:list' },
      { path: '/tasks', title: '审查任务', icon: 'Tickets', permission: 'task:list' },
      { path: '/issues', title: '问题中心', icon: 'Warning', permission: 'issue:list' }
    ]
  },
  {
    title: '增强',
    items: [
      { path: '/lab/compare', title: '三方案沙盘', icon: 'Histogram', permission: 'lab:compare:run' },
      { path: '/config/gate', title: '合并门禁', icon: 'Lock', permission: 'config:gate:list' }
    ]
  },
  {
    title: '系统',
    items: [
      { path: '/system/users', title: '用户管理', icon: 'UserFilled', permission: 'system:user:list' },
      { path: '/system/roles', title: '角色权限', icon: 'Key', permission: 'system:role:list' },
      { path: '/system/audit', title: '审计日志', icon: 'DocumentCopy', permission: 'system:audit:list' }
    ]
  }
]

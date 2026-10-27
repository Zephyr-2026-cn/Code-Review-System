<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import { navGroups } from './nav'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()

const visibleGroups = computed(() =>
  navGroups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => !item.permission || userStore.hasPermission(item.permission))
    }))
    .filter((group) => group.items.length > 0)
)

const currentTitle = computed(() => (route.meta.title as string | undefined) ?? '')

// ---------------- 修改密码 ----------------
const passwordDialogVisible = ref(false)
const passwordFormRef = ref<FormInstance>()
const passwordSubmitting = ref(false)
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const passwordRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 64, message: '长度需在 8-64 位之间', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (!value || !/[A-Za-z]/.test(value) || !/\d/.test(value)) {
          callback(new Error('必须同时包含字母和数字'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

function openPasswordDialog(): void {
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
  passwordDialogVisible.value = true
}

async function submitPassword(): Promise<void> {
  if (!passwordFormRef.value) {
    return
  }
  const valid = await passwordFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  passwordSubmitting.value = true
  try {
    await userStore.changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    })
    passwordDialogVisible.value = false
    ElMessage.success('密码已修改，请使用新密码重新登录')
    await router.replace('/login')
  } finally {
    passwordSubmitting.value = false
  }
}

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      type: 'warning',
      confirmButtonText: '退出',
      cancelButtonText: '取消'
    })
  } catch {
    // 用户取消，不做任何处理
    return
  }
  await userStore.logout()
  await router.replace('/login')
}

function handleCommand(command: string): void {
  if (command === 'password') {
    openPasswordDialog()
  } else if (command === 'logout') {
    void handleLogout()
  }
}
</script>

<template>
  <el-container class="layout-root">
    <el-aside :width="appStore.sidebarCollapsed ? 'var(--cr-sidebar-collapsed-width)' : 'var(--cr-sidebar-width)'"
              class="layout-aside">
      <div class="brand">
        <el-icon :size="22" color="#409eff">
          <Cpu />
        </el-icon>
        <span v-show="!appStore.sidebarCollapsed" class="brand-title">智能代码审查</span>
      </div>

      <el-scrollbar class="menu-scroll">
        <el-menu :default-active="route.path" :collapse="appStore.sidebarCollapsed" router
                 :collapse-transition="false" class="layout-menu">
          <el-menu-item-group v-for="group in visibleGroups" :key="group.title" :title="group.title">
            <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
              <el-icon>
                <component :is="item.icon" />
              </el-icon>
              <template #title>{{ item.title }}</template>
            </el-menu-item>
          </el-menu-item-group>
        </el-menu>
      </el-scrollbar>
    </el-aside>

    <el-container>
      <el-header class="layout-header">
        <div class="cr-flex header-left">
          <el-button text circle @click="appStore.toggleSidebar()">
            <el-icon :size="18">
              <component :is="appStore.sidebarCollapsed ? 'Expand' : 'Fold'" />
            </el-icon>
          </el-button>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="currentTitle">{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="cr-flex header-right">
          <el-tooltip :content="appStore.theme === 'dark' ? '切换为浅色' : '切换为深色'" placement="bottom">
            <el-button text circle @click="appStore.toggleTheme()">
              <el-icon :size="18">
                <component :is="appStore.theme === 'dark' ? 'Sunny' : 'Moon'" />
              </el-icon>
            </el-button>
          </el-tooltip>

          <el-dropdown trigger="click" @command="handleCommand">
            <span class="user-trigger">
              <el-avatar :size="30" class="user-avatar">{{ userStore.displayName.charAt(0) }}</el-avatar>
              <span class="user-name">{{ userStore.displayName }}</span>
              <el-tag v-if="userStore.isAdmin" size="small" type="danger" effect="plain">管理员</el-tag>
              <el-tag v-else-if="userStore.roles.includes('REVIEWER')" size="small" type="warning" effect="plain">
                审查者
              </el-tag>
              <el-tag v-else size="small" type="info" effect="plain">开发者</el-tag>
              <el-icon><CaretBottom /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="password">
                  <el-icon><Key /></el-icon> 修改密码
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <el-icon><SwitchButton /></el-icon> 退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>

    <el-dialog v-model="passwordDialogVisible" title="修改密码" width="420px" destroy-on-close>
      <el-form ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" label-width="88px">
        <el-form-item label="原密码" prop="oldPassword">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password autocomplete="off" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="off" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="off" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="passwordSubmitting" @click="submitPassword">确定</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<style scoped lang="scss">
.layout-root {
  height: 100%;
}

.layout-aside {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
  transition: width 0.2s ease;
  overflow: hidden;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  height: var(--cr-header-height);
  padding: 0 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  flex-shrink: 0;
}

.brand-title {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}

.menu-scroll {
  flex: 1;
  overflow-x: hidden;
}

.layout-menu {
  border-right: none;
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--cr-header-height);
  padding: 0 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}

.header-left {
  gap: 12px;
}

.header-right {
  gap: 8px;
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 6px;
  outline: none;

  &:hover {
    background: var(--el-fill-color-light);
  }
}

.user-avatar {
  background: var(--el-color-primary);
  color: #fff;
  font-size: 13px;
}

.user-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.layout-main {
  padding: 0;
  overflow-y: auto;
  background: var(--el-bg-color-page);
}

/* 窄屏：侧边栏自动折叠为图标态，保证报告页在被窝里用手机也能看 */
@media (max-width: 768px) {
  .layout-aside {
    width: var(--cr-sidebar-collapsed-width) !important;
  }

  .brand-title,
  .user-name {
    display: none;
  }
}
</style>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ username: '', password: '' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

/** 仅允许站内跳转，防止 ?redirect=//evil.com 这类开放重定向。 */
function safeRedirect(raw: unknown): string {
  if (typeof raw !== 'string' || !raw.startsWith('/') || raw.startsWith('//')) {
    return '/dashboard'
  }
  return raw
}

async function handleSubmit(): Promise<void> {
  if (!formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }

  submitting.value = true
  try {
    await userStore.login({ username: form.username.trim(), password: form.password })
    ElMessage.success(`欢迎回来，${userStore.displayName}`)
    await router.replace(safeRedirect(route.query.redirect))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-panel">
      <div class="login-intro">
        <div class="intro-brand">
          <el-icon :size="26"><Cpu /></el-icon>
          <span>智能代码审查系统</span>
        </div>
        <h1 class="intro-title">静态分析 + 大模型语义审查<br />双引擎融合的代码质量平台</h1>
        <ul class="intro-list">
          <li><el-icon><Select /></el-icon> PMD 规则扫描定位确定性缺陷</li>
          <li><el-icon><Select /></el-icon> 大语言模型承接语义级问题与修复建议</li>
          <li><el-icon><Select /></el-icon> 历史缺陷知识库检索，抑制模型幻觉</li>
          <li><el-icon><Select /></el-icon> 合并门禁按严重度阻断，问题闭环可追溯</li>
        </ul>
        <p class="intro-foot">南昌应用技术师范学院 · 软件工程 · 2027 届毕业设计</p>
      </div>

      <div class="login-form-wrap">
        <h2 class="form-title">账号登录</h2>
        <p class="form-subtitle">请使用管理员分配的账号登录</p>

        <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleSubmit">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名" autocomplete="username" clearable>
              <template #prefix>
                <el-icon><User /></el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              autocomplete="current-password"
              show-password
            >
              <template #prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-button type="primary" class="submit-btn" :loading="submitting" @click="handleSubmit">
            登 录
          </el-button>
        </el-form>

        <el-alert type="info" :closable="false" class="login-tip">
          <template #title>
            初始账号 <b>admin</b> / <b>Admin@123456</b>，首次登录后请立即修改密码
          </template>
        </el-alert>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 24px;
  background:
    radial-gradient(circle at 15% 20%, rgba(64, 158, 255, 0.18), transparent 45%),
    radial-gradient(circle at 85% 80%, rgba(103, 194, 58, 0.14), transparent 45%),
    var(--el-bg-color-page);
}

.login-panel {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  width: 100%;
  max-width: 960px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  box-shadow: var(--el-box-shadow-light);
  overflow: hidden;
}

.login-intro {
  padding: 44px 40px;
  background: linear-gradient(150deg, #1f2d3d 0%, #2b4a6f 100%);
  color: #eaf2fb;
  display: flex;
  flex-direction: column;
}

.intro-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 1px;
}

.intro-title {
  margin: 28px 0 24px;
  font-size: 25px;
  line-height: 1.5;
  font-weight: 600;
}

.intro-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 14px;
  font-size: 13.5px;
  color: #c8d8ea;

  li {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .el-icon {
    color: #67c23a;
  }
}

.intro-foot {
  margin-top: auto;
  padding-top: 28px;
  font-size: 12px;
  color: #8ba4bf;
}

.login-form-wrap {
  padding: 52px 44px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.form-title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 600;
}

.form-subtitle {
  margin: 0 0 26px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.submit-btn {
  width: 100%;
  margin-top: 4px;
}

.login-tip {
  margin-top: 22px;
  font-size: 12px;
}

@media (max-width: 860px) {
  .login-panel {
    grid-template-columns: 1fr;
  }

  .login-intro {
    display: none;
  }

  .login-form-wrap {
    padding: 36px 26px;
  }
}
</style>

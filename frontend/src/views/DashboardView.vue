<script setup lang="ts">
import { computed } from 'vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const roleLabel = computed(() => {
  if (userStore.isAdmin) {
    return '团队管理员'
  }
  if (userStore.roles.includes('REVIEWER')) {
    return '审查者'
  }
  return '开发者'
})

const permissions = computed(() => Array.from(userStore.profile?.permissions ?? []).sort())

const milestones = [
  { key: 'M0', title: '环境与登录链路', status: 'done', desc: '骨架、Flyway 建库、JWT 登录' },
  { key: 'M1', title: '用户与权限', status: 'todo', desc: '三角色 RBAC、项目接入' },
  { key: 'M2', title: '任务与静态引擎', status: 'todo', desc: '状态机、队列、PMD 分析' },
  { key: 'M3', title: '语义引擎', status: 'todo', desc: '增量切片、LLM 结构化输出' },
  { key: 'M4', title: '融合与报告页', status: 'todo', desc: '双引擎归并、报告主舞台' }
]
</script>

<template>
  <div class="cr-page">
    <el-card shadow="never" class="welcome-card">
      <div class="welcome-content">
        <el-avatar :size="52" class="welcome-avatar">{{ userStore.displayName.charAt(0) }}</el-avatar>
        <div class="welcome-text">
          <h2>你好，{{ userStore.displayName }}</h2>
          <p>
            当前身份：<el-tag size="small" effect="plain">{{ roleLabel }}</el-tag>
            <span class="cr-text-muted">　可用权限点 {{ permissions.length }} 项</span>
          </p>
        </div>
      </div>
    </el-card>

    <el-row :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">审查任务</div>
          <div class="stat-value">—</div>
          <div class="stat-hint">M2 里程碑接入</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">待处理问题</div>
          <div class="stat-value">—</div>
          <div class="stat-hint">M4 里程碑接入</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">历史缺陷库</div>
          <div class="stat-value">—</div>
          <div class="stat-hint">M5 里程碑接入</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">门禁拦截率</div>
          <div class="stat-value">—</div>
          <div class="stat-hint">M6 里程碑接入</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :xs="24" :md="14">
        <el-card shadow="never" class="block-card">
          <template #header>
            <div class="cr-flex-between">
              <span>开发进度</span>
              <el-tag size="small" type="success" effect="plain">M0 已完成</el-tag>
            </div>
          </template>
          <el-timeline>
            <el-timeline-item
              v-for="item in milestones"
              :key="item.key"
              :type="item.status === 'done' ? 'success' : 'info'"
              :hollow="item.status !== 'done'"
            >
              <div class="timeline-title">{{ item.key }} · {{ item.title }}</div>
              <div class="cr-text-muted timeline-desc">{{ item.desc }}</div>
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="10">
        <el-card shadow="never" class="block-card">
          <template #header>
            <div class="cr-flex-between">
              <span>当前账号权限点</span>
              <el-tag size="small" effect="plain">{{ permissions.length }} 项</el-tag>
            </div>
          </template>
          <el-scrollbar max-height="260px">
            <div class="perm-list">
              <el-tag v-for="perm in permissions" :key="perm" size="small" type="info" effect="plain" class="perm-tag">
                {{ perm }}
              </el-tag>
              <el-empty v-if="permissions.length === 0" description="暂无权限点" :image-size="60" />
            </div>
          </el-scrollbar>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped lang="scss">
.welcome-card {
  border-radius: var(--cr-radius);
  margin-bottom: 16px;
}

.welcome-content {
  display: flex;
  align-items: center;
  gap: 16px;
}

.welcome-avatar {
  background: var(--el-color-primary);
  font-size: 20px;
}

.welcome-text {
  h2 {
    margin: 0 0 6px;
    font-size: 18px;
    font-weight: 600;
  }

  p {
    margin: 0;
    font-size: 13px;
    display: flex;
    align-items: center;
  }
}

.stat-row {
  margin-bottom: 16px;
}

.stat-card {
  border-radius: var(--cr-radius);
  margin-bottom: 16px;
}

.stat-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.stat-value {
  margin: 8px 0 4px;
  font-size: 26px;
  font-weight: 600;
  line-height: 1;
}

.stat-hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.block-card {
  border-radius: var(--cr-radius);
  margin-bottom: 16px;
}

.timeline-title {
  font-size: 13.5px;
  font-weight: 500;
}

.timeline-desc {
  font-size: 12px;
  margin-top: 2px;
}

.perm-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding-right: 8px;
}

.perm-tag {
  font-family: 'JetBrains Mono', Consolas, monospace;
}
</style>

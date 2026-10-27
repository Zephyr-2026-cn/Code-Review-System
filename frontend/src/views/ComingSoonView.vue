<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const title = computed(() => (route.meta.title as string | undefined) ?? '功能模块')

/** 各模块的落地里程碑，写在页面上是为了答辩时"边界清晰"——看得见计划，而不是含糊承诺。 */
const plans: Record<string, string> = {
  '/projects': 'M1：仓库接入（Git URL / 代码包上传）、分支选择、凭据加密存储',
  '/tasks': 'M2：任务状态机、Redis 队列、PMD 静态扫描、增量切片',
  '/issues': 'M4：问题列表、严重度分级、状态流转、修复建议',
  '/lab/compare': 'M6.5：三方案同屏对比（静态 / 纯 LLM / 融合），标记独有发现与被过滤的幻觉',
  '/config/gate': 'M6：门禁策略配置、三态判定、强制放行留痕',
  '/system/users': 'M1：用户与角色管理、权限分配、密码重置',
  '/system/audit': 'M1：审计日志查询（登录、权限变更、任务创建、裁决）'
}

const plan = computed(() => plans[route.path] ?? '该模块将在后续里程碑实现')
</script>

<template>
  <div class="cr-page">
    <el-card shadow="never" class="placeholder-card">
      <el-empty :description="`${title} 尚未开发`" :image-size="120">
        <div class="plan-box">
          <el-icon :size="16" color="#e6a23c"><Clock /></el-icon>
          <span>{{ plan }}</span>
        </div>
        <el-button type="primary" plain @click="router.push('/dashboard')">返回质量看板</el-button>
      </el-empty>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.placeholder-card {
  border-radius: var(--cr-radius);
}

.plan-box {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 18px;
  padding: 8px 14px;
  border-radius: 6px;
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
  font-size: 13px;
}
</style>

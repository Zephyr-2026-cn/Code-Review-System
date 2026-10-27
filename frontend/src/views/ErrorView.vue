<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const props = defineProps<{ code?: number }>()

const router = useRouter()

const code = computed(() => props.code ?? 404)

const description = computed(() =>
  code.value === 403 ? '你的账号没有访问该页面的权限，请联系管理员分配权限' : '页面不存在或已被移除'
)
</script>

<template>
  <div class="error-page">
    <div class="error-code">{{ code }}</div>
    <p class="error-desc">{{ description }}</p>
    <div class="error-actions">
      <el-button @click="router.back()">返回上一页</el-button>
      <el-button type="primary" @click="router.push('/dashboard')">回到首页</el-button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.error-page {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 40px 24px;
}

.error-code {
  font-size: 92px;
  font-weight: 700;
  line-height: 1;
  background: linear-gradient(135deg, var(--el-color-primary), var(--el-color-success));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.error-desc {
  margin: 18px 0 26px;
  font-size: 14px;
  color: var(--el-text-color-secondary);
}

.error-actions {
  display: flex;
  gap: 12px;
}
</style>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { projectApi, type ProjectItem } from '@/api/project'
import { issueApi, type IssueItem, type Severity } from '@/api/task'

/**
 * 问题中心：跨任务查询缺陷。
 *
 * 与任务详情页的区别是不锁定单个任务，用于回答
 * "这个项目累计还有多少未处理的高危问题"。
 */
const router = useRouter()

const loading = ref(false)
const rows = ref<IssueItem[]>([])
const total = ref(0)
const projects = ref<ProjectItem[]>([])

const query = reactive({
  page: 1,
  pageSize: 20,
  projectId: undefined as number | undefined,
  severity: '' as Severity | '',
  category: '',
  filePath: ''
})

const severityMeta: Record<Severity, { label: string; tag: 'danger' | 'warning' | 'info' }> = {
  BLOCKER: { label: '阻断', tag: 'danger' },
  CRITICAL: { label: '严重', tag: 'danger' },
  MAJOR: { label: '主要', tag: 'warning' },
  MINOR: { label: '次要', tag: 'info' },
  INFO: { label: '提示', tag: 'info' }
}

const categories = [
  'NULL_POINTER',
  'RESOURCE_LEAK',
  'CONCURRENCY',
  'INJECTION',
  'PERFORMANCE',
  'MAINTAINABILITY',
  'LOGIC_ERROR',
  'CODE_STYLE'
]

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await issueApi.page({
      page: query.page,
      pageSize: query.pageSize,
      projectId: query.projectId,
      severity: query.severity || undefined,
      category: query.category || undefined,
      filePath: query.filePath || undefined
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function search(): void {
  query.page = 1
  load()
}

onMounted(async () => {
  await load()
  const page = await projectApi.page({ page: 1, pageSize: 100 })
  projects.value = page.records
})

function openTask(row: IssueItem): void {
  router.push(`/tasks/${row.taskId}`)
}

const formatTime = (value?: string | null) => (value ? value.replace('T', ' ').slice(0, 19) : '—')

const unresolved = computed(() => rows.value.filter((row) => row.status === 'OPEN').length)
</script>

<template>
  <div class="cr-page">
    <el-card shadow="never" class="filter-card">
      <div class="filter-bar">
        <el-select v-model="query.projectId" placeholder="全部项目" clearable style="width: 200px" @change="search">
          <el-option v-for="item in projects" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>

        <el-select v-model="query.severity" placeholder="全部严重度" clearable style="width: 130px" @change="search">
          <el-option v-for="(meta, key) in severityMeta" :key="key" :label="meta.label" :value="key" />
        </el-select>

        <el-select v-model="query.category" placeholder="全部类别" clearable style="width: 160px" @change="search">
          <el-option v-for="item in categories" :key="item" :label="item" :value="item" />
        </el-select>

        <el-input v-model="query.filePath" placeholder="文件路径" clearable style="width: 200px" @keyup.enter="search">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <el-button type="primary" @click="search">查询</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border stripe size="small" @row-click="openTask">
        <el-table-column label="严重度" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="severityMeta[row.severity as Severity].tag" effect="dark">
              {{ row.severityLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="categoryLabel" label="类别" width="110" />
        <el-table-column label="位置" min-width="300">
          <template #default="{ row }">
            <span class="mono">{{ row.filePath }}:{{ row.startLine }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="结论" min-width="180" show-overflow-tooltip />
        <el-table-column prop="ruleId" label="规则" width="170" show-overflow-tooltip />
        <el-table-column label="任务" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openTask(row)">#{{ row.taskId }}</el-button>
          </template>
        </el-table-column>
        <el-table-column label="发现时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>

        <template #empty>
          <el-empty description="还没有缺陷记录，先发起一次审查" :image-size="80" />
        </template>
      </el-table>

      <div class="pager">
        <span class="cr-text-muted summary">本页待处理 {{ unresolved }} 条</span>
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="load"
          @size-change="search"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.filter-card {
  margin-bottom: 12px;
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.pager {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
}

.summary {
  font-size: 12px;
}

.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}
</style>

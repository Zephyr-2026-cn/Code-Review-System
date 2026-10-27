<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { auditLogApi, type AuditLogItem } from '@/api/system'

/**
 * 审计日志页（只读）。
 *
 * 后端不提供任何写/删接口：日志一旦可被业务侧修改就失去取证价值。
 * 前端因此只做三件事：过滤、分页、查看详情 JSON。
 */
const loading = ref(false)
const rows = ref<AuditLogItem[]>([])
const total = ref(0)
const dateRange = ref<[string, string] | null>(null)

const query = reactive({
  page: 1,
  pageSize: 20,
  username: '',
  action: '',
  resource: '',
  success: '' as boolean | ''
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await auditLogApi.page({
      page: query.page,
      pageSize: query.pageSize,
      username: query.username || undefined,
      action: query.action || undefined,
      resource: query.resource || undefined,
      success: query.success === '' ? undefined : query.success,
      from: dateRange.value?.[0],
      to: dateRange.value?.[1]
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

function resetQuery(): void {
  query.username = ''
  query.action = ''
  query.resource = ''
  query.success = ''
  dateRange.value = null
  query.page = 1
  load()
}

onMounted(load)

// ---------------------------------------------------------------- 详情

const detailVisible = ref(false)
const detailRow = ref<AuditLogItem | null>(null)

function openDetail(row: AuditLogItem): void {
  detailRow.value = row
  detailVisible.value = true
}

/** 尝试格式化 JSON，失败则原样展示（历史记录可能是自由文本）。 */
function prettyJson(raw?: string | null): string {
  if (!raw) return '—'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

function formatTime(value?: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}
</script>

<template>
  <div class="cr-page">
    <el-card shadow="never" class="filter-card">
      <div class="filter-bar">
        <el-input v-model="query.username" placeholder="操作人登录名" clearable class="w-160" />
        <el-input v-model="query.action" placeholder="动作码，如 USER_CREATE" clearable class="w-200" />
        <el-input v-model="query.resource" placeholder="资源类型，如 USER" clearable class="w-160" />
        <el-select v-model="query.success" placeholder="全部结果" clearable class="w-130">
          <el-option label="成功" :value="true" />
          <el-option label="失败" :value="false" />
        </el-select>
        <el-date-picker
          v-model="dateRange"
          type="datetimerange"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DDTHH:mm:ss"
          class="w-360"
        />
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="resetQuery">重置</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="rows" stripe @row-click="openDetail">
        <el-table-column label="时间" width="170">
          <template #default="{ row }"><span class="mono">{{ formatTime(row.createdAt) }}</span></template>
        </el-table-column>
        <el-table-column prop="username" label="操作人" width="120">
          <template #default="{ row }">{{ row.username || '—' }}</template>
        </el-table-column>
        <el-table-column prop="action" label="动作" width="180">
          <template #default="{ row }"><span class="mono">{{ row.action }}</span></template>
        </el-table-column>
        <el-table-column prop="resource" label="资源" width="110">
          <template #default="{ row }">{{ row.resource || '—' }}</template>
        </el-table-column>
        <el-table-column prop="resourceId" label="资源 ID" width="100">
          <template #default="{ row }">{{ row.resourceId || '—' }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.success ? 'success' : 'danger'" effect="plain">
              {{ row.success ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90" align="right">
          <template #default="{ row }">{{ row.durationMs != null ? `${row.durationMs} ms` : '—' }}</template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="140">
          <template #default="{ row }"><span class="mono">{{ row.ip || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="180">
          <template #default="{ row }">
            <span class="cr-text-muted">{{ row.errorMsg || '—' }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
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

    <el-drawer v-model="detailVisible" title="审计详情" size="520px">
      <el-descriptions v-if="detailRow" :column="1" border>
        <el-descriptions-item label="时间">{{ formatTime(detailRow.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="操作人">
          {{ detailRow.username || '未知' }}{{ detailRow.userId ? ` (#${detailRow.userId})` : '' }}
        </el-descriptions-item>
        <el-descriptions-item label="动作">{{ detailRow.action }}</el-descriptions-item>
        <el-descriptions-item label="资源">
          {{ detailRow.resource || '—' }}{{ detailRow.resourceId ? ` #${detailRow.resourceId}` : '' }}
        </el-descriptions-item>
        <el-descriptions-item label="结果">
          {{ detailRow.success ? '成功' : '失败' }}
          {{ detailRow.errorMsg ? `（${detailRow.errorMsg}）` : '' }}
        </el-descriptions-item>
        <el-descriptions-item label="耗时">{{ detailRow.durationMs ?? '—' }} ms</el-descriptions-item>
        <el-descriptions-item label="IP">{{ detailRow.ip || '—' }}</el-descriptions-item>
        <el-descriptions-item label="User-Agent">{{ detailRow.userAgent || '—' }}</el-descriptions-item>
      </el-descriptions>
      <div class="detail-title">请求详情（已脱敏）</div>
      <pre class="detail-json">{{ prettyJson(detailRow?.detail) }}</pre>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.w-130 {
  width: 130px;
}
.w-160 {
  width: 160px;
}
.w-200 {
  width: 200px;
}
.w-360 {
  width: 360px;
}

.mono {
  font-family: var(--cr-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.detail-title {
  margin: 20px 0 8px;
  font-weight: 600;
  font-size: 13px;
}

.detail-json {
  margin: 0;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.6;
  max-height: 40vh;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>

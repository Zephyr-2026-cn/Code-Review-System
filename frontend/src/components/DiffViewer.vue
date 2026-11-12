<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { DiffHunk } from '@/api/task'

/**
 * 差异视图（只读）。
 *
 * 与 CodeViewer 分开而不是合并成一个带 mode 的组件：两者的行结构不同
 * （差异要双列行号 + 增删标记），塞在一起会出现大量 v-if，读起来反而更难维护。
 */
const props = defineProps<{
  hunks: DiffHunk[]
  /** 缺陷行：在新文件一侧命中时整行加标记 */
  highlightStart?: number
  highlightEnd?: number
  truncated?: boolean
}>()

const bodyRef = ref<HTMLElement | null>(null)

const totalAdd = computed(() =>
  props.hunks.reduce((sum, hunk) => sum + hunk.lines.filter((line) => line.type === 'ADD').length, 0)
)
const totalDelete = computed(() =>
  props.hunks.reduce((sum, hunk) => sum + hunk.lines.filter((line) => line.type === 'DELETE').length, 0)
)

function inHighlight(newLine: number): boolean {
  if (!props.highlightStart || !newLine) return false
  const end = Math.max(props.highlightStart, props.highlightEnd ?? props.highlightStart)
  return newLine >= props.highlightStart && newLine <= end
}

/** 差异块可能很长，缺陷行不一定在第一屏，切到差异视图时滚到它 */
watch(
  () => [props.hunks, props.highlightStart],
  async () => {
    await nextTick()
    const body = bodyRef.value
    if (!body || !props.highlightStart) return
    const target = body.querySelector<HTMLElement>('.diff-line.hit')
    if (!target) return
    body.scrollTop = Math.max(0, target.offsetTop - body.clientHeight / 2 + target.clientHeight)
  },
  { immediate: true }
)
</script>

<template>
  <div class="diff-viewer">
    <div v-if="!hunks.length" class="diff-empty">
      本次提交没有修改该文件（缺陷可能来自历史代码）
    </div>
    <template v-else>
      <div class="diff-summary">
        <span class="add">+{{ totalAdd }}</span>
        <span class="del">-{{ totalDelete }}</span>
        <span v-if="truncated" class="muted">（差异过大，仅展示前 {{ hunks.length }} 段）</span>
      </div>

      <div ref="bodyRef" class="diff-body">
        <div v-for="(hunk, index) in hunks" :key="index" class="hunk">
          <div class="hunk-head">
            @@ 新文件第 {{ hunk.newStart }} 行起 · 旧文件第 {{ hunk.oldStart }} 行起 @@
          </div>
          <div
            v-for="(line, lineIndex) in hunk.lines"
            :key="lineIndex"
            class="diff-line"
            :class="[line.type.toLowerCase(), { hit: inHighlight(line.newLine) }]"
          >
            <span class="ln old">{{ line.oldLine || '' }}</span>
            <span class="ln new">{{ line.newLine || '' }}</span>
            <span class="sign">{{ line.type === 'ADD' ? '+' : line.type === 'DELETE' ? '-' : ' ' }}</span>
            <span class="txt">{{ line.text }}</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.diff-viewer {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  overflow: hidden;
  background: #fbfbfc;
}

.diff-summary {
  display: flex;
  gap: 12px;
  padding: 6px 12px;
  font-size: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  font-family: 'JetBrains Mono', Consolas, monospace;
}

.diff-summary .add {
  color: #1a7f37;
}

.diff-summary .del {
  color: #cf222e;
}

.diff-summary .muted {
  color: var(--el-text-color-secondary);
}

.diff-body {
  max-height: 420px;
  overflow: auto;
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.65;
}

.hunk-head {
  padding: 2px 12px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: 11.5px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.diff-line {
  display: flex;
  min-width: max-content;
  white-space: pre;
}

.diff-line.add {
  background: #e6ffec;
}

.diff-line.delete {
  background: #ffebe9;
}

.diff-line.hit {
  box-shadow: inset 3px 0 0 var(--el-color-warning);
}

.ln {
  flex: 0 0 44px;
  padding-right: 8px;
  text-align: right;
  color: #a8abb2;
  user-select: none;
}

.sign {
  flex: 0 0 14px;
  text-align: center;
  color: #6e7781;
}

.txt {
  flex: 1;
  padding-right: 16px;
  color: #24292f;
}

.diff-empty {
  padding: 16px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>

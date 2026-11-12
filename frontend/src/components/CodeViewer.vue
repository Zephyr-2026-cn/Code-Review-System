<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { CodeLine } from '@/api/task'

/**
 * 轻量代码视图。
 *
 * <p>没有用 Monaco：它需要独立的 web worker 与数 MB 资源，首次打开报告页会白屏等待，
 * 而这里的需求只是"行号 + 缺陷行高亮 + 语义着色"。自己做的 tokenizer 覆盖关键字、
 * 字符串、注释、数字、注解五类，足以让人快速读懂上下文，代价是几十行代码和零额外体积。
 *
 * <p>着色结果通过 v-html 输出，因此每个 token 都强制 HTML 转义——
 * 源码内容来自用户仓库，不能假设它是安全的。
 */
const props = withDefaults(
  defineProps<{
    lines: CodeLine[]
    /** 缺陷起始行，命中的行会高亮 */
    highlightStart?: number
    highlightEnd?: number
    language?: string
    /** 变化时自动滚动到该行 */
    scrollTo?: number
  }>(),
  { highlightStart: 0, highlightEnd: 0, language: 'java', scrollTo: 0 }
)

const rootRef = ref<HTMLElement | null>(null)

const KEYWORDS = new Set([
  'abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class', 'const',
  'continue', 'default', 'do', 'double', 'else', 'enum', 'extends', 'final', 'finally', 'float',
  'for', 'goto', 'if', 'implements', 'import', 'instanceof', 'int', 'interface', 'long', 'native',
  'new', 'package', 'private', 'protected', 'public', 'return', 'short', 'static', 'strictfp',
  'super', 'switch', 'synchronized', 'this', 'throw', 'throws', 'transient', 'try', 'void',
  'volatile', 'while', 'record', 'sealed', 'permits', 'yield', 'var',
  'function', 'let', 'async', 'await', 'export', 'from', 'type', 'interface',
  'def', 'elif', 'lambda', 'pass', 'raise', 'with', 'as', 'None', 'True', 'False',
  'func', 'defer', 'chan', 'select', 'go', 'struct', 'map'
])

const escapeHtml = (value: string): string =>
  value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

interface Token {
  cls: string
  text: string
}

/**
 * 逐行分词。
 *
 * 块注释状态跨行传递：Java 里的多行注释经常横跨十几行，
 * 每行独立判断会把后续行当成代码着色，看起来像语法全错。
 */
function tokenizeLine(source: string, state: { inBlock: boolean }): Token[] {
  const tokens: Token[] = []
  let buffer = ''
  let i = 0

  const flush = () => {
    if (buffer) {
      tokens.push({ cls: '', text: buffer })
      buffer = ''
    }
  }

  while (i < source.length) {
    if (state.inBlock) {
      const end = source.indexOf('*/', i)
      if (end < 0) {
        tokens.push({ cls: 'tok-comment', text: source.slice(i) })
        i = source.length
        break
      }
      tokens.push({ cls: 'tok-comment', text: source.slice(i, end + 2) })
      i = end + 2
      state.inBlock = false
      continue
    }

    const ch = source[i]

    if (ch === '/' && source[i + 1] === '*') {
      flush()
      tokens.push({ cls: 'tok-comment', text: '/*' })
      i += 2
      state.inBlock = true
      continue
    }
    if (ch === '/' && source[i + 1] === '/') {
      flush()
      tokens.push({ cls: 'tok-comment', text: source.slice(i) })
      break
    }
    if (ch === '"' || ch === "'" || ch === '`') {
      flush()
      let j = i + 1
      while (j < source.length) {
        if (source[j] === '\\') {
          j += 2
          continue
        }
        if (source[j] === ch) {
          j += 1
          break
        }
        j += 1
      }
      tokens.push({ cls: 'tok-string', text: source.slice(i, j) })
      i = j
      continue
    }
    if (ch === '@' && /[A-Za-z]/.test(source[i + 1] ?? '')) {
      flush()
      const match = /^@[A-Za-z_][A-Za-z0-9_.]*/.exec(source.slice(i))
      const word = match ? match[0] : '@'
      tokens.push({ cls: 'tok-annotation', text: word })
      i += word.length
      continue
    }
    if (/[A-Za-z_$]/.test(ch)) {
      const match = /^[A-Za-z_$][A-Za-z0-9_$]*/.exec(source.slice(i))
      const word = match ? match[0] : ch
      flush()
      tokens.push({ cls: KEYWORDS.has(word) ? 'tok-keyword' : '', text: word })
      i += word.length
      continue
    }
    if (/[0-9]/.test(ch)) {
      const match = /^[0-9][0-9a-fA-FxX._]*[lLfFdD]?/.exec(source.slice(i))
      const word = match ? match[0] : ch
      flush()
      tokens.push({ cls: 'tok-number', text: word })
      i += word.length
      continue
    }

    buffer += ch
    i += 1
  }

  flush()
  return tokens
}

const rendered = computed(() => {
  const state = { inBlock: false }
  return props.lines.map((line) => {
    const tokens = tokenizeLine(line.text, state)
    const html = tokens
      .map((token) => (token.cls
        ? `<span class="${token.cls}">${escapeHtml(token.text)}</span>`
        : escapeHtml(token.text)))
      .join('')
    return {
      no: line.no,
      html: html || '&nbsp;',
      hit: line.no >= props.highlightStart && line.no <= Math.max(props.highlightStart, props.highlightEnd)
    }
  })
})

const hitCount = computed(() => rendered.value.filter((line) => line.hit).length)

function scrollToLine(no: number): void {
  const root = rootRef.value
  if (!root || !no) return
  const target = root.querySelector<HTMLElement>(`[data-no="${no}"]`)
  if (!target) return
  // 居中而不是滚到顶部：缺陷行上方保留一点上下文，读者才知道自己从哪来
  root.scrollTop = Math.max(0, target.offsetTop - root.clientHeight / 2 + target.clientHeight)
}

watch(
  () => [props.scrollTo, props.lines],
  async () => {
    await nextTick()
    scrollToLine(props.scrollTo || props.highlightStart)
  },
  { immediate: true }
)

defineExpose({ scrollToLine })
</script>

<template>
  <div ref="rootRef" class="code-viewer">
    <div
      v-for="line in rendered"
      :key="line.no"
      class="code-line"
      :class="{ hit: line.hit }"
      :data-no="line.no"
    >
      <span class="ln">{{ line.no }}</span>
      <span class="txt" v-html="line.html" />
    </div>
    <div v-if="!rendered.length" class="code-empty">没有可展示的代码</div>
    <div v-else-if="hitCount === 0 && highlightStart" class="code-hint">
      缺陷行 {{ highlightStart }} 不在当前片段内
    </div>
  </div>
</template>

<style scoped>
.code-viewer {
  position: relative;
  max-height: 460px;
  overflow: auto;
  background: #fbfbfc;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.65;
  padding: 6px 0;
}

.code-line {
  display: flex;
  min-width: max-content;
}

.code-line.hit {
  background: var(--el-color-warning-light-8);
  box-shadow: inset 3px 0 0 var(--el-color-warning);
}

.ln {
  flex: 0 0 56px;
  padding-right: 12px;
  text-align: right;
  color: #a8abb2;
  user-select: none;
  border-right: 1px solid var(--el-border-color-lighter);
  margin-right: 12px;
}

.txt {
  flex: 1;
  white-space: pre;
  padding-right: 16px;
  color: #24292f;
}

/*
 * token 的 span 是 v-html 注入的，拿不到 scoped 样式的 data-v 属性，
 * 必须用 :deep() 穿透，否则着色规则一条都不会命中（表现为整片纯黑代码）。
 */
.code-viewer :deep(.tok-keyword) {
  color: #cf222e;
}

.code-viewer :deep(.tok-string) {
  color: #0a3069;
}

.code-viewer :deep(.tok-comment) {
  color: #6e7781;
  font-style: italic;
}

.code-viewer :deep(.tok-number) {
  color: #0550ae;
}

.code-viewer :deep(.tok-annotation) {
  color: #6639ba;
}

.code-empty,
.code-hint {
  padding: 16px;
  color: var(--el-text-color-secondary);
  font-family: inherit;
  font-size: 12px;
}

.code-hint {
  padding: 6px 16px;
}
</style>

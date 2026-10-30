<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { buildSideBySideDiff } from '../utils/lineDiff'

const props = defineProps<{
  previousContent: string
  currentContent: string
  previousLabel?: string
  currentLabel?: string
}>()

const leftRef = ref<HTMLElement | null>(null)
const rightRef = ref<HTMLElement | null>(null)
let syncing = false

const rows = computed(() =>
  buildSideBySideDiff(props.previousContent || '', props.currentContent || ''),
)

const stats = computed(() => {
  let added = 0
  let removed = 0
  for (const r of rows.value) {
    if (r.right.kind === 'add') added++
    if (r.left.kind === 'remove') removed++
  }
  return { added, removed }
})

function onScroll(side: 'left' | 'right') {
  if (syncing) return
  const src = side === 'left' ? leftRef.value : rightRef.value
  const dst = side === 'left' ? rightRef.value : leftRef.value
  if (!src || !dst) return
  syncing = true
  dst.scrollTop = src.scrollTop
  dst.scrollLeft = src.scrollLeft
  requestAnimationFrame(() => {
    syncing = false
  })
}

watch(
  () => [props.previousContent, props.currentContent],
  () => {
    requestAnimationFrame(() => {
      if (leftRef.value) leftRef.value.scrollTop = 0
      if (rightRef.value) rightRef.value.scrollTop = 0
    })
  },
)
</script>

<template>
  <div class="diff-root">
    <div class="diff-stats">
      <span class="stat add">+{{ stats.added }}</span>
      <span class="stat remove">-{{ stats.removed }}</span>
      <span v-if="!rows.length" class="muted">无内容</span>
      <span v-else-if="!stats.added && !stats.removed" class="muted">内容相同</span>
    </div>
    <div class="diff-panes">
      <div class="diff-pane">
        <div class="diff-hd">{{ previousLabel || '旧版' }}</div>
        <div ref="leftRef" class="diff-body" @scroll="onScroll('left')">
          <div
            v-for="(row, i) in rows"
            :key="'L' + i"
            class="diff-line"
            :class="row.left.kind"
          >
            <span class="ln">{{ row.left.lineNo ?? '' }}</span>
            <pre class="tx">{{ row.left.text }}</pre>
          </div>
        </div>
      </div>
      <div class="diff-pane">
        <div class="diff-hd">{{ currentLabel || '当前' }}</div>
        <div ref="rightRef" class="diff-body" @scroll="onScroll('right')">
          <div
            v-for="(row, i) in rows"
            :key="'R' + i"
            class="diff-line"
            :class="row.right.kind"
          >
            <span class="ln">{{ row.right.lineNo ?? '' }}</span>
            <pre class="tx">{{ row.right.text }}</pre>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.diff-root {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}
.diff-stats {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
}
.stat.add {
  color: #389e0d;
  font-weight: 600;
}
.stat.remove {
  color: #cf1322;
  font-weight: 600;
}
.muted {
  color: var(--dap-gray-500, #8f959e);
}
.diff-panes {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  min-height: 0;
}
.diff-pane {
  display: flex;
  flex-direction: column;
  min-width: 0;
  border: 1px solid var(--dap-gray-200, #e5e6eb);
  border-radius: 8px;
  overflow: hidden;
  background: var(--dap-gray-0, #fff);
}
.diff-hd {
  padding: 8px 10px;
  font-size: 13px;
  font-weight: 500;
  color: var(--dap-gray-700, #646a73);
  background: var(--dap-gray-50, #f5f6f7);
  border-bottom: 1px solid var(--dap-gray-200, #e5e6eb);
}
.diff-body {
  height: 420px;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
}
.diff-line {
  display: flex;
  align-items: stretch;
  min-height: 20px;
}
.diff-line .ln {
  flex: 0 0 44px;
  padding: 0 8px;
  text-align: right;
  color: var(--dap-gray-400, #bbbfc4);
  user-select: none;
  border-right: 1px solid var(--dap-gray-100, #f0f1f2);
  background: rgba(15, 23, 42, 0.02);
}
.diff-line .tx {
  flex: 1;
  margin: 0;
  padding: 0 10px;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
  font: inherit;
}
.diff-line.equal .tx {
  background: transparent;
}
.diff-line.add {
  background: #f6ffed;
}
.diff-line.add .ln {
  background: #d9f7be;
  color: #389e0d;
}
.diff-line.remove {
  background: #fff2f0;
}
.diff-line.remove .ln {
  background: #ffccc7;
  color: #cf1322;
}
.diff-line.empty {
  background: repeating-linear-gradient(
    -45deg,
    transparent,
    transparent 4px,
    rgba(15, 23, 42, 0.03) 4px,
    rgba(15, 23, 42, 0.03) 8px
  );
}
.diff-line.empty .tx {
  min-height: 20px;
}
</style>

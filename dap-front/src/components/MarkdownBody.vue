<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import mermaid from 'mermaid'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps<{
  content: string
}>()

mermaid.initialize({
  startOnLoad: false,
  securityLevel: 'strict',
  theme: 'neutral',
  fontFamily: 'inherit',
})

let mermaidSeq = 0

const rootRef = ref<HTMLElement | null>(null)
const html = computed(() => renderMarkdown(props.content || ''))

function isMermaidBlock(pre: Element): boolean {
  const code = pre.querySelector('code')
  if (!code) return false
  const cls = code.className || ''
  return /\blanguage-mermaid\b/i.test(cls) || code.getAttribute('data-lang') === 'mermaid'
}

async function copyText(text: string) {
  const value = text.replace(/\u00a0/g, ' ')
  if (!value) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value)
    } else {
      const ta = document.createElement('textarea')
      ta.value = value
      ta.setAttribute('readonly', '')
      ta.style.position = 'fixed'
      ta.style.left = '-9999px'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}

/** 将 ```mermaid 代码块渲染为 SVG */
async function renderMermaidBlocks() {
  await nextTick()
  const root = rootRef.value
  if (!root) return

  const blocks = Array.from(root.querySelectorAll('pre')).filter(isMermaidBlock)
  for (const pre of blocks) {
    if (pre.getAttribute('data-mermaid-done') === '1') continue
    const code = pre.querySelector('code')
    const source = (code?.textContent ?? pre.textContent ?? '').trim()
    if (!source) continue

    const wrap = pre.closest('.md-code-wrap')
    const host = document.createElement('div')
    host.className = 'md-mermaid'
    const target = wrap || pre

    try {
      const id = `md-mermaid-${++mermaidSeq}-${Date.now()}`
      const { svg } = await mermaid.render(id, source)
      host.innerHTML = svg
      target.replaceWith(host)
    } catch (err) {
      pre.setAttribute('data-mermaid-done', '1')
      const tip = document.createElement('div')
      tip.className = 'md-mermaid-error'
      tip.textContent = `Mermaid 渲染失败：${err instanceof Error ? err.message : String(err)}`
      target.parentNode?.insertBefore(tip, target)
    }
  }
}

/** 为非 Mermaid 代码块加「复制」按钮；划选/快捷键仍走浏览器原生能力 */
async function decorateCodeBlocks() {
  await nextTick()
  const root = rootRef.value
  if (!root) return
  root.querySelectorAll('pre').forEach((pre) => {
    if (pre.closest('.md-code-wrap')) return
    if (isMermaidBlock(pre)) return
    const wrap = document.createElement('div')
    wrap.className = 'md-code-wrap'
    pre.parentNode?.insertBefore(wrap, pre)
    wrap.appendChild(pre)

    const btn = document.createElement('button')
    btn.type = 'button'
    btn.className = 'md-code-copy'
    btn.textContent = '复制'
    btn.addEventListener('click', (e) => {
      e.preventDefault()
      e.stopPropagation()
      const code = pre.querySelector('code')?.textContent ?? pre.textContent ?? ''
      void copyText(code)
    })
    wrap.appendChild(btn)
  })
}

async function enhance() {
  await renderMermaidBlocks()
  await decorateCodeBlocks()
}

watch(html, () => {
  void enhance()
})

onMounted(() => {
  void enhance()
})
</script>

<template>
  <div ref="rootRef" class="md-body dap-selectable" v-html="html" />
</template>

<style scoped>
.md-body {
  font-size: 14px;
  line-height: 1.65;
  word-break: break-word;
  overflow-wrap: anywhere;
  user-select: text;
  -webkit-user-select: text;
  cursor: text;
}

.md-body :deep(:first-child) {
  margin-top: 0;
}

.md-body :deep(:last-child) {
  margin-bottom: 0;
}

.md-body :deep(p) {
  margin: 0.5em 0;
}

.md-body :deep(h1),
.md-body :deep(h2),
.md-body :deep(h3),
.md-body :deep(h4) {
  margin: 0.8em 0 0.4em;
  font-weight: 650;
  line-height: 1.35;
}

.md-body :deep(h1) {
  font-size: 1.25em;
}
.md-body :deep(h2) {
  font-size: 1.15em;
}
.md-body :deep(h3) {
  font-size: 1.05em;
}

.md-body :deep(ul),
.md-body :deep(ol) {
  margin: 0.45em 0;
  padding-left: 1.35em;
}

.md-body :deep(li) {
  margin: 0.2em 0;
}

.md-body :deep(blockquote) {
  margin: 0.55em 0;
  padding: 0.25em 0.85em;
  border-left: 3px solid rgba(22, 119, 255, 0.35);
  color: var(--dap-gray-700, #646a73);
  background: rgba(15, 23, 42, 0.03);
  border-radius: 0 8px 8px 0;
}

.md-body :deep(a) {
  color: var(--dap-primary, #1677ff);
  text-decoration: none;
}

.md-body :deep(a:hover) {
  text-decoration: underline;
}

.md-body :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.9em;
  padding: 0.12em 0.4em;
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.06);
}

.md-body :deep(.md-code-wrap) {
  position: relative;
  margin: 0.6em 0;
}

.md-body :deep(.md-code-wrap pre) {
  margin: 0;
}

.md-body :deep(.md-code-copy) {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
  padding: 2px 8px;
  border: 1px solid rgba(226, 232, 240, 0.35);
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.72);
  color: #e2e8f0;
  font-size: 12px;
  line-height: 1.4;
  cursor: pointer;
  user-select: none;
  -webkit-user-select: none;
}

.md-body :deep(.md-code-copy:hover) {
  background: rgba(22, 119, 255, 0.85);
  border-color: transparent;
}

.md-body :deep(pre) {
  margin: 0.6em 0;
  padding: 12px 14px;
  overflow: auto;
  border-radius: 10px;
  background: #0f172a;
  color: #e2e8f0;
  max-width: 100%;
}

.md-body :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
  font-size: 0.86em;
  line-height: 1.55;
  white-space: pre;
}

.md-body :deep(.md-mermaid) {
  margin: 0.75em 0;
  padding: 12px 14px;
  overflow: auto;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid var(--dap-gray-200, #e5e6eb);
  text-align: center;
}

.md-body :deep(.md-mermaid svg) {
  max-width: 100%;
  height: auto;
}

.md-body :deep(.md-mermaid-error) {
  margin: 0.4em 0 0;
  padding: 6px 10px;
  border-radius: 8px;
  font-size: 12px;
  color: #b42318;
  background: #fef3f2;
  border: 1px solid #fecdca;
}

.md-body :deep(table) {
  border-collapse: collapse;
  margin: 0.6em 0;
  width: 100%;
  font-size: 0.92em;
}

.md-body :deep(th),
.md-body :deep(td) {
  border: 1px solid var(--dap-gray-200, #e5e6eb);
  padding: 6px 10px;
  text-align: left;
}

.md-body :deep(th) {
  background: rgba(15, 23, 42, 0.04);
}

.md-body :deep(hr) {
  border: none;
  border-top: 1px solid var(--dap-gray-200, #e5e6eb);
  margin: 0.8em 0;
}

.md-body :deep(img) {
  max-width: 100%;
  border-radius: 8px;
}
</style>

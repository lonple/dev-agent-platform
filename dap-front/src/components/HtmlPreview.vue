<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  content: string
}>()

/**
 * 用沙箱 iframe 渲染完整/片段 HTML，避免污染工作台样式。
 * 允许同源文档与样式；默认不开脚本（UI 稿展示够用）。
 */
const srcdoc = computed(() => props.content || '')
</script>

<template>
  <iframe
    class="html-preview dap-selectable"
    title="HTML 预览"
    sandbox="allow-same-origin"
    :srcdoc="srcdoc"
  />
</template>

<style scoped>
.html-preview {
  display: block;
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  min-height: 480px;
  border: 0;
  border-radius: 8px;
  background: #fff;
}
</style>

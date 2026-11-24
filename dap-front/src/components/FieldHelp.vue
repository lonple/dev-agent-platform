<script setup lang="ts">
import { QuestionFilled } from '@element-plus/icons-vue'

defineProps<{ content: string }>()
</script>

<template>
  <el-tooltip placement="top" effect="dark" :show-after="120">
    <template #content>
      <div class="field-help-content">{{ content }}</div>
    </template>
    <el-icon class="field-help-icon" :size="14"><QuestionFilled /></el-icon>
  </el-tooltip>
</template>

<style scoped>
.field-help-icon {
  margin-left: 6px;
  color: var(--dap-gray-400, #909399);
  cursor: help;
  vertical-align: middle;
  flex-shrink: 0;
}
.field-help-content {
  max-width: 320px;
  line-height: 1.6;
  white-space: pre-line;
}
</style>

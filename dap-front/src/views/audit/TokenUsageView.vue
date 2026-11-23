<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listTokenUsageByIssueUser,
  listTokenUsageByRequirementUser,
  listTokenUsageByUser,
  type TokenUsageStatItem,
} from '../../api/audit'

const loading = ref(false)
const byUser = ref<TokenUsageStatItem[]>([])
const byReqUser = ref<TokenUsageStatItem[]>([])
const byIssueUser = ref<TokenUsageStatItem[]>([])

function fmt(n?: number) {
  return (n ?? 0).toLocaleString()
}

async function load() {
  loading.value = true
  try {
    ;[byUser.value, byReqUser.value, byIssueUser.value] = await Promise.all([
      listTokenUsageByUser(),
      listTokenUsageByRequirementUser(),
      listTokenUsageByIssueUser(),
    ])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载 Token 统计失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-hd">
      <div>
        <h2>Token 消耗</h2>
        <p class="hint">统计自 Agent 响应上报的累计用量；按用户 / 需求+人 / 问题单+人 三维查看</p>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <section class="block">
      <h3>按用户汇总</h3>
      <el-table :data="byUser" stripe empty-text="暂无数据">
        <el-table-column prop="username" label="用户" min-width="140" />
        <el-table-column label="Prompt" width="120" align="right">
          <template #default="{ row }">{{ fmt(row.promptTokens) }}</template>
        </el-table-column>
        <el-table-column label="Completion" width="120" align="right">
          <template #default="{ row }">{{ fmt(row.completionTokens) }}</template>
        </el-table-column>
        <el-table-column label="合计" width="140" align="right">
          <template #default="{ row }">
            <b>{{ fmt(row.totalTokens) }}</b>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="block">
      <h3>按需求 + 人</h3>
      <el-table :data="byReqUser" stripe empty-text="暂无数据">
        <el-table-column label="需求" min-width="220">
          <template #default="{ row }">
            <span v-if="row.requirementNo" class="mono">{{ row.requirementNo }}</span>
            {{ row.requirementName || (row.requirementId ? `#${row.requirementId}` : '—') }}
          </template>
        </el-table-column>
        <el-table-column prop="username" label="用户" width="140" />
        <el-table-column label="Prompt" width="120" align="right">
          <template #default="{ row }">{{ fmt(row.promptTokens) }}</template>
        </el-table-column>
        <el-table-column label="Completion" width="120" align="right">
          <template #default="{ row }">{{ fmt(row.completionTokens) }}</template>
        </el-table-column>
        <el-table-column label="合计" width="140" align="right">
          <template #default="{ row }">
            <b>{{ fmt(row.totalTokens) }}</b>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="block">
      <h3>按问题单 + 人</h3>
      <el-table :data="byIssueUser" stripe empty-text="暂无数据">
        <el-table-column label="问题单" min-width="180">
          <template #default="{ row }">
            <span class="mono">{{ row.issueNo || (row.issueId ? `#${row.issueId}` : '—') }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="用户" width="140" />
        <el-table-column label="Prompt" width="120" align="right">
          <template #default="{ row }">{{ fmt(row.promptTokens) }}</template>
        </el-table-column>
        <el-table-column label="Completion" width="120" align="right">
          <template #default="{ row }">{{ fmt(row.completionTokens) }}</template>
        </el-table-column>
        <el-table-column label="合计" width="140" align="right">
          <template #default="{ row }">
            <b>{{ fmt(row.totalTokens) }}</b>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--dap-space-5);
}
.page-hd {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--dap-space-4);
}
.page-hd h2 {
  margin: 0;
  font-size: 18px;
}
.hint {
  margin: 6px 0 0;
  color: var(--dap-gray-500);
  font-size: 13px;
}
.block h3 {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
}
.mono {
  font-family: var(--dap-font-mono, Consolas, monospace);
  margin-right: 6px;
  color: var(--dap-gray-500);
}
</style>

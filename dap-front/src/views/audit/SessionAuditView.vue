<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getChatStep, searchChatSteps, type ChatStepItem } from '../../api/audit'

const route = useRoute()
const loading = ref(false)
const rows = ref<ChatStepItem[]>([])
const total = ref(0)
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<ChatStepItem | null>(null)

const query = reactive({
  page: 1,
  size: 20,
  sessionId: undefined as number | undefined,
  runId: '' as string,
  pipeInstanceId: undefined as number | undefined,
  keyword: '',
})

function numOrUndef(v: unknown): number | undefined {
  if (v == null || v === '') return undefined
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}

async function load() {
  loading.value = true
  try {
    const res = await searchChatSteps({
      page: query.page,
      size: query.size,
      sessionId: query.sessionId,
      runId: query.runId.trim() || undefined,
      pipeInstanceId: query.pipeInstanceId,
      keyword: query.keyword.trim() || undefined,
      includeDetail: false,
    })
    rows.value = res.records || []
    total.value = res.total || 0
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  void load()
}

async function openDetail(row: ChatStepItem) {
  detailVisible.value = true
  detail.value = null
  detailLoading.value = true
  try {
    detail.value = await getChatStep(row.eventId)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载详情失败')
  } finally {
    detailLoading.value = false
  }
}

onMounted(() => {
  query.sessionId = numOrUndef(route.query.sessionId)
  query.runId = typeof route.query.runId === 'string' ? route.query.runId : ''
  query.pipeInstanceId = numOrUndef(route.query.pipeInstanceId)
  void load()
})
</script>

<template>
  <div class="page">
    <div class="page-hd">
      <div>
        <h2>会话日志</h2>
        <p class="hint">按会话 / 一次响应(runId) / 流水线实例筛选；详情含步骤全量 detail</p>
      </div>
    </div>

    <el-form :inline="true" class="filters" @submit.prevent="search">
      <el-form-item label="会话 ID">
        <el-input-number v-model="query.sessionId" :controls="false" placeholder="sessionId" />
      </el-form-item>
      <el-form-item label="响应 runId">
        <el-input v-model="query.runId" clearable placeholder="一次响应" style="width: 220px" />
      </el-form-item>
      <el-form-item label="流水线实例">
        <el-input-number
          v-model="query.pipeInstanceId"
          :controls="false"
          placeholder="pipeInstanceId"
        />
      </el-form-item>
      <el-form-item label="关键词">
        <el-input v-model="query.keyword" clearable placeholder="搜简要 message" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" stripe @row-click="openDetail">
      <el-table-column prop="occurredAt" label="时间" min-width="170" />
      <el-table-column prop="sessionTitle" label="会话" min-width="140" show-overflow-tooltip />
      <el-table-column prop="username" label="用户" width="100" />
      <el-table-column prop="step" label="步骤" width="120" />
      <el-table-column prop="llmRound" label="轮次" width="70" />
      <el-table-column prop="message" label="摘要" min-width="220" show-overflow-tooltip />
      <el-table-column prop="runId" label="runId" min-width="160" show-overflow-tooltip />
      <el-table-column prop="pipeStepCode" label="流水线步" width="120" show-overflow-tooltip />
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        layout="total, prev, pager, next"
        :total="total"
        @current-change="load"
      />
    </div>

    <el-drawer v-model="detailVisible" title="步骤详情" size="480px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <p><b>步骤</b>：{{ detail.step }} · seq {{ detail.seq }} · 轮次 {{ detail.llmRound ?? '—' }}</p>
          <p><b>摘要</b>：{{ detail.message }}</p>
          <p><b>会话</b>：{{ detail.sessionTitle }} ({{ detail.sessionId }})</p>
          <p><b>runId</b>：{{ detail.runId }}</p>
          <p v-if="detail.pipeInstanceId">
            <b>流水线</b>：instance {{ detail.pipeInstanceId }} / step {{ detail.pipeStepInstanceId }} /
            {{ detail.pipeStepCode }}
          </p>
          <h4>detail</h4>
          <pre class="detail-json">{{ JSON.stringify(detail.detail ?? {}, null, 2) }}</pre>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.page {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--dap-space-4);
}
.page-hd h2 {
  margin: 0;
  font-size: 18px;
}
.hint {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--dap-gray-500);
}
.filters {
  flex-wrap: wrap;
}
.pager {
  display: flex;
  justify-content: flex-end;
}
.detail-json {
  margin: 0;
  padding: 12px;
  background: #0f172a;
  color: #e2e8f0;
  border-radius: 8px;
  font-size: 12px;
  overflow: auto;
  max-height: 60vh;
}
</style>

<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  approvePipelineStep,
  cancelPipeline,
  completePipelineAiStep,
  finalizePipelineAi,
  formatDuration,
  formatTokens,
  getPipeline,
  preparePipelineAi,
  rejectPipelineStep,
  retryPipelineAiStep,
  startPipeline,
  updatePipelineReviewItems,
  type PipelineInstance,
  type PipelineReviewItem,
  type PipelineStep,
} from '../../api/pipeline'
import { listMessages, streamCompletion, type ChatMessage } from '../../api/chat'
import { getWikiFileContent } from '../../api/wiki'

const props = defineProps<{
  requirementId: number
  /** automation=左右分栏会话；embedded=详情内嵌面板 */
  mode?: 'embedded' | 'automation'
}>()

const emit = defineEmits<{
  close: []
  started: []
}>()

const loading = ref(false)
const actionLoading = ref(false)
const pipe = ref<PipelineInstance | null>(null)
const selectedStepId = ref<number | null>(null)
const sessionMessages = ref<ChatMessage[]>([])
const liveContent = ref('')
const liveSteps = ref<string[]>([])
const streaming = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null
const chatBox = ref<HTMLElement | null>(null)
const reviewPreview = ref('')
const reviewPreviewLoading = ref(false)
const activeReviewWikiNodeId = ref<number | null>(null)
const editableReviewItems = ref<PipelineReviewItem[]>([])

const isAutomation = computed(() => props.mode === 'automation')

const selectedStep = computed(() => {
  if (!pipe.value?.steps?.length) return null
  if (selectedStepId.value != null) {
    return pipe.value.steps.find((s) => s.stepInstanceId === selectedStepId.value) ?? null
  }
  const cur = pipe.value.steps.find((s) =>
    ['RUNNING', 'WAITING_HUMAN', 'WAITING_TRIGGER'].includes(s.status),
  )
  return cur ?? pipe.value.steps[pipe.value.steps.length - 1] ?? null
})

const isActive = computed(
  () => pipe.value != null && ['RUNNING', 'WAITING_HUMAN'].includes(pipe.value.status),
)

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function refreshQuiet() {
  try {
    pipe.value = await getPipeline(props.requirementId)
  } catch {
    /* ignore */
  }
}

async function refresh() {
  loading.value = true
  try {
    pipe.value = await getPipeline(props.requirementId)
    if (selectedStep.value) {
      selectedStepId.value = selectedStep.value.stepInstanceId
    }
    await loadSessionMessages()
  } catch (e) {
    pipe.value = null
    ElMessage.error(e instanceof Error ? e.message : '加载流水线失败')
  } finally {
    loading.value = false
  }
}

async function loadSessionMessages() {
  const sid = selectedStep.value?.sessionId
  if (!sid) {
    sessionMessages.value = []
    return
  }
  try {
    sessionMessages.value = await listMessages(sid)
    await nextTick()
    if (chatBox.value) chatBox.value.scrollTop = chatBox.value.scrollHeight
  } catch {
    sessionMessages.value = []
  }
}

watch(
  () => props.requirementId,
  () => {
    selectedStepId.value = null
    stopPoll()
    void refresh()
  },
  { immediate: true },
)

watch(
  () => selectedStep.value?.sessionId,
  () => {
    void loadSessionMessages()
  },
)

watch(
  selectedStep,
  (step) => {
    editableReviewItems.value = (step?.reviewItems || []).map((i) => ({ ...i }))
    const docs = step?.reviewDocs || []
    const nodeId = step?.reviewWikiNodeId || docs[0]?.wikiNodeId || null
    activeReviewWikiNodeId.value = nodeId
    void loadReviewPreview(nodeId)
  },
  { immediate: true },
)

async function loadReviewPreview(nodeId: number | null | undefined) {
  reviewPreview.value = ''
  if (!nodeId) return
  reviewPreviewLoading.value = true
  try {
    const file = await getWikiFileContent(nodeId)
    reviewPreview.value = file.content || ''
  } catch {
    reviewPreview.value = '（无法加载评审书预览）'
  } finally {
    reviewPreviewLoading.value = false
  }
}

async function onSaveReviewItems(step: PipelineStep) {
  actionLoading.value = true
  try {
    await updatePipelineReviewItems(
      props.requirementId,
      step.stepInstanceId,
      editableReviewItems.value.map((i) => ({
        id: i.id,
        checked: !!i.checked,
        content: i.content,
      })),
    )
    ElMessage.success('评审项已保存')
    await refresh()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    actionLoading.value = false
  }
}

function addReviewItem() {
  editableReviewItems.value.push({
    content: '',
    checked: true,
    source: 'HUMAN',
  })
}

function removeReviewItem(idx: number) {
  editableReviewItems.value.splice(idx, 1)
}

watch(
  () => selectedStep.value?.status,
  (st) => {
    stopPoll()
    if (st === 'RUNNING') {
      pollTimer = setInterval(() => {
        void refreshQuiet()
        void loadSessionMessages()
      }, 2500)
    }
  },
)

onUnmounted(() => stopPoll())

async function onStart() {
  actionLoading.value = true
  try {
    pipe.value = await startPipeline(props.requirementId)
    ElMessage.success('已进入自动化；AI 步骤需人工点「执行」')
    emit('started')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '启动失败')
  } finally {
    actionLoading.value = false
  }
}

async function onCancel() {
  try {
    await ElMessageBox.confirm('确认取消自动化？', '取消自动化', { type: 'warning' })
  } catch {
    return
  }
  actionLoading.value = true
  try {
    pipe.value = await cancelPipeline(props.requirementId)
    ElMessage.success('已取消')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '取消失败')
  } finally {
    actionLoading.value = false
  }
}

async function onApprove(step: PipelineStep) {
  actionLoading.value = true
  try {
    if (editableReviewItems.value.length) {
      await updatePipelineReviewItems(
        props.requirementId,
        step.stepInstanceId,
        editableReviewItems.value
          .filter((i) => (i.content || '').trim())
          .map((i) => ({
            id: i.id,
            checked: !!i.checked,
            content: i.content,
          })),
      )
    }
    pipe.value = await approvePipelineStep(props.requirementId, step.stepInstanceId)
    ElMessage.success('已确认并通过')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    actionLoading.value = false
  }
}

async function onReject(step: PipelineStep) {
  let comment = ''
  try {
    const { value } = await ElMessageBox.prompt('驳回原因（可选）', '驳回', {
      inputPlaceholder: '原因',
      confirmButtonText: '驳回',
    })
    comment = value || ''
  } catch {
    return
  }
  actionLoading.value = true
  try {
    pipe.value = await rejectPipelineStep(props.requirementId, step.stepInstanceId, comment)
    ElMessage.success('已驳回并结束流水线')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    actionLoading.value = false
  }
}

async function onExecuteAi(step: PipelineStep) {
  if (streaming.value) return
  actionLoading.value = true
  liveContent.value = ''
  liveSteps.value = []
  try {
    const prep = await preparePipelineAi(props.requirementId, step.stepInstanceId)
    pipe.value = prep.instance
    selectedStepId.value = prep.stepInstanceId
    streaming.value = true
    actionLoading.value = false
    await streamCompletion(
      { sessionId: prep.sessionId, content: prep.kickoffPrompt },
      (event, data) => {
        if (event === 'content' && data?.delta) {
          liveContent.value += data.delta
        } else if (event === 'step') {
          const msg = typeof data === 'string' ? data : data?.message || JSON.stringify(data)
          liveSteps.value.push(msg)
        }
      },
    )
    await loadSessionMessages()
    liveContent.value = ''
    pipe.value = await finalizePipelineAi(props.requirementId, prep.stepInstanceId)
    ElMessage.success('AI 完成，产物已写入 Wiki')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '执行失败')
    void refresh()
  } finally {
    streaming.value = false
    actionLoading.value = false
  }
}

async function onSkipAi(step: PipelineStep) {
  actionLoading.value = true
  try {
    pipe.value = await completePipelineAiStep(props.requirementId, step.stepInstanceId)
    ElMessage.success('已跳过并推进')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '失败')
  } finally {
    actionLoading.value = false
  }
}

async function onResetTrigger(step: PipelineStep) {
  actionLoading.value = true
  try {
    pipe.value = await retryPipelineAiStep(props.requirementId, step.stepInstanceId)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '失败')
  } finally {
    actionLoading.value = false
  }
}

function stepIcon(s: PipelineStep) {
  if (s.status === 'SUCCEEDED') return '✓'
  if (s.status === 'FAILED' || s.status === 'CANCELLED') return '!'
  if (['RUNNING', 'WAITING_HUMAN', 'WAITING_TRIGGER'].includes(s.status)) return '►'
  return '○'
}

function statusTag(status: string) {
  if (status === 'WAITING_HUMAN' || status === 'WAITING_TRIGGER') return 'warning'
  if (status === 'RUNNING') return 'primary'
  if (status === 'DONE' || status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED' || status === 'CANCELLED') return 'danger'
  return 'info'
}
</script>

<template>
  <div class="pipe-panel" :class="{ automation: isAutomation }" v-loading="loading">
    <div class="pipe-toolbar">
      <template v-if="!pipe || !isActive">
        <span class="pipe-hint">{{
          pipe?.status === 'DONE'
            ? '流水线已完成'
            : pipe?.status === 'CANCELLED'
              ? '流水线已取消'
              : '自动化未启动'
        }}</span>
        <el-button
          v-if="!isActive && pipe?.status !== 'DONE'"
          type="primary"
          size="small"
          :loading="actionLoading"
          @click="onStart"
        >
          进入自动化流程
        </el-button>
        <el-button v-if="pipe" size="small" @click="refresh">刷新</el-button>
        <el-button v-if="isAutomation" size="small" @click="emit('close')">返回需求</el-button>
      </template>
      <template v-else>
        <el-tag :type="statusTag(pipe.status)" size="small">{{ pipe.status }}</el-tag>
        <span v-if="pipe.waitingReason" class="pipe-wait">{{ pipe.waitingReason }}</span>
        <span class="pipe-metrics">
          合计 {{ formatDuration(pipe.totalDurationMs) }} · Token {{ formatTokens(pipe.totalTokens) }}
        </span>
        <div class="pipe-actions">
          <el-button size="small" @click="refresh">刷新</el-button>
          <el-button size="small" type="danger" plain :loading="actionLoading" @click="onCancel">
            取消自动化
          </el-button>
          <el-button v-if="isAutomation" size="small" @click="emit('close')">返回需求</el-button>
        </div>
      </template>
    </div>

    <div v-if="pipe?.steps?.length" class="pipe-body" :class="{ split: isAutomation }">
      <div class="pipe-left">
        <div class="pipe-timeline">
          <button
            v-for="s in pipe.steps"
            :key="s.stepInstanceId"
            type="button"
            class="pipe-step"
            :class="{
              current: selectedStep?.stepInstanceId === s.stepInstanceId,
              ok: s.status === 'SUCCEEDED',
              bad: s.status === 'FAILED' || s.status === 'CANCELLED',
            }"
            @click="selectedStepId = s.stepInstanceId"
          >
            <span class="ico">{{ stepIcon(s) }}</span>
            <span class="name">{{ s.stepName }}</span>
            <span class="meta">{{ s.status }} · {{ formatDuration(s.durationMs) }}</span>
          </button>
        </div>
        <div v-if="selectedStep" class="pipe-detail">
          <h4>{{ selectedStep.stepName }}</h4>
          <div class="detail-grid">
            <div><label>状态</label>{{ selectedStep.status }}</div>
            <div><label>执行器</label>{{ selectedStep.executor }}</div>
            <div><label>耗时</label>{{ formatDuration(selectedStep.durationMs) }}</div>
            <div><label>Token</label>{{ formatTokens(selectedStep.totalTokens) }}</div>
          </div>
          <div class="path-block">
            <div v-if="selectedStep.inputPaths?.length">
              <label>输入交付件</label>
              <ul>
                <li v-for="(p, i) in selectedStep.inputPaths" :key="i">{{ p }}</li>
              </ul>
            </div>
            <div v-if="selectedStep.contextPaths?.length">
              <label>上下文</label>
              <ul>
                <li v-for="(p, i) in selectedStep.contextPaths" :key="i">{{ p }}</li>
              </ul>
            </div>
            <div>
              <label>输出模板</label>
              <div>{{ selectedStep.outputTemplatePath || '（步骤未配置 / 执行后绑定 Wiki 模板）' }}</div>
            </div>
            <div>
              <label>输出目录</label>
              <div>{{ selectedStep.outputDirPath || '（产品 Wiki / 需求编码）' }}</div>
              <div class="muted tip">流水线任务输出目录为 Wiki；手动任务只能选个人空间</div>
            </div>
            <div v-if="selectedStep.codeRepoPaths?.length">
              <label>仓库</label>
              <ul>
                <li v-for="(p, i) in selectedStep.codeRepoPaths" :key="i">{{ p }}</li>
              </ul>
            </div>
          </div>
          <div
            v-if="
              selectedStep.status === 'WAITING_HUMAN' &&
              selectedStep.executor === 'HUMAN_APPROVE' &&
              (editableReviewItems.length || selectedStep.reviewDocs?.length)
            "
            class="review-confirm"
          >
            <div class="review-confirm-hd">
              <strong>评审确认</strong>
              <span class="muted">可预览 AI 评审书，编辑有效项后确认</span>
            </div>
            <div v-if="selectedStep.reviewDocs?.length" class="review-docs">
              <button
                v-for="d in selectedStep.reviewDocs"
                :key="d.reviewId"
                type="button"
                class="doc-chip"
                :class="{ on: activeReviewWikiNodeId === d.wikiNodeId }"
                @click="
                  activeReviewWikiNodeId = d.wikiNodeId ?? null;
                  loadReviewPreview(d.wikiNodeId)
                "
              >
                {{ d.stepName || d.roleHint }}
              </button>
            </div>
            <div v-loading="reviewPreviewLoading" class="review-preview">
              <label>评审书预览（只读）</label>
              <pre>{{ reviewPreview || '（暂无 Wiki 评审书）' }}</pre>
            </div>
            <div class="review-block">
              <label>有效评审项（可编辑 · 勾选=有效）</label>
              <div v-for="(it, idx) in editableReviewItems" :key="it.id ?? `n-${idx}`" class="review-edit-row">
                <el-checkbox v-model="it.checked" />
                <el-input v-model="it.content" type="textarea" :rows="2" placeholder="评审项内容" />
                <el-button link type="danger" @click="removeReviewItem(idx)">删</el-button>
              </div>
              <div class="review-edit-actions">
                <el-button size="small" @click="addReviewItem">新增有效项</el-button>
                <el-button size="small" :loading="actionLoading" @click="onSaveReviewItems(selectedStep)">
                  仅保存
                </el-button>
              </div>
            </div>
          </div>
          <div v-if="selectedStep.errorMessage" class="err">{{ selectedStep.errorMessage }}</div>
          <div class="detail-actions">
            <template v-if="selectedStep.status === 'WAITING_HUMAN' && selectedStep.executor === 'HUMAN_APPROVE'">
              <el-button type="primary" size="small" :loading="actionLoading" @click="onApprove(selectedStep)">
                确认并继续
              </el-button>
              <el-button type="danger" plain size="small" :loading="actionLoading" @click="onReject(selectedStep)">
                驳回
              </el-button>
            </template>
            <template
              v-else-if="
                selectedStep.status === 'WAITING_TRIGGER' ||
                (selectedStep.status === 'FAILED' && selectedStep.executor?.startsWith('AI_'))
              "
            >
              <el-button
                type="primary"
                size="small"
                :loading="actionLoading || streaming"
                @click="onExecuteAi(selectedStep)"
              >
                执行 AI
              </el-button>
              <el-button size="small" plain :loading="actionLoading" @click="onSkipAi(selectedStep)">
                跳过
              </el-button>
            </template>
            <template v-else-if="selectedStep.status === 'RUNNING' && selectedStep.executor?.startsWith('AI_')">
              <span class="muted tip">{{ streaming ? 'AI 生成中…' : '会话执行中，完成后将自动落盘推进' }}</span>
              <el-button size="small" :loading="actionLoading" @click="onResetTrigger(selectedStep)">
                中止并待重试
              </el-button>
            </template>
          </div>
        </div>
      </div>

      <div v-if="isAutomation" class="pipe-session">
        <div class="sess-hd">
          <span>AI 会话</span>
          <span v-if="selectedStep?.sessionId" class="muted">#{{ selectedStep.sessionId }}</span>
          <span v-else class="muted">选中 AI 步并点「执行」后显示</span>
        </div>
        <div ref="chatBox" class="sess-body">
          <div v-for="m in sessionMessages" :key="m.id" class="msg" :class="m.role">
            <div class="role">{{ m.role }}</div>
            <pre class="content">{{ m.content }}</pre>
          </div>
          <div v-if="liveContent" class="msg assistant live">
            <div class="role">assistant · streaming</div>
            <pre class="content">{{ liveContent }}</pre>
          </div>
          <div v-if="liveSteps.length" class="steps-log">
            <div v-for="(st, i) in liveSteps" :key="i" class="step-line">{{ st }}</div>
          </div>
          <div v-if="!sessionMessages.length && !liveContent" class="empty-sess">暂无会话消息</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pipe-panel {
  border: 1px solid var(--dap-gray-200);
  border-radius: var(--dap-radius-md);
  overflow: hidden;
  background: var(--dap-gray-0);
  display: flex;
  flex-direction: column;
  min-height: 220px;
}
.pipe-panel.automation {
  height: 100%;
  border: none;
  border-radius: 0;
}
.pipe-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: var(--dap-gray-50);
  border-bottom: 1px solid var(--dap-gray-200);
}
.pipe-hint { font-size: 13px; color: var(--dap-gray-700); flex: 1; }
.pipe-wait { font-size: 12px; color: var(--dap-warning); }
.pipe-metrics { font-size: 12px; color: var(--dap-gray-700); }
.pipe-actions { margin-left: auto; display: flex; gap: 8px; }
.muted { color: var(--dap-gray-500); font-size: 12px; }
.pipe-body { display: flex; flex: 1; min-height: 0; }
.pipe-body.split .pipe-left { width: 42%; border-right: 1px solid var(--dap-gray-200); display: flex; flex-direction: column; min-width: 280px; }
.pipe-body:not(.split) .pipe-left { display: grid; grid-template-columns: minmax(200px, 240px) 1fr; width: 100%; }
.pipe-timeline { max-height: 360px; overflow: auto; padding: 6px 0; border-right: 1px solid var(--dap-gray-200); }
.pipe-body.split .pipe-timeline { border-right: none; border-bottom: 1px solid var(--dap-gray-200); max-height: 40%; }
.pipe-step {
  display: grid;
  grid-template-columns: 16px 1fr;
  grid-template-rows: auto auto;
  column-gap: 8px;
  width: 100%;
  text-align: left;
  border: none;
  background: transparent;
  padding: 8px 12px;
  cursor: pointer;
  font-family: inherit;
  color: var(--dap-gray-700);
  font-size: 13px;
}
.pipe-step .ico { grid-row: 1 / span 2; }
.pipe-step .meta { grid-column: 2; font-size: 11px; color: var(--dap-gray-500); }
.pipe-step:hover { background: var(--dap-gray-50); }
.pipe-step.current { background: var(--dap-primary-light); color: var(--dap-primary); font-weight: 500; }
.pipe-step.ok .ico { color: var(--dap-success); }
.pipe-step.bad .ico { color: var(--dap-danger); }
.pipe-detail { padding: 12px 16px; overflow: auto; flex: 1; }
.pipe-detail h4 { margin: 0 0 10px; font-size: 14px; }
.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 12px;
  font-size: 13px;
  margin-bottom: 12px;
}
.detail-grid label, .path-block label, .review-block label {
  display: block;
  font-size: 12px;
  color: var(--dap-gray-500);
}
.path-block { font-size: 12px; margin-bottom: 10px; }
.path-block ul { margin: 2px 0 8px; padding-left: 18px; }
.review-confirm {
  border: 1px solid var(--dap-gray-200);
  border-radius: 8px;
  padding: 10px;
  margin-bottom: 12px;
  background: #fff;
}
.review-confirm-hd { display: flex; flex-direction: column; gap: 2px; margin-bottom: 8px; font-size: 13px; }
.review-docs { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.doc-chip {
  border: 1px solid var(--dap-gray-200);
  background: var(--dap-gray-50);
  border-radius: 999px;
  padding: 2px 10px;
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
}
.doc-chip.on { border-color: var(--dap-primary); color: var(--dap-primary); background: var(--dap-primary-light); }
.review-preview label { font-size: 12px; color: var(--dap-gray-500); }
.review-preview pre {
  margin: 4px 0 10px;
  max-height: 220px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  font-family: inherit;
  background: var(--dap-gray-50);
  border: 1px solid var(--dap-gray-200);
  border-radius: 6px;
  padding: 8px;
}
.review-block { margin-bottom: 10px; display: flex; flex-direction: column; gap: 8px; }
.review-edit-row { display: grid; grid-template-columns: 24px 1fr auto; gap: 8px; align-items: start; }
.review-edit-actions { display: flex; gap: 8px; }
.detail-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.tip { flex-basis: 100%; }
.err { color: var(--dap-danger); font-size: 13px; margin-bottom: 8px; }
.pipe-session { flex: 1; display: flex; flex-direction: column; min-width: 0; background: #fafafa; }
.sess-hd {
  padding: 8px 12px;
  border-bottom: 1px solid var(--dap-gray-200);
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 13px;
  font-weight: 500;
}
.sess-body { flex: 1; overflow: auto; padding: 12px; }
.msg { margin-bottom: 12px; }
.msg .role { font-size: 11px; color: var(--dap-gray-500); margin-bottom: 4px; }
.msg .content {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  font-family: inherit;
  background: #fff;
  border: 1px solid var(--dap-gray-200);
  border-radius: 6px;
  padding: 8px 10px;
}
.msg.user .content { background: var(--dap-primary-light); }
.msg.live .content { border-color: var(--dap-primary); }
.steps-log { font-size: 12px; color: var(--dap-gray-600); margin-top: 8px; }
.step-line { padding: 2px 0; border-bottom: 1px dashed var(--dap-gray-200); }
.empty-sess { color: var(--dap-gray-500); font-size: 13px; padding: 24px; text-align: center; }
@media (max-width: 900px) {
  .pipe-body.split { flex-direction: column; }
  .pipe-body.split .pipe-left { width: 100%; }
}
</style>

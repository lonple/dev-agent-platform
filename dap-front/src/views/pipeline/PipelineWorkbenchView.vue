<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
import {
  cancelPipeline,
  confirmPipelineTrackMerge,
  confirmPipelineUnit,
  getCodingProgress,
  getPipelineByIssue,
  getPipelineByRequirement,
  getPipelineUnitConfirm,
  openPipelineDoc,
  pmSecondPipeline,
  publishPipelineUnit,
  startIssuePipeline,
  startPipeline,
  stopPipelineUnit,
  triggerPipelineUnit,
  type CodingProgress,
  type PipelineConfirmRound,
  type PipelineInstance,
  type PipelineNodeItem,
  type PipelineTrackMerge,
  type PipelineTrackMergeItem,
  type PipelineUnitItem,
} from '../../api/pipeline'
import { listProductVersions, type ProductVersionItem } from '../../api/product'
import { listMessages, listSessionSteps, type ChatMessage, type ChatStepBrief } from '../../api/chat'
import MarkdownBody from '../../components/MarkdownBody.vue'
import {
  UNIT_FLOW_STEPS,
  canOpenUnitDoc,
  docFileName,
  nodeFlowNote,
  unitFlowIndex,
  unitMeta,
} from './pipelineUnitMeta'

const route = useRoute()
const router = useRouter()
const requirementId = computed(() => {
  const n = Number(route.params.requirementId)
  return Number.isFinite(n) && n > 0 ? n : 0
})
const issueId = computed(() => {
  const n = Number(route.params.issueId)
  return Number.isFinite(n) && n > 0 ? n : 0
})
const isIssuePipe = computed(() => issueId.value > 0)

const loading = ref(false)
const actionLoading = ref(false)
const instance = ref<PipelineInstance | null>(null)
const progress = computed(() => {
  const nodes = (instance.value?.nodes || []).filter(n => n.enabledFlag !== false && n.status !== 'SKIPPED')
  const completed = nodes.filter(n => n.status === 'SUCCEEDED').length
  const waiting = nodes.flatMap(n => n.units || []).filter(u => ['WAITING_CONFIRM', 'WAITING_PUBLISH'].includes(u.status)).length
  const running = nodes.flatMap(n => n.units || []).filter(u => u.status === 'RUNNING').length
  return { completed, total: nodes.length, percent: nodes.length ? Math.round(completed / nodes.length * 100) : 0, waiting, running }
})
const selectedNodeId = ref<number | null>(null)
const activeUnitId = ref<number | null>(null)
const expandedConfirmUnitId = ref<number | null>(null)
const aiOpen = ref(false)
const sessionMsgs = ref<ChatMessage[]>([])
const sessionSteps = ref<ChatStepBrief[]>([])
/** 按 runId 展开的执行过程气泡；默认只展开最近一轮 */
const expandedStepRuns = ref<Record<string, boolean>>({})
const stepsStartedAt = ref<number | null>(null)
const stepsDurationSec = ref<number | null>(null)
const sessionLoading = ref(false)
const codingProgress = ref<CodingProgress | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null

const codingStageLabel: Record<string, string> = {
  CODING_PLAN: '① 任务拆分',
  CODING_ANALYZE: '② 调度分析',
  CODING_TASK: '③ 任务实现',
  CODING_INTEGRATE: '④ 汇总验收',
}

const drawerOpen = ref(false)
const drawerTitle = ref('')
const drawerSub = ref('')
const drawerBody = ref('')
const drawerHtml = ref(false)

const statusText: Record<string, string> = {
  RUNNING: '运行中',
  WAITING_HUMAN: '等待确认',
  SUSPENDED: '已挂起',
  CANCELLED: '已取消',
  FAILED: '失败',
  DONE: '已完成',
  PENDING: '待开始',
  WAITING_TRIGGER: '待触发',
  WAITING_CONFIRM: '等待确认',
  WAITING_PUBLISH: '待发布文档',
  SUCCEEDED: '已完成',
  SKIPPED: '已跳过',
}

const kindLabel: Record<string, string> = {
  HUMAN: '人工',
  AI: 'AI 任务',
  AI_MULTI: '多岗 AI',
  GATE: '门禁',
  MERGE: '合并',
  SYSTEM: '系统',
}

function tagClass(status: string) {
  if (['SUCCEEDED', 'DONE'].includes(status)) return 'tag-ok'
  if (['WAITING_HUMAN', 'WAITING_CONFIRM', 'WAITING_PUBLISH', 'SUSPENDED'].includes(status)) return 'tag-warn'
  if (['RUNNING', 'WAITING_TRIGGER'].includes(status)) return 'tag-run'
  if (['FAILED', 'CANCELLED'].includes(status)) return 'tag-bad'
  return 'tag-info'
}

function stepIcon(status: string) {
  if (status === 'SUCCEEDED') return '✓'
  if (status === 'SKIPPED') return '–'
  if (['RUNNING', 'WAITING_TRIGGER', 'WAITING_HUMAN', 'WAITING_CONFIRM', 'WAITING_PUBLISH'].includes(status))
    return '●'
  if (status === 'FAILED') return '!'
  return '○'
}

function canOpenOutput(u: PipelineUnitItem) {
  return canOpenUnitDoc(u.status, u.outputWorkspaceNodeId, u.outputWikiNodeId)
}

function canOpenConfirm(u: PipelineUnitItem) {
  // 有确认轮次即可点开（工作台可读 hidden；个人空间树仍不展示）
  return (u.confirmRound || 0) > 0
}

function roleLabel(code: string) {
  return (
    { PM: '产品经理', ARCH: '架构师', UI: 'UI设计师', DEV: '研发', QA: '测试' }[code] || code
  )
}

const REVIEW_NODE_TRACK: Record<string, string> = {
  prd_review: 'PRD',
  arch_review: 'ARCH',
  ui_review: 'UI',
  detail_review: 'DETAIL',
  cases_review: 'CASES',
}

/** 当前节点对应的轨合并草稿 */
const trackMergeDraft = ref<PipelineTrackMergeItem[]>([])

function parseMergeItems(raw?: string | null): PipelineTrackMergeItem[] {
  if (!raw) return []
  try {
    const arr = JSON.parse(raw)
    if (!Array.isArray(arr)) return []
    return arr.map((x: PipelineTrackMergeItem) => ({
      ...x,
      valuable: x.valuable == null ? true : !!x.valuable,
      reviewers: Array.isArray(x.reviewers) ? x.reviewers : [],
    }))
  } catch {
    return []
  }
}

/** 优先用会话真实绑定路径；未建会话时回退到元数据占位文案 */
function unitContextPaths(u: PipelineUnitItem): string[] {
  return Array.isArray(u.contextPaths) ? u.contextPaths.filter(Boolean) : []
}

function metaOf(u: PipelineUnitItem) {
  return unitMeta(u.taskTypeCode, u.positionCode)
}

function repoLabel(kind?: string) {
  if (!kind || kind === 'NONE') return '无'
  if (kind === 'BIZ') return '有（业务仓）'
  if (kind === 'TEST') return '有（测试仓）'
  if (kind === 'BOTH') return '有（业务+测试）'
  return kind
}

const selectedNode = computed(() => {
  if (!instance.value?.nodes?.length) return null
  return (
    instance.value.nodes.find((n) => n.id === selectedNodeId.value) ||
    instance.value.nodes.find((n) =>
      ['RUNNING', 'WAITING_TRIGGER', 'WAITING_HUMAN', 'WAITING_CONFIRM'].includes(n.status),
    ) ||
    instance.value.nodes[0] ||
    null
  )
})

const selectedTrackMerge = computed<PipelineTrackMerge | null>(() => {
  const node = selectedNode.value
  const merges = instance.value?.trackMerges || []
  if (!node || !merges.length) return null
  const track = REVIEW_NODE_TRACK[node.nodeCode]
  if (track) {
    return merges.find((m) => m.trackCode === track) || null
  }
  if (node.nodeCode === 'doc_revise' || node.nodeCode === 'doc_revise_dt') {
    return merges.find((m) => m.status === 'WAITING_HUMAN') || null
  }
  return null
})

watch(
  () => [selectedTrackMerge.value?.id, selectedTrackMerge.value?.status, selectedTrackMerge.value?.llmMergeJson] as const,
  () => {
    const m = selectedTrackMerge.value
    if (!m || m.status !== 'WAITING_HUMAN') {
      trackMergeDraft.value = []
      return
    }
    trackMergeDraft.value = parseMergeItems(m.itemsJson || m.llmMergeJson)
  },
  { immediate: true },
)

function setMergeValuable(id: string, valuable: boolean) {
  const it = trackMergeDraft.value.find((x) => x.id === id)
  if (it) it.valuable = valuable
}

async function submitTrackMerge() {
  const m = selectedTrackMerge.value
  if (!instance.value || !m || m.status !== 'WAITING_HUMAN') return
  if (trackMergeDraft.value.some((x) => x.valuable == null)) {
    ElMessage.warning('请先对每条标记「有价值 / 无价值」')
    return
  }
  actionLoading.value = true
  try {
    await confirmPipelineTrackMerge(instance.value.id, m.trackCode, trackMergeDraft.value)
    ElMessage.success(
      `已生成 ${m.mergeFileName || '合并评审'}，${
        trackMergeDraft.value.some((x) => x.valuable) ? '即将启动修订' : '无有效项已跳过修订'
      }`,
    )
    await load(true)
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '确认合并失败')
  } finally {
    actionLoading.value = false
  }
}

const flowNote = computed(() =>
  selectedNode.value ? nodeFlowNote(selectedNode.value.nodeCode) : null,
)

const isAiNode = computed(() => {
  const k = selectedNode.value?.kind
  return k === 'AI' || k === 'AI_MULTI' || !!selectedNode.value?.units?.length
})

const activeUnit = computed(() => {
  const units = selectedNode.value?.units || []
  return units.find((u) => u.id === activeUnitId.value) || units[0] || null
})

const isCodingUnit = computed(() => {
  const code = (activeUnit.value?.taskTypeCode || '').toUpperCase()
  // 仅需求开发编码走四阶段条；问题修复是单次 RUN_UNIT，不展示
  return code === 'GEN_CODING'
})

async function refreshCodingProgress(unitId: number | null | undefined) {
  if (!unitId) {
    codingProgress.value = null
    return
  }
  const code = (
    (selectedNode.value?.units || []).find((u) => u.id === unitId)?.taskTypeCode || ''
  ).toUpperCase()
  if (code !== 'GEN_CODING') {
    codingProgress.value = null
    return
  }
  try {
    codingProgress.value = await getCodingProgress(unitId)
  } catch {
    /* 旧后端无接口时忽略 */
  }
}

async function openCodingSession(sessionId?: number | null) {
  if (!sessionId) return
  await loadSession(sessionId)
}

const aiPreDone = computed(() => {
  const n = instance.value?.nodes?.find((x) => x.nodeCode === 'ai_pre')
  return n?.status === 'SUCCEEDED'
})

watch(
  () => [instance.value?.nodes, instance.value?.trackMerges] as const,
  ([nodes, merges]) => {
    if (!nodes?.length) return
    if (selectedNodeId.value && nodes.some((n) => n.id === selectedNodeId.value)) {
      return
    }
    // 首次定位：有待确认合并时优先到对应评审节点
    const waitingMerge = (merges || []).find((m) => m.status === 'WAITING_HUMAN')
    if (waitingMerge?.reviewNodeCode) {
      const reviewNode = nodes.find((n) => n.nodeCode === waitingMerge.reviewNodeCode)
      if (reviewNode) {
        selectedNodeId.value = reviewNode.id
        return
      }
    }
    const active = nodes.find((n) =>
      ['RUNNING', 'WAITING_TRIGGER', 'WAITING_HUMAN', 'WAITING_CONFIRM', 'WAITING_PUBLISH'].includes(
        n.status,
      ),
    )
    selectedNodeId.value = (active || nodes[0]).id
  },
  { immediate: true },
)

watch(selectedNode, (n) => {
  if (!n?.units?.length) {
    activeUnitId.value = null
    aiOpen.value = false
    return
  }
  if (!activeUnitId.value || !n.units.some((u) => u.id === activeUnitId.value)) {
    activeUnitId.value = n.units[0].id
  }
  aiOpen.value = isAiNode.value
})

/** 只在会话 ID 变化时重载；勿跟单元对象引用/状态轮询绑定，否则会闪空并丢掉步骤日志 */
watch(
  () => activeUnit.value?.sessionId ?? null,
  (sessionId, prev) => {
    if (sessionId && aiOpen.value) {
      if (sessionId !== prev) {
        stepsStartedAt.value = null
        stepsDurationSec.value = null
        expandedStepRuns.value = {}
      }
      loadSession(sessionId, sessionId === prev)
    } else if (!sessionId) {
      sessionMsgs.value = []
      sessionSteps.value = []
      stepsStartedAt.value = null
      stepsDurationSec.value = null
      expandedStepRuns.value = {}
    }
  },
)

/** 跑完后立刻再拉一次步骤，避免只在 RUNNING 时刷新导致日志「消失」 */
watch(
  () => activeUnit.value?.status,
  (status, prev) => {
    const sid = activeUnit.value?.sessionId
    if (!sid || !aiOpen.value) return
    if (prev === 'RUNNING' && status && status !== 'RUNNING') {
      loadSession(sid, true)
    }
  },
)

const summaryBits = computed(() => {
  const inst = instance.value
  if (!inst) return []
  const nodes = inst.nodes || []
  const done = nodes.filter((n) => n.status === 'SUCCEEDED' || n.status === 'SKIPPED').length
  const bits = [
    { label: '状态', value: statusText[inst.status] || inst.status },
    { label: '节点进度', value: `${done}/${nodes.length}` },
  ]
  if (inst.waitingReason) bits.push({ label: '等待', value: inst.waitingReason })
  if (inst.worktrees?.length) {
    bits.push({ label: 'Worktree', value: `${inst.worktrees.length} 个编码仓` })
  }
  return bits
})

const versions = ref<ProductVersionItem[]>([])
const pmForm = reactive({
  action: 'PASS_AND_DEV' as 'PASS' | 'PASS_AND_DEV',
  releaseVersionId: null as number | null,
  enableArch: false,
  enableUi: false,
})

const confirmRound = ref<PipelineConfirmRound | null>(null)
const confirmTaskType = ref('')
function isCodeReviewConfirm() {
  return confirmTaskType.value === 'GEN_CODE_REVIEW' || confirmTaskType.value === 'GEN_ISSUE_CODE_REVIEW'
}
function isIntTestConfirm() {
  return confirmTaskType.value === 'GEN_INT_TEST' || confirmTaskType.value === 'GEN_ISSUE_INT_TEST'
}
const findingRows = ref<
  Array<{
    id: string
    title: string
    severity?: string
    suggestions?: string
    recommend?: string
    agree: boolean | null
    comment: string
  }>
>([])

function isLowSeverity(severity: unknown) {
  return String(severity ?? '')
    .trim()
    .toUpperCase() === 'LOW'
}
const questionRows = ref<Array<{ id: string; question: string; answer: string }>>([])

const needsPoll = computed(() => {
  const s = instance.value?.status
  return !!s && !['DONE', 'CANCELLED', 'FAILED', 'SUSPENDED'].includes(s)
})

async function load(silent = false) {
  if (!requirementId.value && !issueId.value) return
  if (!silent) loading.value = true
  try {
    instance.value = isIssuePipe.value
      ? await getPipelineByIssue(issueId.value)
      : await getPipelineByRequirement(requirementId.value)
    const u = activeUnit.value
    // 执行中持续刷步骤；结束后仍保留会话日志（不再因状态变化清空）
    if (u?.sessionId && (u.status === 'RUNNING' || sessionSteps.value.length > 0 || stepsStartedAt.value != null)) {
      await loadSession(u.sessionId, true)
    }
    if (u?.id) await refreshCodingProgress(u.id)
  } catch (e: any) {
    if (!silent) ElMessage.error(e?.message || '加载流水线失败')
  } finally {
    if (!silent) loading.value = false
  }
}

function startPoll() {
  stopPoll()
  pollTimer = setInterval(() => {
    if (needsPoll.value) load(true)
  }, 2500)
}
function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function selectNode(n: PipelineNodeItem) {
  selectedNodeId.value = n.id
  expandedConfirmUnitId.value = null
  confirmRound.value = null
  if (n.units?.length) {
    activeUnitId.value = n.units[0].id
    aiOpen.value = true
  } else {
    aiOpen.value = false
  }
}

function focusUnit(u: PipelineUnitItem) {
  const sameSession = u.sessionId != null && u.sessionId === activeUnit.value?.sessionId
  activeUnitId.value = u.id
  aiOpen.value = true
  if (!sameSession) {
    stepsStartedAt.value = null
    stepsDurationSec.value = null
  }
  // 已有会话：默认展开执行过程，避免点选后「日志没了」
  if (u.sessionId && u.status !== 'WAITING_TRIGGER' && u.status !== 'PENDING') {
    loadSession(u.sessionId)
  } else if (!u.sessionId) {
    sessionMsgs.value = []
    sessionSteps.value = []
    expandedStepRuns.value = {}
  }
  refreshCodingProgress(u.id)
}

async function loadSession(sessionId: number, silent = false) {
  if (!silent) sessionLoading.value = true
  try {
    const [msgs, steps] = await Promise.all([
      listMessages(sessionId),
      listSessionSteps(sessionId).catch(() => [] as ChatStepBrief[]),
    ])
    sessionMsgs.value = [...msgs].sort((a, b) => (a.id || 0) - (b.id || 0))
    // 组内按 seq；组间靠 occurredAt（勿跨 run 只按 seq 混排）
    const orderedSteps = [...steps].sort((a, b) => {
      const ta = String(a.occurredAt || '')
      const tb = String(b.occurredAt || '')
      if (ta && tb && ta !== tb) return ta.localeCompare(tb)
      const sa = a.seq ?? 0
      const sb = b.seq ?? 0
      if (sa !== sb) return sa - sb
      return String(a.eventId || '').localeCompare(String(b.eventId || ''))
    })
    if (orderedSteps.length > 0 || !silent || sessionSteps.value.length === 0) {
      sessionSteps.value = orderedSteps
    }
    const runs = groupStepsByRun(sessionSteps.value)
    const lastRunKey = runs.length ? runs[runs.length - 1].key : null
    if (activeUnit.value?.status === 'RUNNING') {
      if (stepsStartedAt.value == null) stepsStartedAt.value = Date.now()
      stepsDurationSec.value = null
      if (lastRunKey) expandedStepRuns.value = { ...expandedStepRuns.value, [lastRunKey]: true }
    } else if (stepsStartedAt.value != null && stepsDurationSec.value == null) {
      stepsDurationSec.value = Math.max(1, Math.round((Date.now() - stepsStartedAt.value) / 1000))
      if (lastRunKey) expandedStepRuns.value = { ...expandedStepRuns.value, [lastRunKey]: true }
    } else if (lastRunKey && expandedStepRuns.value[lastRunKey] == null) {
      expandedStepRuns.value = { ...expandedStepRuns.value, [lastRunKey]: true }
    }
  } catch {
    if (!silent) {
      sessionMsgs.value = []
      // 失败时保留已有步骤，避免误清空
    }
  } finally {
    if (!silent) sessionLoading.value = false
  }
}

type StepRunGroup = { key: string; runId: string; messageId?: number; steps: ChatStepBrief[] }

function groupStepsByRun(steps: ChatStepBrief[]): StepRunGroup[] {
  const map = new Map<string, StepRunGroup>()
  for (const s of steps) {
    const key =
      s.runId || (s.messageId != null ? `msg-${s.messageId}` : `evt-${s.eventId || 'x'}`)
    let g = map.get(key)
    if (!g) {
      g = { key, runId: s.runId || key, messageId: s.messageId, steps: [] }
      map.set(key, g)
    }
    if (g.messageId == null && s.messageId != null) g.messageId = s.messageId
    g.steps.push(s)
  }
  for (const g of map.values()) {
    g.steps.sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
  }
  return [...map.values()].sort((a, b) => {
    const ta = a.steps[0]?.occurredAt || ''
    const tb = b.steps[0]?.occurredAt || ''
    if (ta && tb) return ta.localeCompare(tb)
    return (a.messageId || 0) - (b.messageId || 0)
  })
}

type TimelineItem =
  | { kind: 'steps'; key: string; run: StepRunGroup; live: boolean }
  | { kind: 'message'; key: string; msg: ChatMessage; placeholder?: string }

/** 每轮 LLM 独立「已处理」气泡，挂在对应助手消息前；确认续跑 = 新 run */
const executionTimeline = computed((): TimelineItem[] => {
  const msgs = [...sessionMsgs.value].sort((a, b) => (a.id || 0) - (b.id || 0))
  const runs = groupStepsByRun(sessionSteps.value)
  const used = new Set<string>()
  const items: TimelineItem[] = []
  const isLive = activeUnit.value?.status === 'RUNNING'
  const lastRunKey = runs.length ? runs[runs.length - 1].key : null

  for (const m of msgs) {
    if (m.role === 'assistant') {
      let run = runs.find((r) => !used.has(r.key) && r.messageId != null && r.messageId === m.id)
      if (!run) run = runs.find((r) => !used.has(r.key))
      if (run) {
        used.add(run.key)
        items.push({
          kind: 'steps',
          key: `steps-${run.key}`,
          run,
          live: !!isLive && run.key === lastRunKey,
        })
      }
      if (isConfirmJsonAssistant(m.content)) {
        items.push({
          kind: 'message',
          key: `msg-${m.id}`,
          msg: m,
          placeholder: '已输出确认项，请在左侧展开确认',
        })
      } else {
        items.push({ kind: 'message', key: `msg-${m.id}`, msg: m })
      }
    } else {
      items.push({ kind: 'message', key: `msg-${m.id}`, msg: m })
    }
  }
  for (const run of runs) {
    if (used.has(run.key)) continue
    items.push({
      kind: 'steps',
      key: `steps-${run.key}`,
      run,
      live: !!isLive && run.key === lastRunKey,
    })
  }
  return items
})

function toggleStepRun(runKey: string) {
  expandedStepRuns.value = {
    ...expandedStepRuns.value,
    [runKey]: !expandedStepRuns.value[runKey],
  }
}

function isStepRunExpanded(run: StepRunGroup, live: boolean) {
  if (expandedStepRuns.value[run.key] != null) return !!expandedStepRuns.value[run.key]
  return live
}

function stepRunSummaryLabel(run: StepRunGroup, live: boolean) {
  if (live) {
    if (stepsStartedAt.value != null) {
      const sec = Math.max(1, Math.round((Date.now() - stepsStartedAt.value) / 1000))
      return `处理中 · ${sec}秒 · ${run.steps.length} 步`
    }
    return `处理中 · ${run.steps.length} 步`
  }
  const first = run.steps[0]?.occurredAt
  const last = run.steps[run.steps.length - 1]?.occurredAt
  let secLabel = ''
  if (first && last) {
    const ms = Date.parse(last) - Date.parse(first)
    if (Number.isFinite(ms) && ms >= 0) secLabel = ` ${Math.max(1, Math.round(ms / 1000))}秒`
  }
  return `已处理${secLabel} · ${run.steps.length} 步`
}

/** 用户消息右侧简洁展示（流水线 kickoff 很长，只留任务摘要） */
function displayUserContent(content?: string | null): string {
  if (!content) return ''
  // 确认回灌 JSON 不展示原文
  if (looksLikeConfirmPayload(content)) return '已提交确认，继续执行…'
  const task = content.match(/【流水线任务】[^\n]*/)
  if (task) {
    const start = content.indexOf('【流水线任务】')
    let end = content.indexOf('\n\n', start)
    if (end < 0) end = Math.min(content.length, start + 160)
    const block = content.slice(start, end).replace(/\s+/g, ' ').trim()
    return block.length > 140 ? block.slice(0, 140) + '…' : block
  }
  const plain = content.replace(/\s+/g, ' ').trim()
  return plain.length > 120 ? plain.slice(0, 120) + '…' : plain
}

/** 平台两段式确认 JSON / 回灌 JSON，自动化流水线日志里不展示 */
function looksLikeConfirmPayload(content: string): boolean {
  const t = content.trim()
  if (!t) return false
  if (/"phase"\s*:\s*"need_human_confirm"/.test(t)) return true
  if (/"findings"\s*:/.test(t) && /"openQuestions"\s*:/.test(t) && t.startsWith('{')) return true
  if (t.startsWith('{') && /"findingAnswers"|"questionAnswers"/.test(t)) return true
  return false
}

function isConfirmJsonAssistant(content?: string | null): boolean {
  if (!content) return false
  const t = content.trim()
  if (looksLikeConfirmPayload(t)) return true
  // 整段基本是 ```json ... phase ... ```
  const fence = t.match(/```(?:json)?\s*([\s\S]*?)```/i)
  if (fence && looksLikeConfirmPayload(fence[1])) {
    const rest = t.replace(/```(?:json)?\s*[\s\S]*?```/gi, '').trim()
    return rest.length < 30
  }
  return false
}

/** 正式文档回复：去掉内嵌确认 JSON 代码块，只留可读正文 */
function displayAssistantContent(content?: string | null): string {
  if (!content) return ''
  let text = content.replace(/```(?:json)?\s*\{[\s\S]*?"phase"\s*:[\s\S]*?\}\s*```/gi, '')
  text = text.replace(/^\s*\{[\s\S]*"phase"\s*:\s*"need_human_confirm"[\s\S]*\}\s*$/m, '')
  return text.trim()
}

function flowState(u: PipelineUnitItem) {
  return unitFlowIndex(u.status, u.confirmRound)
}

function flowStepClass(u: PipelineUnitItem, idx: number) {
  const { current, failed, allDone } = flowState(u)
  if (current < 0) return 'idle'
  if (allDone) return 'done'
  if (idx < current) return 'done'
  if (idx === current) return failed ? 'failed' : 'current'
  return 'idle'
}

async function openDocByName(fileName: string | null, title?: string) {
  if (!fileName || !instance.value) {
    ElMessage.info('暂无对应文档')
    return
  }
  try {
    const file = await openPipelineDoc(instance.value.id, fileName)
    drawerTitle.value = title || file.displayName
    drawerSub.value = file.displayName
    drawerBody.value = file.content || ''
    drawerHtml.value = /\.html$/i.test(file.displayName)
    drawerOpen.value = true
  } catch (e: any) {
    ElMessage.warning(e?.message || `未找到文档：${fileName}`)
  }
}

function openInputDoc(label: string) {
  openDocByName(docFileName(label), label)
}

function openTemplateDoc(u: PipelineUnitItem) {
  const meta = metaOf(u)
  const name = docFileName(meta.template) || meta.outputDocName
  openDocByName(name, meta.template)
}

function openConfirmDoc(u: PipelineUnitItem) {
  // 优先最终确认文件名；兼容旧的 -r{n} 展示名
  const meta = metaOf(u)
  const finalName = meta.confirmDocPattern.replace(/-r\{n\}/i, '').replace('{n}', '')
  openDocByName(finalName || meta.confirmDocPattern, '确认归档')
}

async function doStart() {
  actionLoading.value = true
  try {
    if (isIssuePipe.value) {
      await startIssuePipeline(issueId.value)
    } else {
      await startPipeline(requirementId.value)
    }
    ElMessage.success('流水线已启动')
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message || '启动失败')
  } finally {
    actionLoading.value = false
  }
}

async function doCancel() {
  if (!instance.value) return
  try {
    await ElMessageBox.confirm(
      isIssuePipe.value ? '确认取消本问题单的自动化流水线？' : '确认取消本需求的自动化流水线？',
      '取消自动化',
      { type: 'warning' },
    )
  } catch {
    return
  }
  actionLoading.value = true
  try {
    await cancelPipeline(instance.value.id)
    ElMessage.success('已取消')
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message || '取消失败')
  } finally {
    actionLoading.value = false
  }
}

async function doTrigger(unit: PipelineUnitItem) {
  const isRetry = isRetriggerStatus(unit.status)
  if (isRetry) {
    try {
      await ElMessageBox.confirm(
        `将重新执行「${unit.unitName}」：确认轮次与待发布状态会重置，并开启新的执行会话（旧日志仍可在历史会话查看）。是否继续？`,
        '重新执行',
        { type: 'warning', confirmButtonText: '重新执行', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  actionLoading.value = true
  try {
    await triggerPipelineUnit(unit.id)
    ElMessage.success(isRetry ? '已重新执行：' + unit.unitName : '已触发：' + unit.unitName)
    activeUnitId.value = unit.id
    aiOpen.value = true
    expandedConfirmUnitId.value = null
    confirmRound.value = null
    stepsStartedAt.value = Date.now()
    stepsDurationSec.value = null
    expandedStepRuns.value = {}
    await load()
    const fresh = instance.value?.nodes
      ?.flatMap((n) => n.units || [])
      .find((u) => u.id === unit.id)
    if (fresh?.sessionId) await loadSession(fresh.sessionId)
  } catch (e: any) {
    ElMessage.error(e?.message || '触发失败')
  } finally {
    actionLoading.value = false
  }
}

async function doStop(unit: PipelineUnitItem) {
  try {
    await ElMessageBox.confirm(
      `停止「${unit.unitName}」当前 Agent 执行？已生成的中间结果会保留，之后可重新执行。`,
      '停止执行',
      { type: 'warning', confirmButtonText: '停止', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  actionLoading.value = true
  try {
    await stopPipelineUnit(unit.id)
    ElMessage.success('已停止：' + unit.unitName)
    await load()
    if (unit.sessionId) await loadSession(unit.sessionId, true)
  } catch (e: any) {
    ElMessage.error(e?.message || '停止失败')
  } finally {
    actionLoading.value = false
  }
}

async function doPublish(unit: PipelineUnitItem) {
  actionLoading.value = true
  try {
    await publishPipelineUnit(unit.id)
    ElMessage.success('已发布到 Wiki：' + metaOf(unit).writeLabel)
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message || '发布失败')
  } finally {
    actionLoading.value = false
  }
}

function parseJsonArray(raw?: string | null): any[] {
  if (!raw) return []
  try {
    const v = JSON.parse(raw)
    return Array.isArray(v) ? v : []
  } catch {
    return []
  }
}

async function toggleConfirm(unit: PipelineUnitItem) {
  if (expandedConfirmUnitId.value === unit.id) {
    expandedConfirmUnitId.value = null
    confirmRound.value = null
    return
  }
  actionLoading.value = true
  try {
    const round = await getPipelineUnitConfirm(unit.id)
    if (!round) {
      ElMessage.warning('当前没有待确认内容')
      return
    }
    confirmRound.value = round
    confirmTaskType.value = unit.taskTypeCode || ''
    findingRows.value = parseJsonArray(round.findingsJson).map((f: any, i: number) => {
      // Agent 有时吐 string[]，不是 {id,title}[]
      const asText = typeof f === 'string' ? f.trim() : ''
      const obj = f && typeof f === 'object' ? f : null
      const severity = obj?.severity != null ? String(obj.severity) : undefined
      const lowDefault = isCodeReviewConfirm() && isLowSeverity(severity)
      const title = asText
        || String(obj?.title ?? obj?.issue ?? obj?.summary ?? obj?.content ?? obj?.description ?? `发现项 ${i + 1}`)
      return {
        id: String(obj?.id ?? i + 1),
        title,
        severity,
        suggestions: obj?.suggestions
          ? Array.isArray(obj.suggestions)
            ? obj.suggestions.join('；')
            : String(obj.suggestions)
          : obj?.conclusion
            ? String(obj.conclusion)
            : obj?.suggestion
              ? String(obj.suggestion)
              : obj?.impact
                ? String(obj.impact)
                : undefined,
        recommend: obj?.recommend != null ? String(obj.recommend) : undefined,
        // 代码评审：LOW 默认「不修改」，避免 nit 循环
        agree: lowDefault ? false : null,
        comment: lowDefault ? 'LOW 默认不修改（可改为修改）' : '',
      }
    })
    questionRows.value = parseJsonArray(round.openQuestionsJson).map((q: any, i: number) => {
      const asText = typeof q === 'string' ? q.trim() : ''
      const obj = q && typeof q === 'object' ? q : null
      return {
        id: String(obj?.id ?? i + 1),
        question: asText || String(obj?.question ?? obj?.text ?? obj?.title ?? `问题 ${i + 1}`),
        answer: '',
      }
    })
    expandedConfirmUnitId.value = unit.id
    activeUnitId.value = unit.id
  } catch (e: any) {
    ElMessage.error(e?.message || '加载确认内容失败')
  } finally {
    actionLoading.value = false
  }
}

async function submitConfirm(unit: PipelineUnitItem) {
  const unanswered = findingRows.value.some((r) => r.agree == null)
  const emptyQ = questionRows.value.some((r) => !r.answer.trim())
  if (unanswered) {
    ElMessage.warning(isCodeReviewConfirm() ? '请对每个问题选择修改或不修改' : '请对每个发现项选择同意/不同意')
    return
  }
  if (isCodeReviewConfirm() && findingRows.value.some((r) => r.agree === false && !r.comment.trim())) {
    ElMessage.warning('选择不修改时必须填写原因')
    return
  }
  if (isIntTestConfirm() && findingRows.value.some((r) => r.agree === false && !r.comment.trim())) {
    ElMessage.warning('不同意建议时必须填写原因')
    return
  }
  if (isIntTestConfirm() && findingRows.value.length === 0) {
    ElMessage.warning('缺少失败分析建议，请重新执行集成测试')
    return
  }
  if (emptyQ) {
    ElMessage.warning('请填写全部开放问题')
    return
  }
  actionLoading.value = true
  try {
    await confirmPipelineUnit(unit.id, {
      findingAnswers: findingRows.value.map((r) => ({
        id: r.id,
        agree: r.agree,
        comment: r.comment,
      })),
      questionAnswers: questionRows.value.map((r) => ({
        id: r.id,
        answer: r.answer,
      })),
    })
    ElMessage.success('已提交确认，流水线继续')
    expandedConfirmUnitId.value = null
    confirmRound.value = null
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message || '确认失败')
  } finally {
    actionLoading.value = false
  }
}

async function ensureVersions() {
  if (!instance.value?.productId) return
  if (versions.value.length) return
  versions.value = (await listProductVersions(instance.value.productId)).filter(
    (v) => v.status !== 'INVALID',
  )
  if (!pmForm.releaseVersionId) pmForm.releaseVersionId = versions.value[0]?.id ?? null
}

async function submitPmSecond() {
  if (!instance.value) return
  await ensureVersions()
  if (!pmForm.releaseVersionId) {
    ElMessage.warning('请选择发布版本')
    return
  }
  actionLoading.value = true
  try {
    await pmSecondPipeline(instance.value.id, {
      action: pmForm.action,
      releaseVersionId: pmForm.releaseVersionId,
      enableArch: pmForm.enableArch,
      enableUi: pmForm.enableUi,
    })
    ElMessage.success(pmForm.action === 'PASS' ? '已通过并挂起' : '已通过并投入研发')
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message || '二审提交失败')
  } finally {
    actionLoading.value = false
  }
}

function goPmSecond() {
  const n = instance.value?.nodes?.find((x) => x.nodeCode === 'pm_second')
  if (n) selectNode(n)
}

function isRetriggerStatus(status: string) {
  return ['SUCCEEDED', 'WAITING_CONFIRM', 'WAITING_PUBLISH', 'FAILED'].includes(status)
}

function canTrigger(unit: PipelineUnitItem, node: PipelineNodeItem) {
  if (node.enabledFlag === false) return false
  if (unit.status === 'RUNNING') return false
  if (instance.value && ['DONE', 'CANCELLED'].includes(instance.value.status)) return false
  return [
    'WAITING_TRIGGER',
    'FAILED',
    'PENDING',
    'SUCCEEDED',
    'WAITING_CONFIRM',
    'WAITING_PUBLISH',
  ].includes(unit.status)
}

function triggerButtonLabel(unit: PipelineUnitItem) {
  return isRetriggerStatus(unit.status) ? '重新执行' : '执行 Agent'
}

function goBack() {
  if (isIssuePipe.value) {
    router.push({ name: 'workbench', query: { issueId: String(issueId.value) } })
    return
  }
  router.push({ name: 'workbench', query: { requirementId: String(requirementId.value) } })
}

async function openOutputDoc(unit: PipelineUnitItem) {
  if (unit.outputWikiNodeId) {
    try {
      const file = await openPipelineDoc(instance.value!.id, metaOf(unit).outputDocName)
      drawerTitle.value = metaOf(unit).writeLabel
      drawerSub.value = file.displayName
      drawerBody.value = file.content || ''
      drawerHtml.value = /\.html$/i.test(file.displayName)
      drawerOpen.value = true
      return
    } catch {
      /* fallback by name */
    }
  }
  await openDocByName(metaOf(unit).outputDocName, metaOf(unit).writeLabel)
}

watch(
  () => selectedNode.value?.nodeCode,
  async (code) => {
    if (code === 'pm_second') await ensureVersions()
  },
)

onMounted(async () => {
  await load()
  startPoll()
})
onUnmounted(stopPoll)
</script>

<template>
  <div class="wb" v-loading="loading">
    <header class="wb-top">
      <button type="button" class="btn btn-sm" @click="goBack">
        {{ isIssuePipe ? '← 返回问题' : '← 返回需求' }}
      </button>
      <div class="title">
        <template v-if="instance">
          <span class="code">{{ instance.issueNo || instance.reqNo }}</span>
          <span>{{ instance.requirementName || instance.definitionName || '问题流水' }}</span>
          <span class="tag" :class="tagClass(instance.status)">
            {{ statusText[instance.status] || instance.status }}
          </span>
        </template>
        <template v-else>{{ isIssuePipe ? '问题单自动化流水线' : '自动化流水线' }}</template>
      </div>
      <div class="metrics" v-if="instance">
        <span>定义 {{ instance.definitionName || instance.definitionCode || '—' }}</span>
      </div>
      <div class="actions">
        <button
          v-if="!instance"
          type="button"
          class="btn btn-sm btn-primary"
          :disabled="actionLoading"
          @click="doStart"
        >
          启动流水线
        </button>
        <template v-else>
          <button type="button" class="btn btn-sm" :disabled="actionLoading" @click="load()">
            刷新
          </button>
          <button
            v-if="!['DONE', 'CANCELLED'].includes(instance.status)"
            type="button"
            class="btn btn-sm btn-danger-plain"
            :disabled="actionLoading"
            @click="doCancel"
          >
            取消自动化
          </button>
        </template>
      </div>
    </header>

    <div v-if="instance" class="execution-overview">
      <div class="progress-copy"><span class="overview-label">流程进度</span><strong>{{ progress.completed }}<small> / {{ progress.total }} 个节点</small></strong></div>
      <div class="progress-track" role="progressbar" :aria-valuenow="progress.percent" aria-valuemin="0" aria-valuemax="100" aria-label="流程节点完成进度"><span :style="{ width: progress.percent + '%' }" /></div>
      <span class="overview-percent">{{ progress.percent }}%</span>
      <div class="overview-stat"><i class="activity-dot" />{{ progress.running }} 个任务运行中</div>
      <div class="overview-stat" :class="{ 'needs-attention': progress.waiting > 0 }">{{ progress.waiting }} 项待确认 / 发布</div>
    </div>
    <div v-if="instance" class="summary">
      <template v-for="(b, i) in summaryBits" :key="b.label">
        <span v-if="i > 0" class="sep">|</span>
        <span>{{ b.label }} <b>{{ b.value }}</b></span>
      </template>
    </div>

    <div v-if="!instance && !loading" class="empty-wrap">
      <p class="muted">尚未启动本需求的自动化流水线</p>
      <button type="button" class="btn btn-primary" :disabled="actionLoading" @click="doStart">
        启动流水线
      </button>
    </div>

    <div
      v-else-if="instance"
      class="wb-main"
      :class="{ 'ai-open': aiOpen && isAiNode }"
    >
      <section class="col col-flow">
        <div class="col-hd"><span>流程导航</span><span class="muted">{{ instance.nodes.length }} 个节点</span></div>
        <div class="col-bd">
          <button
            v-for="n in instance.nodes"
            :key="n.id"
            type="button"
            class="step"
            :class="{
              current: selectedNode?.id === n.id,
              off: n.enabledFlag === false,
            }"
            @click="selectNode(n)"
          >
            <span class="ico">{{ stepIcon(n.status) }}</span>
            <span class="body">
              <span class="name">{{ n.nodeName }}</span>
              <span class="meta">
                {{ statusText[n.status] || n.status }}
                <template v-if="n.optionalFlag"> · 可选</template>
              </span>
            </span>
          </button>
        </div>
      </section>

      <section class="col col-detail">
        <div class="col-hd">
          <span>节点详情</span>
          <span class="muted">{{ kindLabel[selectedNode?.kind || ''] || selectedNode?.kind || '' }}</span>
        </div>
        <div class="col-bd" v-if="selectedNode">
          <h3 class="detail-title">
            {{ selectedNode.nodeName }}
            <span class="tag" :class="tagClass(selectedNode.status)">
              {{ statusText[selectedNode.status] || selectedNode.status }}
            </span>
            <span v-if="selectedNode.optionalFlag" class="tag tag-info">可选</span>
          </h3>

          <div
            v-if="selectedNode.nodeCode === 'pm_second' && ['WAITING_HUMAN', 'PENDING', 'WAITING_TRIGGER'].includes(selectedNode.status)"
            class="gate"
          >
            <h4>产品经理二审</h4>
            <div class="note">确认是否投入研发；可勾选是否启用架构 / UI 设计。</div>
            <div class="pm2-form">
              <div>
                <label class="req">动作</label>
                <select v-model="pmForm.action">
                  <option value="PASS_AND_DEV">通过并投入研发</option>
                  <option value="PASS">仅通过（挂起）</option>
                </select>
              </div>
              <div>
                <label class="req">发布版本</label>
                <select v-model="pmForm.releaseVersionId">
                  <option :value="null" disabled>请选择</option>
                  <option v-for="v in versions" :key="v.id" :value="v.id">{{ v.version }}</option>
                </select>
              </div>
              <div>
                <label>启动架构设计</label>
                <label class="chk"><input v-model="pmForm.enableArch" type="checkbox" /> 启用</label>
              </div>
              <div>
                <label>启动 UI 设计</label>
                <label class="chk"><input v-model="pmForm.enableUi" type="checkbox" /> 启用</label>
              </div>
            </div>
            <div class="gate-actions">
              <span class="grow" />
              <button type="button" class="btn btn-primary" :disabled="actionLoading" @click="submitPmSecond">
                提交二审
              </button>
            </div>
          </div>

          <div
            v-if="selectedTrackMerge && selectedTrackMerge.status === 'WAITING_HUMAN'"
            class="gate"
          >
            <h4>{{ selectedTrackMerge.label || selectedTrackMerge.trackCode }} · 本轨合并确认</h4>
            <div class="note">
              本轨各岗评审书已齐；相似问题已归并。勾选「有价值」后生成
              <b>{{ selectedTrackMerge.mergeFileName || '合并评审.md' }}</b>
              ，并作为文档修订 Agent 的输入。
            </div>
            <div v-if="!trackMergeDraft.length" class="muted" style="margin: 8px 0">无条目（可直接确认并跳过修订）</div>
            <div
              v-for="it in trackMergeDraft"
              :key="it.id"
              class="rv-card"
              :class="{
                'done-agree': it.valuable === true,
                'done-disagree': it.valuable === false,
              }"
            >
              <div class="rv-hd">
                <span class="id">{{ it.id }}</span>
                <span class="desc">{{ it.title }}</span>
                <span v-if="it.target" class="tag tag-info">{{ it.target }}</span>
              </div>
              <div class="rv-meta">
                <template v-if="it.severity">严重级别：{{ it.severity }} · </template>
                评审人：{{ (it.reviewers || []).join('、') || '—' }}
                <template v-if="it.suggestion"> · {{ it.suggestion }}</template>
              </div>
              <div class="rv-actions">
                <button
                  type="button"
                  class="btn btn-sm"
                  :class="{ 'chip-on': it.valuable === true }"
                  @click="setMergeValuable(it.id, true)"
                >
                  有价值
                </button>
                <button
                  type="button"
                  class="btn btn-sm"
                  :class="{ 'chip-on': it.valuable === false }"
                  @click="setMergeValuable(it.id, false)"
                >
                  无价值
                </button>
              </div>
            </div>
            <div class="gate-actions">
              <span class="grow" />
              <button
                type="button"
                class="btn btn-primary"
                :disabled="actionLoading"
                @click="submitTrackMerge"
              >
                确认并生成 {{ selectedTrackMerge.mergeFileName || '合并评审' }}
              </button>
            </div>
          </div>

          <div
            v-else-if="selectedTrackMerge && selectedTrackMerge.status === 'SUCCEEDED'"
            class="gate"
          >
            <h4>{{ selectedTrackMerge.label || selectedTrackMerge.trackCode }} · 合并已完成</h4>
            <div class="note">
              已发布
              <a
                v-if="selectedTrackMerge.mergeFileName"
                class="doc-link"
                href="javascript:void(0)"
                @click="openDocByName(selectedTrackMerge.mergeFileName!)"
              >{{ selectedTrackMerge.mergeFileName }}</a>
              <span v-else>合并评审</span>
              ，修订 Agent 将以此为输入。
            </div>
          </div>

          <div class="detail-grid">
            <div>
              <label>节点编码</label>
              <span class="muted">{{ selectedNode.nodeCode }}</span>
            </div>
            <div>
              <label>单元数</label>
              {{ selectedNode.units?.length || 0 }}
            </div>
          </div>

          <div v-if="selectedNode.units?.length" class="task-units">
            <div v-if="flowNote" class="flow-note">
              <b>{{ flowNote.title }}</b><br />
              <span v-html="flowNote.html" />
            </div>

            <div class="lab">任务单元</div>

            <div
              v-for="u in selectedNode.units"
              :key="u.id"
              class="tu"
              :class="{ active: activeUnitId === u.id }"
            >
              <div class="tu-hd">
                <span class="name">{{ u.unitName }}</span>
                <span class="tag" :class="tagClass(u.status)">
                  {{ statusText[u.status] || u.status }}
                </span>
                <span class="tag tag-info">{{ roleLabel(u.positionCode) }}</span>
              </div>

              <div class="tu-flow">
                <template v-for="(s, idx) in UNIT_FLOW_STEPS" :key="s.key + idx">
                  <span v-if="idx > 0" class="tu-flow-line" :class="flowStepClass(u, idx)" />
                  <span class="tu-flow-step" :class="flowStepClass(u, idx)">{{ s.label }}</span>
                </template>
              </div>

              <div class="tu-meta">
                Agent：{{ metaOf(u).agent }}
                <br />
                输出模板：
                <button type="button" class="doc-link" @click="openTemplateDoc(u)">
                  {{ metaOf(u).template }}
                </button>
                <br />
                输入：
                <template v-if="metaOf(u).inputs.length">
                  <template v-for="(inp, i) in metaOf(u).inputs" :key="inp">
                    <span v-if="i">、</span>
                    <button type="button" class="doc-link" @click="openInputDoc(inp)">{{ inp }}</button>
                  </template>
                </template>
                <template v-else>—</template>
                <br />
                产出：
                <button
                  v-if="canOpenOutput(u)"
                  type="button"
                  class="doc-link"
                  @click="openOutputDoc(u)"
                >
                  {{ metaOf(u).writeLabel }}
                </button>
                <span v-else class="muted">{{ metaOf(u).writeLabel }}（待归档）</span>
                <template v-if="(u.confirmRound || 0) > 0">
                  <br />
                  确认归档：
                  <button
                    v-if="canOpenConfirm(u)"
                    type="button"
                    class="doc-link"
                    @click="openConfirmDoc(u)"
                  >
                    {{ metaOf(u).confirmDocPattern.replace(/-r\{n\}/i, '').replace('{n}', '') }}
                  </button>
                  <span v-else class="muted">
                    {{ metaOf(u).confirmDocPattern.replace(/-r\{n\}/i, '').replace('{n}', '') }}（待完成输出后显示）
                  </span>
                </template>
                <br />
                上下文：
                <span
                  v-if="unitContextPaths(u).length"
                  class="ctx-paths"
                  :title="unitContextPaths(u).join('\n')"
                >
                  {{ unitContextPaths(u)[0] }}
                  <template v-if="unitContextPaths(u).length > 1"> …</template>
                </span>
                <span v-else class="muted">—</span>
                <br />
                仓库：{{ repoLabel(u.repoKind) }}
                · 轮次 {{ u.confirmRound || 0 }}/{{ u.maxRound }}（{{ u.roundTier }}）
                <template v-if="u.errorMessage">
                  <br /><span class="err">{{ u.errorMessage }}</span>
                </template>
              </div>

              <div class="tu-actions">
                <button type="button" class="btn btn-sm" @click="focusUnit(u)">查看执行</button>
                <button
                  v-if="u.status === 'RUNNING'"
                  type="button"
                  class="btn btn-sm btn-danger-plain"
                  :disabled="actionLoading"
                  @click="doStop(u)"
                >
                  停止
                </button>
                <button
                  v-if="u.status === 'WAITING_CONFIRM'"
                  type="button"
                  class="btn btn-sm"
                  :class="{ 'chip-on': expandedConfirmUnitId === u.id }"
                  :disabled="actionLoading"
                  @click="toggleConfirm(u)"
                >
                  {{ expandedConfirmUnitId === u.id ? '收起确认' : '展开确认' }}
                </button>
                <button
                  v-if="u.status === 'WAITING_PUBLISH'"
                  type="button"
                  class="btn btn-primary btn-sm"
                  :disabled="actionLoading"
                  @click="doPublish(u)"
                >
                  发布文档
                </button>
                <button
                  v-if="canTrigger(u, selectedNode)"
                  type="button"
                  class="btn btn-sm"
                  :class="{ 'btn-primary': !isRetriggerStatus(u.status) }"
                  :disabled="actionLoading"
                  @click="doTrigger(u)"
                >
                  {{ triggerButtonLabel(u) }}
                </button>
                <span v-if="u.status === 'RUNNING'" class="muted">
                  生成中，正在写出《{{ metaOf(u).writeLabel }}》…
                </span>
                <span v-if="u.status === 'WAITING_PUBLISH'" class="muted">
                  已完成输出（个人空间），请发布到 Wiki
                </span>
                <span v-if="u.status === 'SUCCEEDED'" class="muted">本单元完成</span>
              </div>

              <div v-if="expandedConfirmUnitId === u.id" class="tu-confirm">
                <div class="note" style="margin-bottom: 8px">
                  <template v-if="isCodeReviewConfirm()">
                    每条选择修改/不修改（不修改须填原因）。LOW 默认不修改。仅 HIGH/MEDIUM
                    的修改会触发复评（只看修复 diff，且不再报新的 LOW）；纯 LOW 修改后直接放行。达轮次上限将放行而非失败。
                  </template>
                  <template v-else-if="isIntTestConfirm()">
                    平台已跑存量测试失败；下方为 LLM 失败原因与处理建议。请对每条选择同意或不同意（不同意须填原因）。提交后按同意项自动修复并由平台复测。
                  </template>
                  <template v-else>
                    一类 findings 同意/不同意；二类 openQuestions 必答。提交后继续写出《{{ metaOf(u).writeLabel }}》。
                  </template>
                </div>

                <h5><span class="tag tag-warn">一类</span> findings</h5>
                <div v-if="!findingRows.length" class="muted" style="margin-bottom: 8px">无发现项</div>
                <div
                  v-for="row in findingRows"
                  :key="row.id"
                  class="rv-card"
                  :class="{
                    'done-agree': row.agree === true,
                    'done-disagree': row.agree === false,
                  }"
                >
                  <div class="rv-hd">
                    <span class="id">#{{ row.id }}</span>
                    <span
                      v-if="row.severity"
                      class="tag"
                      :class="{
                        'tag-warn': String(row.severity).toUpperCase() === 'MEDIUM',
                        'tag-info': isLowSeverity(row.severity),
                      }"
                      style="margin-right: 6px"
                    >{{ row.severity }}</span>
                    <span class="desc">{{ row.title }}</span>
                  </div>
                  <div v-if="row.suggestions || row.recommend" class="rv-meta">
                    <template v-if="row.suggestions">建议：{{ row.suggestions }}</template>
                    <template v-if="row.recommend">
                      · <span class="rec">推荐 {{ row.recommend }}</span>
                    </template>
                  </div>
                  <div class="rv-actions">
                    <button
                      type="button"
                      class="btn btn-sm"
                      :class="{ 'chip-on': row.agree === true }"
                      @click="row.agree = true; if (!row.comment) row.comment = ''"
                    >
                      {{ isCodeReviewConfirm() ? '修改' : isIntTestConfirm() ? '同意建议' : '同意' }}
                    </button>
                    <button
                      type="button"
                      class="btn btn-sm"
                      :class="{ 'chip-on': row.agree === false }"
                      @click="
                        row.agree = false;
                        if (isCodeReviewConfirm() && isLowSeverity(row.severity) && !row.comment.trim()) {
                          row.comment = 'LOW 默认不修改（可改为修改）'
                        }
                      "
                    >
                      {{ isCodeReviewConfirm() ? '不修改' : isIntTestConfirm() ? '不同意' : '不同意' }}
                    </button>
                  </div>
                  <div v-if="row.agree === false" class="rv-extra">
                    <label>{{ isCodeReviewConfirm() ? '不修改原因（必填）' : isIntTestConfirm() ? '不同意原因（必填）' : '不同意意见' }}</label>
                    <textarea v-model="row.comment" rows="2" placeholder="请填写原因" />
                  </div>
                </div>

                <h5><span class="tag tag-info">二类</span> openQuestions</h5>
                <div v-if="!questionRows.length" class="muted" style="margin-bottom: 8px">无开放问题</div>
                <div
                  v-for="row in questionRows"
                  :key="row.id"
                  class="rv-card"
                  :class="{ 'done-answered': !!row.answer.trim() }"
                >
                  <div class="rv-hd">
                    <span class="id">{{ row.id }}</span>
                    <span class="desc">{{ row.question }}</span>
                    <span class="tag tag-info">开放问题</span>
                  </div>
                  <div class="rv-extra">
                    <label>人类解答（必填）</label>
                    <textarea v-model="row.answer" rows="2" placeholder="解答后写入确认 JSON…" />
                  </div>
                </div>

                <div class="gate-actions">
                  <span class="grow" />
                  <button
                    type="button"
                    class="btn btn-primary btn-sm"
                    :disabled="actionLoading"
                    @click="submitConfirm(u)"
                  >
                    提交确认并继续
                  </button>
                </div>
              </div>
            </div>

            <!-- AI 初审完成后的汇总/进二审（对齐 Mock） -->
            <div
              v-if="selectedNode.nodeCode === 'ai_pre' && aiPreDone"
              class="tu"
              style="border-style: dashed"
            >
              <div class="tu-hd">
                <span class="name">进入产品经理二审</span>
                <span class="tag tag-ok">初审已齐</span>
              </div>
              <div class="tu-meta">两侧评审书齐后可进入二审（汇总可选，当前直接进门禁）。</div>
              <div class="tu-actions">
                <button type="button" class="btn btn-primary btn-sm" @click="goPmSecond">
                  进入产品经理二审
                </button>
              </div>
            </div>
          </div>
          <p v-else class="muted">本节点无任务单元（人工/系统节点）。</p>
        </div>
        <div v-else class="col-bd"><p class="muted">未选择节点</p></div>
      </section>

      <section v-show="aiOpen && isAiNode" class="col col-ai">
        <div class="col-hd">
          <span>执行日志</span>
          <span class="muted">
            {{ activeUnit?.unitName || '—' }}
            <template v-if="activeUnit?.sessionId"> · #{{ activeUnit.sessionId }}</template>
            <template v-if="isCodingUnit && codingProgress?.currentStage">
              · {{ codingStageLabel[codingProgress.currentStage] || codingProgress.currentStage }}
            </template>
          </span>
        </div>
        <div v-if="isCodingUnit && codingProgress" class="coding-stage-bar">
          <div class="coding-stage-row">
            <span
              v-for="s in ['CODING_PLAN', 'CODING_ANALYZE', 'CODING_TASK', 'CODING_INTEGRATE']"
              :key="s"
              class="coding-stage-chip"
              :class="{
                on: codingProgress.currentStage === s,
                done:
                  codingProgress.jobs?.some(
                    (j) => j.jobType === s && j.status === 'SUCCEEDED',
                  ) && codingProgress.currentStage !== s,
              }"
            >
              {{ codingStageLabel[s] || s }}
            </span>
          </div>
          <div v-if="codingProgress.planTasks?.length" class="coding-tasks">
            <button
              v-for="t in codingProgress.planTasks"
              :key="t.id || t.title"
              type="button"
              class="coding-task-chip"
              :class="{ done: t.status === 'DONE' }"
              :disabled="!t.sessionId"
              @click="openCodingSession(t.sessionId)"
            >
              {{ t.id || '?' }} {{ t.title || '' }}
              <span class="muted">{{ t.status === 'DONE' ? '✓' : '…' }}</span>
            </button>
          </div>
          <div v-if="codingProgress.sessions?.length" class="coding-sessions">
            <button
              v-for="s in codingProgress.sessions"
              :key="s.sessionId"
              type="button"
              class="doc-link"
              :class="{ on: s.sessionId === activeUnit?.sessionId }"
              @click="openCodingSession(s.sessionId)"
            >
              #{{ s.sessionId }} {{ s.title?.replace(/^流水线·/, '') || '' }}
            </button>
          </div>
          <p v-if="codingProgress.errorMessage" class="coding-err">{{ codingProgress.errorMessage }}</p>
        </div>
        <div class="chat-body" v-loading="sessionLoading">
          <div
            v-if="!activeUnit || activeUnit.status === 'WAITING_TRIGGER' || activeUnit.status === 'PENDING'"
            class="sess-empty"
          >
            选择任务单元并点「执行 Agent」后显示消息流
          </div>
          <template v-else>
            <div v-if="!executionTimeline.length" class="sess-empty">
              <template v-if="activeUnit?.status === 'RUNNING'">Agent 执行中…</template>
              <template v-else>暂无消息（会话可能仍在创建）</template>
            </div>
            <template v-for="item in executionTimeline" :key="item.key">
              <div
                v-if="item.kind === 'steps'"
                class="step-log"
                :class="{
                  live: item.live,
                  expanded: isStepRunExpanded(item.run, item.live),
                }"
              >
                <button type="button" class="step-log-hd" @click="toggleStepRun(item.run.key)">
                  <el-icon
                    class="step-log-chevron"
                    :class="{ open: isStepRunExpanded(item.run, item.live) }"
                  >
                    <ArrowRight />
                  </el-icon>
                  <span>{{ stepRunSummaryLabel(item.run, item.live) }}</span>
                </button>
                <div v-show="isStepRunExpanded(item.run, item.live)" class="step-log-body">
                  <div
                    v-for="(s, si) in item.run.steps"
                    :key="s.eventId || si"
                    class="step-item"
                  >
                    <span class="step-dot" />
                    <span class="step-msg">{{ s.message || s.step || '处理中' }}</span>
                  </div>
                  <div v-if="!item.run.steps.length" class="step-item muted">暂无明细…</div>
                </div>
              </div>
              <div v-else class="bubble" :class="item.msg.role">
                <template v-if="item.msg.role === 'assistant'">
                  <span v-if="item.placeholder" class="muted">{{ item.placeholder }}</span>
                  <MarkdownBody
                    v-else-if="displayAssistantContent(item.msg.content)"
                    :content="displayAssistantContent(item.msg.content)"
                  />
                  <span
                    v-else-if="item.msg.status === 'streaming' || activeUnit?.status === 'RUNNING'"
                    class="typing"
                  >
                    正在回复…
                  </span>
                  <span v-else class="muted">（无正文）</span>
                </template>
                <span v-else>{{ displayUserContent(item.msg.content) }}</span>
              </div>
            </template>
          </template>
        </div>
      </section>
    </div>

    <div class="mask" :class="{ show: drawerOpen }" @click="drawerOpen = false" />
    <aside class="drawer" :class="{ open: drawerOpen }">
      <div class="drawer-hd">
        <div>
          <strong>{{ drawerTitle }}</strong>
          <div class="muted" style="margin-top: 4px">{{ drawerSub }}</div>
        </div>
        <button type="button" class="btn btn-sm" @click="drawerOpen = false">关闭</button>
      </div>
      <div class="drawer-bd">
        <div v-if="drawerHtml" class="md-preview" v-html="drawerBody" />
        <MarkdownBody v-else-if="drawerBody" :content="drawerBody" />
      </div>
    </aside>
  </div>
</template>

<style scoped>
.wb {
  --dap-warning-bg: #fef3e2;
  --dap-success-bg: #e8f7ee;

  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--dap-gray-50);
  color: var(--dap-gray-900);
  font-size: 14px;
}

.wb-top {
  height: 48px;
  background: var(--dap-gray-0);
  border-bottom: 1px solid var(--dap-gray-200);
  display: flex;
  align-items: center;
  padding: 0 16px;
  gap: 12px;
  flex-shrink: 0;
}
.wb-top .title {
  font-weight: 600;
  font-size: 15px;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.wb-top .title .code {
  color: var(--dap-gray-500);
  font-weight: 400;
  font-size: 13px;
}
.wb-top .metrics {
  margin-left: auto;
  font-size: 12px;
  color: var(--dap-gray-700);
}
.wb-top .actions {
  display: flex;
  gap: 8px;
}

.summary {
  background: var(--dap-gray-0);
  border-bottom: 1px solid var(--dap-gray-200);
  padding: 10px 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  font-size: 13px;
  color: var(--dap-gray-700);
  flex-shrink: 0;
}
.summary b {
  color: var(--dap-gray-900);
  font-weight: 500;
}
.summary .sep {
  color: var(--dap-gray-300);
}

.wb-main {
  flex: 1;
  display: grid;
  grid-template-columns: 220px 1fr;
  min-height: 0;
  transition: grid-template-columns 0.15s ease;
}
.wb-main.ai-open {
  grid-template-columns: 220px 1fr 340px;
}

.col {
  background: var(--dap-gray-0);
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
}
.col-flow,
.col-detail {
  border-right: 1px solid var(--dap-gray-200);
}
.col-hd {
  height: 40px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--dap-gray-200);
  font-weight: 500;
  font-size: 13px;
  flex-shrink: 0;
}
.col-bd {
  flex: 1;
  overflow: auto;
  padding: 8px 0;
  min-height: 0;
}
.col-detail .col-bd {
  padding: 14px 16px;
}

.tag {
  display: inline-block;
  font-size: 12px;
  padding: 0 6px;
  border-radius: var(--dap-radius-sm);
  line-height: 20px;
  font-weight: 400;
  vertical-align: middle;
}
.tag-info {
  background: var(--dap-gray-100);
  color: var(--dap-gray-700);
}
.tag-warn {
  background: var(--dap-warning-bg);
  color: var(--dap-warning);
}
.tag-ok {
  background: var(--dap-success-bg);
  color: var(--dap-success);
}
.tag-run {
  background: var(--dap-primary-light);
  color: var(--dap-primary);
}
.tag-bad {
  background: #fee2e2;
  color: var(--dap-danger);
}

.btn {
  border: 1px solid var(--dap-gray-200);
  background: var(--dap-gray-0);
  color: var(--dap-gray-900);
  border-radius: var(--dap-radius-sm);
  padding: 5px 12px;
  font-size: 13px;
  cursor: pointer;
  font-family: inherit;
}
.btn:hover:not(:disabled) {
  border-color: var(--dap-primary);
  color: var(--dap-primary);
}
.btn-primary {
  background: var(--dap-primary);
  border-color: var(--dap-primary);
  color: #fff;
}
.btn-primary:hover:not(:disabled) {
  background: var(--dap-primary-hover);
  color: #fff;
}
.btn-danger-plain {
  color: var(--dap-danger);
  border-color: #f5c2c2;
}
.btn-sm {
  padding: 3px 10px;
  font-size: 12px;
}
.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.chip-on {
  background: var(--dap-primary-light);
  border-color: var(--dap-primary);
  color: var(--dap-primary);
}

.doc-link {
  color: var(--dap-primary);
  text-decoration: none;
  border: none;
  background: none;
  padding: 0;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.doc-link:hover {
  text-decoration: underline;
}
.doc-sep {
  color: var(--dap-gray-500);
  margin: 0 2px;
  user-select: none;
}
.link-btn {
  border: 1px solid var(--dap-gray-200);
  background: var(--dap-gray-50);
  color: var(--dap-primary);
  border-radius: var(--dap-radius-sm);
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
}
.link-btn:hover {
  border-color: var(--dap-primary);
  background: var(--dap-primary-light);
}

.step {
  width: 100%;
  text-align: left;
  border: none;
  background: transparent;
  padding: 8px 12px;
  cursor: pointer;
  font-size: 13px;
  display: flex;
  gap: 8px;
  align-items: flex-start;
  color: var(--dap-gray-700);
  border-left: 2px solid transparent;
  font-family: inherit;
}
.step:hover {
  background: var(--dap-gray-50);
}
.step.current {
  background: var(--dap-primary-light);
  color: var(--dap-primary);
  font-weight: 500;
  border-left-color: var(--dap-primary);
}
.step.off {
  opacity: 0.45;
}
.step .ico {
  width: 14px;
  flex-shrink: 0;
  text-align: center;
  line-height: 1.4;
}
.step .body {
  flex: 1;
  min-width: 0;
}
.step .name {
  display: block;
  line-height: 1.35;
}
.step .meta {
  display: block;
  font-size: 11px;
  color: var(--dap-gray-500);
  margin-top: 2px;
  font-weight: 400;
}
.step.current .meta {
  color: #6b8af0;
}

.detail-title {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 16px;
  margin-bottom: 14px;
  font-size: 13px;
}
.detail-grid label {
  display: block;
  font-size: 12px;
  color: var(--dap-gray-500);
  margin-bottom: 2px;
}

.muted {
  color: var(--dap-gray-500);
  font-size: 12px;
}
.err {
  color: var(--dap-danger);
}
.empty-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  background: var(--dap-gray-0);
}

.gate {
  border: 1px solid var(--dap-gray-200);
  border-radius: var(--dap-radius-md);
  padding: 12px;
  background: var(--dap-gray-50);
  margin-bottom: 14px;
}
.gate h4 {
  margin: 0 0 6px;
  font-size: 13px;
}
.gate .note,
.tu-confirm .note {
  font-size: 12px;
  color: var(--dap-gray-500);
  margin-bottom: 10px;
  line-height: 1.55;
}
.pm2-form {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 14px;
  margin: 12px 0;
}
.pm2-form label {
  display: block;
  font-size: 12px;
  color: var(--dap-gray-500);
  margin-bottom: 4px;
}
.pm2-form .req::after {
  content: ' *';
  color: var(--dap-danger);
}
.pm2-form select {
  width: 100%;
  border: 1px solid var(--dap-gray-200);
  border-radius: var(--dap-radius-sm);
  padding: 7px 10px;
  font-family: inherit;
  font-size: 13px;
  background: var(--dap-gray-0);
}
.pm2-form .chk {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--dap-gray-700);
  font-size: 13px;
  cursor: pointer;
  margin: 0;
}
.gate-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
  margin-top: 12px;
}
.gate-actions .grow {
  flex: 1;
}

.flow-note {
  font-size: 12px;
  color: var(--dap-gray-700);
  line-height: 1.55;
  padding: 8px 10px;
  background: var(--dap-gray-50);
  border-radius: var(--dap-radius-sm);
  margin-bottom: 10px;
}
.task-units > .lab {
  font-size: 12px;
  color: var(--dap-gray-500);
  margin-bottom: 8px;
}
.tu {
  border: 1px solid var(--dap-gray-200);
  border-radius: var(--dap-radius-md);
  padding: 10px 12px;
  margin-bottom: 8px;
  background: var(--dap-gray-0);
}
.tu.active {
  outline: 1px solid var(--dap-primary);
}
.tu-hd {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}
.tu-hd .name {
  font-weight: 600;
  font-size: 13px;
  flex: 1;
}
.tu-flow {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0;
  margin: 4px 0 10px;
  padding: 8px 10px;
  background: var(--dap-gray-50);
  border-radius: var(--dap-radius-sm);
}
.tu-flow-step {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  color: var(--dap-gray-500);
  background: transparent;
  border: 1px solid transparent;
  white-space: nowrap;
}
.tu-flow-step.done {
  color: var(--dap-success);
  background: var(--dap-success-bg);
}
.tu-flow-step.current {
  color: var(--dap-primary);
  background: var(--dap-primary-light);
  border-color: #bfd0fb;
  font-weight: 600;
}
.tu-flow-step.failed {
  color: var(--dap-danger);
  background: #fee2e2;
  font-weight: 600;
}
.tu-flow-step.idle {
  color: var(--dap-gray-500);
}
.tu-flow-line {
  width: 16px;
  height: 2px;
  background: var(--dap-gray-200);
  flex-shrink: 0;
}
.tu-flow-line.done,
.tu-flow-line.current {
  background: #93c5fd;
}
.tu-flow-line.failed {
  background: #fca5a5;
}
.tu-meta {
  font-size: 12px;
  color: var(--dap-gray-700);
  line-height: 1.55;
  margin-bottom: 8px;
}
.ctx-paths {
  color: var(--dap-gray-900);
  cursor: default;
  word-break: break-all;
}
.tu-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
.tu-confirm {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--dap-gray-200);
}
.tu-confirm h5 {
  margin: 14px 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--dap-gray-700);
  display: flex;
  align-items: center;
  gap: 8px;
}

.rv-card {
  border: 1px solid var(--dap-gray-200);
  border-radius: var(--dap-radius-md);
  padding: 10px 12px;
  margin-bottom: 8px;
  background: var(--dap-gray-0);
}
.rv-card.done-agree {
  border-color: #86efac;
  background: #f7fdf9;
}
.rv-card.done-disagree {
  border-color: #fca5a5;
  background: #fffafa;
}
.rv-card.done-answered {
  border-color: #93c5fd;
  background: #f8fbff;
}
.rv-hd {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 6px;
}
.rv-hd .id {
  font-weight: 600;
  font-size: 12px;
  color: var(--dap-primary);
  flex-shrink: 0;
}
.rv-hd .desc {
  flex: 1;
  font-size: 13px;
  line-height: 1.45;
}
.rv-meta {
  font-size: 12px;
  color: var(--dap-gray-700);
  line-height: 1.55;
  margin-bottom: 8px;
}
.rv-meta .rec {
  color: var(--dap-warning);
  font-weight: 500;
}
.rv-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.rv-extra {
  margin-top: 8px;
}
.rv-extra label {
  display: block;
  font-size: 12px;
  color: var(--dap-gray-500);
  margin-bottom: 4px;
}
.rv-extra textarea {
  width: 100%;
  border: 1px solid var(--dap-gray-200);
  border-radius: var(--dap-radius-sm);
  padding: 8px 10px;
  font-family: inherit;
  font-size: 13px;
  resize: vertical;
  min-height: 56px;
  box-sizing: border-box;
}
.rv-extra textarea:focus {
  outline: none;
  border-color: var(--dap-primary);
}

.chat-body {
  flex: 1;
  overflow: auto;
  padding: 12px;
  background: var(--dap-gray-50);
  min-height: 0;
}
.chat-body .bubble {
  display: block;
  width: fit-content;
  max-width: min(560px, 92%);
  margin-bottom: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--dap-gray-0);
  box-sizing: border-box;
  overflow-wrap: anywhere;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.5;
}
.chat-body .bubble.user {
  margin-left: auto;
  background: var(--dap-primary-light);
  white-space: pre-wrap;
  color: var(--dap-gray-900);
}
.chat-body .bubble.assistant {
  margin-right: auto;
  border: 1px solid var(--dap-gray-200);
}
.chat-body .typing {
  color: var(--dap-gray-500);
  font-size: 13px;
}
.step-log {
  width: fit-content;
  max-width: min(560px, 92%);
  margin: 8px 0 12px;
  padding: 6px 10px;
  border-radius: 8px;
  border: 1px dashed var(--dap-gray-200);
  background: rgba(15, 23, 42, 0.02);
}
.step-log.live {
  border-color: rgba(43, 90, 237, 0.35);
  background: rgba(43, 90, 237, 0.04);
}
.coding-stage-bar {
  padding: 8px 12px;
  border-bottom: 1px solid var(--dap-gray-200);
  background: var(--dap-gray-50, #f7f8fa);
  font-size: 12px;
}
.coding-stage-row,
.coding-tasks,
.coding-sessions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 6px;
}
.coding-stage-chip,
.coding-task-chip {
  border: 1px solid var(--dap-gray-200);
  background: #fff;
  border-radius: 999px;
  padding: 2px 10px;
  font-size: 12px;
  color: var(--dap-gray-700);
}
.coding-stage-chip.on,
.coding-task-chip:not(:disabled).on,
.coding-sessions .doc-link.on {
  border-color: var(--dap-primary);
  color: var(--dap-primary);
  font-weight: 600;
}
.coding-stage-chip.done,
.coding-task-chip.done {
  opacity: 0.75;
}
.coding-task-chip:disabled {
  opacity: 0.55;
  cursor: default;
}
.coding-err {
  margin: 0;
  color: var(--dap-danger, #c0392b);
  font-size: 12px;
}
.step-log-hd {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  color: var(--dap-gray-700);
  line-height: 1.4;
  font-family: inherit;
}
.step-log-hd:hover {
  color: var(--dap-primary);
}
.step-log-chevron {
  font-size: 12px;
  transition: transform 0.15s ease;
}
.step-log-chevron.open {
  transform: rotate(90deg);
}
.step-log-body {
  margin-top: 6px;
  padding-top: 4px;
  border-top: 1px dashed rgba(15, 23, 42, 0.08);
}
.step-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 3px 0;
  font-size: 12px;
  color: var(--dap-gray-700);
  line-height: 1.45;
}
.step-dot {
  width: 6px;
  height: 6px;
  margin-top: 5px;
  border-radius: 50%;
  background: var(--dap-primary);
  flex-shrink: 0;
}
.step-msg {
  word-break: break-word;
}
.sess-empty {
  text-align: center;
  color: var(--dap-gray-500);
  font-size: 13px;
  padding: 32px 12px;
}
.drawer-bd .md-preview {
  font-size: 13px;
  line-height: 1.6;
}

.mask {
  position: fixed;
  inset: 0;
  background: rgba(31, 35, 41, 0.35);
  display: none;
  z-index: 40;
}
.mask.show {
  display: block;
}
.drawer {
  position: fixed;
  top: 0;
  right: 0;
  width: min(480px, 100%);
  height: 100%;
  background: var(--dap-gray-0);
  box-shadow: -4px 0 24px rgba(0, 0, 0, 0.08);
  z-index: 50;
  transform: translateX(100%);
  transition: transform 0.2s ease;
  display: flex;
  flex-direction: column;
}
.drawer.open {
  transform: translateX(0);
}
.drawer-hd {
  height: auto;
  min-height: 52px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--dap-gray-200);
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.drawer-bd {
  flex: 1;
  overflow: auto;
  padding: 16px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 1100px) {
  .wb-main,
  .wb-main.ai-open {
    grid-template-columns: 1fr;
    grid-template-rows: 160px 1fr auto;
  }
  .col-flow {
    border-right: none;
    border-bottom: 1px solid var(--dap-gray-200);
    max-height: 160px;
  }
  .col-ai {
    max-height: 240px;
    border-top: 1px solid var(--dap-gray-200);
  }
}
/* 工作台视觉层级 */
.wb-top { min-height:76px; height:auto; padding:18px 24px; flex-wrap:wrap; }
.wb-top .title { font-size:18px; gap:12px; flex:1; overflow-wrap:anywhere; }
.wb-top .title .code { font-family:Consolas,monospace; font-size:11px; padding:4px 7px; background:var(--dap-gray-100); border-radius:4px; }
.wb-top .metrics { display:none; }
.execution-overview { display:flex; align-items:center; flex-wrap:wrap; gap:18px; padding:18px 24px; background:white; border-bottom:1px solid var(--dap-gray-200); }
.progress-copy { display:flex; align-items:center; gap:14px; }
.overview-label { color:var(--dap-gray-500); font-size:12px; }
.progress-copy strong { color:var(--dap-gray-900); font-size:18px; font-weight:600; }
.progress-copy small { font-size:11px; color:var(--dap-gray-500); font-weight:400; }
.progress-track { height:5px; width:clamp(60px,12vw,180px); border-radius:4px; background:var(--dap-gray-100); overflow:hidden; }
.progress-track > span { display:block; height:100%; background:var(--dap-primary); border-radius:4px; transition:width .25s; }
.overview-percent { color:var(--dap-gray-500); font-size:11px; font-family:Consolas,monospace; }
.overview-stat { font-size:12px; color:var(--dap-gray-500); display:flex; align-items:center; gap:7px; }
.activity-dot { width:6px; height:6px; border-radius:50%; background:var(--dap-primary); }
.needs-attention { color:var(--dap-warning); }
.summary { padding:10px 24px; background:var(--dap-gray-50); font-size:11px; }
.wb-main { grid-template-columns:224px minmax(0,1fr); }
.wb-main.ai-open { grid-template-columns:210px minmax(0,1fr) 320px; }
.col-flow { background:#fafbfd; }
.col-hd { min-height:46px; padding:0 18px; font-size:12px; color:var(--dap-gray-700); }
.col-flow .col-bd { padding:12px 10px; }
.step { border-left:0; border-radius:6px; padding:11px 10px; margin-bottom:4px; gap:10px; }
.step.current { box-shadow:inset 2px 0 var(--dap-primary); }
.step .ico { border:1px solid var(--dap-gray-200); border-radius:50%; width:20px; height:20px; font-size:10px; line-height:18px; background:white; }
.step.current .ico { color:white; background:var(--dap-primary); border-color:var(--dap-primary); }
.step .name { font-size:12px; line-height:20px; }
.step .meta { font-size:11px; margin-top:3px; }
.col-detail .col-bd { padding:24px; }
.detail-title { font-size:20px; margin-bottom:24px; gap:10px; }
.tag { border-radius:4px; font-size:11px; }
.btn { min-height:30px; border-radius:6px; transition:background .15s,border-color .15s; }
.btn:focus-visible,.step:focus-visible { outline:2px solid var(--dap-primary); outline-offset:2px; }
.gate { border-radius:8px; }
@media(max-width:1250px) and (min-width:1101px) { .wb-main.ai-open { grid-template-columns:180px minmax(0,1fr) 260px; } }
@media(max-width:1100px) { .wb-main,.wb-main.ai-open { grid-template-columns:minmax(0,1fr); grid-template-rows:130px minmax(0,1fr) auto; } .col-flow { max-height:130px; } .col-flow .col-bd { display:flex; gap:6px; overflow:auto; padding:8px; } .step { min-width:150px; width:150px; margin:0; align-self:stretch; } .col-flow .col-hd { min-height:34px; height:34px; } .col-detail .col-bd { padding:20px; } }
@media(max-width:680px) { .wb-top { padding:14px; gap:10px; } .wb-top .title { font-size:15px; flex-wrap:wrap; } .execution-overview { gap:12px; padding:14px; } .overview-stat { font-size:11px; } .summary { padding-inline:14px; } .detail-title { font-size:17px; } .col-detail .col-bd { padding:14px; } }
</style>

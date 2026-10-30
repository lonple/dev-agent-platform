<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type TabPaneName } from 'element-plus'
import {
  ArrowRight,
  ChatDotRound,
  Close,
  Document,
  Folder,
  List,
  MoreFilled,
  Plus,
  Refresh,
} from '@element-plus/icons-vue'
import {
  listMessages,
  listSessionSteps,
  streamCompletion,
  stopGeneration,
  type ChatMessage,
} from '../../api/chat'
import { listRequirementWorkbench, type RequirementItem } from '../../api/requirement'
import { listPositions, type PositionItem } from '../../api/position'
import {
  createWorkbenchWorkspace,
  createWorkbenchWorkspaceDir,
  createWorkbenchWorkspaceFile,
  createWorkbenchWorkspaceSession,
  deleteWorkbenchPersonalWorkspace,
  deleteWorkbenchWorkspaceNode,
  deleteWorkbenchWorkspaceSession,
  getWorkbenchWorkspaceFileContent,
  getWorkbenchWorkspaceFileDiff,
  listSessionTaskTypes,
  listWorkbenchPersonalWorkspaces,
  listWorkbenchWorkspaceFileRevisions,
  listWorkbenchWorkspaceSessions,
  listWorkbenchWorkspaceTree,
  updateWorkbenchWorkspaceFileContent,
  updateWorkbenchWorkspaceSession,
  type SessionTaskTypeOption,
  type TenantWorkspaceItem,
  type WorkspaceFileDiff,
  type WorkspaceFileRevisionItem,
  type WorkspaceNodeItem,
  type WorkspaceSessionItem,
  type WorkspaceSessionType,
} from '../../api/tenantWorkspace'
import {
  listWikiTree,
  publishWiki,
  type WikiNodeItem,
} from '../../api/wiki'
import MarkdownBody from '../../components/MarkdownBody.vue'
import HtmlPreview from '../../components/HtmlPreview.vue'
import { isHtmlPreview, isMdFile } from '../../utils/fileKind'
import DiffSideBySide from '../../components/DiffSideBySide.vue'

const personalWorkspaces = ref<TenantWorkspaceItem[]>([])
const activeWorkspaceId = ref<number | null>(null)
const activeSessionId = ref<number | null>(null)
const sessionsByWs = ref<Record<number, WorkspaceSessionItem[]>>({})
const loadingList = ref(false)

const fileTree = ref<WorkspaceNodeItem[]>([])
const loadingTree = ref(false)
const selectedDirId = ref<number | null>(null)
const loadingFile = ref(false)
const savingFile = ref(false)

/** 中间顶栏：会话 Tab 与文档 Tab 同级 */
type CenterSessionTab = {
  kind: 'session'
  key: string
  workspaceId: number
  sessionId: number
  title: string
  sessionType: WorkspaceSessionType
  outputDirLabel?: string | null
  outputTemplatePath?: string | null
  standardsPath?: string | null
}
type CenterDocTab = {
  kind: 'doc'
  key: string
  workspaceId: number
  nodeId: number
  displayName: string
  content: string
  dirty: boolean
  /** 默认浏览；点编辑后可改 */
  mode: 'preview' | 'edit'
}
type CenterTab = CenterSessionTab | CenterDocTab

type StepLog = {
  id: number
  step: string
  message: string
  at: number
}
type SessionRuntime = {
  messages: ChatMessage[]
  inputText: string
  streaming: boolean
  currentRunId: string | null
  abortCtrl: AbortController | null
  messagesLoaded: boolean
  /** 本轮/回放步骤日志（流式 SSE + ES 简要回放） */
  steps: StepLog[]
  /** 步骤面板是否展开（默认折叠） */
  stepsExpanded: boolean
  stepsStartedAt: number | null
  /** 本轮结束后的耗时（秒）；进行中为 null */
  stepsDurationSec: number | null
}

const centerTabs = ref<CenterTab[]>([])
const activeCenterTabKey = ref<string | null>(null)
const sessionRuntime = reactive<Record<number, SessionRuntime>>({})
const chatScrollRef = ref<HTMLElement | null>(null)

/** 左中右可拖宽度（范围放宽） */
const leftWidth = ref(320)
const rightWidth = ref(380)
const draggingSide = ref<'left' | 'right' | null>(null)

const activeWorkspace = computed(
  () => personalWorkspaces.value.find((w) => w.id === activeWorkspaceId.value) ?? null,
)
const activeCenterTab = computed(
  () => centerTabs.value.find((t) => t.key === activeCenterTabKey.value) ?? null,
)
const activeSessionTab = computed(() => {
  const t = activeCenterTab.value
  return t?.kind === 'session' ? t : null
})
const activeDocTab = computed(() => {
  const t = activeCenterTab.value
  return t?.kind === 'doc' ? t : null
})
const activeRuntime = computed(() => {
  const sid = activeSessionTab.value?.sessionId
  return sid != null ? sessionRuntime[sid] ?? null : null
})

function sessionTabKey(sessionId: number) {
  return `s-${sessionId}`
}
function docTabKey(workspaceId: number, nodeId: number) {
  return `f-${workspaceId}-${nodeId}`
}
function ensureRuntime(sessionId: number): SessionRuntime {
  if (!sessionRuntime[sessionId]) {
    sessionRuntime[sessionId] = {
      messages: [],
      inputText: '',
      streaming: false,
      currentRunId: null,
      abortCtrl: null,
      messagesLoaded: false,
      steps: [],
      stepsExpanded: false,
      stepsStartedAt: null,
      stepsDurationSec: null,
    }
  }
  return sessionRuntime[sessionId]
}

function toggleStepsExpanded(sessionId: number) {
  const rt = ensureRuntime(sessionId)
  rt.stepsExpanded = !rt.stepsExpanded
}

function stepsSummaryLabel(rt: SessionRuntime) {
  if (rt.streaming) return '处理中'
  const sec = rt.stepsDurationSec
  if (sec != null) return `已处理 ${sec}秒`
  return '已处理'
}

function onSplitterDown(side: 'left' | 'right', e: MouseEvent) {
  draggingSide.value = side
  e.preventDefault()
  const startX = e.clientX
  const startLeft = leftWidth.value
  const startRight = rightWidth.value
  const onMove = (ev: MouseEvent) => {
    if (side === 'left') {
      leftWidth.value = Math.min(560, Math.max(220, startLeft + (ev.clientX - startX)))
    } else {
      rightWidth.value = Math.min(640, Math.max(240, startRight - (ev.clientX - startX)))
    }
  }
  const onUp = () => {
    draggingSide.value = null
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}
const wsDialogVisible = ref(false)
const wsForm = reactive({ name: '' })
const wsFormRef = ref<FormInstance>()
const wsSubmitting = ref(false)
const wsNameRules = [
  { required: true, message: '请输入名称', trigger: 'blur' },
  {
    pattern: /^[a-zA-Z0-9_]+$/,
    message: '仅支持英文、数字、下划线',
    trigger: 'blur',
  },
]

const nodeDialogVisible = ref(false)
const nodeDialogMode = ref<'dir' | 'file'>('dir')
const nodeName = ref('')
const nodeSubmitting = ref(false)
/** 新建目录/文件的父节点；null 表示工作空间根 */
const createParentId = ref<number | null>(null)
const mdUploadInputRef = ref<HTMLInputElement | null>(null)
const mdUploadParentId = ref<number | null>(null)

const sessionDialogVisible = ref(false)
const sessionDialogMode = ref<'create' | 'edit'>('create')
const sessionEditingId = ref<number | null>(null)
const sessionSubmitting = ref(false)
type SessionConfigKey =
  | 'outputDir'
  | 'outputTemplate'
  | 'standards'
  | 'codeRepos'
  | 'wikiContext'
  | 'inputFiles'
const sessionConfigEnabled = reactive({
  outputDir: false,
  outputTemplate: false,
  standards: false,
  codeRepos: false,
  wikiContext: false,
  inputFiles: false,
})
const sessionForm = reactive({
  sessionType: 'NORMAL' as WorkspaceSessionType,
  title: '',
  requirementId: undefined as number | undefined,
  taskTypeCode: undefined as string | undefined,
  positionId: undefined as number | undefined,
  outputDirNodeId: null as number | null,
  /** 输出参考模板：Wiki「模板」下文件 */
  outputTemplateWikiNodeId: null as number | null,
  /** 规范：Wiki「规范」目录 */
  standardsWikiNodeId: null as number | null,
  /** 代码仓库本地路径（可多个） */
  codeRepos: [''] as string[],
})
const sessionReqOptions = ref<RequirementItem[]>([])
const sessionTaskTypeOptions = ref<SessionTaskTypeOption[]>([])
const sessionPositionOptions = ref<PositionItem[]>([])
/** 编辑态：任务类型信息（创建态从表单取） */
const sessionBoundTaskMeta = ref<{ taskTypeCode?: string | null; taskTypeName?: string | null } | null>(
  null,
)
const sessionPersonalTree = ref<WorkspaceNodeItem[]>([])
const contextTree = ref<WikiNodeItem[]>([])
/** Wiki 上下文：下拉树多选回填的节点 id */
const contextCheckedKeys = ref<number[]>([])
const inputTree = ref<WikiNodeItem[]>([])
const inputTreeRef = ref<{ getCheckedNodes: (leafOnly?: boolean) => WikiNodeItem[] } | null>(null)
const inputCheckedKeys = ref<number[]>([])

const diffDialogVisible = ref(false)
const diffLoading = ref(false)
const diffRevisions = ref<WorkspaceFileRevisionItem[]>([])
const diffSelectedVersion = ref<number | undefined>(undefined)
const diffData = ref<WorkspaceFileDiff | null>(null)

const publishDialogVisible = ref(false)
const publishSubmitting = ref(false)
const publishSourceNode = ref<WorkspaceNodeItem | null>(null)
const publishTargetParentId = ref<number | null>(null)
const wikiTreeForPublish = ref<WikiNodeItem[]>([])

const sessionDirOptions = computed(() => collectDirOptions(sessionPersonalTree.value))

/** 输出参考模板：仅 Wiki 顶级「模板」目录下的文件 */
const sessionTemplateFileOptions = computed(() => {
  const root = findWikiTopDir(contextTree.value, '模板')
  return root ? collectWikiFileOptions(root.children || [], root.displayName) : []
})

/** 规范：Wiki「规范」目录本身 + 其下文件（与模板一致，便于下拉可见） */
const sessionStandardsOptions = computed(() => {
  const root = findWikiTopDir(contextTree.value, '规范')
  if (!root) return [] as { id: number; label: string }[]
  return [
    { id: root.id, label: `${root.displayName}（全部）` },
    ...collectWikiFileOptions(root.children || [], root.displayName),
  ]
})

const sessionTaskTypesByKind = computed(() => {
  const gen = sessionTaskTypeOptions.value.filter((t) => t.kind === 'GEN')
  const review = sessionTaskTypeOptions.value.filter((t) => t.kind === 'REVIEW')
  return { gen, review }
})

function showCodeReposForTaskCode(code: string | null | undefined) {
  const c = (code || '').trim().toUpperCase()
  return c === 'GEN_CODING' || c === 'GEN_INT_TEST'
}

const codeReposRequired = computed(() => {
  if (sessionForm.sessionType !== 'TASK') return false
  if (sessionDialogMode.value === 'create') {
    return showCodeReposForTaskCode(sessionForm.taskTypeCode)
  }
  return showCodeReposForTaskCode(sessionBoundTaskMeta.value?.taskTypeCode)
})

const showCodeReposField = computed(
  () => sessionForm.sessionType === 'TASK' && (sessionConfigEnabled.codeRepos || codeReposRequired.value),
)

const sessionConfigOptions = computed(() => {
  const all: { key: SessionConfigKey; label: string }[] = [
    { key: 'outputDir', label: '输出目录' },
    { key: 'wikiContext', label: 'Wiki 上下文' },
  ]
  if (sessionForm.sessionType === 'TASK') {
    all.splice(1, 0, { key: 'outputTemplate', label: '输出参考模板' }, { key: 'standards', label: '规范' })
    if (!codeReposRequired.value) {
      all.splice(3, 0, { key: 'codeRepos', label: '代码仓库' })
    }
    all.push({ key: 'inputFiles', label: '输入文件' })
  }
  return all.filter((o) => !sessionConfigEnabled[o.key] && !(o.key === 'codeRepos' && showCodeReposField.value))
})

function resetSessionConfigs() {
  sessionConfigEnabled.outputDir = false
  sessionConfigEnabled.outputTemplate = false
  sessionConfigEnabled.standards = false
  sessionConfigEnabled.codeRepos = false
  sessionConfigEnabled.wikiContext = false
  sessionConfigEnabled.inputFiles = false
}

function enableSessionConfig(key: string | number) {
  if (
    key === 'outputDir' ||
    key === 'outputTemplate' ||
    key === 'standards' ||
    key === 'codeRepos' ||
    key === 'wikiContext' ||
    key === 'inputFiles'
  ) {
    sessionConfigEnabled[key] = true
  }
}

function removeSessionConfig(key: SessionConfigKey) {
  if (key === 'codeRepos' && codeReposRequired.value) return
  sessionConfigEnabled[key] = false
  if (key === 'outputDir') sessionForm.outputDirNodeId = null
  if (key === 'outputTemplate') sessionForm.outputTemplateWikiNodeId = null
  if (key === 'standards') sessionForm.standardsWikiNodeId = null
  if (key === 'codeRepos') sessionForm.codeRepos = ['']
  if (key === 'wikiContext') {
    contextCheckedKeys.value = []
  }
  if (key === 'inputFiles') {
    inputCheckedKeys.value = []
    nextTick(() => {
      const tree = inputTreeRef.value as { setCheckedKeys?: (k: number[]) => void } | null
      tree?.setCheckedKeys?.([])
    })
  }
}

function addCodeRepoRow() {
  sessionForm.codeRepos.push('')
}
function removeCodeRepoRow(idx: number) {
  if (sessionForm.codeRepos.length <= 1) {
    sessionForm.codeRepos[0] = ''
    return
  }
  sessionForm.codeRepos.splice(idx, 1)
}
function collectCodeRepos(): string[] {
  return sessionForm.codeRepos.map((p) => p.trim()).filter(Boolean)
}

function collectDirOptions(nodes: WorkspaceNodeItem[], prefix = ''): { id: number; label: string }[] {
  const out: { id: number; label: string }[] = []
  for (const n of nodes) {
    if (n.nodeType === 'DIR') {
      const label = prefix ? `${prefix}/${n.displayName}` : n.displayName
      out.push({ id: n.id, label })
      if (n.children?.length) out.push(...collectDirOptions(n.children, label))
    }
  }
  return out
}

function findWikiTopDir(nodes: WikiNodeItem[], name: string): WikiNodeItem | null {
  for (const n of nodes) {
    if (n.nodeType === 'DIR' && n.displayName === name) return n
  }
  return null
}

function collectWikiFileOptions(
  nodes: WikiNodeItem[],
  prefix = '',
): { id: number; label: string }[] {
  const out: { id: number; label: string }[] = []
  for (const n of nodes) {
    const label = prefix ? `${prefix}/${n.displayName}` : n.displayName
    if (n.nodeType === 'FILE') {
      out.push({ id: n.id, label })
    }
    if (n.children?.length) {
      out.push(...collectWikiFileOptions(n.children, label))
    }
  }
  return out
}

async function refreshWorkspaces() {
  loadingList.value = true
  try {
    personalWorkspaces.value = await listWorkbenchPersonalWorkspaces()
    if (
      activeWorkspaceId.value != null &&
      !personalWorkspaces.value.some((w) => w.id === activeWorkspaceId.value)
    ) {
      activeWorkspaceId.value = null
      activeSessionId.value = null
    }
    for (const ws of personalWorkspaces.value) {
      await loadSessions(ws.id)
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载工作空间失败')
  } finally {
    loadingList.value = false
  }
}

function collectWikiDirOptions(
  nodes: WikiNodeItem[],
  prefix = '',
): { id: number; label: string }[] {
  const out: { id: number; label: string }[] = []
  for (const n of nodes) {
    if (n.nodeType === 'DIR') {
      const label = prefix ? `${prefix}/${n.displayName}` : n.displayName
      out.push({ id: n.id, label })
      if (n.children?.length) out.push(...collectWikiDirOptions(n.children, label))
    }
  }
  return out
}

const publishDirOptions = computed(() => collectWikiDirOptions(wikiTreeForPublish.value))

async function loadSessions(workspaceId: number) {
  try {
    sessionsByWs.value = {
      ...sessionsByWs.value,
      [workspaceId]: await listWorkbenchWorkspaceSessions(workspaceId),
    }
  } catch {
    sessionsByWs.value = { ...sessionsByWs.value, [workspaceId]: [] }
  }
}

async function refreshFileTree() {
  if (activeWorkspaceId.value == null) {
    fileTree.value = []
    return
  }
  loadingTree.value = true
  try {
    fileTree.value = await listWorkbenchWorkspaceTree(activeWorkspaceId.value)
  } catch (e) {
    fileTree.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载目录失败')
  } finally {
    loadingTree.value = false
  }
}

function selectWorkspace(ws: TenantWorkspaceItem) {
  activeWorkspaceId.value = ws.id
  selectedDirId.value = null
  void refreshFileTree()
}

function activateCenterTab(tab: CenterTab) {
  activeCenterTabKey.value = tab.key
  activeWorkspaceId.value = tab.workspaceId
  if (tab.kind === 'session') {
    activeSessionId.value = tab.sessionId
    ensureRuntime(tab.sessionId)
    void loadChatMessages(tab.sessionId)
  }
  void refreshFileTree()
}

function openSessionTab(ws: TenantWorkspaceItem, session: WorkspaceSessionItem) {
  const key = sessionTabKey(session.id)
  let tab = centerTabs.value.find((t) => t.key === key)
  if (!tab || tab.kind !== 'session') {
    tab = {
      kind: 'session',
      key,
      workspaceId: ws.id,
      sessionId: session.id,
      title: session.title,
      sessionType: session.sessionType,
      outputDirLabel: session.outputDirLabel,
      outputTemplatePath: session.outputTemplatePath,
      standardsPath: session.standardsPath,
    }
    centerTabs.value.push(tab)
  } else {
    tab.title = session.title
    tab.outputDirLabel = session.outputDirLabel
    tab.outputTemplatePath = session.outputTemplatePath
    tab.standardsPath = session.standardsPath
  }
  activateCenterTab(tab)
}

function selectSession(ws: TenantWorkspaceItem, session: WorkspaceSessionItem) {
  openSessionTab(ws, session)
}

function onCenterTabChange(name: TabPaneName | undefined) {
  if (name == null) return
  const tab = centerTabs.value.find((t) => t.key === String(name))
  if (!tab) return
  activateCenterTab(tab)
}

function closeCenterTab(key: string) {
  const idx = centerTabs.value.findIndex((t) => t.key === key)
  if (idx < 0) return
  const tab = centerTabs.value[idx]
  if (tab.kind === 'session') {
    const rt = sessionRuntime[tab.sessionId]
    if (rt?.streaming) {
      rt.abortCtrl?.abort()
      rt.streaming = false
    }
  }
  centerTabs.value.splice(idx, 1)
  if (activeCenterTabKey.value === key) {
    const next = centerTabs.value[idx] || centerTabs.value[idx - 1] || null
    if (next) {
      activateCenterTab(next)
    } else {
      activeCenterTabKey.value = null
      activeSessionId.value = null
    }
  }
}

async function loadChatMessages(sessionId: number, opts?: { force?: boolean }) {
  const rt = ensureRuntime(sessionId)
  if (rt.streaming && !opts?.force) return
  if (rt.messagesLoaded && !opts?.force) {
    await scrollChatBottom()
    return
  }
  try {
    rt.messages = await listMessages(sessionId)
    rt.messagesLoaded = true
    try {
      const brief = await listSessionSteps(sessionId)
      if (!rt.streaming && brief?.length) {
        const lastRun = brief[brief.length - 1]?.runId
        const ofRun = lastRun ? brief.filter((s) => s.runId === lastRun) : brief
        rt.steps = ofRun.map((s, i) => ({
          id: i + 1,
          step: s.step || 'step',
          message: s.message || '',
          at: s.occurredAt ? Date.parse(s.occurredAt) : Date.now(),
        }))
        rt.currentRunId = lastRun || null
        rt.stepsExpanded = false
      }
    } catch {
      /* ES 不可用时忽略回放 */
    }
    await scrollChatBottom()
  } catch (e) {
    rt.messages = []
    ElMessage.error(e instanceof Error ? e.message : '加载消息失败')
  }
}

async function onFileTreeClick(data: WorkspaceNodeItem) {
  if (data.nodeType === 'DIR') {
    selectedDirId.value = data.id
    return
  }
  if (activeWorkspaceId.value == null) {
    ElMessage.warning('请先选择工作空间')
    return
  }
  const wsId = activeWorkspaceId.value
  const key = docTabKey(wsId, data.id)
  const existing = centerTabs.value.find((t) => t.key === key)
  if (existing && existing.kind === 'doc') {
    activateCenterTab(existing)
    return
  }
  loadingFile.value = true
  try {
    const file = await getWorkbenchWorkspaceFileContent(wsId, data.id)
    const tab: CenterDocTab = {
      kind: 'doc',
      key,
      workspaceId: wsId,
      nodeId: file.id,
      displayName: file.displayName,
      content: file.content,
      dirty: false,
      mode: 'preview',
    }
    centerTabs.value.push(tab)
    activateCenterTab(tab)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '打开文件失败')
  } finally {
    loadingFile.value = false
  }
}

function onDocContentInput(key: string, value: string) {
  const doc = centerTabs.value.find((t) => t.key === key)
  if (!doc || doc.kind !== 'doc') return
  doc.content = value
  doc.dirty = true
}

function startEditDoc() {
  const doc = activeDocTab.value
  if (!doc) return
  doc.mode = 'edit'
}

function cancelEditDoc() {
  const doc = activeDocTab.value
  if (!doc || !doc.dirty) {
    if (doc) doc.mode = 'preview'
    return
  }
  void ElMessageBox.confirm('有未保存修改，放弃编辑？', '提示', { type: 'warning' })
    .then(async () => {
      loadingFile.value = true
      try {
        const file = await getWorkbenchWorkspaceFileContent(doc.workspaceId, doc.nodeId)
        doc.content = file.content
        doc.dirty = false
        doc.mode = 'preview'
      } catch (e) {
        ElMessage.error(e instanceof Error ? e.message : '重新加载失败')
      } finally {
        loadingFile.value = false
      }
    })
    .catch(() => undefined)
}

async function saveFile() {
  const doc = activeDocTab.value
  if (!doc) return
  savingFile.value = true
  try {
    await updateWorkbenchWorkspaceFileContent(doc.workspaceId, doc.nodeId, doc.content)
    doc.dirty = false
    doc.mode = 'preview'
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    savingFile.value = false
  }
}

async function removeNode(node: WorkspaceNodeItem) {
  if (!activeWorkspace.value) return
  try {
    await ElMessageBox.confirm(`确定删除「${node.displayName}」？`, '提示', { type: 'warning' })
    await deleteWorkbenchWorkspaceNode(activeWorkspace.value.id, node.id)
    const key = docTabKey(activeWorkspace.value.id, node.id)
    closeCenterTab(key)
    if (selectedDirId.value === node.id) selectedDirId.value = null
    ElMessage.success('已删除')
    await refreshFileTree()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

function openCreateNode(mode: 'dir' | 'file', parentId: number | null = null) {
  if (!activeWorkspace.value) return
  createParentId.value = parentId
  nodeDialogMode.value = mode
  nodeName.value = mode === 'file' ? '未命名.md' : ''
  nodeDialogVisible.value = true
}

function openCreateRootDir() {
  selectedDirId.value = null
  openCreateNode('dir', null)
}

async function openPublishDialog(node: WorkspaceNodeItem) {
  if (activeWorkspaceId.value == null || node.nodeType !== 'FILE') return
  publishSourceNode.value = node
  publishTargetParentId.value = null
  publishDialogVisible.value = true
  try {
    wikiTreeForPublish.value = await listWikiTree()
  } catch (e) {
    wikiTreeForPublish.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载 Wiki 目录失败')
  }
}

async function submitPublish() {
  if (activeWorkspaceId.value == null || !publishSourceNode.value) return
  publishSubmitting.value = true
  try {
    await publishWiki({
      sourceWorkspaceId: activeWorkspaceId.value,
      sourceNodeId: publishSourceNode.value.id,
      targetParentId: publishTargetParentId.value,
    })
    publishDialogVisible.value = false
    ElMessage.success('已发布到 Wiki')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发布失败')
  } finally {
    publishSubmitting.value = false
  }
}

function onFileNodeMenu(cmd: string, data: WorkspaceNodeItem) {
  if (cmd === 'publish-wiki') {
    void openPublishDialog(data)
  } else if (cmd === 'new-dir') {
    openCreateNode('dir', data.nodeType === 'DIR' ? data.id : data.parentId ?? null)
  } else if (cmd === 'new-file') {
    openCreateNode('file', data.nodeType === 'DIR' ? data.id : data.parentId ?? null)
  } else if (cmd === 'upload-md') {
    openUploadMd(data.nodeType === 'DIR' ? data.id : data.parentId ?? null)
  } else if (cmd === 'delete') {
    void removeNode(data)
  }
}

function openUploadMd(parentId: number | null) {
  if (!activeWorkspace.value) return
  mdUploadParentId.value = parentId
  const input = mdUploadInputRef.value
  if (!input) return
  input.value = ''
  input.click()
}

function readFileAsText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(reader.error ?? new Error('读取文件失败'))
    reader.readAsText(file, 'UTF-8')
  })
}

async function onMdUploadChange(ev: Event) {
  const input = ev.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length || !activeWorkspace.value) return

  const wsId = activeWorkspace.value.id
  const parentId = mdUploadParentId.value
  let ok = 0
  let fail = 0
  for (const file of files) {
    const name = file.name.trim()
    const lower = name.toLowerCase()
    if (!lower.endsWith('.md') && !lower.endsWith('.html') && !lower.endsWith('.htm')) {
      fail += 1
      ElMessage.warning(`仅支持 .md / .html：${name || file.name}`)
      continue
    }
    try {
      const content = await readFileAsText(file)
      await createWorkbenchWorkspaceFile(wsId, {
        parentId,
        displayName: name,
        content,
      })
      ok += 1
    } catch (e) {
      fail += 1
      ElMessage.error(e instanceof Error ? e.message : `上传失败：${name}`)
    }
  }
  if (ok) {
    ElMessage.success(ok === 1 ? '已上传' : `已上传 ${ok} 个文件`)
    await refreshFileTree()
  } else if (fail && !ok) {
    // 错误已逐条提示
  }
}

async function submitCreateNode() {
  if (!activeWorkspace.value) return
  let name = nodeName.value.trim()
  if (!name) {
    ElMessage.warning('请输入名称')
    return
  }
  if (nodeDialogMode.value === 'file') {
    if (!isMdFile(name) && !name.includes('.')) name = `${name}.md`
    if (!isMdFile(name)) {
      ElMessage.warning('新建仅支持 Markdown（.md）文件；HTML 请通过上传或 Agent 输出')
      return
    }
  }
  nodeSubmitting.value = true
  try {
    // 仅用 createParentId；null 表示工作空间根，绝不回落到树选中节点
    const parentId = createParentId.value == null ? null : createParentId.value
    if (nodeDialogMode.value === 'dir') {
      await createWorkbenchWorkspaceDir(activeWorkspace.value.id, {
        parentId,
        displayName: name,
      })
    } else {
      await createWorkbenchWorkspaceFile(activeWorkspace.value.id, {
        parentId,
        displayName: name,
        content: '',
      })
    }
    nodeDialogVisible.value = false
    ElMessage.success('已创建')
    await refreshFileTree()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    nodeSubmitting.value = false
  }
}

function openCreateWorkspace() {
  wsForm.name = ''
  wsDialogVisible.value = true
}

async function submitWorkspace() {
  await wsFormRef.value?.validate()
  wsSubmitting.value = true
  try {
    const payload = { name: wsForm.name.trim() }
    const res = await createWorkbenchWorkspace(payload)
    wsDialogVisible.value = false
    await refreshWorkspaces()
    const ws = personalWorkspaces.value.find((w) => w.id === res.id)
    if (ws) selectWorkspace(ws)
    ElMessage.success('已创建')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    wsSubmitting.value = false
  }
}

async function loadWikiContextTree() {
  try {
    const tree = await listWikiTree()
    contextTree.value = tree
    inputTree.value = tree
  } catch {
    contextTree.value = []
    inputTree.value = []
  }
}

async function loadSessionTaskAndPositions() {
  try {
    const [types, positions] = await Promise.all([listSessionTaskTypes(), listPositions(false)])
    sessionTaskTypeOptions.value = types
    sessionPositionOptions.value = positions
  } catch {
    sessionTaskTypeOptions.value = []
    sessionPositionOptions.value = []
  }
}

function onSessionTaskTypeChange(code: string | undefined) {
  if (!code) {
    sessionForm.positionId = undefined
    sessionForm.codeRepos = ['']
    sessionConfigEnabled.codeRepos = false
    return
  }
  const opt = sessionTaskTypeOptions.value.find((t) => t.code === code)
  if (opt?.defaultPositionCode) {
    const match = sessionPositionOptions.value.find(
      (p) => (p.code || '').trim() === opt.defaultPositionCode.trim(),
    )
    if (match) sessionForm.positionId = match.id
  }
  if (showCodeReposForTaskCode(code)) {
    sessionConfigEnabled.codeRepos = true
    if (!sessionForm.codeRepos.length) sessionForm.codeRepos = ['']
  } else if (!sessionConfigEnabled.codeRepos) {
    sessionForm.codeRepos = ['']
  }
}

async function onPersonalMenu(cmd: string, ws: TenantWorkspaceItem) {
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        `确定删除个人工作空间「${ws.name}」？将同时删除其下会话与全部文件目录。`,
        '提示',
        { type: 'warning' },
      )
      await deleteWorkbenchPersonalWorkspace(ws.id)
      centerTabs.value = centerTabs.value.filter((t) => t.workspaceId !== ws.id)
      if (activeWorkspaceId.value === ws.id) {
        activeWorkspaceId.value = null
        activeSessionId.value = null
        activeCenterTabKey.value = null
        fileTree.value = []
      }
      await refreshWorkspaces()
      ElMessage.success('已删除')
    } catch (e) {
      if (e !== 'cancel') ElMessage.error(e instanceof Error ? e.message : '删除失败')
    }
  } else if (cmd === 'session-normal' || cmd === 'session-task') {
    activeWorkspaceId.value = ws.id
    await openCreateSessionDialog(cmd === 'session-task' ? 'TASK' : 'NORMAL')
  }
}

async function openCreateSessionDialog(type: WorkspaceSessionType) {
  if (activeWorkspaceId.value == null) return
  sessionDialogMode.value = 'create'
  sessionEditingId.value = null
  sessionForm.sessionType = type
  sessionForm.title = ''
  sessionForm.requirementId = undefined
  sessionForm.taskTypeCode = undefined
  sessionForm.positionId = undefined
  sessionForm.outputDirNodeId = null
  sessionForm.outputTemplateWikiNodeId = null
  sessionForm.standardsWikiNodeId = null
  sessionForm.codeRepos = ['']
  sessionBoundTaskMeta.value = null
  resetSessionConfigs()
  contextTree.value = []
  contextCheckedKeys.value = []
  inputTree.value = []
  inputCheckedKeys.value = []
  sessionDialogVisible.value = true
  sessionPersonalTree.value = await listWorkbenchWorkspaceTree(activeWorkspaceId.value)
  await loadWikiContextTree()
  if (type === 'TASK') {
    sessionReqOptions.value = (await listRequirementWorkbench('all')).filter(
      (r) => r.status === 'DEVELOPING',
    )
    await loadSessionTaskAndPositions()
  }
}

async function openEditSession(ws: TenantWorkspaceItem, session: WorkspaceSessionItem) {
  activeWorkspaceId.value = ws.id
  sessionDialogMode.value = 'edit'
  sessionEditingId.value = session.id
  sessionForm.sessionType = session.sessionType
  sessionForm.title = session.title
  sessionForm.requirementId = session.requirementId ?? undefined
  sessionForm.taskTypeCode = session.taskTypeCode ?? undefined
  sessionForm.positionId = session.positionId ?? undefined
  sessionForm.outputDirNodeId = session.outputDirNodeId ?? null
  sessionForm.outputTemplateWikiNodeId = session.outputTemplateWikiNodeId ?? null
  sessionForm.standardsWikiNodeId = session.standardsWikiNodeId ?? null
  const repos = (session.codeRepos || []).map((r) => r.localPath).filter(Boolean)
  sessionForm.codeRepos = repos.length ? repos : ['']
  sessionBoundTaskMeta.value = {
    taskTypeCode: session.taskTypeCode,
    taskTypeName: session.taskTypeName,
  }
  resetSessionConfigs()
  sessionConfigEnabled.outputDir = session.outputDirNodeId != null
  sessionConfigEnabled.outputTemplate = session.outputTemplateWikiNodeId != null
  sessionConfigEnabled.standards = session.standardsWikiNodeId != null
  sessionConfigEnabled.codeRepos =
    repos.length > 0 || showCodeReposForTaskCode(session.taskTypeCode)
  sessionConfigEnabled.wikiContext = (session.contexts || []).length > 0
  sessionConfigEnabled.inputFiles = (session.inputs || []).length > 0
  sessionPersonalTree.value = await listWorkbenchWorkspaceTree(ws.id)
  contextCheckedKeys.value = (session.contexts || []).map((c) => c.wikiNodeId)
  inputCheckedKeys.value = (session.inputs || []).map((i) => i.wikiNodeId)
  await loadWikiContextTree()
  sessionDialogVisible.value = true
}

function onSessionReqChange() {
  if (!showCodeReposForTaskCode(sessionForm.taskTypeCode) && !sessionConfigEnabled.codeRepos) {
    sessionForm.codeRepos = ['']
  }
}

function collectContexts(): { wikiNodeId: number }[] {
  return (contextCheckedKeys.value || [])
    .filter((id) => id != null)
    .map((wikiNodeId) => ({ wikiNodeId }))
}

function collectInputs(): { wikiNodeId: number }[] {
  if (!inputTreeRef.value) return []
  return (inputTreeRef.value.getCheckedNodes(true) || [])
    .filter((n) => n.nodeType === 'FILE')
    .map((n) => ({ wikiNodeId: n.id }))
}

async function submitSession() {
  if (activeWorkspaceId.value == null) return
  if (!sessionForm.title.trim()) {
    ElMessage.warning('请输入会话名称')
    return
  }
  if (sessionDialogMode.value === 'create' && sessionForm.sessionType === 'TASK') {
    if (sessionForm.requirementId == null) {
      ElMessage.warning('请选择需求')
      return
    }
    if (!sessionForm.taskTypeCode?.trim()) {
      ElMessage.warning('请选择任务类型')
      return
    }
    if (sessionForm.positionId == null) {
      ElMessage.warning('请选择岗位')
      return
    }
  }
  if (codeReposRequired.value && collectCodeRepos().length === 0) {
    ElMessage.warning('编码/自动化测试任务请至少填写一个本地代码仓库地址')
    return
  }
  sessionSubmitting.value = true
  try {
    const contexts = sessionConfigEnabled.wikiContext ? collectContexts() : []
    const inputs =
      sessionConfigEnabled.inputFiles && sessionForm.sessionType === 'TASK' ? collectInputs() : []
    const codeRepos = showCodeReposField.value ? collectCodeRepos() : []
    const outputDirNodeId = sessionConfigEnabled.outputDir ? sessionForm.outputDirNodeId : null
    const outputTemplateWikiNodeId =
      sessionConfigEnabled.outputTemplate && sessionForm.sessionType === 'TASK'
        ? sessionForm.outputTemplateWikiNodeId
        : null
    const standardsWikiNodeId =
      sessionConfigEnabled.standards && sessionForm.sessionType === 'TASK'
        ? sessionForm.standardsWikiNodeId
        : null
    if (sessionDialogMode.value === 'create') {
      const res = await createWorkbenchWorkspaceSession(activeWorkspaceId.value, {
        sessionType: sessionForm.sessionType,
        title: sessionForm.title.trim(),
        requirementId: sessionForm.requirementId,
        taskTypeCode: sessionForm.taskTypeCode,
        positionId: sessionForm.positionId,
        outputDirNodeId,
        outputTemplateWikiNodeId,
        standardsWikiNodeId,
        contexts,
        inputs,
        codeRepos,
      })
      await loadSessions(activeWorkspaceId.value)
      const ws = personalWorkspaces.value.find((w) => w.id === activeWorkspaceId.value)
      const session = (sessionsByWs.value[activeWorkspaceId.value!] || []).find(
        (s) => s.id === res.id,
      )
      if (ws && session) selectSession(ws, session)
    } else if (sessionEditingId.value != null) {
      await updateWorkbenchWorkspaceSession(activeWorkspaceId.value, sessionEditingId.value, {
        title: sessionForm.title.trim(),
        outputDirNodeId,
        outputTemplateWikiNodeId,
        standardsWikiNodeId,
        contexts,
        inputs,
        codeRepos,
      })
      await loadSessions(activeWorkspaceId.value)
      const tab = centerTabs.value.find(
        (t) => t.kind === 'session' && t.sessionId === sessionEditingId.value,
      )
      if (tab && tab.kind === 'session') {
        tab.title = sessionForm.title.trim()
        const s = (sessionsByWs.value[activeWorkspaceId.value!] || []).find(
          (x) => x.id === sessionEditingId.value,
        )
        tab.outputDirLabel = s?.outputDirLabel
        tab.outputTemplatePath = s?.outputTemplatePath
        tab.standardsPath = s?.standardsPath
      }
    }
    sessionDialogVisible.value = false
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    sessionSubmitting.value = false
  }
}

async function openDiffDialog() {
  const doc = activeDocTab.value
  if (!doc) return
  diffDialogVisible.value = true
  diffLoading.value = true
  diffData.value = null
  diffSelectedVersion.value = undefined
  try {
    diffRevisions.value = await listWorkbenchWorkspaceFileRevisions(doc.workspaceId, doc.nodeId)
    diffData.value = await getWorkbenchWorkspaceFileDiff(doc.workspaceId, doc.nodeId)
    if (diffData.value.previousVersionNo != null) {
      diffSelectedVersion.value = diffData.value.previousVersionNo
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载差异失败')
  } finally {
    diffLoading.value = false
  }
}

async function onDiffVersionChange(versionNo: number | undefined) {
  const doc = activeDocTab.value
  if (!doc || versionNo == null) return
  diffLoading.value = true
  try {
    diffData.value = await getWorkbenchWorkspaceFileDiff(doc.workspaceId, doc.nodeId, versionNo)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载差异失败')
  } finally {
    diffLoading.value = false
  }
}

async function removeSession(ws: TenantWorkspaceItem, session: WorkspaceSessionItem) {
  try {
    await ElMessageBox.confirm(`确定删除会话「${session.title}」？`, '提示', { type: 'warning' })
    await deleteWorkbenchWorkspaceSession(ws.id, session.id)
    closeCenterTab(sessionTabKey(session.id))
    delete sessionRuntime[session.id]
    await loadSessions(ws.id)
    ElMessage.success('已删除')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

function unwrapSseData(data: unknown): any {
  let cur: any = data
  for (let i = 0; i < 2; i++) {
    if (typeof cur !== 'string') break
    try {
      cur = JSON.parse(cur)
    } catch {
      break
    }
  }
  return cur ?? {}
}

async function scrollChatBottom() {
  await nextTick()
  if (chatScrollRef.value) {
    chatScrollRef.value.scrollTop = chatScrollRef.value.scrollHeight
  }
}

async function sendChat() {
  if (activeSessionId.value == null) return
  const sid = activeSessionId.value
  const rt = ensureRuntime(sid)
  const content = rt.inputText.trim()
  if (!content || rt.streaming) return
  rt.streaming = true
  rt.inputText = ''
  rt.steps = []
  rt.stepsExpanded = false
  rt.stepsStartedAt = Date.now()
  rt.stepsDurationSec = null
  rt.messages.push({
    id: Date.now(),
    sessionId: sid,
    role: 'user',
    content,
    status: 'done',
  })
  rt.messages.push({
    id: Date.now() + 1,
    sessionId: sid,
    role: 'assistant',
    content: '',
    status: 'streaming',
  })
  const aiIdx = rt.messages.length - 1
  const abort = new AbortController()
  rt.abortCtrl = abort

  const patchAi = (partial: Partial<ChatMessage>) => {
    const cur = rt.messages[aiIdx]
    if (!cur) return
    // 替换整段数组，确保嵌套 reactive 也能触发视图更新
    const next = { ...cur, ...partial }
    rt.messages = [...rt.messages.slice(0, aiIdx), next, ...rt.messages.slice(aiIdx + 1)]
  }

  try {
    await streamCompletion(
      { sessionId: sid, content },
      (event, data) => {
        const payload = unwrapSseData(data)
        if (event === 'meta') {
          rt.currentRunId = payload.runId
          if (payload.messageId) {
            patchAi({ id: payload.messageId })
          }
        } else if (event === 'step') {
          rt.steps = [
            ...rt.steps,
            {
              id: Date.now() + rt.steps.length,
              step: String(payload.step || 'step'),
              message: String(payload.message || ''),
              at: Date.now(),
            },
          ]
          void scrollChatBottom()
        } else if (event === 'content') {
          const delta = payload?.delta ?? ''
          if (!delta) return
          const prev = rt.messages[aiIdx]?.content || ''
          patchAi({ content: prev + delta, status: 'streaming' })
          void scrollChatBottom()
        } else if (event === 'error') {
          ElMessage.error(payload.message || '生成失败')
          patchAi({ status: 'error' })
        } else if (event === 'done') {
          patchAi({ status: 'done' })
        }
      },
      abort.signal,
    )
    rt.streaming = false
    if (rt.stepsStartedAt != null) {
      rt.stepsDurationSec = Math.max(0, Math.round((Date.now() - rt.stepsStartedAt) / 1000))
    }
    rt.messagesLoaded = false
    await loadChatMessages(sid, { force: true })
    await refreshFileTree()
  } catch (e) {
    if ((e as Error).name !== 'AbortError') {
      ElMessage.error(e instanceof Error ? e.message : '发送失败')
      patchAi({ status: 'error' })
    } else {
      patchAi({ status: 'stopped' })
    }
  } finally {
    if (rt.stepsDurationSec == null && rt.stepsStartedAt != null) {
      rt.stepsDurationSec = Math.max(0, Math.round((Date.now() - rt.stepsStartedAt) / 1000))
    }
    rt.streaming = false
    rt.currentRunId = null
    rt.abortCtrl = null
  }
}

async function stopChat() {
  if (activeSessionId.value == null) return
  const rt = sessionRuntime[activeSessionId.value]
  if (!rt?.currentRunId) return
  try {
    await stopGeneration(activeSessionId.value, rt.currentRunId)
    rt.abortCtrl?.abort()
  } catch {
    /* ignore */
  }
}

onMounted(() => {
  void refreshWorkspaces().then(() => {
    const first = personalWorkspaces.value[0]
    if (first) selectWorkspace(first)
  })
})
</script>

<template>
  <div class="spaces">
    <aside class="col left" :style="{ width: leftWidth + 'px' }">
      <div class="pane-hd">
        <span class="pos-label">工作空间</span>
        <el-button
          class="icon-btn"
          text
          :icon="Plus"
          title="新建个人空间"
          @click="openCreateWorkspace"
        />
        <el-button class="icon-btn" text :icon="Refresh" title="刷新" @click="refreshWorkspaces" />
      </div>
      <div class="left-body" v-loading="loadingList">
        <div
          v-for="ws in personalWorkspaces"
          :key="ws.id"
          class="ws-block"
          :class="{ active: ws.id === activeWorkspaceId && activeSessionId == null }"
        >
          <div class="ws-row" @click="selectWorkspace(ws)">
            <el-icon><Folder /></el-icon>
            <span class="ws-name">{{ ws.name }}</span>
            <el-dropdown
              class="row-actions"
              trigger="click"
              @command="(c: string) => onPersonalMenu(c, ws)"
              @click.stop
            >
              <button type="button" class="more-btn" title="更多" @click.stop>
                <el-icon class="more-v"><MoreFilled /></el-icon>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="session-normal">新建普通会话</el-dropdown-item>
                  <el-dropdown-item command="session-task">新建任务会话</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除空间</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <div
            v-for="s in sessionsByWs[ws.id] || []"
            :key="s.id"
            class="session-row"
            :class="{ active: s.id === activeSessionId }"
            @click.stop="selectSession(ws, s)"
          >
            <el-icon :title="s.sessionType === 'TASK' ? '任务会话' : '普通会话'">
              <List v-if="s.sessionType === 'TASK'" />
              <ChatDotRound v-else />
            </el-icon>
            <span class="session-title">{{ s.title }}</span>
            <el-dropdown
              class="row-actions"
              trigger="click"
              @command="(c: string) => (c === 'edit' ? openEditSession(ws, s) : removeSession(ws, s))"
              @click.stop
            >
              <button type="button" class="more-btn" title="更多" @click.stop>
                <el-icon class="more-v"><MoreFilled /></el-icon>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit">编辑</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>

        <div v-if="!personalWorkspaces.length" class="empty">暂无个人空间</div>
      </div>
    </aside>

    <div
      class="splitter"
      :class="{ active: draggingSide === 'left' }"
      @mousedown="onSplitterDown('left', $event)"
    />

    <section class="col mid">
      <template v-if="centerTabs.length">
        <el-tabs
          class="center-tabs"
          :model-value="activeCenterTabKey ?? undefined"
          type="card"
          @tab-change="onCenterTabChange"
        >
          <el-tab-pane v-for="t in centerTabs" :key="t.key" :name="t.key">
            <template #label>
              <span class="center-tab-label">
                <el-icon v-if="t.kind === 'session'">
                  <List v-if="t.sessionType === 'TASK'" />
                  <ChatDotRound v-else />
                </el-icon>
                <el-icon v-else><Document /></el-icon>
                <span class="center-tab-title">
                  {{ t.kind === 'session' ? t.title : t.displayName
                  }}{{ t.kind === 'doc' && t.dirty ? ' *' : '' }}
                </span>
                <button
                  type="button"
                  class="tab-close"
                  title="关闭"
                  @click.stop="closeCenterTab(t.key)"
                >
                  <el-icon :size="12"><Close /></el-icon>
                </button>
              </span>
            </template>
          </el-tab-pane>
        </el-tabs>

        <template v-if="activeSessionTab && activeRuntime">
          <div class="session-meta muted">
            输出 · {{ activeSessionTab.outputDirLabel || '根目录' }}
            <template v-if="activeSessionTab.outputTemplatePath">
              · 参考模板 · {{ activeSessionTab.outputTemplatePath }}
            </template>
            <template v-if="activeSessionTab.standardsPath">
              · 规范 · {{ activeSessionTab.standardsPath }}
            </template>
          </div>
          <div ref="chatScrollRef" class="chat-body">
            <template
              v-for="(m, idx) in activeRuntime.messages"
              :key="m.id"
            >
              <!-- 步骤日志插在最后一条助手消息之前；默认折叠 -->
              <div
                v-if="
                  m.role === 'assistant' &&
                  idx === activeRuntime.messages.length - 1 &&
                  (activeRuntime.streaming || activeRuntime.steps.length > 0)
                "
                class="step-log"
                :class="{
                  live: activeRuntime.streaming,
                  expanded: activeRuntime.stepsExpanded,
                }"
              >
                <button
                  type="button"
                  class="step-log-hd"
                  @click="activeSessionId != null && toggleStepsExpanded(activeSessionId)"
                >
                  <el-icon
                    class="step-log-chevron"
                    :class="{ open: activeRuntime.stepsExpanded }"
                  >
                    <ArrowRight />
                  </el-icon>
                  <span>{{ stepsSummaryLabel(activeRuntime) }}</span>
                </button>
                <div v-show="activeRuntime.stepsExpanded" class="step-log-body">
                  <div
                    v-for="s in activeRuntime.steps"
                    :key="s.id"
                    class="step-item"
                    :data-step="s.step"
                  >
                    <span class="step-dot" />
                    <span class="step-msg">{{ s.message }}</span>
                  </div>
                  <div v-if="!activeRuntime.steps.length" class="step-item muted">
                    暂无明细…
                  </div>
                </div>
              </div>
              <div class="bubble" :class="m.role">
                <template v-if="m.role === 'assistant'">
                  <MarkdownBody v-if="m.content" :content="m.content" />
                  <span v-else-if="m.status === 'streaming'" class="typing">正在回复…</span>
                  <span v-else-if="m.status === 'error'" class="err">生成失败</span>
                </template>
                <span v-else>{{ m.content }}</span>
              </div>
            </template>
            <div v-if="!activeRuntime.messages.length" class="empty">开始对话吧</div>
          </div>
          <div class="chat-ft">
            <el-input
              v-model="activeRuntime.inputText"
              type="textarea"
              :rows="2"
              placeholder="Enter 发送，Shift+Enter 换行"
              @keydown.enter.exact.prevent="sendChat"
            />
            <el-button v-if="activeRuntime.streaming" @click="stopChat">停止</el-button>
            <el-button type="primary" :loading="activeRuntime.streaming" @click="sendChat">
              发送
            </el-button>
          </div>
        </template>

        <template v-else-if="activeDocTab">
          <div class="doc-pane" v-loading="loadingFile">
            <div class="file-actions">
              <span class="doc-name">{{ activeDocTab.displayName }}</span>
              <el-button size="small" @click="openDiffDialog">与上一版对比</el-button>
              <template v-if="activeDocTab.mode === 'preview'">
                <el-button type="primary" size="small" @click="startEditDoc">编辑</el-button>
              </template>
              <template v-else>
                <el-button size="small" @click="cancelEditDoc">取消</el-button>
                <el-button type="primary" size="small" :loading="savingFile" @click="saveFile">
                  保存
                </el-button>
              </template>
            </div>
            <div v-if="activeDocTab.mode === 'preview'" class="md-preview grow">
              <HtmlPreview
                v-if="isHtmlPreview(activeDocTab.displayName)"
                :content="activeDocTab.content"
              />
              <MarkdownBody v-else :content="activeDocTab.content" />
            </div>
            <el-input
              v-else
              :model-value="activeDocTab.content"
              type="textarea"
              class="file-editor grow"
              @update:model-value="(v: string) => onDocContentInput(activeDocTab!.key, v)"
            />
          </div>
        </template>
      </template>
      <div v-else class="mid-empty">
        <p>从左侧打开会话，或从右侧打开 md / html 文档</p>
        <p class="sub">会话与文档都会以同级 Tab 显示在中间</p>
      </div>
    </section>

    <div
      class="splitter"
      :class="{ active: draggingSide === 'right' }"
      @mousedown="onSplitterDown('right', $event)"
    />

    <aside class="col right" :style="{ width: rightWidth + 'px' }">
      <div class="pane-hd">
        <span class="title">文件</span>
        <el-button
          class="icon-btn"
          text
          :icon="Plus"
          title="新建根目录"
          @click="openCreateRootDir"
        />
      </div>
      <div class="right-body" v-loading="loadingTree || loadingFile">
        <el-tree
          class="file-tree"
          :data="fileTree"
          node-key="id"
          default-expand-all
          highlight-current
          :current-node-key="selectedDirId ?? undefined"
          :props="{ label: 'displayName', children: 'children' }"
          @node-click="onFileTreeClick"
        >
          <template #default="{ data }">
            <div class="tree-node">
              <el-icon><Folder v-if="data.nodeType === 'DIR'" /><Document v-else /></el-icon>
              <span class="tree-label">{{ data.displayName }}</span>
              <el-dropdown
                class="row-actions"
                trigger="click"
                @command="(c: string) => onFileNodeMenu(c, data)"
                @click.stop
              >
                <button type="button" class="more-btn" title="更多" @click.stop>
                  <el-icon class="more-v"><MoreFilled /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-if="data.nodeType === 'DIR'" command="new-dir">
                      新增文件夹
                    </el-dropdown-item>
                    <el-dropdown-item v-if="data.nodeType === 'DIR'" command="new-file">
                      新增 md
                    </el-dropdown-item>
                    <el-dropdown-item v-if="data.nodeType === 'DIR'" command="upload-md">
                      上传 md/html
                    </el-dropdown-item>
                    <el-dropdown-item v-if="data.nodeType === 'FILE'" command="publish-wiki">
                      发布到 Wiki
                    </el-dropdown-item>
                    <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-tree>
      </div>
    </aside>

    <!-- dialogs -->
    <el-dialog
      v-model="wsDialogVisible"
      title="新建个人工作空间"
      width="440px"
      destroy-on-close
    >
      <el-form ref="wsFormRef" :model="wsForm" label-width="72px">
        <el-form-item label="名称" prop="name" :rules="wsNameRules">
          <el-input v-model="wsForm.name" maxlength="64" placeholder="英文、数字、下划线" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="wsDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="wsSubmitting" @click="submitWorkspace">创建</el-button>
      </template>
    </el-dialog>

    <input
      ref="mdUploadInputRef"
      type="file"
      accept=".md,.html,.htm,text/markdown,text/html"
      multiple
      class="hidden-file-input"
      @change="onMdUploadChange"
    />

    <el-dialog
      v-model="nodeDialogVisible"
      :title="nodeDialogMode === 'dir' ? '新建目录' : '新建 Markdown'"
      width="400px"
      destroy-on-close
    >
      <el-input v-model="nodeName" :placeholder="nodeDialogMode === 'file' ? '文件名（仅 .md）' : '名称'" />
      <template #footer>
        <el-button @click="nodeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="nodeSubmitting" @click="submitCreateNode">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="sessionDialogVisible"
      :title="
        sessionDialogMode === 'edit'
          ? '编辑会话'
          : sessionForm.sessionType === 'TASK'
            ? '创建任务会话'
            : '创建普通会话'
      "
      width="560px"
      destroy-on-close
    >
      <el-form label-width="108px">
        <el-form-item label="名称" required>
          <el-input
            v-model="sessionForm.title"
            maxlength="128"
            placeholder="请输入会话名称"
          />
        </el-form-item>
        <el-form-item
          v-if="sessionForm.sessionType === 'TASK' && sessionDialogMode === 'create'"
          label="需求"
          required
        >
          <el-select
            v-model="sessionForm.requirementId"
            filterable
            placeholder="选择研发中需求"
            style="width: 100%"
            @change="onSessionReqChange"
          >
            <el-option
              v-for="r in sessionReqOptions"
              :key="r.id"
              :label="`${r.reqNo || ''} ${r.name}`"
              :value="r.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="sessionForm.sessionType === 'TASK' && sessionDialogMode === 'create'"
          label="任务类型"
          required
        >
          <el-select
            v-model="sessionForm.taskTypeCode"
            filterable
            placeholder="选择任务类型"
            style="width: 100%"
            @change="onSessionTaskTypeChange"
          >
            <el-option-group v-if="sessionTaskTypesByKind.gen.length" label="生成 (GEN)">
              <el-option
                v-for="t in sessionTaskTypesByKind.gen"
                :key="t.code"
                :label="t.name"
                :value="t.code"
              />
            </el-option-group>
            <el-option-group v-if="sessionTaskTypesByKind.review.length" label="评审 (REVIEW)">
              <el-option
                v-for="t in sessionTaskTypesByKind.review"
                :key="t.code"
                :label="t.name"
                :value="t.code"
              />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="sessionForm.sessionType === 'TASK' && sessionDialogMode === 'create'"
          label="岗位"
          required
        >
          <el-select
            v-model="sessionForm.positionId"
            filterable
            placeholder="选择岗位"
            style="width: 100%"
          >
            <el-option
              v-for="p in sessionPositionOptions"
              :key="p.id"
              :label="p.code ? `${p.name} (${p.code})` : p.name"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="sessionForm.sessionType === 'TASK' && sessionDialogMode === 'edit'"
          label="任务类型"
        >
          <span>{{ sessionBoundTaskMeta?.taskTypeName || sessionForm.taskTypeCode || '—' }}</span>
        </el-form-item>
        <el-form-item v-if="showCodeReposField" label="代码仓库" :required="codeReposRequired">
          <div class="code-repo-list">
            <div class="session-config-block-hd" v-if="!codeReposRequired">
              <el-button text type="danger" @click="removeSessionConfig('codeRepos')">移除</el-button>
            </div>
            <div v-for="(_, idx) in sessionForm.codeRepos" :key="idx" class="code-repo-row">
              <el-input
                v-model="sessionForm.codeRepos[idx]"
                placeholder="本地绝对路径，如 E:\workspace\my-app"
              />
              <el-button text type="danger" @click="removeCodeRepoRow(idx)">删除</el-button>
            </div>
            <el-button text type="primary" @click="addCodeRepoRow">添加仓库</el-button>
            <div class="form-hint">可填写多个本机目录路径（当前版本不支持远程 URL）。</div>
          </div>
        </el-form-item>

        <el-form-item v-if="sessionConfigEnabled.outputDir" label="输出目录">
          <div class="session-config-row">
            <el-select
              v-model="sessionForm.outputDirNodeId"
              clearable
              filterable
              placeholder="清空=根目录"
              style="flex: 1"
            >
              <el-option v-for="d in sessionDirOptions" :key="d.id" :label="d.label" :value="d.id" />
            </el-select>
            <el-button text type="danger" @click="removeSessionConfig('outputDir')">移除</el-button>
          </div>
        </el-form-item>
        <el-form-item
          v-if="sessionConfigEnabled.outputTemplate && sessionForm.sessionType === 'TASK'"
          label="输出参考模板"
        >
          <div class="session-config-row">
            <el-select
              v-model="sessionForm.outputTemplateWikiNodeId"
              clearable
              filterable
              placeholder="从 Wiki「模板」目录选择文件"
              style="flex: 1"
            >
              <el-option
                v-for="f in sessionTemplateFileOptions"
                :key="f.id"
                :label="f.label"
                :value="f.id"
              />
            </el-select>
            <el-button text type="danger" @click="removeSessionConfig('outputTemplate')">
              移除
            </el-button>
          </div>
          <div v-if="!sessionTemplateFileOptions.length" class="form-hint">
            Wiki 中尚无「模板」目录或其中无文件，请先在知识库创建。
          </div>
        </el-form-item>
        <el-form-item
          v-if="sessionConfigEnabled.standards && sessionForm.sessionType === 'TASK'"
          label="规范"
        >
          <div class="session-config-row">
            <el-select
              v-model="sessionForm.standardsWikiNodeId"
              clearable
              filterable
              placeholder="从 Wiki「规范」选择目录或文件"
              style="flex: 1"
            >
              <el-option
                v-for="d in sessionStandardsOptions"
                :key="d.id"
                :label="d.label"
                :value="d.id"
              />
            </el-select>
            <el-button text type="danger" @click="removeSessionConfig('standards')">移除</el-button>
          </div>
          <div v-if="!sessionStandardsOptions.length" class="form-hint">
            Wiki 中尚无「规范」目录，请先在知识库根下创建该目录。
          </div>
        </el-form-item>
        <el-form-item v-if="sessionConfigEnabled.wikiContext" label="Wiki 上下文">
          <div class="session-config-row">
            <el-tree-select
              v-model="contextCheckedKeys"
              :data="contextTree"
              multiple
              show-checkbox
              check-strictly
              filterable
              clearable
              collapse-tags
              collapse-tags-tooltip
              default-expand-all
              node-key="id"
              :render-after-expand="false"
              :props="{ label: 'displayName', value: 'id', children: 'children' }"
              placeholder="选择 Wiki 文件或文件夹"
              style="flex: 1"
            />
            <el-button text type="danger" @click="removeSessionConfig('wikiContext')">移除</el-button>
          </div>
          <div v-if="!contextTree.length" class="form-hint">Work Wiki 暂无内容</div>
        </el-form-item>
        <el-form-item
          v-if="sessionConfigEnabled.inputFiles && sessionForm.sessionType === 'TASK'"
          label="输入文件"
        >
          <div class="session-config-block">
            <div class="session-config-block-hd">
              <el-button text type="danger" @click="removeSessionConfig('inputFiles')">移除</el-button>
            </div>
            <el-tree
              v-if="inputTree.length"
              ref="inputTreeRef"
              :data="inputTree"
              node-key="id"
              show-checkbox
              default-expand-all
              :default-checked-keys="inputCheckedKeys"
              :props="{ label: 'displayName', children: 'children' }"
            />
            <div v-else class="muted">勾选 Wiki 中的 Markdown 文件作为任务输入</div>
            <div class="form-hint">仅文件会作为输入；目录请展开后勾选具体文件。</div>
          </div>
        </el-form-item>

        <el-form-item v-if="sessionConfigOptions.length" label=" ">
          <el-dropdown trigger="click" @command="enableSessionConfig">
            <el-button plain>
              <el-icon class="el-icon--left"><Plus /></el-icon>
              添加配置项
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="opt in sessionConfigOptions"
                  :key="opt.key"
                  :command="opt.key"
                >
                  {{ opt.label }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sessionDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="sessionSubmitting" @click="submitSession">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="diffDialogVisible"
      :title="diffData ? `对比 · ${diffData.displayName}` : '与上一版对比'"
      width="960px"
      destroy-on-close
      class="diff-dialog"
    >
      <div v-loading="diffLoading" class="diff-wrap">
        <el-form-item label="对比版本">
          <el-select
            v-model="diffSelectedVersion"
            clearable
            placeholder="选择历史版本"
            style="width: 240px"
            @change="onDiffVersionChange"
          >
            <el-option
              v-for="r in diffRevisions"
              :key="r.versionNo"
              :label="`v${r.versionNo}`"
              :value="r.versionNo"
            />
          </el-select>
          <span v-if="!diffRevisions.length" class="muted">暂无历史版本（尚未被 Agent 覆盖写入）</span>
        </el-form-item>
        <DiffSideBySide
          v-if="diffData"
          :previous-content="diffData.previousContent"
          :current-content="diffData.currentContent"
          :previous-label="
            diffData.previousVersionNo != null
              ? `旧版 v${diffData.previousVersionNo}`
              : '旧版'
          "
          current-label="当前"
        />
      </div>
      <template #footer>
        <el-button type="primary" @click="diffDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="publishDialogVisible" title="发布到 Wiki" width="480px" destroy-on-close>
      <p v-if="publishSourceNode" class="form-hint">
        将个人文件「{{ publishSourceNode.displayName }}」复制到 Wiki 目标目录。
      </p>
      <el-form label-width="88px">
        <el-form-item label="目标目录">
          <el-select
            v-model="publishTargetParentId"
            clearable
            filterable
            placeholder="清空即 Wiki 根目录"
            style="width: 100%"
          >
            <el-option v-for="d in publishDirOptions" :key="d.id" :label="d.label" :value="d.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="publishDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="publishSubmitting" @click="submitPublish">
          发布
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hidden-file-input {
  display: none;
}
.text-btn {
  padding: 4px 6px !important;
  margin: 0 !important;
  font-size: 13px;
}
.form-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--dap-gray-500);
  line-height: 1.4;
}
.spaces {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  background: var(--dap-gray-50);
}
.col {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: var(--dap-gray-0);
}
.left,
.right {
  flex-shrink: 0;
}
.mid {
  flex: 1;
  min-width: 280px;
}
.splitter {
  width: 5px;
  cursor: col-resize;
  flex-shrink: 0;
  background: transparent;
  position: relative;
  z-index: 2;
}
.splitter:hover,
.splitter.active {
  background: var(--dap-primary-light, #e8f3ff);
}
.center-tabs {
  flex-shrink: 0;
  padding: 6px 8px 0;
}
.center-tabs :deep(.el-tabs__header) {
  margin: 0;
}
.center-tabs :deep(.el-tabs__content) {
  display: none;
}
.center-tab-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 200px;
}
.center-tab-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.md-preview {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px 14px;
  border: 1px solid var(--dap-gray-200);
  border-radius: 8px;
  background: var(--dap-gray-0);
  display: flex;
  flex-direction: column;
}
.md-preview :deep(.html-preview) {
  flex: 1;
  min-height: 480px;
}
.tab-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  padding: 0;
  margin-left: 2px;
  cursor: pointer;
  color: var(--dap-gray-500);
  border-radius: 3px;
}
.tab-close:hover {
  background: rgba(0, 0, 0, 0.08);
  color: var(--dap-gray-800);
}
.session-meta {
  padding: 4px 12px 0;
  flex-shrink: 0;
}
.doc-pane {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 8px;
}
.doc-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}
.file-editor.grow {
  flex: 1;
  min-height: 0;
}
.file-editor.grow :deep(.el-textarea) {
  height: 100%;
}
.file-editor.grow :deep(textarea) {
  height: 100% !important;
}
.pane-hd {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--dap-gray-200);
  flex-shrink: 0;
}
.pos-label,
.title {
  font-weight: 600;
  flex: 1;
}
.left-body,
.right-body,
.chat-body {
  flex: 1;
  overflow: auto;
  padding: 8px;
}
.ws-block {
  margin-bottom: 4px;
}
.ws-row,
.session-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
}
.ws-row:hover,
.session-row:hover,
.ws-block.active > .ws-row,
.session-row.active {
  background: var(--dap-primary-light);
  color: var(--dap-primary);
}
.ws-name,
.session-title,
.tree-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-row {
  padding-left: 22px;
  font-size: 13px;
}
.row-actions {
  opacity: 0;
  pointer-events: none;
  flex-shrink: 0;
}
.ws-row:hover .row-actions,
.session-row:hover .row-actions,
.file-tree :deep(.el-tree-node__content:hover) .row-actions,
.row-actions:focus-within {
  opacity: 1;
  pointer-events: auto;
}
.more-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: none;
  background: transparent;
  color: inherit;
  cursor: pointer;
  border-radius: 4px;
}
.more-btn:hover {
  background: rgba(0, 0, 0, 0.06);
}
.more-v {
  transform: rotate(90deg);
}
.icon-btn {
  padding: 4px !important;
  margin: 0 !important;
  border: none !important;
  background: transparent !important;
}
.file-tree.under-ws {
  margin: 0 0 8px 12px;
  padding-left: 4px;
  border-left: 1px solid var(--dap-gray-200);
}
.file-tree :deep(.el-tree-node__content) {
  height: 32px;
  padding-right: 4px;
}
.tree-node {
  display: flex;
  flex: 1 1 auto;
  width: 0;
  min-width: 0;
  align-items: center;
  gap: 4px;
  padding-right: 2px;
  box-sizing: border-box;
}
.tree-node .tree-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tree-node .row-actions {
  flex: 0 0 auto;
}
.mid-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--dap-gray-500);
}
.mid-empty .sub {
  font-size: 12px;
  margin-top: 6px;
}
.chat-body .bubble {
  display: block;
  width: fit-content;
  max-width: min(560px, 85%);
  margin-bottom: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--dap-gray-50);
  box-sizing: border-box;
  overflow-wrap: anywhere;
  word-break: break-word;
  user-select: text;
  -webkit-user-select: text;
  cursor: text;
}
.chat-body .bubble.user {
  margin-left: auto;
  background: var(--dap-primary-light);
  white-space: pre-wrap;
}
.chat-body .bubble.assistant {
  margin-right: auto;
}
.chat-body .typing {
  color: var(--dap-gray-500);
  font-size: 13px;
}
.chat-body .err {
  color: var(--el-color-danger);
  font-size: 13px;
}
.step-log {
  width: fit-content;
  max-width: min(560px, 85%);
  margin: 8px 0 12px;
  padding: 6px 10px;
  border-radius: 8px;
  border: 1px dashed var(--dap-gray-200, #e5e6eb);
  background: rgba(15, 23, 42, 0.02);
}
.step-log.live {
  border-color: rgba(22, 119, 255, 0.35);
  background: rgba(22, 119, 255, 0.04);
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
  color: var(--dap-gray-600, #646a73);
  line-height: 1.4;
}
.step-log-hd:hover {
  color: var(--dap-primary, #1677ff);
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
  color: var(--dap-gray-700, #4e5969);
  line-height: 1.45;
}
.step-dot {
  width: 6px;
  height: 6px;
  margin-top: 5px;
  border-radius: 50%;
  background: var(--dap-primary, #1677ff);
  flex-shrink: 0;
}
.step-msg {
  word-break: break-word;
}
.chat-ft {
  display: flex;
  gap: 8px;
  padding: 10px;
  border-top: 1px solid var(--dap-gray-200);
  align-items: flex-end;
}
.chat-ft .el-textarea {
  flex: 1;
}
.file-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.file-editor :deep(textarea) {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 13px;
}
.code-repo-list {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.code-repo-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.code-repo-row .el-input {
  flex: 1;
}
.session-config-row {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
}
.session-config-block {
  width: 100%;
}
.session-config-block-hd {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 4px;
}
.muted {
  color: var(--dap-gray-500);
  font-size: 12px;
}
.diff-wrap {
  min-height: 120px;
}
.empty {
  padding: 16px;
  color: var(--dap-gray-500);
  text-align: center;
}
</style>

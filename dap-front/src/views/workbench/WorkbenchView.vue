<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Folder, List, Notebook, Refresh } from '@element-plus/icons-vue'
import {
  addRequirementHandlers,
  approveRequirement,
  completeRequirement,
  deleteRequirement,
  developRequirement,
  getRequirement,
  listApproverCandidates,
  listRequirementWorkbench,
  planRequirement,
  rejectRequirement,
  removeRequirementHandler,
  type ApproverCandidate,
  type RequirementItem,
  type RequirementStatus,
  type WorkbenchScope,
} from '../../api/requirement'
import {
  changeIssueStatus,
  getIssue,
  listIssueWorkbench,
  type IssueItem,
  type IssueStatus,
  type IssueWorkbenchScope,
} from '../../api/issue'
import AuthMarkdownBody from '../../components/AuthMarkdownBody.vue'
import { listPositions, type PositionItem } from '../../api/position'
import { listProductVersions, type ProductVersionItem } from '../../api/product'
import {
  createWorkbenchWorkspace,
  createWorkbenchWorkspaceDir,
  createWorkbenchWorkspaceFile,
  importWorkbenchRequirement,
  listWorkbenchPersonalWorkspaces,
  listWorkbenchWorkspaceTree,
  type TenantWorkspaceItem,
  type WorkspaceFileContent,
  type WorkspaceNodeItem,
} from '../../api/tenantWorkspace'
import { useAuthStore } from '../../stores/auth'
import { formatDateTime } from '../../utils/date'
import WorkbenchSpacesPane from './WorkbenchSpacesPane.vue'
import WorkbenchWikiPane from './WorkbenchWikiPane.vue'

type LeftPanel = 'requirements' | 'workspaces' | 'wiki'

interface ReqTreeNode {
  key: string
  label: string
  type: 'product' | 'version' | 'req'
  requirement?: RequirementItem
  children?: ReqTreeNode[]
}

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const isCurrentHandler = computed(
  () =>
    detail.value != null &&
    detail.value.currentHandlerId != null &&
    detail.value.currentHandlerId === auth.user?.id,
)
const canManageReqHandlers = computed(() => {
  if (!detail.value || auth.user?.id == null) return false
  if (detail.value.status !== 'DEVELOPING') return false
  return (
    detail.value.createdBy === auth.user.id ||
    detail.value.currentHandlerId === auth.user.id
  )
})
interface HandlerBatchRow {
  key: number
  positionId?: number
  userIds: number[]
}
let handlerRowSeq = 1
const handlerBatchRows = ref<HandlerBatchRow[]>([{ key: handlerRowSeq++, positionId: undefined, userIds: [] }])
const handlerPositions = ref<PositionItem[]>([])
const handlerCandidates = ref<ApproverCandidate[]>([])
const handlerActionLoading = ref(false)
const handlerDialogVisible = ref(false)
const canDeleteRequirement = computed(
  () =>
    detail.value != null &&
    auth.user?.id != null &&
    detail.value.createdBy === auth.user.id &&
    auth.hasPermission('requirement:operate'),
)

const leftWidth = ref(300)
const dragging = ref(false)
const leftPanel = ref<LeftPanel>('requirements')
/** 任务面板内手风琴：需求列表 / 问题列表；null = 均收起 */
const taskSection = ref<'req' | 'issue' | null>('req')

const issues = ref<IssueItem[]>([])
const loadingIssues = ref(false)
const activeIssueId = ref<number | null>(null)
const issueDetail = ref<IssueItem | null>(null)
const loadingIssueDetail = ref(false)

const ISSUE_STATUS_TAG: Record<IssueStatus, '' | 'info' | 'success' | 'warning' | 'danger'> = {
  OPEN: 'warning',
  IN_PROGRESS: '',
  DONE: 'success',
}

/** 当前状态 → 下一状态操作（只展示一个推进按钮） */
const ISSUE_NEXT_ACTION: Record<IssueStatus, { status: IssueStatus; label: string } | null> = {
  OPEN: { status: 'IN_PROGRESS', label: '开始处理' },
  IN_PROGRESS: { status: 'DONE', label: '完成' },
  DONE: null,
}

const personalWorkspaces = ref<TenantWorkspaceItem[]>([])
const activeWorkspaceId = ref<number | null>(null)
const loadingWorkspaces = ref(false)
const wsDialogVisible = ref(false)
const importDialogVisible = ref(false)
const importTarget = ref<TenantWorkspaceItem | null>(null)
const importReqId = ref<number | undefined>()
const importCandidates = ref<RequirementItem[]>([])
const importLoading = ref(false)
const importSubmitting = ref(false)
const wsTree = ref<WorkspaceNodeItem[]>([])
const loadingWsTree = ref(false)
const activeFile = ref<WorkspaceFileContent | null>(null)
const selectedDirId = ref<number | null>(null)
const nodeDialogVisible = ref(false)
const nodeDialogMode = ref<'dir' | 'file'>('dir')
const nodeName = ref('')
const nodeSubmitting = ref(false)

const wsFormRef = ref<FormInstance>()
const wsSubmitting = ref(false)
const wsForm = reactive({
  name: '',
  description: '',
})
const wsRules: FormRules = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    {
      pattern: /^[a-zA-Z0-9_]+$/,
      message: '仅支持英文、数字、下划线',
      trigger: 'blur',
    },
  ],
}
const defaultWorkDir = computed(() => {
  const user = auth.user?.username || 'user'
  const name = wsForm.name.trim() || 'workspace'
  return `${user}/${name}`
})

const listScope = ref<WorkbenchScope>('todo')
const issueListScope = ref<IssueWorkbenchScope>('todo')
const requirements = ref<RequirementItem[]>([])
const activeId = ref<number | null>(null)
const detail = ref<RequirementItem | null>(null)
const loadingList = ref(false)
const loadingDetail = ref(false)
const actionLoading = ref(false)
const bootstrapped = ref(false)

const rejectVisible = ref(false)
const rejectReason = ref('')
const planVisible = ref(false)
const planFormRef = ref<FormInstance>()
const planVersions = ref<ProductVersionItem[]>([])
const planVersionsLoading = ref(false)
const planForm = reactive({
  releaseVersionId: undefined as number | undefined,
})
const planRules: FormRules = {
  releaseVersionId: [{ required: true, message: '请选择发布版本', trigger: 'change' }],
}

const STATUS_TAG: Record<RequirementStatus, '' | 'info' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'info',
  APPROVING: 'warning',
  REJECTED: 'danger',
  PENDING_PLAN: 'warning',
  PLANNING: '',
  DEVELOPING: '',
  RELEASED: 'success',
}

const reqTree = computed<ReqTreeNode[]>(() => buildReqTree(requirements.value))

function buildReqTree(items: RequirementItem[]): ReqTreeNode[] {
  const productMap = new Map<string, { productId: number; productName: string; versions: Map<string, RequirementItem[]> }>()
  for (const item of items) {
    const pKey = String(item.productId)
    let product = productMap.get(pKey)
    if (!product) {
      product = {
        productId: item.productId,
        productName: item.productName || `产品#${item.productId}`,
        versions: new Map(),
      }
      productMap.set(pKey, product)
    }
    const ver = item.productVersion || '未指定版本'
    if (!product.versions.has(ver)) product.versions.set(ver, [])
    product.versions.get(ver)!.push(item)
  }
  return Array.from(productMap.values()).map((p) => ({
    key: `p-${p.productId}`,
    label: p.productName,
    type: 'product' as const,
    children: Array.from(p.versions.entries()).map(([ver, reqs]) => ({
      key: `p-${p.productId}-v-${ver}`,
      label: ver,
      type: 'version' as const,
      children: reqs.map((r) => ({
        key: `r-${r.id}`,
        label: r.name,
        type: 'req' as const,
        requirement: r,
      })),
    })),
  }))
}

function onDragStart(e: MouseEvent) {
  dragging.value = true
  e.preventDefault()
  const onMove = (ev: MouseEvent) => {
    leftWidth.value = Math.min(420, Math.max(240, ev.clientX - 0))
  }
  const onUp = () => {
    dragging.value = false
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

async function bootstrap() {
  if (!auth.currentTenantId) {
    ElMessage.warning('请先选择租户')
    router.push('/home')
    return
  }
  bootstrapped.value = false
  try {
    if (leftPanel.value === 'workspaces') {
      await refreshWorkspaces()
    } else {
      const qIssue = Number(route.query.issueId)
      const qReq = Number(route.query.requirementId)
      if (Number.isFinite(qIssue) && qIssue > 0) {
        taskSection.value = 'issue'
        await refreshIssues(false)
        const hit = issues.value.find((i) => i.id === qIssue)
        if (hit) await selectIssue(hit)
        else await loadIssueDetail(qIssue)
      } else {
        taskSection.value = 'req'
        await refreshList(false)
        if (Number.isFinite(qReq) && qReq > 0) {
          const hit = requirements.value.find((r) => r.id === qReq)
          if (hit) await selectReq(hit)
          else {
            activeId.value = qReq
            await loadDetail(qReq)
          }
        }
      }
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '初始化失败')
  }
  bootstrapped.value = true
}

async function switchLeftPanel(panel: LeftPanel) {
  leftPanel.value = panel
  if (panel === 'requirements') {
    if (taskSection.value === 'issue') {
      await refreshIssues()
    } else {
      await refreshList()
    }
  } else if (panel === 'workspaces') {
    await refreshWorkspaces()
  }
}

async function refreshWorkspaces() {
  loadingWorkspaces.value = true
  try {
    personalWorkspaces.value = await listWorkbenchPersonalWorkspaces()
    if (
      activeWorkspaceId.value != null &&
      !personalWorkspaces.value.some((w) => w.id === activeWorkspaceId.value)
    ) {
      activeWorkspaceId.value = null
    }
  } catch (e) {
    personalWorkspaces.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载工作空间失败')
  } finally {
    loadingWorkspaces.value = false
  }
}

async function submitWorkspace() {
  await wsFormRef.value?.validate()
  wsSubmitting.value = true
  try {
    const res = await createWorkbenchWorkspace({
      name: wsForm.name.trim(),
    })
    ElMessage.success('已创建个人工作空间')
    wsDialogVisible.value = false
    await refreshWorkspaces()
    activeWorkspaceId.value = res.id
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    wsSubmitting.value = false
  }
}

async function submitImportRequirement() {
  if (!importTarget.value || importReqId.value == null) {
    ElMessage.warning('请选择需求')
    return
  }
  importSubmitting.value = true
  try {
    await importWorkbenchRequirement(importTarget.value.id, importReqId.value)
    ElMessage.success('已引入需求')
    importDialogVisible.value = false
    activeWorkspaceId.value = importTarget.value.id
    await refreshWsTree()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '引入失败')
  } finally {
    importSubmitting.value = false
  }
}

async function refreshWsTree() {
  if (activeWorkspaceId.value == null) {
    wsTree.value = []
    activeFile.value = null
    selectedDirId.value = null
    return
  }
  loadingWsTree.value = true
  try {
    wsTree.value = await listWorkbenchWorkspaceTree(activeWorkspaceId.value)
  } catch (e) {
    wsTree.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载目录失败')
  } finally {
    loadingWsTree.value = false
  }
}

async function submitCreateNode() {
  if (activeWorkspaceId.value == null) return
  const name = nodeName.value.trim()
  if (!name) {
    ElMessage.warning('请输入名称')
    return
  }
  nodeSubmitting.value = true
  try {
    const parentId = selectedDirId.value
    if (nodeDialogMode.value === 'dir') {
      await createWorkbenchWorkspaceDir(activeWorkspaceId.value, { parentId, displayName: name })
      ElMessage.success('已创建目录')
    } else {
      await createWorkbenchWorkspaceFile(activeWorkspaceId.value, {
        parentId,
        displayName: name,
        content: '',
      })
      ElMessage.success('已创建文件')
    }
    nodeDialogVisible.value = false
    await refreshWsTree()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    nodeSubmitting.value = false
  }
}

watch(activeWorkspaceId, () => {
  activeFile.value = null
  selectedDirId.value = null
  void refreshWsTree()
})

async function refreshList(keepSelection = true) {
  loadingList.value = true
  try {
    requirements.value = await listRequirementWorkbench(listScope.value)
    if (!keepSelection) {
      activeId.value = null
      detail.value = null
      return
    }
    if (activeId.value != null) {
      const still = requirements.value.find((i) => i.id === activeId.value)
      if (!still) {
        activeId.value = null
        detail.value = null
      } else {
        await loadDetail(activeId.value)
      }
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载需求列表失败')
  } finally {
    loadingList.value = false
  }
}

async function refreshIssues(keepSelection = true) {
  loadingIssues.value = true
  try {
    issues.value = await listIssueWorkbench(issueListScope.value)
    if (!keepSelection) {
      activeIssueId.value = null
      issueDetail.value = null
      return
    }
    if (activeIssueId.value != null) {
      const still = issues.value.find((i) => i.id === activeIssueId.value)
      if (!still) {
        activeIssueId.value = null
        issueDetail.value = null
      } else {
        await loadIssueDetail(activeIssueId.value)
      }
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载问题列表失败')
  } finally {
    loadingIssues.value = false
  }
}

async function loadIssueDetail(id: number) {
  loadingIssueDetail.value = true
  try {
    issueDetail.value = await getIssue(id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载问题详情失败')
    issueDetail.value = null
  } finally {
    loadingIssueDetail.value = false
  }
}

async function selectIssue(item: IssueItem) {
  taskSection.value = 'issue'
  activeIssueId.value = item.id
  activeId.value = null
  detail.value = null
  await loadIssueDetail(item.id)
}

function openTaskSection(section: 'req' | 'issue') {
  if (taskSection.value === section) {
    taskSection.value = null
    return
  }
  taskSection.value = section
  if (section === 'req') {
    void refreshList()
  } else {
    void refreshIssues()
  }
}

function openIssuePipeline() {
  if (!issueDetail.value) return
  router.push({
    name: 'issue-pipeline',
    params: { issueId: String(issueDetail.value.id) },
  })
}

async function loadDetail(id: number) {
  loadingDetail.value = true
  try {
    detail.value = await getRequirement(id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载详情失败')
    detail.value = null
  } finally {
    loadingDetail.value = false
  }
}

async function selectReq(item: RequirementItem) {
  taskSection.value = 'req'
  activeIssueId.value = null
  issueDetail.value = null
  activeId.value = item.id
  await loadDetail(item.id)
}

function onTreeNodeClick(data: ReqTreeNode) {
  if (data.type === 'req' && data.requirement) {
    selectReq(data.requirement)
  }
}

async function onScopeChange() {
  activeId.value = null
  detail.value = null
  await refreshList(false)
}

async function onIssueScopeChange() {
  activeIssueId.value = null
  issueDetail.value = null
  await refreshIssues(false)
}

async function doApprove() {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm(`确认通过「${detail.value.name}」？通过后进入待计划。`, '审批通过', {
      type: 'warning',
    })
    actionLoading.value = true
    await approveRequirement(detail.value.id)
    ElMessage.success('已通过')
    await refreshList()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e instanceof Error ? e.message : '操作失败')
    }
  } finally {
    actionLoading.value = false
  }
}

function openReject() {
  rejectReason.value = ''
  rejectVisible.value = true
}

async function confirmReject() {
  if (!detail.value) return
  const reason = rejectReason.value.trim()
  if (!reason) {
    ElMessage.warning('请填写驳回原因')
    return
  }
  actionLoading.value = true
  try {
    await rejectRequirement(detail.value.id, { reason })
    ElMessage.success('已驳回')
    rejectVisible.value = false
    await refreshList(false)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '驳回失败')
  } finally {
    actionLoading.value = false
  }
}

async function openPlan() {
  if (!detail.value?.productId) return
  planForm.releaseVersionId = undefined
  planVisible.value = true
  planVersionsLoading.value = true
  try {
    const versions = await listProductVersions(detail.value.productId)
    planVersions.value = versions.filter((v) => v.status === 'PLANNING')
    const matched = planVersions.value.find((v) => v.version === detail.value?.productVersion)
    planForm.releaseVersionId = matched?.id
  } catch (e) {
    planVersions.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载版本失败')
  } finally {
    planVersionsLoading.value = false
  }
}

async function confirmPlan() {
  if (!detail.value) return
  await planFormRef.value?.validate()
  if (planForm.releaseVersionId == null) return
  actionLoading.value = true
  try {
    await planRequirement(detail.value.id, {
      releaseVersionId: planForm.releaseVersionId,
    })
    ElMessage.success('计划已提交')
    planVisible.value = false
    await refreshList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '提交计划失败')
  } finally {
    actionLoading.value = false
  }
}

function openPipeline() {
  if (!detail.value?.id) return
  router.push({
    name: 'requirement-pipeline',
    params: { requirementId: String(detail.value.id) },
  })
}

async function doDevelop() {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm(
      '确认投入研发？状态将变为「研发中」，并自动创建产品共享空间与原始需求文件。',
      '投入研发',
      { type: 'warning' },
    )
  } catch {
    return
  }
  actionLoading.value = true
  try {
    await developRequirement(detail.value.id)
    ElMessage.success('已投入研发')
    await refreshList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    actionLoading.value = false
  }
}

async function doCompleteRequirement() {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm('确认将需求标记为「完成」？', '完成', { type: 'warning' })
  } catch {
    return
  }
  actionLoading.value = true
  try {
    await completeRequirement(detail.value.id)
    ElMessage.success('需求已完成')
    await refreshList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    actionLoading.value = false
  }
}

async function doChangeIssueStatus(status: IssueStatus) {
  if (!issueDetail.value || issueDetail.value.status === status) return
  const id = issueDetail.value.id
  actionLoading.value = true
  try {
    await changeIssueStatus(id, status)
    ElMessage.success('状态已更新')
    await refreshIssues(true)
    if (activeIssueId.value === id) {
      await loadIssueDetail(id)
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '状态更新失败')
  } finally {
    actionLoading.value = false
  }
}

const issueNextAction = computed(() => {
  if (!issueDetail.value) return null
  return ISSUE_NEXT_ACTION[issueDetail.value.status] ?? null
})

async function doDeleteRequirement() {
  if (!detail.value) return
  const name = detail.value.name
  const id = detail.value.id
  try {
    await ElMessageBox.confirm(
      `确定删除需求「${name}」？删除后不可恢复，关联任务一并清除。`,
      '删除需求',
      { type: 'warning' },
    )
  } catch {
    return
  }
  actionLoading.value = true
  try {
    await deleteRequirement(id)
    ElMessage.success('已删除')
    detail.value = null
    await refreshList(false)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  } finally {
    actionLoading.value = false
  }
}

async function loadHandlerCandidates() {
  if (!detail.value) return
  try {
    // 同一人可挂多个岗位，候选不再排除已在名单中的成员
    const candidates = await listApproverCandidates(detail.value.productId)
    handlerCandidates.value = candidates.candidates ?? []
  } catch {
    handlerCandidates.value = []
  }
}

async function openAddHandlerDialog() {
  handlerBatchRows.value = [{ key: handlerRowSeq++, positionId: undefined, userIds: [] }]
  await Promise.all([loadHandlerCandidates(), loadHandlerPositions()])
  if (!handlerPositions.value.length) {
    ElMessage.warning('请先在租户配置中维护岗位')
    return
  }
  if (!handlerCandidates.value.length) {
    ElMessage.info('暂无可添加的成员')
    return
  }
  handlerDialogVisible.value = true
}

async function loadHandlerPositions() {
  try {
    handlerPositions.value = await listPositions(false)
  } catch {
    handlerPositions.value = []
  }
}

function addHandlerBatchRow() {
  handlerBatchRows.value.push({ key: handlerRowSeq++, positionId: undefined, userIds: [] })
}

function removeHandlerBatchRow(key: number) {
  if (handlerBatchRows.value.length <= 1) {
    handlerBatchRows.value[0] = { key: handlerRowSeq++, positionId: undefined, userIds: [] }
    return
  }
  handlerBatchRows.value = handlerBatchRows.value.filter((r) => r.key !== key)
}

async function onAddReqHandlers() {
  if (!detail.value) return
  const items: { positionId: number; userIds: number[] }[] = []
  for (const row of handlerBatchRows.value) {
    if (row.positionId == null && !row.userIds.length) continue
    if (row.positionId == null) {
      ElMessage.warning('请为每一行选择岗位')
      return
    }
    if (!row.userIds.length) {
      ElMessage.warning('请为每一行至少选择一名成员')
      return
    }
    items.push({ positionId: row.positionId, userIds: [...row.userIds] })
  }
  if (!items.length) {
    ElMessage.warning('请至少添加一组处理人')
    return
  }
  handlerActionLoading.value = true
  try {
    await addRequirementHandlers(detail.value.id, { items })
    const n = items.reduce((s, it) => s + it.userIds.length, 0)
    ElMessage.success(`已添加 ${n} 名需求处理人`)
    handlerDialogVisible.value = false
    detail.value = await getRequirement(detail.value.id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '添加失败')
  } finally {
    handlerActionLoading.value = false
  }
}

async function onRemoveReqHandler(handler: {
  id?: number
  userId: number
  username?: string
  roleLabel?: string
}) {
  if (!detail.value || handler.id == null) return
  try {
    await ElMessageBox.confirm(
      `确认移除「${handler.roleLabel || ''} ${handler.username || handler.userId}」？`,
      '移除',
      { type: 'warning' },
    )
  } catch {
    return
  }
  handlerActionLoading.value = true
  try {
    await removeRequirementHandler(detail.value.id, handler.id)
    ElMessage.success('已移除')
    detail.value = await getRequirement(detail.value.id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '移除失败')
  } finally {
    handlerActionLoading.value = false
  }
}

watch(
  () => detail.value?.id && detail.value?.status,
  () => {
    if (detail.value?.status === 'DEVELOPING') {
      void loadHandlerCandidates()
    }
  },
)

watch(
  () => auth.currentTenantId,
  () => {
    bootstrap()
  },
)

onMounted(() => {
  void bootstrap()
})
async function refreshAllTasks() { await Promise.all([refreshList(), refreshIssues()]) }
</script>

<template>
  <div class="workbench">
    <div class="workbench-main">
    <template v-if="leftPanel === 'requirements'">
    <div class="pane pane-left" :style="{ width: leftWidth + 'px' }">
        <div class="pane-hd">
          <span class="pos-label">任务列表</span>
          <el-button
            circle
            size="small"
            :icon="Refresh"
            title="刷新"
            @click="
              taskSection === 'issue'
                ? refreshIssues()
                : taskSection === 'req'
                  ? refreshList()
                  : refreshAllTasks()
            "
          />
        </div>
        <div class="task-acc">
          <button
            type="button"
            class="task-acc-hd"
            :class="{ open: taskSection === 'req' }"
            @click="openTaskSection('req')"
          >
            需求列表
          </button>
          <div v-show="taskSection === 'req'" class="task-acc-bd">
            <div class="scope-bar">
              <el-radio-group v-model="listScope" size="small" @change="onScopeChange">
                <el-radio-button value="todo">待办</el-radio-button>
                <el-radio-button value="all">全部</el-radio-button>
              </el-radio-group>
            </div>
            <div class="pane-bd req-tree-wrap" v-loading="loadingList">
              <el-tree
                v-if="reqTree.length"
                :data="reqTree"
                node-key="key"
                default-expand-all
                highlight-current
                :expand-on-click-node="false"
                :current-node-key="activeId != null ? `r-${activeId}` : undefined"
                :props="{ label: 'label', children: 'children' }"
                @node-click="onTreeNodeClick"
              >
                <template #default="{ data }">
                  <div class="tree-node" :class="data.type">
                    <template v-if="data.type === 'req' && data.requirement">
                      <span class="tree-req-name">
                        <span v-if="data.requirement.reqNo" class="tree-req-no">
                          {{ data.requirement.reqNo }}
                        </span>
                        {{ data.label }}
                      </span>
                      <el-tag
                        :type="STATUS_TAG[data.requirement.status as RequirementStatus] ?? 'info'"
                        size="small"
                      >
                        {{ data.requirement.statusLabel || data.requirement.status }}
                      </el-tag>
                    </template>
                    <template v-else>
                      <span class="tree-group">{{ data.label }}</span>
                      <span v-if="data.type === 'version'" class="tree-hint">版本</span>
                    </template>
                  </div>
                </template>
              </el-tree>
              <div v-else-if="!loadingList" class="empty">
                {{ listScope === 'todo' ? '暂无待办需求' : '暂无需求' }}
              </div>
            </div>
          </div>

          <button
            type="button"
            class="task-acc-hd"
            :class="{ open: taskSection === 'issue' }"
            @click="openTaskSection('issue')"
          >
            问题列表
          </button>
          <div v-show="taskSection === 'issue'" class="task-acc-bd">
            <div class="scope-bar">
              <el-radio-group v-model="issueListScope" size="small" @change="onIssueScopeChange">
                <el-radio-button value="todo">待办</el-radio-button>
                <el-radio-button value="all">全部</el-radio-button>
              </el-radio-group>
            </div>
            <div class="pane-bd issue-list-wrap" v-loading="loadingIssues">
              <button
                v-for="item in issues"
                :key="item.id"
                type="button"
                class="issue-row"
                :class="{ active: activeIssueId === item.id }"
                @click="selectIssue(item)"
              >
                <span class="issue-row-main">
                  <span class="tree-req-no">{{ item.issueNo }}</span>
                  <span class="issue-desc">{{ item.description }}</span>
                </span>
                <el-tag :type="ISSUE_STATUS_TAG[item.status] ?? 'info'" size="small">
                  {{ item.statusLabel || item.status }}
                </el-tag>
              </button>
              <div v-if="!loadingIssues && !issues.length" class="empty">
                {{ issueListScope === 'todo' ? '暂无待办问题' : '暂无问题' }}
              </div>
            </div>
          </div>
        </div>
    </div>

    <div
      class="splitter"
      :class="{ active: dragging }"
      @mousedown="onDragStart($event)"
    />

    <div
      class="pane pane-mid"
      v-loading="issueDetail ? loadingIssueDetail : loadingDetail"
    >
      <template v-if="issueDetail">
        <div class="pane-hd req-hd">
          <span class="title">
            <span class="title-no">{{ issueDetail.issueNo }}</span>
            问题详情
          </span>
          <el-tag :type="ISSUE_STATUS_TAG[issueDetail.status] ?? 'info'" size="small">
            {{ issueDetail.statusLabel || issueDetail.status }}
          </el-tag>
          <div class="req-hd-actions">
            <el-button
              v-if="issueNextAction"
              size="small"
              type="primary"
              :loading="actionLoading"
              @click="doChangeIssueStatus(issueNextAction.status)"
            >
              {{ issueNextAction.label }}
            </el-button>
            <el-button size="small" type="primary" @click="openIssuePipeline">
              自动化流水
            </el-button>
          </div>
        </div>
        <div class="pane-bd detail-bd">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="产品">
              {{ issueDetail.productName || issueDetail.productId }}
            </el-descriptions-item>
            <el-descriptions-item label="关联需求">
              {{
                issueDetail.requirementNo
                  ? `${issueDetail.requirementNo} ${issueDetail.requirementName || ''}`
                  : '—'
              }}
            </el-descriptions-item>
            <el-descriptions-item label="处理人">
              {{ issueDetail.handlerUsername || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="问题描述">
              <div class="pre-wrap">{{ issueDetail.description }}</div>
            </el-descriptions-item>
            <el-descriptions-item label="实现步骤">
              <AuthMarkdownBody
                v-if="issueDetail.steps"
                :content="issueDetail.steps"
              />
              <span v-else>—</span>
            </el-descriptions-item>
            <el-descriptions-item label="预期结果">
              <div class="pre-wrap">{{ issueDetail.expectedResult || '—' }}</div>
            </el-descriptions-item>
            <el-descriptions-item label="解决方案">
              <div class="pre-wrap">{{ issueDetail.solution || '—' }}</div>
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </template>
      <template v-else-if="detail">
        <div class="pane-hd req-hd">
          <span class="title">
            <span v-if="detail.reqNo" class="title-no">{{ detail.reqNo }}</span>
            {{ detail.name }}
          </span>
          <el-tag :type="STATUS_TAG[detail.status] ?? 'info'" size="small">
            {{ detail.statusLabel || detail.status }}
          </el-tag>
          <div class="req-hd-actions">
            <template v-if="isCurrentHandler">
              <template v-if="detail.status === 'APPROVING'">
                <el-button size="small" type="danger" plain :loading="actionLoading" @click="openReject">
                  驳回
                </el-button>
                <el-button size="small" type="primary" :loading="actionLoading" @click="doApprove">
                  通过
                </el-button>
              </template>
              <template v-else-if="detail.status === 'PENDING_PLAN'">
                <el-button size="small" type="primary" :loading="actionLoading" @click="openPlan">
                  提交计划
                </el-button>
              </template>
              <template v-else-if="detail.status === 'PLANNING'">
                <el-button size="small" type="primary" :loading="actionLoading" @click="doDevelop">
                  投入研发
                </el-button>
              </template>
              <template v-else-if="detail.status === 'DEVELOPING'">
                <el-button
                  size="small"
                  type="success"
                  :loading="actionLoading"
                  @click="doCompleteRequirement"
                >
                  完成
                </el-button>
              </template>
            </template>
            <el-button
              size="small"
              type="primary"
              plain
              @click="openPipeline"
            >
              自动化流水线
            </el-button>
            <el-button
              v-if="canDeleteRequirement"
              size="small"
              type="danger"
              plain
              :loading="actionLoading"
              @click="doDeleteRequirement"
            >
              删除
            </el-button>
          </div>
        </div>
        <div class="pane-bd detail-bd">
          <div class="detail-grid">
            <div class="field">
              <label>需求编号</label>
              <span>{{ detail.reqNo || '—' }}</span>
            </div>
            <div class="field">
              <label>关联产品</label>
              <span>{{ detail.productName }} / {{ detail.productVersion }}</span>
            </div>
            <div class="field">
              <label>申请人</label>
              <span>{{ detail.createdByUsername || '—' }}</span>
            </div>
            <div class="field">
              <label>审批人</label>
              <span>{{ detail.approverUsername || '—' }}</span>
            </div>
            <div class="field">
              <label>当前处理人</label>
              <span>{{ detail.currentHandlerUsername || '—' }}</span>
            </div>
            <div v-if="detail.releaseVersion" class="field">
              <label>发布版本</label>
              <span>{{ detail.releaseVersion }}</span>
            </div>
            <div class="field full">
              <label>需求描述</label>
              <pre class="desc">{{ detail.description }}</pre>
            </div>
          </div>

          <div v-if="detail.status === 'DEVELOPING'" class="handlers-block">
            <div class="handlers-hd">
              <h3>需求处理人</h3>
              <button
                v-if="canManageReqHandlers"
                type="button"
                class="handler-add-icon"
                title="添加处理人"
                @click="openAddHandlerDialog"
              >
                +
              </button>
            </div>
            <div class="handler-tags">
              <el-tag
                v-for="h in detail.handlers || []"
                :key="h.id ?? `${h.userId}-${h.positionId ?? 'owner'}`"
                class="handler-tag"
                :type="h.currentHandler && !h.positionId ? 'primary' : 'info'"
                :closable="canManageReqHandlers && !(h.currentHandler && !h.positionId)"
                :disable-transitions="true"
                @close="onRemoveReqHandler(h)"
              >
                <span v-if="h.roleLabel" class="handler-role">{{ h.roleLabel }}</span>
                {{ h.username || `#${h.userId}` }}
                <span v-if="h.currentHandler && !h.positionId" class="handler-badge">当前</span>
              </el-tag>
              <span v-if="!(detail.handlers || []).length" class="muted">暂无处理人</span>
            </div>
          </div>

        </div>

      </template>
      <div v-else class="mid-empty">
        <p v-if="taskSection === 'issue'">从左侧选择一条问题</p>
        <p v-else>从左侧选择一条需求</p>
        <p class="sub">
          {{
            taskSection === 'issue'
              ? '查看详情后可启动问题单自动化流水'
              : '按产品 / 版本浏览；审批与计划在此完成'
          }}
        </p>
      </div>

    </div>
    </template>
    <WorkbenchWikiPane v-else-if="leftPanel === 'wiki'" />
    <WorkbenchSpacesPane v-else />
    </div>

    <nav class="wb-toolbar" aria-label="工作台工具栏">
      <button
        type="button"
        class="wb-tool"
        :class="{ active: leftPanel === 'requirements' }"
        title="任务列表"
        @click="switchLeftPanel('requirements')"
      >
        <el-icon :size="20"><List /></el-icon>
        <span>任务</span>
      </button>
      <button
        type="button"
        class="wb-tool"
        :class="{ active: leftPanel === 'workspaces' }"
        title="工作空间"
        @click="switchLeftPanel('workspaces')"
      >
        <el-icon :size="20"><Folder /></el-icon>
        <span>工作空间</span>
      </button>
      <button
        type="button"
        class="wb-tool"
        :class="{ active: leftPanel === 'wiki' }"
        title="Work Wiki"
        @click="switchLeftPanel('wiki')"
      >
        <el-icon :size="20"><Notebook /></el-icon>
        <span>Wiki</span>
      </button>
    </nav>

    <el-dialog v-model="wsDialogVisible" title="新建个人工作空间" width="440px" destroy-on-close>
      <el-form ref="wsFormRef" :model="wsForm" :rules="wsRules" label-width="88px">
        <el-form-item label="名称" prop="name">
          <el-input
            v-model="wsForm.name"
            maxlength="128"
            placeholder="英文、数字、下划线"
          />
        </el-form-item>
        <el-form-item label="所有者">
          <el-input :model-value="auth.user?.username || ''" disabled />
        </el-form-item>
        <el-form-item label="工作目录">
          <el-input :model-value="defaultWorkDir" disabled />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="wsForm.description"
            type="textarea"
            :rows="2"
            maxlength="500"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="wsDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="wsSubmitting" @click="submitWorkspace">
          创建
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="nodeDialogVisible"
      :title="nodeDialogMode === 'dir' ? '新建目录' : '新建 Markdown 文件'"
      width="420px"
      destroy-on-close
    >
      <el-form label-width="72px" @submit.prevent>
        <el-form-item label="名称">
          <el-input
            v-model="nodeName"
            maxlength="200"
            :placeholder="nodeDialogMode === 'dir' ? '目录名称' : '文件名，默认 .md'"
            @keyup.enter="submitCreateNode"
          />
        </el-form-item>
        <el-form-item label="位置">
          <span class="muted">
            {{ selectedDirId != null ? '当前选中的目录下' : '工作空间根目录' }}
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="nodeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="nodeSubmitting" @click="submitCreateNode">
          创建
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="importDialogVisible"
      :title="`引入需求${importTarget ? ' · ' + importTarget.name : ''}`"
      width="520px"
      destroy-on-close
    >
      <div v-loading="importLoading">
        <el-select
          v-model="importReqId"
          filterable
          placeholder="选择待办需求"
          style="width: 100%"
        >
          <el-option
            v-for="r in importCandidates"
            :key="r.id"
            :label="`${r.reqNo || '—'} · ${r.name}`"
            :value="r.id"
          />
        </el-select>
        <div v-if="!importLoading && !importCandidates.length" class="form-tip">
          暂无待办需求
        </div>
      </div>
      <template #footer>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="importSubmitting"
          :disabled="importReqId == null"
          @click="submitImportRequirement"
        >
          引入
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rejectVisible" title="驳回需求" width="480px" destroy-on-close>
      <el-input
        v-model="rejectReason"
        type="textarea"
        :rows="4"
        maxlength="1000"
        show-word-limit
        placeholder="请填写驳回原因"
      />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="actionLoading" @click="confirmReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="planVisible" title="提交计划" width="480px" destroy-on-close>
      <el-form
        ref="planFormRef"
        v-loading="planVersionsLoading"
        :model="planForm"
        :rules="planRules"
        label-width="96px"
      >
        <el-form-item label="发布版本" prop="releaseVersionId">
          <el-select
            v-model="planForm.releaseVersionId"
            filterable
            placeholder="选择计划中的版本"
            style="width: 100%"
          >
            <el-option
              v-for="v in planVersions"
              :key="v.id"
              :label="
                v.planReleaseAt
                  ? `${v.version}（计划 ${formatDateTime(v.planReleaseAt)}）`
                  : v.version
              "
              :value="v.id"
            />
          </el-select>
          <div v-if="!planVersionsLoading && !planVersions.length" class="form-tip">
            暂无「计划中」版本，请先在产品管理中维护
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" @click="confirmPlan">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="handlerDialogVisible"
      title="批量添加需求处理人"
      width="640px"
      destroy-on-close
    >
      <p class="form-tip" style="margin-top: 0">
        每行一组：岗位 + 成员（可多选）。同一人可挂多个岗位，将分别展示；不同人请在成员里多选或分行添加。
      </p>
      <div class="handler-batch-list">
        <div v-for="row in handlerBatchRows" :key="row.key" class="handler-batch-row">
          <el-select
            v-model="row.positionId"
            filterable
            placeholder="岗位"
            style="width: 140px; flex-shrink: 0"
          >
            <el-option
              v-for="p in handlerPositions"
              :key="p.id"
              :label="p.name"
              :value="p.id"
            />
          </el-select>
          <el-select
            v-model="row.userIds"
            multiple
            filterable
            placeholder="选择成员（可多选）"
            style="flex: 1; min-width: 0"
          >
            <el-option
              v-for="u in handlerCandidates"
              :key="u.id"
              :label="u.username"
              :value="u.id"
            />
          </el-select>
          <el-button link type="danger" @click="removeHandlerBatchRow(row.key)">删</el-button>
        </div>
      </div>
      <el-button class="handler-batch-add" text type="primary" @click="addHandlerBatchRow">
        + 再加一组
      </el-button>
      <template #footer>
        <el-button @click="handlerDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="handlerActionLoading" @click="onAddReqHandlers">
          确定添加
        </el-button>
      </template>
    </el-dialog>

  </div>
</template>

<style scoped>
.workbench {
  height: calc(100vh - 56px);
  display: flex;
  flex-direction: column;
  background: var(--dap-gray-50);
}
.workbench-main {
  flex: 1;
  min-height: 0;
  display: flex;
}
.pane {
  background: var(--dap-gray-0);
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.pane-left {
  border-right: 1px solid var(--dap-gray-200);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.pane-mid {
  flex: 1;
  min-width: 360px;
  position: relative;
}
.wb-toolbar {
  height: 52px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-top: 1px solid var(--dap-gray-200);
  background: var(--dap-gray-0);
}
.wb-tool {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-width: 72px;
  height: 44px;
  padding: 4px 12px;
  border: none;
  border-radius: var(--dap-radius-sm);
  background: transparent;
  color: var(--dap-gray-600);
  cursor: pointer;
  font: inherit;
  font-size: 11px;
}
.wb-tool:hover {
  background: var(--dap-gray-50);
  color: var(--dap-gray-900);
}
.wb-tool.active {
  color: var(--dap-primary);
  background: var(--dap-primary-light);
}
.ws-scope {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.ws-picker {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px 8px;
}
.ws-opt-dir {
  float: right;
  margin-left: 12px;
  color: var(--dap-gray-500);
  font-size: 12px;
}
.ws-tree-pane {
  padding: 0 8px 12px;
  overflow: auto;
}
.ws-tree-hint {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 11px;
  color: var(--dap-gray-500);
}
.ws-item-body .ws-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  padding: 8px 12px;
  border-bottom: 1px solid var(--dap-gray-200);
  background: var(--dap-gray-50);
  font-size: 12px;
}
.session-bar .pill,
.session-chips .pill {
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--dap-gray-100);
  color: var(--dap-gray-600);
}
.session-bar .pill.write,
.session-chips .pill.write {
  background: #f6ffed;
  color: #389e0d;
}
.session-bar .pill.read,
.session-chips .pill.read {
  background: var(--dap-primary-light);
  color: var(--dap-primary);
}
.session-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.archive-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  min-height: 240px;
}
.archive-title {
  font-weight: 600;
  margin-bottom: 8px;
}
.file-empty {
  justify-content: center;
}
.assoc-list {
  max-height: 360px;
  overflow: auto;
}
.assoc-item {
  display: block;
  padding: 8px 4px;
  border-bottom: 1px solid var(--dap-gray-100);
  cursor: pointer;
}
.assoc-item :deep(.el-checkbox) {
  width: 100%;
  height: auto;
  align-items: flex-start;
}
.assoc-item :deep(.el-checkbox__label) {
  width: 100%;
}
.muted {
  color: var(--dap-gray-500);
}
.form-tip {
  margin-top: 8px;
  color: var(--dap-gray-500);
  font-size: 12px;
}
.ws-mid {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  overflow: auto;
  flex: 1;
  min-height: 0;
}
.ws-tree {
  background: transparent;
}
.ws-tree-node {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.file-view-hd {
  display: flex;
  align-items: center;
  gap: 10px;
}
.file-view-name {
  font-weight: 600;
}
.file-view-body {
  flex: 1;
  overflow: auto;
  padding: 8px 4px 16px;
}
.ws-item-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.ws-name {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ws-dir {
  font-size: 12px;
  color: var(--dap-gray-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.splitter {
  width: 4px;
  cursor: col-resize;
  flex-shrink: 0;
}
.splitter:hover,
.splitter.active {
  background: var(--dap-primary-light);
}
.pane-hd {
  height: 48px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid var(--dap-gray-200);
  flex-shrink: 0;
}
.pane-hd .title {
  flex: 1;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.req-hd {
  height: auto;
  min-height: 48px;
  flex-wrap: wrap;
  padding: 8px 12px;
}
.req-hd .title {
  flex: 0 1 auto;
  max-width: 42%;
}
.req-hd-actions {
  margin-left: auto;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.pane-bd {
  flex: 1;
  overflow: auto;
  min-height: 0;
}
.pos-label {
  flex: 1;
  font-weight: 500;
  color: var(--dap-gray-900);
}
.scope-bar {
  padding: 8px 12px;
  border-bottom: 1px solid var(--dap-gray-100);
}
.req-tree-wrap {
  padding: 8px;
}
.tree-node {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding-right: 4px;
  min-width: 0;
}
.tree-group {
  font-weight: 500;
  color: var(--dap-gray-900);
}
.tree-hint {
  font-size: 11px;
  color: var(--dap-gray-500);
}
.tree-req-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--dap-gray-800);
}
.tree-req-no {
  margin-right: 6px;
  color: var(--dap-gray-500);
  font-size: 12px;
}
.title-no {
  margin-right: 8px;
  color: var(--dap-gray-500);
  font-weight: 500;
  font-size: 13px;
}
.empty,
.mid-empty {
  color: var(--dap-gray-500);
  padding: 32px 24px;
  text-align: center;
}
.mid-empty .sub {
  margin-top: 8px;
  font-size: 13px;
}
.empty-inline {
  color: var(--dap-gray-500);
  font-size: 13px;
  padding: 4px 0;
}
.detail-bd {
  padding: 16px 20px 24px;
}
.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px 20px;
  margin-bottom: 20px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.field.full {
  grid-column: 1 / -1;
}
.field label {
  font-size: 12px;
  color: var(--dap-gray-500);
}
.field span,
.desc {
  font-size: 14px;
  color: var(--dap-gray-900);
}
.desc {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  line-height: 1.6;
  background: var(--dap-gray-50);
  padding: 12px;
  border-radius: var(--dap-radius-sm);
}
.handlers-block {
  margin-bottom: 24px;
}
.handlers-hd {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
}
.handlers-hd h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}
.handler-add-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--dap-primary);
  font-size: 18px;
  font-weight: 500;
  line-height: 1;
  cursor: pointer;
}
.handler-add-icon:hover {
  background: var(--dap-primary-light, #eff6ff);
}
.handler-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.handler-tag {
  margin: 0;
}
.handler-batch-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.handler-batch-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.handler-batch-add {
  margin-top: 8px;
  padding: 0;
}
.handler-role {
  margin-right: 4px;
  font-size: 12px;
  opacity: 0.9;
}
.handler-badge {
  margin-left: 4px;
  font-size: 11px;
  opacity: 0.85;
}
.timeline-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: var(--dap-gray-50);
  padding: 10px 12px;
  border-radius: var(--dap-radius-sm);
  cursor: pointer;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  color: var(--dap-gray-900);
  text-align: left;
}
.timeline-toggle:hover {
  background: var(--dap-gray-100);
}
.timeline-count {
  font-weight: 400;
  font-size: 12px;
  color: var(--dap-gray-500);
}
.timeline-arrow {
  margin-left: auto;
  font-weight: 400;
  font-size: 12px;
  color: var(--dap-primary);
}
.timeline-body {
  margin-top: 12px;
  padding-left: 4px;
}
.submit-hint {
  margin: 0 0 16px;
  color: var(--dap-gray-700);
  font-size: 14px;
  line-height: 1.5;
}
.handler-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.handler-tag {
  max-width: 100%;
}
.handler-badge {
  margin-left: 4px;
  font-size: 11px;
  opacity: 0.75;
}
.timeline-block h3 {
  margin-bottom: 12px;
}
.flow-title {
  font-weight: 500;
}
.flow-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--dap-gray-500);
}
.flow-comment {
  margin-top: 6px;
  font-size: 13px;
  color: var(--dap-gray-700);
  white-space: pre-wrap;
}
.action-bar {
  border-top: 1px solid var(--dap-gray-200);
  padding: 12px 16px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-shrink: 0;
}
.action-hint {
  font-size: 13px;
  color: var(--dap-gray-500);
}
.form-tip {
  margin-top: 6px;
  color: var(--dap-gray-500);
  font-size: 12px;
}
.task-acc {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.task-acc-hd {
  flex-shrink: 0;
  border: none;
  border-bottom: 1px solid var(--dap-gray-200);
  background: var(--dap-gray-50);
  padding: 10px 14px;
  text-align: left;
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  color: var(--dap-gray-700);
  cursor: pointer;
}
.task-acc-hd.open {
  color: var(--dap-primary);
  background: var(--dap-primary-light);
}
.task-acc-bd {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.issue-list-wrap {
  padding: 8px;
  overflow: auto;
}
.issue-row {
  width: 100%;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  border: none;
  background: transparent;
  padding: 8px 10px;
  border-radius: var(--dap-radius-sm);
  cursor: pointer;
  text-align: left;
  font: inherit;
}
.issue-row:hover {
  background: var(--dap-gray-50);
}
.issue-row.active {
  background: var(--dap-primary-light);
}
.issue-row-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.issue-desc {
  font-size: 13px;
  color: var(--dap-gray-800);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pre-wrap {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>

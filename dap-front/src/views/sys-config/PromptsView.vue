<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createPrompt,
  deletePrompt,
  ensurePreReviewPrompts,
  listPrompts,
  updatePrompt,
  type PromptItem,
} from '../../api/prompt'
import { listPositions, type PositionItem } from '../../api/position'
import {
  listSessionTaskTypes,
  type SessionTaskTypeOption,
} from '../../api/tenantWorkspace'
import MarkdownBody from '../../components/MarkdownBody.vue'
import { useAuthStore } from '../../stores/auth'
import {
  PIPELINE_PROMPT_NODES,
  PIPELINE_PROMPT_GROUPS,
  promptGroupForTask,
  type PipelinePromptGroupId,
  type PipelinePromptNode,
  type PipelinePromptSlot,
} from './pipelinePromptNodes'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
function backToPositions() {
  const positionId = selected.value?.positionId ?? route.query.positionId
  router.push({ path: '/tenant-config/positions', query: positionId ? { positionId: String(positionId) } : {} })
}
const canOperate = computed(() => auth.hasPermission('prompt:operate'))

const loading = ref(false)
const ensuring = ref(false)
const rows = ref<PromptItem[]>([])
const positions = ref<PositionItem[]>([])
const taskTypes = ref<SessionTaskTypeOption[]>([])

const selectedNodeId = ref(PIPELINE_PROMPT_NODES[0]?.id ?? '')
const selectedNode = computed(
  () => selectedNodeId.value === 'other'
    ? { id: 'other', name: '其他提示词', slots: [] as PipelinePromptSlot[] }
    : PIPELINE_PROMPT_NODES.find((n) => n.id === selectedNodeId.value) ?? null,
)
const selectedGroup = computed(() => PIPELINE_PROMPT_GROUPS.find(g => g.nodes.some(n => n.id === selectedNodeId.value)) ?? PIPELINE_PROMPT_GROUPS[0]!)

const selectedId = ref<number | null>(null)
const selected = computed(() => rows.value.find((r) => r.id === selectedId.value) ?? null)

const viewMode = ref<'preview' | 'edit'>('preview')
const editingContent = ref('')
const savingContent = ref(false)

const metaDialog = ref(false)
const metaMode = ref<'create' | 'edit'>('create')
const metaFormRef = ref<FormInstance>()
const metaSubmitting = ref(false)
const metaForm = reactive({
  code: '',
  name: '',
  positionId: undefined as number | undefined,
  taskTypeCode: undefined as string | undefined,
})
const metaRules: FormRules = {
  code: [{ required: true, message: '请输入编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  positionId: [{ required: true, message: '请选择岗位', trigger: 'change' }],
  taskTypeCode: [{ required: true, message: '请选择任务', trigger: 'change' }],
}

const taskTypeGroups = computed(() => PIPELINE_PROMPT_GROUPS.map(g => ({
  ...g, types: taskTypes.value.filter(t => promptGroupForTask(t.code) === g.id),
})))

interface NodeSlotView {
  slot: PipelinePromptSlot
  prompt: PromptItem | null
  missingPosition: boolean
}

function slotView(slot: PipelinePromptSlot): NodeSlotView {
    const pos = positions.value.find(
      (p) => (p.code || '').toUpperCase() === slot.positionCode.toUpperCase(),
    )
    const prompt =
      rows.value.find(
        (r) =>
          (r.taskTypeCode || '').toUpperCase() === slot.taskTypeCode.toUpperCase() &&
          ((r.positionCode || '').toUpperCase() === slot.positionCode.toUpperCase() ||
            (pos != null && r.positionId === pos.id)),
      ) ?? null
    return { slot, prompt, missingPosition: !pos }
}
const otherPrompts = computed(() => rows.value.filter(row => !PIPELINE_PROMPT_NODES.some(node => node.slots.some(slot => slotView(slot).prompt?.id === row.id))))
const nodeSlots = computed((): NodeSlotView[] => selectedNodeId.value === 'other'
  ? otherPrompts.value.map(prompt => ({ prompt, missingPosition: !positions.value.some(p => p.id === prompt.positionId), slot: { taskTypeCode: prompt.taskTypeCode, positionCode: prompt.positionCode || String(prompt.positionId), roleLabel: prompt.positionName || prompt.name } }))
  : selectedNode.value?.slots.map(slotView) ?? [])

const nodeConfiguredCount = computed(
  () => nodeSlots.value.filter((x) => x.prompt?.content?.trim()).length,
)

const hasUnsavedContent = computed(() => viewMode.value === 'edit' && editingContent.value !== (selected.value?.content ?? ''))
async function confirmDiscard() {
  if (savingContent.value || metaSubmitting.value || ensuring.value) return false
  if (!hasUnsavedContent.value) return true
  try {
    await ElMessageBox.confirm('当前提示词有未保存的修改，确定放弃修改并继续？', '未保存的修改', {
      type: 'warning', confirmButtonText: '放弃修改', cancelButtonText: '继续编辑',
    })
    cancelEdit()
    return true
  } catch { return false }
}
onBeforeRouteLeave(() => confirmDiscard())
function beforeUnload(event: BeforeUnloadEvent) {
  if (hasUnsavedContent.value) { event.preventDefault(); event.returnValue = '' }
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))

watch(selectedId, () => {
  viewMode.value = 'preview'
  editingContent.value = ''
})

watch(selectedNodeId, () => {
  const first = nodeSlots.value.find((x) => x.prompt)?.prompt
  selectedId.value = first?.id ?? null
  viewMode.value = 'preview'
})

watch(nodeSlots, (slots) => {
  if (selectedId.value != null && slots.some((x) => x.prompt?.id === selectedId.value)) return
  selectedId.value = slots.find((x) => x.prompt)?.prompt?.id ?? null
})

async function loadOptions() {
  const [pos, types] = await Promise.all([listPositions(false), listSessionTaskTypes()])
  positions.value = pos
  taskTypes.value = types
}

async function load() {
  loading.value = true
  try {
    await loadOptions()
    rows.value = await listPrompts()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载提示词失败')
  } finally {
    loading.value = false
  }
}

async function onEnsurePipeline() {
  if (!(await confirmDiscard())) return
  ensuring.value = true
  try {
    const res = await ensurePreReviewPrompts()
    const parts: string[] = []
    if (res.created?.length) parts.push(`新建 ${res.created.length} 条`)
    if (res.skipped?.length) parts.push(`已存在跳过 ${res.skipped.length} 条`)
    if (res.missingPositions?.length) {
      parts.push(
        `仍缺岗位编码：${res.missingPositions.join('、')}（请到 Agent 岗位补齐）`,
      )
    }
    ElMessage.success(parts.length ? parts.join('；') : '无需变更')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '初始化失败')
  } finally {
    ensuring.value = false
  }
}

async function selectNode(node: PipelinePromptNode) {
  if (node.id === selectedNodeId.value || !(await confirmDiscard())) return
  selectedNodeId.value = node.id
}

async function selectGroup(id: PipelinePromptGroupId) {
  const first = PIPELINE_PROMPT_GROUPS.find(g => g.id === id)?.nodes[0]
  if (first) await selectNode(first)
}

async function selectOther() {
  if (selectedNodeId.value !== 'other' && await confirmDiscard()) selectedNodeId.value = 'other'
}

async function focusPrompt(id: number) {
  const node = PIPELINE_PROMPT_NODES.find(n => n.slots.some(slot => slotView(slot).prompt?.id === id))
  selectedNodeId.value = node?.id ?? 'other'
  await nextTick()
  selectedId.value = id
  await nextTick()
}

async function selectSlotPrompt(item: NodeSlotView) {
  if (item.prompt?.id === selectedId.value) return
  if (!(await confirmDiscard())) return
  if (item.prompt) {
    selectedId.value = item.prompt.id
    return
  }
  if (!canOperate.value) {
    ElMessage.warning('尚未配置该岗位提示词，请先初始化或新增')
    return
  }
  openCreateForSlot(item.slot)
}

function onTaskTypeChange(code: string | undefined) {
  if (!code || metaForm.positionId != null) return
  const t = taskTypes.value.find((x) => x.code === code)
  if (!t?.defaultPositionCode) return
  const match = positions.value.find(
    (p) => (p.code || '').toUpperCase() === t.defaultPositionCode.toUpperCase(),
  )
  if (match) metaForm.positionId = match.id
}

function openCreateForSlot(slot: PipelinePromptSlot) {
  metaMode.value = 'create'
  metaForm.code = `${slot.taskTypeCode}_${slot.positionCode}`
  metaForm.name = `${selectedNode.value?.name ?? ''}·${slot.roleLabel}`
  metaForm.taskTypeCode = slot.taskTypeCode
  const match = positions.value.find(
    (p) => (p.code || '').toUpperCase() === slot.positionCode.toUpperCase(),
  )
  metaForm.positionId = match?.id
  metaDialog.value = true
}

async function openCreate() {
  if (!(await confirmDiscard())) return
  const slot = selectedNode.value?.slots[0]
  if (slot) {
    openCreateForSlot(slot)
    return
  }
  metaMode.value = 'create'
  metaForm.code = ''
  metaForm.name = ''
  metaForm.positionId = undefined
  metaForm.taskTypeCode = undefined
  metaDialog.value = true
}

function openEditMeta() {
  if (!selected.value) return
  metaMode.value = 'edit'
  metaForm.code = selected.value.code
  metaForm.name = selected.value.name
  metaForm.positionId = selected.value.positionId
  metaForm.taskTypeCode = selected.value.taskTypeCode
  metaDialog.value = true
}

function buildPayload(content: string) {
  return {
    code: metaForm.code.trim(),
    name: metaForm.name.trim(),
    positionId: metaForm.positionId!,
    taskTypeCode: metaForm.taskTypeCode!,
    content,
  }
}

async function submitMeta() {
  if (!await metaFormRef.value?.validate().catch(() => false)) return
  metaSubmitting.value = true
  try {
    if (metaMode.value === 'create') {
      const res = await createPrompt(buildPayload(''))
      ElMessage.success('已创建')
      await load()
      await focusPrompt(res.id)
      enterEdit()
    } else if (selected.value) {
      const id = selected.value.id
      await updatePrompt(id, buildPayload(selected.value.content ?? ''))
      ElMessage.success('已保存')
      await load()
      await focusPrompt(id)
    }
    metaDialog.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    metaSubmitting.value = false
  }
}

async function removeRow() {
  if (!selected.value) return
  try {
    await ElMessageBox.confirm(`确定删除提示词「${selected.value.name}」？`, '提示', {
      type: 'warning',
    })
    await deletePrompt(selected.value.id)
    ElMessage.success('已删除')
    selectedId.value = null
    await load()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败')
    }
  }
}

function enterEdit() {
  editingContent.value = selected.value?.content ?? ''
  viewMode.value = 'edit'
}

function cancelEdit() {
  viewMode.value = 'preview'
  editingContent.value = ''
}

async function saveContent() {
  if (!selected.value) return
  savingContent.value = true
  try {
    await updatePrompt(selected.value.id, {
      code: selected.value.code,
      name: selected.value.name,
      positionId: selected.value.positionId,
      taskTypeCode: selected.value.taskTypeCode,
      content: editingContent.value,
    })
    ElMessage.success('已保存')
    await load()
    viewMode.value = 'preview'
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    savingContent.value = false
  }
}

function taskTypeName(code: string) {
  return taskTypes.value.find((t) => t.code === code)?.name || code
}

function nodeConfigured(node: PipelinePromptNode) {
  return node.slots.map(slotView).filter(item => item.prompt?.content?.trim()).length
}

async function openLinkedPrompt() {
  if (route.query.node === 'other') { selectedNodeId.value = 'other'; await nextTick() }
  const promptId = typeof route.query.promptId === 'string' ? Number(route.query.promptId) : null
  const positionId = typeof route.query.positionId === 'string' ? Number(route.query.positionId) : null
  const prompt = rows.value.find(p => p.id === promptId && (positionId == null || p.positionId === positionId))
  if (prompt) { await focusPrompt(prompt.id); return }
  const node = PIPELINE_PROMPT_NODES.find(n => n.id === route.query.node)
  const position = positions.value.find(p => p.id === positionId)
  const slot = node?.slots.find(s => s.positionCode === position?.code?.trim().toUpperCase())
  if (!node || !slot) return
  selectedNodeId.value = node.id
  await nextTick()
  const item = slotView(slot)
  if (item.prompt) { await focusPrompt(item.prompt.id); return }
  selectedId.value = null
  if (canOperate.value) openCreateForSlot(slot)
}
onMounted(async () => { await load(); await openLinkedPrompt() })
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-hd">
      <div>
        <el-button v-if="route.query.positionId" link type="primary" @click="backToPositions">← 返回 Agent 岗位</el-button>
        <h2>提示词管理</h2>
        <p class="hint">
          按业务流程管理各节点、岗位的提示词。开发需求与问题修复独立展示，初始化仅补齐缺失配置。
        </p>
      </div>
      <div class="hd-actions">
        <el-button v-if="canOperate" :loading="ensuring" :disabled="loading || savingContent" @click="onEnsurePipeline">
          补齐默认提示词
        </el-button>
        <el-button v-if="canOperate" type="primary" :disabled="loading || ensuring || savingContent" @click="openCreate">新增提示词</el-button>
      </div>
    </div>

    <div class="group-tabs" role="tablist" aria-label="提示词业务分组">
      <button v-for="group in PIPELINE_PROMPT_GROUPS" :key="group.id" type="button" role="tab" :aria-selected="selectedNodeId !== 'other' && selectedGroup.id === group.id" :class="{ active: selectedNodeId !== 'other' && selectedGroup.id === group.id }" @click="selectGroup(group.id)">
        <strong>{{ group.name }}</strong><span>{{ group.nodes.length }} 个节点</span>
      </button>
      <span class="group-description">{{ selectedNodeId === 'other' ? '未绑定标准流水线槽位的自定义提示词' : selectedGroup.description }}</span>
    </div>
    <div class="body">
      <aside class="node-list">
        <div class="pane-label">{{ selectedGroup.name }} · 工作节点</div>
        <button
          v-for="n in selectedGroup.nodes"
          :key="n.id"
          type="button"
          class="node-item"
          :class="{ active: n.id === selectedNodeId }"
          @click="selectNode(n)"
        >
          <span class="node-name">{{ n.name }}</span>
          <span class="node-meta">
            {{ nodeConfigured(n) }} / {{ n.slots.length }} 已就绪
            <span v-if="nodeConfigured(n) === n.slots.length" class="dot ok" />
            <span v-else-if="nodeConfigured(n) > 0" class="dot partial" />
            <span v-else class="dot miss" />
          </span>
        </button>
        <button v-if="otherPrompts.length" class="node-item other-node" :class="{ active: selectedNodeId === 'other' }" @click="selectOther"><span class="node-name">其他提示词</span><span class="node-meta">{{ otherPrompts.length }} 条自定义配置</span></button>
      </aside>

      <section class="main-pane">
        <div class="node-hd" v-if="selectedNode">
          <div>
            <h3>{{ selectedNode.name }}</h3>
            <p class="meta">
              已就绪 {{ nodeConfiguredCount }} / {{ nodeSlots.length }} 个岗位提示词（需匹配岗位、任务且内容非空）
            </p>
          </div>
        </div>

        <div class="split">
          <aside class="role-list">
            <div class="pane-label">本节点岗位任务</div>
            <button
              v-for="item in nodeSlots"
              :key="item.slot.taskTypeCode + item.slot.positionCode"
              type="button"
              class="role-item"
              :class="{
                active: item.prompt && item.prompt.id === selectedId,
                empty: !item.prompt,
              }"
              @click="selectSlotPrompt(item)"
            >
              <span class="role-name">{{ item.slot.roleLabel }}</span>
              <span class="role-sub">{{ taskTypeName(item.slot.taskTypeCode) }}</span>
              <span class="role-status">
                <template v-if="item.missingPosition">缺岗位 {{ item.slot.positionCode }}</template>
                <template v-else-if="item.prompt?.content?.trim()">已就绪</template>
                <template v-else-if="item.prompt">内容为空 · 待编写</template>
                <template v-else>未配置 · 点击新增</template>
              </span>
            </button>
            <div v-if="!nodeSlots.length" class="empty">本节点无岗位槽位</div>
          </aside>

          <section class="detail" v-if="selected">
            <div class="detail-hd">
              <div>
                <h3>{{ selected.name }}</h3>
                <p class="meta">
                  编码 {{ selected.code }} · 任务
                  {{ selected.taskTypeName || selected.taskTypeCode }} · 岗位
                  {{ selected.positionName || selected.positionId }}
                  <template v-if="selected.positionCode"> ({{ selected.positionCode }})</template>
                </p>
              </div>
              <div class="actions">
                <template v-if="viewMode === 'preview'">
                  <el-button v-if="canOperate" @click="openEditMeta">编辑信息</el-button>
                  <el-button v-if="canOperate" type="primary" @click="enterEdit">编辑内容</el-button>
                  <el-button v-if="canOperate" type="danger" plain @click="removeRow">删除</el-button>
                </template>
                <template v-else>
                  <el-button @click="cancelEdit">取消</el-button>
                  <el-button type="primary" :loading="savingContent" @click="saveContent">
                    保存
                  </el-button>
                </template>
              </div>
            </div>
            <div class="content-pane" :class="{ editing: viewMode === 'edit' }">
              <div v-if="viewMode === 'preview'" class="md-preview">
                <MarkdownBody v-if="selected.content" :content="selected.content" />
                <div v-else class="empty-content">暂无内容，点击「编辑内容」编写 Markdown</div>
              </div>
              <el-input
                v-else
                v-model="editingContent"
                type="textarea"
                class="md-editor"
                resize="none"
                placeholder="支持 Markdown…"
              />
            </div>
          </section>
          <section v-else class="detail empty-detail">
            请选择左侧岗位任务；若未配置可点「补齐默认提示词」或「新增提示词」
          </section>
        </div>
      </section>
    </div>

    <el-dialog
      v-model="metaDialog"
      :title="metaMode === 'create' ? '新增提示词' : '编辑信息'"
      width="520px"
    >
      <el-form ref="metaFormRef" :model="metaForm" :rules="metaRules" label-width="80px">
        <el-form-item label="编码" prop="code">
          <el-input
            v-model="metaForm.code"
            maxlength="64"
            placeholder="租户内唯一，如 GEN_PRD_PM"
            :disabled="metaMode === 'edit'"
          />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="metaForm.name" maxlength="128" placeholder="展示名称" />
        </el-form-item>
        <el-form-item label="任务" prop="taskTypeCode">
          <el-select
            v-model="metaForm.taskTypeCode"
            filterable
            placeholder="选择内置任务类型"
            style="width: 100%"
            @change="onTaskTypeChange"
          >
            <el-option-group v-for="group in taskTypeGroups" :key="group.id" :label="group.name">
              <el-option
                v-for="t in group.types"
                :key="t.code"
                :label="t.name"
                :value="t.code"
              />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="岗位" prop="positionId">
          <el-select
            v-model="metaForm.positionId"
            filterable
            placeholder="选择岗位"
            style="width: 100%"
          >
            <el-option
              v-for="p in positions"
              :key="p.id"
              :label="p.code ? `${p.name} (${p.code})` : p.name"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="metaDialog = false">取消</el-button>
        <el-button type="primary" :loading="metaSubmitting" @click="submitMeta">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--dap-space-4);
}
.group-tabs { display:flex; align-items:center; gap:8px; flex-shrink:0; }
.group-tabs button { display:flex; align-items:center; gap:10px; background:var(--dap-gray-0); border:1px solid var(--dap-gray-200); padding:11px 16px; border-radius:7px; cursor:pointer; color:var(--dap-gray-700); font:inherit; }
.group-tabs button.active { background:var(--dap-primary-light); border-color:var(--dap-primary); color:var(--dap-primary); }
.group-tabs strong { font-size:13px; font-weight:600; }
.group-tabs button span,.group-description { font-size:12px; color:var(--dap-gray-500); }
.group-description { margin-left:12px; }
.other-node { margin-top:18px; border-top:1px solid var(--dap-gray-200); border-radius:0; padding-top:16px; }
.node-item:focus-visible,.role-item:focus-visible,.group-tabs button:focus-visible { outline:2px solid var(--dap-primary); outline-offset:2px; }
.dot.partial { background:var(--dap-warning); }
.page-hd {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--dap-space-4);
}
.hd-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}
.page-hd h2 {
  margin: 0;
  font-size: 18px;
  color: var(--dap-gray-900);
}
.hint {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--dap-gray-500);
}
.body {
  flex: 1;
  min-height: 0;
  display: flex;
  background: var(--dap-gray-0);
  border: 1px solid var(--dap-gray-200);
  border-radius: var(--dap-radius-md);
  overflow: hidden;
}
.pane-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--dap-gray-500);
  padding: 4px 8px 10px;
}
.node-list {
  width: 220px;
  border-right: 1px solid var(--dap-gray-200);
  padding: var(--dap-space-3);
  overflow: auto;
  flex-shrink: 0;
  background: var(--dap-gray-50);
}
.node-item {
  width: 100%;
  text-align: left;
  border: none;
  background: transparent;
  padding: 10px 12px;
  border-radius: var(--dap-radius-sm);
  cursor: pointer;
  color: var(--dap-gray-700);
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.node-item:hover {
  background: var(--dap-gray-0);
}
.node-item.active {
  background: var(--dap-primary-light);
  color: var(--dap-primary);
}
.node-name {
  font-size: 14px;
  font-weight: 500;
}
.node-meta {
  font-size: 12px;
  opacity: 0.8;
  display: flex;
  align-items: center;
  gap: 6px;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
}
.dot.ok {
  background: var(--dap-success, #16a34a);
}
.dot.miss {
  background: var(--dap-gray-300);
}
.main-pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.node-hd {
  padding: 14px 20px 10px;
  border-bottom: 1px solid var(--dap-gray-200);
  flex-shrink: 0;
}
.node-hd h3 {
  margin: 0 0 4px;
  font-size: 16px;
}
.split {
  flex: 1;
  min-height: 0;
  display: flex;
}
.role-list {
  width: 240px;
  border-right: 1px solid var(--dap-gray-200);
  padding: var(--dap-space-3);
  overflow: auto;
  flex-shrink: 0;
}
.role-item {
  width: 100%;
  text-align: left;
  border: 1px solid transparent;
  background: transparent;
  padding: 10px 12px;
  border-radius: var(--dap-radius-sm);
  cursor: pointer;
  color: var(--dap-gray-700);
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 4px;
}
.role-item:hover {
  background: var(--dap-gray-50);
}
.role-item.active {
  background: var(--dap-primary-light);
  color: var(--dap-primary);
  border-color: color-mix(in srgb, var(--dap-primary) 25%, transparent);
}
.role-item.empty {
  border-style: dashed;
  border-color: var(--dap-gray-200);
}
.role-name {
  font-size: 14px;
  font-weight: 500;
}
.role-sub {
  font-size: 12px;
  opacity: 0.85;
}
.role-status {
  font-size: 11px;
  opacity: 0.7;
}
.detail {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.detail-hd {
  display: flex;
  justify-content: space-between;
  gap: var(--dap-space-4);
  padding: var(--dap-space-4) var(--dap-space-5);
  border-bottom: 1px solid var(--dap-gray-200);
  flex-shrink: 0;
}
.detail-hd h3 {
  margin: 0 0 4px;
}
.meta {
  margin: 0;
  font-size: 13px;
  color: var(--dap-gray-500);
}
.actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
  align-items: flex-start;
}
.content-pane {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: var(--dap-space-5);
}
.content-pane.editing {
  padding: 0;
  display: flex;
}
.md-preview {
  max-width: 900px;
}
.md-editor {
  flex: 1;
  height: 100%;
}
.md-editor :deep(textarea) {
  height: 100% !important;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.55;
  border: none;
  border-radius: 0;
  padding: var(--dap-space-5);
}
.empty,
.empty-detail,
.empty-content {
  color: var(--dap-gray-500);
  padding: var(--dap-space-4);
}
@media(max-width:1200px) { .role-list { width:175px; } .node-list { width:185px; } .detail-hd { flex-wrap:wrap; } .hd-actions { flex-shrink:1; } }
@media(max-width:850px) { .page { height:auto; min-height:100%; } .page-hd { flex-wrap:wrap; } .group-tabs { flex-wrap:wrap; } .group-description { flex-basis:100%; margin:4px 0; } .body { flex-direction:column; min-height:600px; overflow:visible; } .node-list { width:100%; max-height:185px; border-right:0; border-bottom:1px solid var(--dap-gray-200); } .split { flex-direction:column; } .role-list { width:100%; max-height:200px; border-right:0; border-bottom:1px solid var(--dap-gray-200); } .detail { min-height:360px; } .content-pane { max-height:65vh; } .md-editor { min-height:320px; } }
</style>

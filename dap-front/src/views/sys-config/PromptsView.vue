<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
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
  type PipelinePromptNode,
  type PipelinePromptSlot,
} from './pipelinePromptNodes'

const auth = useAuthStore()
const canOperate = computed(() => auth.hasPermission('prompt:operate'))

const loading = ref(false)
const ensuring = ref(false)
const rows = ref<PromptItem[]>([])
const positions = ref<PositionItem[]>([])
const taskTypes = ref<SessionTaskTypeOption[]>([])

const selectedNodeId = ref(PIPELINE_PROMPT_NODES[0]?.id ?? '')
const selectedNode = computed(
  () => PIPELINE_PROMPT_NODES.find((n) => n.id === selectedNodeId.value) ?? null,
)

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

const taskTypesByKind = computed(() => ({
  gen: taskTypes.value.filter((t) => t.kind === 'GEN'),
  review: taskTypes.value.filter((t) => t.kind === 'REVIEW'),
}))

interface NodeSlotView {
  slot: PipelinePromptSlot
  prompt: PromptItem | null
  missingPosition: boolean
}

const nodeSlots = computed((): NodeSlotView[] => {
  const node = selectedNode.value
  if (!node) return []
  return node.slots.map((slot) => {
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
  })
})

const nodeConfiguredCount = computed(
  () => nodeSlots.value.filter((x) => x.prompt != null).length,
)

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
  ensuring.value = true
  try {
    const res = await ensurePreReviewPrompts()
    const parts: string[] = []
    if (res.created?.length) parts.push(`新建 ${res.created.length} 条`)
    if (res.skipped?.length) parts.push(`已存在跳过 ${res.skipped.length} 条`)
    if (res.missingPositions?.length) {
      parts.push(
        `仍缺岗位编码：${res.missingPositions.join('、')}（请到岗位管理补齐，UI 设计需编码 UI）`,
      )
    }
    ElMessage.success(parts.length ? parts.join('；') : '无需变更')
    await load()
    // 初始化后优先落到问题分析，便于核对 ISSUE_AUTO；否则 UI 设计
    if (res.created?.some((c: string) => String(c).includes('GEN_ISSUE'))) {
      selectedNodeId.value = 'issue_analyze'
    } else if (res.created?.some((c: string) => String(c).includes('GEN_UI'))) {
      selectedNodeId.value = 'gen_ui'
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '初始化失败')
  } finally {
    ensuring.value = false
  }
}

function selectNode(node: PipelinePromptNode) {
  selectedNodeId.value = node.id
}

function selectSlotPrompt(item: NodeSlotView) {
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

function openCreate() {
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
  await metaFormRef.value?.validate()
  metaSubmitting.value = true
  try {
    if (metaMode.value === 'create') {
      const res = await createPrompt(buildPayload(''))
      ElMessage.success('已创建')
      await load()
      selectedId.value = res.id
      enterEdit()
    } else if (selected.value) {
      await updatePrompt(selected.value.id, buildPayload(selected.value.content ?? ''))
      ElMessage.success('已保存')
      await load()
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
    if (e !== 'cancel') {
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

function nodeHasAnyPrompt(node: PipelinePromptNode) {
  return node.slots.some((slot) =>
    rows.value.some(
      (r) => (r.taskTypeCode || '').toUpperCase() === slot.taskTypeCode.toUpperCase(),
    ),
  )
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-hd">
      <div>
        <h2>提示词管理</h2>
        <p class="hint">
          左侧为流水线<strong>工作节点</strong>；右侧为该节点下各<strong>岗位任务</strong>提示词。覆盖初审、生成、评审、详细设计与测试用例等环节。
        </p>
      </div>
      <div class="hd-actions">
        <el-button v-if="canOperate" :loading="ensuring" @click="onEnsurePipeline">
          初始化流水线提示词
        </el-button>
        <el-button v-if="canOperate" type="primary" @click="openCreate">新增提示词</el-button>
      </div>
    </div>

    <div class="body">
      <aside class="node-list">
        <div class="pane-label">工作节点</div>
        <button
          v-for="n in PIPELINE_PROMPT_NODES"
          :key="n.id"
          type="button"
          class="node-item"
          :class="{ active: n.id === selectedNodeId }"
          @click="selectNode(n)"
        >
          <span class="node-name">{{ n.name }}</span>
          <span class="node-meta">
            {{ n.slots.length }} 岗
            <span v-if="nodeHasAnyPrompt(n)" class="dot ok" />
            <span v-else class="dot miss" />
          </span>
        </button>
      </aside>

      <section class="main-pane">
        <div class="node-hd" v-if="selectedNode">
          <div>
            <h3>{{ selectedNode.name }}</h3>
            <p class="meta">
              已配置 {{ nodeConfiguredCount }} / {{ selectedNode.slots.length }} 个岗位提示词
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
                <template v-else-if="item.prompt">已配置</template>
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
            请选择右侧岗位任务；若未配置可点「初始化流水线提示词」或「新增」
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
            <el-option-group v-if="taskTypesByKind.gen.length" label="生成 (GEN)">
              <el-option
                v-for="t in taskTypesByKind.gen"
                :key="t.code"
                :label="t.name"
                :value="t.code"
              />
            </el-option-group>
            <el-option-group v-if="taskTypesByKind.review.length" label="评审 (REVIEW)">
              <el-option
                v-for="t in taskTypesByKind.review"
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
</style>

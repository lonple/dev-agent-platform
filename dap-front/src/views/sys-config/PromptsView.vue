<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createPrompt,
  deletePrompt,
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

const auth = useAuthStore()
const canOperate = computed(() => auth.hasPermission('prompt:operate'))

const loading = ref(false)
const rows = ref<PromptItem[]>([])
const positions = ref<PositionItem[]>([])
const taskTypes = ref<SessionTaskTypeOption[]>([])
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

watch(selectedId, () => {
  viewMode.value = 'preview'
  editingContent.value = ''
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
    if (selectedId.value == null || !rows.value.some((r) => r.id === selectedId.value)) {
      selectedId.value = rows.value[0]?.id ?? null
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载提示词失败')
  } finally {
    loading.value = false
  }
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

function openCreate() {
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

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-hd">
      <div>
        <h2>提示词管理</h2>
        <p class="hint">按「岗位 + 任务类型」维护；工作台创建任务会话时可按组合加载并直接执行</p>
      </div>
      <el-button v-if="canOperate" type="primary" @click="openCreate">新增提示词</el-button>
    </div>
    <div class="body">
      <aside class="list">
        <button
          v-for="r in rows"
          :key="r.id"
          type="button"
          class="list-item"
          :class="{ active: r.id === selectedId }"
          @click="selectedId = r.id"
        >
          <span class="item-name">{{ r.name }}</span>
          <span class="item-sub">{{ r.taskTypeName || r.taskTypeCode }} · {{ r.positionName || '—' }}</span>
          <span class="item-code">{{ r.code }}</span>
        </button>
        <div v-if="!rows.length" class="empty">暂无提示词</div>
      </aside>

      <section class="detail" v-if="selected">
        <div class="detail-hd">
          <div>
            <h3>{{ selected.name }}</h3>
            <p class="meta">
              编码 {{ selected.code }} · 任务 {{ selected.taskTypeName || selected.taskTypeCode }} · 岗位
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
              <el-button type="primary" :loading="savingContent" @click="saveContent">保存</el-button>
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
      <section v-else class="detail empty-detail">请选择左侧提示词</section>
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
.list {
  width: 280px;
  border-right: 1px solid var(--dap-gray-200);
  padding: var(--dap-space-3);
  overflow: auto;
}
.list-item {
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
.list-item:hover {
  background: var(--dap-gray-50);
}
.list-item.active {
  background: var(--dap-primary-light);
  color: var(--dap-primary);
}
.item-name {
  font-size: 14px;
  font-weight: 500;
}
.item-sub {
  font-size: 12px;
  opacity: 0.85;
}
.item-code {
  font-size: 11px;
  opacity: 0.65;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
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

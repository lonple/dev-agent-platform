<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type UploadRequestOptions } from 'element-plus'
import { listProducts, type ProductItem } from '../../api/product'
import {
  listRequirementWorkbench,
  listRequirements,
  type RequirementItem,
} from '../../api/requirement'
import {
  changeIssueStatus,
  createIssue,
  deleteIssue,
  getIssue,
  listIssueHandlerCandidates,
  listIssues,
  updateIssue,
  uploadIssueAttachment,
  type IssueHandlerCandidate,
  type IssueItem,
  type IssueStatus,
} from '../../api/issue'
import AuthMarkdownBody from '../../components/AuthMarkdownBody.vue'
import { useAuthStore } from '../../stores/auth'
import { formatDateTime } from '../../utils/date'

const auth = useAuthStore()
const canOperate = computed(() => auth.hasPermission('issue:operate'))

const loading = ref(false)
const products = ref<ProductItem[]>([])
const requirements = ref<RequirementItem[]>([])
const handlers = ref<IssueHandlerCandidate[]>([])
const issues = ref<IssueItem[]>([])
const statusFilter = ref<IssueStatus | ''>('')

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit' | 'view'>('create')
const editing = ref<IssueItem | null>(null)
const formRef = ref<FormInstance>()
const submitting = ref(false)
const detailLoading = ref(false)
const uploading = ref(false)
const stepsRef = ref<{ textarea?: HTMLTextAreaElement } | null>(null)

const form = reactive({
  productId: undefined as number | undefined,
  requirementId: undefined as number | undefined,
  description: '',
  steps: '',
  expectedResult: '',
  solution: '',
  status: 'OPEN' as IssueStatus,
  handlerId: undefined as number | undefined,
})

const productRequirements = computed(() =>
  requirements.value.filter((r) => r.productId === form.productId),
)

const rules: FormRules = {
  productId: [{ required: true, message: '请选择关联产品', trigger: 'change' }],
  description: [{ required: true, message: '请输入问题描述', trigger: 'blur' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
}

const STATUS_TAG: Record<IssueStatus, '' | 'info' | 'success' | 'warning' | 'danger'> = {
  OPEN: 'warning',
  IN_PROGRESS: '',
  DONE: 'success',
}

const STATUS_OPTIONS: { value: IssueStatus; label: string }[] = [
  { value: 'OPEN', label: '未处理' },
  { value: 'IN_PROGRESS', label: '处理中' },
  { value: 'DONE', label: '完成' },
]

/** 当前状态 → 下一状态操作（列表操作列只展示一个推进按钮） */
const ISSUE_NEXT_ACTION: Record<IssueStatus, { status: IssueStatus; label: string } | null> = {
  OPEN: { status: 'IN_PROGRESS', label: '开始处理' },
  IN_PROGRESS: { status: 'DONE', label: '完成' },
  DONE: null,
}

function nextIssueAction(status: IssueStatus) {
  return ISSUE_NEXT_ACTION[status] ?? null
}

const dialogTitle = computed(() => {
  if (dialogMode.value === 'create') return '新建问题单'
  if (dialogMode.value === 'edit') return '编辑问题单'
  return '问题单详情'
})

const formReadonly = computed(() => dialogMode.value === 'view')

watch(
  () => form.productId,
  () => {
    if (formReadonly.value) return
    if (
      form.requirementId != null &&
      !productRequirements.value.some((r) => r.id === form.requirementId)
    ) {
      form.requirementId = undefined
    }
  },
)

async function loadRequirements() {
  try {
    requirements.value = auth.hasPermission('work:access')
      ? await listRequirementWorkbench('all')
      : await listRequirements()
  } catch {
    requirements.value = []
  }
}

async function load() {
  loading.value = true
  try {
    const [productList, handlerList, issueList] = await Promise.all([
      listProducts(),
      listIssueHandlerCandidates(),
      listIssues(statusFilter.value),
    ])
    products.value = productList
    handlers.value = handlerList
    issues.value = issueList
    await loadRequirements()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.productId = undefined
  form.requirementId = undefined
  form.description = ''
  form.steps = ''
  form.expectedResult = ''
  form.solution = ''
  form.status = 'OPEN'
  form.handlerId = undefined
  formRef.value?.clearValidate()
}

function fillForm(row: IssueItem) {
  form.productId = row.productId
  form.requirementId = row.requirementId ?? undefined
  form.description = row.description || ''
  form.steps = row.steps || ''
  form.expectedResult = row.expectedResult || ''
  form.solution = row.solution || ''
  form.status = row.status
  form.handlerId = row.handlerId ?? undefined
}

function openCreate() {
  dialogMode.value = 'create'
  editing.value = null
  resetForm()
  dialogVisible.value = true
}

async function openView(row: IssueItem) {
  dialogMode.value = 'view'
  editing.value = row
  fillForm(row)
  dialogVisible.value = true
  detailLoading.value = true
  try {
    editing.value = await getIssue(row.id)
    fillForm(editing.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载详情失败')
  } finally {
    detailLoading.value = false
  }
}

function openEdit(row: IssueItem) {
  dialogMode.value = 'edit'
  editing.value = row
  fillForm(row)
  dialogVisible.value = true
}

async function save() {
  if (formReadonly.value) return
  await formRef.value?.validate()
  submitting.value = true
  try {
    const payload = {
      productId: form.productId!,
      requirementId: form.requirementId ?? null,
      description: form.description.trim(),
      steps: form.steps.trim(),
      expectedResult: form.expectedResult.trim(),
      solution: form.solution.trim(),
      status: form.status,
      handlerId: form.handlerId ?? null,
    }
    if (dialogMode.value === 'create') {
      await createIssue(payload)
      ElMessage.success('已创建')
    } else if (editing.value) {
      await updateIssue(editing.value.id, payload)
      ElMessage.success('已保存')
    }
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

async function remove(row: IssueItem) {
  try {
    await ElMessageBox.confirm(
      `确定删除问题单「${row.issueNo || row.id}」？删除后不可恢复。`,
      '提示',
      { type: 'warning' },
    )
    await deleteIssue(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败')
    }
  }
}

async function quickChangeStatus(row: IssueItem, status: IssueStatus) {
  if (row.status === status) return
  try {
    await changeIssueStatus(row.id, status)
    ElMessage.success('状态已更新')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '状态更新失败')
  }
}

function insertAtCursor(text: string) {
  const textarea = stepsRef.value?.textarea as HTMLTextAreaElement | undefined
  if (!textarea) {
    form.steps = `${form.steps || ''}${form.steps ? '\n' : ''}${text}`
    return
  }
  const start = textarea.selectionStart ?? form.steps.length
  const end = textarea.selectionEnd ?? form.steps.length
  const before = form.steps.slice(0, start)
  const after = form.steps.slice(end)
  form.steps = `${before}${text}${after}`
  nextTick(() => {
    const pos = start + text.length
    textarea.focus()
    textarea.setSelectionRange(pos, pos)
  })
}

async function onUpload(options: UploadRequestOptions) {
  uploading.value = true
  try {
    const file = options.file as File
    const res = await uploadIssueAttachment(file)
    insertAtCursor(`\n![${file.name}](${res.url})\n`)
    ElMessage.success('图片已插入实现步骤')
    options.onSuccess?.(res)
  } catch (e) {
    const err = e instanceof Error ? e : new Error('上传失败')
    options.onError?.(err as any)
    ElMessage.error(err.message)
  } finally {
    uploading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-hd">
      <div>
        <h2>问题单管理</h2>
        <p class="hint">编码按产品自动生成（产品编码-自增号）；实现步骤支持上传图片</p>
      </div>
      <div class="page-actions">
        <el-select
          v-model="statusFilter"
          clearable
          placeholder="全部状态"
          style="width: 140px"
          @change="load"
        >
          <el-option
            v-for="opt in STATUS_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-button v-if="canOperate" type="primary" @click="openCreate">新建问题单</el-button>
      </div>
    </div>

    <el-table :data="issues" stripe>
      <el-table-column prop="issueNo" label="问题单编码" width="160" />
      <el-table-column prop="productName" label="产品" width="120" />
      <el-table-column label="关联需求" width="150">
        <template #default="{ row }">
          {{ row.requirementNo || '—' }}
        </template>
      </el-table-column>
      <el-table-column
        prop="description"
        label="问题描述"
        min-width="200"
        :show-overflow-tooltip="{ popperClass: 'issue-desc-tooltip' }"
      >
        <template #default="{ row }">
          <button type="button" class="name-link" @click="openView(row)">
            {{ row.description }}
          </button>
        </template>
      </el-table-column>
      <el-table-column prop="statusLabel" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG[row.status as IssueStatus] ?? 'info'" size="small">
            {{ row.statusLabel || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="handlerUsername" label="处理人" width="110">
        <template #default="{ row }">
          {{ row.handlerUsername || '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="createdByUsername" label="创建人" width="110" />
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">
          {{ formatDateTime(row.updatedAt) }}
        </template>
      </el-table-column>
      <el-table-column v-if="canOperate" label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="nextIssueAction(row.status)"
            link
            type="primary"
            @click="quickChangeStatus(row, nextIssueAction(row.status)!.status)"
          >
            {{ nextIssueAction(row.status)!.label }}
          </el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="760px"
      destroy-on-close
      class="issue-dialog"
    >
      <div v-loading="detailLoading">
        <el-form
          ref="formRef"
          :model="form"
          :rules="formReadonly ? undefined : rules"
          label-width="96px"
        >
          <el-form-item v-if="editing?.issueNo" label="问题单编码">
            <span>{{ editing.issueNo }}</span>
          </el-form-item>
          <el-form-item label="关联产品" prop="productId">
            <el-select
              v-model="form.productId"
              filterable
              placeholder="选择产品"
              style="width: 100%"
              :disabled="formReadonly"
            >
              <el-option
                v-for="p in products"
                :key="p.id"
                :label="`${p.name}（${p.code}）`"
                :value="p.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="关联需求">
            <el-select
              v-model="form.requirementId"
              filterable
              clearable
              placeholder="可选，用于上下文增强"
              style="width: 100%"
              :disabled="formReadonly || !form.productId"
            >
              <el-option
                v-for="r in productRequirements"
                :key="r.id"
                :label="`${r.reqNo || r.id} · ${r.name}`"
                :value="r.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-radio-group v-model="form.status" :disabled="formReadonly">
              <el-radio
                v-for="opt in STATUS_OPTIONS"
                :key="opt.value"
                :value="opt.value"
              >
                {{ opt.label }}
              </el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="处理人" prop="handlerId">
            <el-select
              v-model="form.handlerId"
              filterable
              clearable
              placeholder="选择处理人"
              style="width: 100%"
              :disabled="formReadonly"
            >
              <el-option
                v-for="h in handlers"
                :key="h.id"
                :label="h.username"
                :value="h.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="问题描述" prop="description">
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="4"
              maxlength="20000"
              :show-word-limit="!formReadonly"
              :disabled="formReadonly"
            />
          </el-form-item>
          <el-form-item label="实现步骤" prop="steps">
            <div v-if="!formReadonly" class="steps-editor">
              <div class="steps-toolbar">
                <el-upload
                  :show-file-list="false"
                  accept="image/png,image/jpeg,image/jpg,image/gif,image/webp,image/bmp"
                  :http-request="onUpload"
                  :disabled="uploading"
                >
                  <el-button size="small" :loading="uploading">上传图片</el-button>
                </el-upload>
                <span class="steps-tip">图片将以 Markdown 插入下方内容</span>
              </div>
              <el-input
                ref="stepsRef"
                v-model="form.steps"
                type="textarea"
                :rows="8"
                maxlength="100000"
                show-word-limit
                placeholder="描述实现/复现步骤，可上传图片"
              />
            </div>
            <div v-else class="steps-view">
              <AuthMarkdownBody v-if="form.steps" :content="form.steps" />
              <span v-else class="empty-text">—</span>
            </div>
          </el-form-item>
          <el-form-item label="预期结果" prop="expectedResult">
            <el-input
              v-model="form.expectedResult"
              type="textarea"
              :rows="3"
              maxlength="20000"
              :show-word-limit="!formReadonly"
              :disabled="formReadonly"
            />
          </el-form-item>
          <el-form-item label="解决方案" prop="solution">
            <el-input
              v-model="form.solution"
              type="textarea"
              :rows="4"
              maxlength="20000"
              :show-word-limit="!formReadonly"
              :disabled="formReadonly"
            />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ formReadonly ? '关闭' : '取消' }}</el-button>
        <el-button v-if="!formReadonly" type="primary" :loading="submitting" @click="save">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-hd {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--dap-space-4);
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
.name-link {
  border: none;
  padding: 0;
  background: none;
  color: var(--dap-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.name-link:hover {
  text-decoration: underline;
}
.page-actions {
  display: flex;
  align-items: center;
  gap: var(--dap-space-3);
}
.steps-editor {
  width: 100%;
}
.steps-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.steps-tip {
  color: var(--dap-gray-500);
  font-size: 12px;
}
.steps-view {
  width: 100%;
  min-height: 40px;
  padding: 8px 12px;
  border: 1px solid var(--dap-gray-200);
  border-radius: var(--dap-radius-sm);
  background: var(--dap-gray-50);
}
.empty-text {
  color: var(--dap-gray-500);
}
</style>

<style>
.issue-desc-tooltip {
  max-width: 360px !important;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
}
</style>

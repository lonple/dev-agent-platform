<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { listProducts, type ProductItem } from '../../api/product'
import {
  createRequirement,
  deleteRequirement,
  getRequirement,
  listApproverCandidates,
  listRequirements,
  submitRequirement,
  updateRequirement,
  type ApproverCandidate,
  type ApproverCandidatesResult,
  type RequirementItem,
  type RequirementStatus,
} from '../../api/requirement'
import { useAuthStore } from '../../stores/auth'
import { formatDateTime } from '../../utils/date'

const auth = useAuthStore()
const canOperate = computed(() => auth.hasPermission('requirement:operate'))

const loading = ref(false)
const products = ref<ProductItem[]>([])
const requirements = ref<RequirementItem[]>([])
const candidates = ref<ApproverCandidate[]>([])
const approverMeta = ref<ApproverCandidatesResult | null>(null)
const candidatesLoading = ref(false)

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit' | 'view'>('create')
const editing = ref<RequirementItem | null>(null)
const formRef = ref<FormInstance>()
const submitting = ref(false)
const detailLoading = ref(false)

const submitVisible = ref(false)
const submitRow = ref<RequirementItem | null>(null)
const submitApproverId = ref<number | undefined>()
const submitLoading = ref(false)

const form = reactive({
  name: '',
  productId: undefined as number | undefined,
  productVersionId: undefined as number | undefined,
  description: '',
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入需求名称', trigger: 'blur' }],
  productId: [{ required: true, message: '请选择关联产品', trigger: 'change' }],
  productVersionId: [{ required: true, message: '请选择产品版本', trigger: 'change' }],
  description: [{ required: true, message: '请输入需求描述', trigger: 'blur' }],
}

const selectedProductVersions = computed(() => {
  const product = products.value.find((p) => p.id === form.productId)
  return (product?.versions ?? []).filter((v) => v.status !== 'INVALID')
})

const STATUS_TAG: Record<RequirementStatus, '' | 'info' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'info',
  APPROVING: 'warning',
  REJECTED: 'danger',
  PENDING_PLAN: 'warning',
  PLANNING: '',
  DEVELOPING: '',
  RELEASED: 'success',
}

const dialogTitle = computed(() => {
  if (dialogMode.value === 'create') return '新建需求'
  if (dialogMode.value === 'edit') return '编辑需求'
  return '需求详情'
})

const formReadonly = computed(() => dialogMode.value === 'view')
const showTimeline = computed(() => dialogMode.value === 'view')
const dialogWidth = computed(() => (dialogMode.value === 'view' ? '920px' : '640px'))

function isEditable(row: RequirementItem) {
  return row.status === 'DRAFT' || row.status === 'REJECTED'
}

function canSubmitApply(row: RequirementItem) {
  return row.status === 'DRAFT' || row.status === 'REJECTED'
}

watch(
  () => form.productId,
  (id, prev) => {
    if (formReadonly.value) return
    if (id == null || id === prev) return
    const versions = products.value.find((p) => p.id === id)?.versions ?? []
    if (!versions.some((v) => v.id === form.productVersionId)) {
      form.productVersionId = versions.length === 1 ? versions[0].id : undefined
    }
  },
)

async function load() {
  loading.value = true
  try {
    ;[products.value, requirements.value] = await Promise.all([
      listProducts(),
      listRequirements(),
    ])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.name = ''
  form.productId = undefined
  form.productVersionId = undefined
  form.description = ''
  formRef.value?.clearValidate()
}

function openCreate() {
  dialogMode.value = 'create'
  editing.value = null
  resetForm()
  dialogVisible.value = true
}

async function openView(row: RequirementItem) {
  dialogMode.value = 'view'
  editing.value = row
  fillForm(row)
  dialogVisible.value = true
  detailLoading.value = true
  try {
    editing.value = await getRequirement(row.id)
    fillForm(editing.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载详情失败')
  } finally {
    detailLoading.value = false
  }
}

function openEdit(row: RequirementItem) {
  if (!isEditable(row)) {
    ElMessage.warning('当前状态不可编辑')
    return
  }
  dialogMode.value = 'edit'
  editing.value = row
  fillForm(row)
  dialogVisible.value = true
}

function fillForm(row: RequirementItem) {
  form.name = row.name
  form.productId = row.productId
  form.productVersionId = row.productVersionId ?? undefined
  form.description = row.description
}

async function save() {
  if (formReadonly.value) return
  await formRef.value?.validate()
  submitting.value = true
  try {
    const payload = {
      name: form.name.trim(),
      productId: form.productId!,
      productVersionId: form.productVersionId!,
      description: form.description.trim(),
    }
    if (dialogMode.value === 'create') {
      await createRequirement(payload)
      ElMessage.success('已保存为草稿')
    } else if (editing.value) {
      await updateRequirement(editing.value.id, payload)
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

async function openSubmit(row: RequirementItem) {
  submitRow.value = row
  submitApproverId.value = undefined
  candidates.value = []
  approverMeta.value = null
  submitVisible.value = true
  candidatesLoading.value = true
  try {
    const result = await listApproverCandidates(row.productId)
    approverMeta.value = result
    candidates.value = result.candidates ?? []
    const defaultId = row.approverId ?? result.defaultApproverId ?? undefined
    if (defaultId != null && candidates.value.some((c) => c.id === defaultId)) {
      submitApproverId.value = defaultId
    } else if (candidates.value.length === 1) {
      submitApproverId.value = candidates.value[0].id
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载审批人失败')
  } finally {
    candidatesLoading.value = false
  }
}

async function confirmSubmit() {
  if (!submitRow.value) return
  if (!submitApproverId.value) {
    ElMessage.warning('请选择审批人')
    return
  }
  submitLoading.value = true
  try {
    await submitRequirement(submitRow.value.id, { approverId: submitApproverId.value })
    ElMessage.success('已提交申请')
    submitVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '提交失败')
  } finally {
    submitLoading.value = false
  }
}

async function remove(row: RequirementItem) {
  try {
    await ElMessageBox.confirm(
      `确定删除需求「${row.name}」？删除后不可恢复，关联任务一并清除。`,
      '提示',
      { type: 'warning' },
    )
    await deleteRequirement(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败')
    }
  }
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-hd">
      <div>
        <h2>需求申请</h2>
        <p class="hint">新建仅保存为草稿；提交时选择审批人，默认产品经理</p>
      </div>
      <el-button v-if="canOperate" type="primary" @click="openCreate">新建需求</el-button>
    </div>

    <el-table :data="requirements" stripe>
      <el-table-column prop="reqNo" label="需求编号" width="180" />
      <el-table-column prop="name" label="需求名称" min-width="150" />
      <el-table-column prop="statusLabel" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG[row.status as RequirementStatus] ?? 'info'" size="small">
            {{ row.statusLabel || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="currentHandlerUsername" label="当前处理人" width="110" />
      <el-table-column prop="approverUsername" label="审批人" width="100" />
      <el-table-column prop="productName" label="关联产品" width="120" />
      <el-table-column prop="productVersion" label="产品版本" width="100" />
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">
          {{ formatDateTime(row.updatedAt) }}
        </template>
      </el-table-column>
      <el-table-column
        prop="description"
        label="需求描述"
        min-width="160"
        :show-overflow-tooltip="{ popperClass: 'req-desc-tooltip' }"
      />
      <el-table-column label="操作" :width="canOperate ? 260 : 90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openView(row)">查看</el-button>
          <template v-if="canOperate">
            <el-button v-if="isEditable(row)" link type="primary" @click="openEdit(row)">
              编辑
            </el-button>
            <el-button v-if="canSubmitApply(row)" link type="success" @click="openSubmit(row)">
              提交申请
            </el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      :width="dialogWidth"
      destroy-on-close
      class="req-dialog"
    >
      <div v-loading="detailLoading" class="dialog-body">
        <div class="dialog-main" :class="{ 'with-timeline': showTimeline }">
          <el-form
            ref="formRef"
            :model="form"
            :rules="formReadonly ? undefined : rules"
            label-width="96px"
          >
            <el-form-item v-if="editing" label="状态">
              <el-tag :type="STATUS_TAG[editing.status] ?? 'info'" size="small">
                {{ editing.statusLabel || editing.status }}
              </el-tag>
            </el-form-item>
            <el-form-item v-if="editing?.reqNo" label="需求编号">
              <span>{{ editing.reqNo }}</span>
            </el-form-item>
            <template v-if="dialogMode === 'view' && editing">
              <el-form-item label="当前处理人">
                <span>{{ editing.currentHandlerUsername || '—' }}</span>
              </el-form-item>
              <el-form-item label="审批人">
                <span>{{ editing.approverUsername || '—' }}</span>
              </el-form-item>
              <el-form-item v-if="editing.releaseVersion" label="发布版本">
                <span>{{ editing.releaseVersion }}</span>
              </el-form-item>
            </template>
            <el-form-item label="需求名称" prop="name">
              <el-input v-model="form.name" maxlength="128" :disabled="formReadonly" />
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
              :label="p.name"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="产品版本" prop="productVersionId">
          <el-select
            v-model="form.productVersionId"
            filterable
            placeholder="选择版本"
            style="width: 100%"
            :disabled="formReadonly || !form.productId"
          >
            <el-option
              v-for="v in selectedProductVersions"
              :key="v.id"
              :label="v.version"
              :value="v.id"
            />
          </el-select>
          <div
            v-if="form.productId && !selectedProductVersions.length && !formReadonly"
            class="form-tip"
          >
            该产品暂无版本，请先在产品管理中新建版本
          </div>
        </el-form-item>
            <el-form-item label="需求描述" prop="description">
              <el-input
                v-model="form.description"
                type="textarea"
                :rows="dialogMode === 'view' ? 10 : 8"
                maxlength="20000"
                :show-word-limit="!formReadonly"
                :disabled="formReadonly"
              />
            </el-form-item>
          </el-form>
        </div>

        <aside v-if="showTimeline" class="dialog-timeline">
          <h3>流程时间线</h3>
          <el-timeline v-if="editing?.flows?.length">
            <el-timeline-item
              v-for="f in editing.flows"
              :key="f.id"
              :timestamp="formatDateTime(f.createdAt)"
              placement="top"
            >
              <div class="flow-title">{{ f.nodeTypeLabel || f.nodeType }}</div>
              <div class="flow-meta">
                {{ f.operatorUsername || '—' }}
                <template v-if="f.fromStatusLabel || f.toStatusLabel">
                  · {{ f.fromStatusLabel || '—' }} → {{ f.toStatusLabel || '—' }}
                </template>
              </div>
              <div v-if="f.commentText" class="flow-comment">{{ f.commentText }}</div>
            </el-timeline-item>
          </el-timeline>
          <div v-else class="empty-flow">暂无流程记录</div>
        </aside>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ formReadonly ? '关闭' : '取消' }}</el-button>
        <el-button v-if="!formReadonly" type="primary" :loading="submitting" @click="save">
          保存
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="submitVisible" title="提交申请" width="480px" destroy-on-close>
      <div v-loading="candidatesLoading">
        <p class="submit-hint">
          提交「{{ submitRow?.name }}」后将进入审批中；默认选中当前产品的产品经理。
        </p>
        <p v-if="approverMeta?.productManagerUsername" class="submit-meta">
          产品经理：{{ approverMeta.productManagerUsername }}
        </p>
        <el-alert
          v-if="!candidatesLoading && candidates.length === 0"
          type="warning"
          :closable="false"
          show-icon
          title="暂无审批人候选"
          description="请先将用户加入当前租户，或在产品管理中配置产品经理。"
          style="margin-bottom: 12px"
        />
        <el-form label-width="88px">
          <el-form-item label="审批人" required>
            <el-select
              v-model="submitApproverId"
              filterable
              clearable
              placeholder="选择审批人"
              style="width: 100%"
              :disabled="candidates.length === 0"
            >
              <el-option
                v-for="c in candidates"
                :key="c.id"
                :label="c.productManager ? `${c.username}（产品经理）` : c.username"
                :value="c.id"
              />
            </el-select>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitLoading"
          :disabled="candidates.length === 0"
          @click="confirmSubmit"
        >
          确认提交
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
.form-tip {
  margin-top: 6px;
  color: var(--dap-gray-500);
  font-size: 12px;
}
.dialog-body {
  display: flex;
  gap: 24px;
  min-height: 320px;
}
.dialog-main {
  flex: 1;
  min-width: 0;
}
.dialog-main.with-timeline {
  border-right: 1px solid var(--dap-gray-200);
  padding-right: 20px;
}
.dialog-timeline {
  width: 280px;
  flex-shrink: 0;
  max-height: 560px;
  overflow: auto;
}
.dialog-timeline h3 {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 600;
}
.flow-title {
  font-weight: 500;
  color: var(--dap-gray-900);
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
.empty-flow {
  color: var(--dap-gray-500);
  font-size: 13px;
  padding: 12px 0;
}
.danger-text {
  color: #cf1322;
}
.submit-hint {
  margin: 0 0 8px;
  color: var(--dap-gray-700);
  font-size: 14px;
  line-height: 1.5;
}
.submit-meta {
  margin: 0 0 16px;
  color: var(--dap-gray-500);
  font-size: 13px;
}
</style>

<style>
.req-desc-tooltip {
  max-width: 360px !important;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
}
</style>

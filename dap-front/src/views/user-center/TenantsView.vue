<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createTenant,
  deleteTenant,
  listAdminCandidates,
  listTenants,
  updateTenant,
  type TenantItem,
} from '../../api/tenant'
import { useAuthStore } from '../../stores/auth'
import type { SysUser } from '../../types/api'

const auth = useAuthStore()
const canOperate = computed(() => auth.hasPermission('tenant:operate'))

const loading = ref(false)
const tenants = ref<TenantItem[]>([])
const allUsers = ref<SysUser[]>([])

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingTenant = ref<TenantItem | null>(null)
const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({
  code: '',
  name: '',
  description: '',
  status: 1,
  adminUserIds: [] as number[],
})

const rules: FormRules = {
  code: [{ required: true, message: '请输入租户编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入租户名称', trigger: 'blur' }],
  adminUserIds: [
    {
      type: 'array',
      required: true,
      min: 1,
      message: '请至少选择一名租户管理员',
      trigger: 'change',
    },
  ],
}

async function loadTenants() {
  loading.value = true
  try {
    tenants.value = await listTenants()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载租户失败')
  } finally {
    loading.value = false
  }
}

async function ensureUsers() {
  if (allUsers.value.length) return
  try {
    allUsers.value = await listAdminCandidates()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载用户失败')
  }
}

function openCreate() {
  dialogMode.value = 'create'
  editingTenant.value = null
  form.code = ''
  form.name = ''
  form.description = ''
  form.status = 1
  form.adminUserIds = []
  dialogVisible.value = true
  ensureUsers()
}

function openEdit(row: TenantItem) {
  dialogMode.value = 'edit'
  editingTenant.value = row
  form.code = row.code
  form.name = row.name
  form.description = row.description ?? ''
  form.status = row.status
  form.adminUserIds = (row.admins ?? []).map((a) => a.id)
  dialogVisible.value = true
  ensureUsers()
}

async function submitForm() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    const payload = {
      code: form.code.trim(),
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      status: form.status,
      adminUserIds: [...form.adminUserIds],
    }
    if (dialogMode.value === 'create') {
      await createTenant(payload)
      ElMessage.success('创建成功')
    } else if (editingTenant.value) {
      await updateTenant(editingTenant.value.id, payload)
      ElMessage.success('更新成功')
    }
    dialogVisible.value = false
    await loadTenants()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

async function onDelete(row: TenantItem) {
  try {
    await ElMessageBox.confirm(
      `确定删除租户「${row.name}」吗？将同时删除该租户下的用户关系、岗位、产品、需求、工作空间、会话等全部数据，且不可恢复。`,
      '删除确认',
      { type: 'warning' },
    )
    await deleteTenant(row.id)
    ElMessage.success('已删除')
    await loadTenants()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败')
    }
  }
}

function adminNames(row: TenantItem) {
  const names = (row.admins ?? []).map((a) => a.username)
  return names.length ? names.join('、') : '—'
}

onMounted(loadTenants)
</script>

<template>
  <div>
    <h1 class="page-title">租户管理</h1>
    <div class="page-card">
      <div class="page-toolbar">
        <span />
        <el-button v-if="canOperate" type="primary" @click="openCreate">
          新建租户
        </el-button>
      </div>

      <el-table v-loading="loading" :data="tenants" row-key="id">
        <el-table-column prop="code" label="编码" min-width="120" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="租户管理员" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            {{ adminNames(row) }}
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canOperate" label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新建租户' : '编辑租户'"
      width="520px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="编码" prop="code">
          <el-input v-model="form.code" :disabled="dialogMode === 'edit'" />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="租户管理员" prop="adminUserIds">
          <el-select
            v-model="form.adminUserIds"
            multiple
            filterable
            placeholder="可选择多名；选定后默认为该租户的「租户管理员」角色"
            style="width: 100%"
          >
            <el-option
              v-for="user in allUsers"
              :key="user.id"
              :label="`${user.username}${user.roleName ? '（' + user.roleName + '）' : ''}`"
              :value="user.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

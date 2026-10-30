<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createRole,
  deleteRole,
  getRolePermissions,
  listRoles,
  saveRolePermissions,
  updateRole,
} from '../../api/role'
import { listPermissions } from '../../api/permission'
import { useAuthStore } from '../../stores/auth'
import type { SysPermission, SysRole } from '../../types/api'

interface ModulePermissionRow {
  module: string
  view?: SysPermission
  operate?: SysPermission
}

const auth = useAuthStore()
const canOperate = computed(() => auth.hasPermission('role:operate'))

const loading = ref(false)
const roles = ref<SysRole[]>([])

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingRole = ref<SysRole | null>(null)
const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ name: '', description: '' })

const rules: FormRules = {
  name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
}

const permDialogVisible = ref(false)
const permLoading = ref(false)
const permSaving = ref(false)
const permRole = ref<SysRole | null>(null)
const allPermissions = ref<SysPermission[]>([])
const selectedCodes = ref<string[]>([])

const moduleRows = computed<ModulePermissionRow[]>(() => {
  const map = new Map<string, ModulePermissionRow>()
  for (const p of allPermissions.value) {
    let row = map.get(p.module)
    if (!row) {
      row = { module: p.module }
      map.set(p.module, row)
    }
    if (p.code.endsWith(':view')) {
      row.view = p
    } else if (p.code.endsWith(':operate')) {
      row.operate = p
    }
  }
  return Array.from(map.values()).sort((a, b) => a.module.localeCompare(b.module))
})

function isCodeChecked(code: string) {
  return selectedCodes.value.includes(code)
}

function toggleCode(code: string, checked: boolean) {
  if (code === 'permission:operate') return
  if (checked) {
    if (!selectedCodes.value.includes(code)) {
      selectedCodes.value.push(code)
    }
  } else {
    selectedCodes.value = selectedCodes.value.filter((c) => c !== code)
  }
}

async function loadRoles() {
  loading.value = true
  try {
    roles.value = await listRoles()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载角色失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  dialogMode.value = 'create'
  editingRole.value = null
  form.name = ''
  form.description = ''
  dialogVisible.value = true
}

function openEdit(row: SysRole) {
  dialogMode.value = 'edit'
  editingRole.value = row
  form.name = row.name
  form.description = row.description ?? ''
  dialogVisible.value = true
}

async function submitForm() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    const payload = {
      name: form.name.trim(),
      description: form.description.trim() || undefined,
    }
    if (dialogMode.value === 'create') {
      await createRole(payload)
      ElMessage.success('创建成功')
    } else if (editingRole.value) {
      await updateRole(editingRole.value.id, payload)
      ElMessage.success('更新成功')
    }
    dialogVisible.value = false
    await loadRoles()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

async function onDelete(row: SysRole) {
  try {
    await ElMessageBox.confirm(`确定删除角色「${row.name}」吗？`, '删除确认', {
      type: 'warning',
    })
    await deleteRole(row.id)
    ElMessage.success('已删除')
    await loadRoles()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败')
    }
  }
}

async function openPermissionConfig(row: SysRole) {
  permRole.value = row
  permDialogVisible.value = true
  permLoading.value = true
  try {
    if (!allPermissions.value.length) {
      allPermissions.value = await listPermissions()
    }
    selectedCodes.value = await getRolePermissions(row.id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载权限配置失败')
    permDialogVisible.value = false
  } finally {
    permLoading.value = false
  }
}

async function savePermissions() {
  if (!permRole.value) return
  permSaving.value = true
  try {
    const codes = selectedCodes.value.filter((c) => c !== 'permission:operate')
    await saveRolePermissions(permRole.value.id, codes)
    ElMessage.success('权限已保存')
    permDialogVisible.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    permSaving.value = false
  }
}

onMounted(loadRoles)
</script>

<template>
  <div>
    <h1 class="page-title">角色管理</h1>
    <div class="page-card">
      <div class="page-toolbar">
        <span />
        <el-button v-if="canOperate" type="primary" @click="openCreate">
          新建角色
        </el-button>
      </div>

      <el-table v-loading="loading" :data="roles" row-key="id">
        <el-table-column prop="name" label="角色名称" min-width="160" />
        <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
        <el-table-column label="系统内置" width="100">
          <template #default="{ row }">
            {{ row.builtin ? '是' : '否' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openPermissionConfig(row)">
              权限配置
            </el-button>
            <template v-if="canOperate">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button
                v-if="!row.builtin"
                link
                type="danger"
                @click="onDelete(row)"
              >
                删除
              </el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新建角色' : '编辑角色'"
      width="480px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">
          保存
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="permDialogVisible"
      :title="permRole ? `权限配置 - ${permRole.name}` : '权限配置'"
      width="560px"
      destroy-on-close
    >
      <div v-loading="permLoading" class="perm-list">
        <div v-for="row in moduleRows" :key="row.module" class="perm-row">
          <span class="perm-module">{{ row.module }}</span>
          <el-checkbox
            v-if="row.view"
            :model-value="isCodeChecked(row.view.code)"
            :disabled="!canOperate"
            @change="(v: string | number | boolean) => toggleCode(row.view!.code, Boolean(v))"
          >
            查看
          </el-checkbox>
          <el-checkbox
            v-if="row.operate"
            :model-value="isCodeChecked(row.operate.code)"
            :disabled="!canOperate || row.operate.code === 'permission:operate'"
            @change="(v: string | number | boolean) => toggleCode(row.operate!.code, Boolean(v))"
          >
            操作
          </el-checkbox>
        </div>
      </div>
      <template #footer>
        <el-button @click="permDialogVisible = false">取消</el-button>
        <el-button
          v-if="canOperate"
          type="primary"
          :loading="permSaving"
          @click="savePermissions"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.perm-list {
  display: flex;
  flex-direction: column;
  gap: var(--dap-space-3);
  min-height: 120px;
}

.perm-row {
  display: flex;
  align-items: center;
  gap: var(--dap-space-4);
  padding: var(--dap-space-2) 0;
  border-bottom: 1px solid var(--dap-gray-200);
}

.perm-module {
  width: 120px;
  flex-shrink: 0;
  color: var(--dap-gray-900);
  font-weight: 500;
}
</style>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  addTenantUser,
  listTenantRoles,
  listTenantUserCandidates,
  listTenantUsers,
  removeTenantUser,
  updateTenantUser,
  type TenantRoleCode,
  type TenantRoleOption,
  type TenantUserItem,
} from '../../api/tenantUser'
import { useAuthStore } from '../../stores/auth'
import type { SysUser } from '../../types/api'

const auth = useAuthStore()
const canOperate = computed(() => auth.hasPermission('tenant-user:operate'))

const loading = ref(false)
const users = ref<TenantUserItem[]>([])
const candidates = ref<SysUser[]>([])
const tenantRoles = ref<TenantRoleOption[]>([])
const keyword = ref('')

const addVisible = ref(false)
const editVisible = ref(false)
const editingUser = ref<TenantUserItem | null>(null)
const formRef = ref<FormInstance>()
const editFormRef = ref<FormInstance>()
const submitting = ref(false)

const addForm = reactive({
  userId: undefined as number | undefined,
  tenantRole: 'OPERATOR' as TenantRoleCode,
})

const editForm = reactive({
  tenantRole: 'OPERATOR' as TenantRoleCode,
})

const filteredUsers = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return users.value
  return users.value.filter((u) => u.username.toLowerCase().includes(q))
})

const addRules: FormRules = {
  userId: [{ required: true, message: '请选择用户', trigger: 'change' }],
  tenantRole: [{ required: true, message: '请选择租户角色', trigger: 'change' }],
}

const editRules: FormRules = {
  tenantRole: [{ required: true, message: '请选择租户角色', trigger: 'change' }],
}

async function loadData() {
  loading.value = true
  try {
    users.value = await listTenantUsers()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载用户失败')
  } finally {
    loading.value = false
  }
}

async function loadRoles() {
  try {
    tenantRoles.value = await listTenantRoles()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载角色失败')
  }
}

async function openAdd() {
  addForm.userId = undefined
  addForm.tenantRole = 'OPERATOR'
  addVisible.value = true
  try {
    candidates.value = await listTenantUserCandidates()
  } catch (e) {
    candidates.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载候选用户失败')
  }
}

function openEdit(row: TenantUserItem) {
  editingUser.value = row
  editForm.tenantRole = row.tenantRole || (row.tenantAdmin ? 'ADMIN' : 'OPERATOR')
  editVisible.value = true
}

async function submitAdd() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || addForm.userId == null) return
  submitting.value = true
  try {
    await addTenantUser({
      userId: addForm.userId,
      tenantRole: addForm.tenantRole,
    })
    ElMessage.success('已添加')
    addVisible.value = false
    await loadData()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '添加失败')
  } finally {
    submitting.value = false
  }
}

async function submitEdit() {
  if (!editingUser.value) return
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await updateTenantUser(editingUser.value.id, {
      tenantRole: editForm.tenantRole,
    })
    ElMessage.success('已保存')
    editVisible.value = false
    await loadData()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

async function onRemove(row: TenantUserItem) {
  try {
    await ElMessageBox.confirm(
      `确定将「${row.username}」移出当前租户？不会删除用户中心账号。`,
      '移除确认',
      { type: 'warning' },
    )
    await removeTenantUser(row.id)
    ElMessage.success('已移除')
    await loadData()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e instanceof Error ? e.message : '移除失败')
    }
  }
}

onMounted(async () => {
  await loadRoles()
  await loadData()
})
</script>

<template>
  <div>
    <h1 class="page-title">用户管理</h1>
    <div class="page-card">
      <div class="page-toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索用户名"
          clearable
          style="width: 240px"
        />
        <div class="toolbar-actions">
          <el-button v-if="canOperate" type="primary" @click="openAdd">
            添加用户
          </el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="filteredUsers" row-key="id">
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column prop="roleName" label="平台角色" min-width="120" />
        <el-table-column label="租户角色" min-width="140">
          <template #default="{ row }">
            <el-tag
              :type="row.tenantRole === 'ADMIN' || row.tenantAdmin ? 'warning' : 'info'"
              size="small"
            >
              {{ row.tenantRoleLabel || (row.tenantAdmin ? '租户管理员' : '租户操作人员') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
        <el-table-column v-if="canOperate" label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.tenantRole !== 'ADMIN' && !row.tenantAdmin"
              link
              type="primary"
              @click="openEdit(row)"
            >
              改角色
            </el-button>
            <el-button
              v-if="row.id !== auth.user?.id"
              link
              type="danger"
              @click="onRemove(row)"
            >
              移除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="addVisible" title="添加用户" width="480px" destroy-on-close>
      <el-form ref="formRef" :model="addForm" :rules="addRules" label-width="96px">
        <el-form-item label="用户" prop="userId">
          <el-select
            v-model="addForm.userId"
            filterable
            placeholder="从用户中心选择"
            style="width: 100%"
          >
            <el-option
              v-for="u in candidates"
              :key="u.id"
              :label="`${u.username}${u.roleName ? '（' + u.roleName + '）' : ''}`"
              :value="u.id"
            />
          </el-select>
          <div v-if="!candidates.length" class="form-tip">
            暂无可添加用户，请先在用户中心创建
          </div>
        </el-form-item>
        <el-form-item label="租户角色" prop="tenantRole">
          <el-select v-model="addForm.tenantRole" style="width: 100%">
            <el-option
              v-for="role in tenantRoles"
              :key="role.code"
              :label="role.label"
              :value="role.code"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitAdd">
          添加
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="修改租户角色" width="420px" destroy-on-close>
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="96px">
        <el-form-item label="用户">
          <span>{{ editingUser?.username }}</span>
        </el-form-item>
        <el-form-item label="租户角色" prop="tenantRole">
          <el-select v-model="editForm.tenantRole" style="width: 100%">
            <el-option
              v-for="role in tenantRoles.filter((r) => r.code !== 'ADMIN')"
              :key="role.code"
              :label="role.label"
              :value="role.code"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitEdit">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar-actions {
  display: flex;
  gap: var(--dap-space-2);
}
.form-tip {
  margin-top: 6px;
  color: var(--dap-gray-500);
  font-size: 12px;
}
</style>

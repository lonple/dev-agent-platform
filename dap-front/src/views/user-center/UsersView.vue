<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createUser,
  deleteUser,
  listUsers,
  updateUser,
  type CreateUserPayload,
} from '../../api/user'
import { listRoles } from '../../api/role'
import { useAuthStore } from '../../stores/auth'
import type { SysRole, SysUser } from '../../types/api'

const auth = useAuthStore()
const canOperate = computed(() => auth.hasPermission('user:operate'))

const loading = ref(false)
const users = ref<SysUser[]>([])
const roles = ref<SysRole[]>([])
const keyword = ref('')

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingUser = ref<SysUser | null>(null)
const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive({
  username: '',
  password: '',
  description: '',
  roleId: undefined as number | undefined,
})

const isAdminEdit = computed(
  () =>
    dialogMode.value === 'edit' &&
    (editingUser.value?.builtin ||
      editingUser.value?.username.toLowerCase() === 'admin'),
)

const rules = computed<FormRules>(() => {
  if (isAdminEdit.value) {
    return {
      password: [
        { required: true, message: '请输入新密码', trigger: 'blur' },
        { min: 6, message: '密码至少 6 位', trigger: 'blur' },
      ],
    }
  }
  return {
    username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
    password: dialogMode.value === 'create'
      ? [
          { required: true, message: '请输入密码', trigger: 'blur' },
          { min: 6, message: '密码至少 6 位', trigger: 'blur' },
        ]
      : [{ min: 6, message: '密码至少 6 位', trigger: 'blur' }],
    roleId: [{ required: true, message: '请选择角色', trigger: 'change' }],
  }
})

async function loadData() {
  loading.value = true
  try {
    users.value = await listUsers({
      username: keyword.value.trim() || undefined,
    })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载用户失败')
  } finally {
    loading.value = false
  }
}

async function loadRoles() {
  try {
    // 平台角色：系统管理员 / 系统操作人员
    const all = await listRoles()
    roles.value = all.filter(
      (r) => r.name === '系统管理员' || r.name === '系统操作人员',
    )
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载角色失败')
  }
}

function openCreate() {
  dialogMode.value = 'create'
  editingUser.value = null
  form.username = ''
  form.password = ''
  form.description = ''
  form.roleId =
    roles.value.find((r) => r.name === '系统操作人员')?.id ?? roles.value[0]?.id
  dialogVisible.value = true
}

function openEdit(row: SysUser) {
  dialogMode.value = 'edit'
  editingUser.value = row
  form.username = row.username
  form.password = ''
  form.description = row.description ?? ''
  form.roleId = row.roleId
  dialogVisible.value = true
}

async function submitForm() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    if (dialogMode.value === 'create') {
      const payload: CreateUserPayload = {
        username: form.username.trim(),
        password: form.password,
        description: form.description.trim() || undefined,
        roleId: form.roleId!,
      }
      await createUser(payload)
      ElMessage.success('创建成功')
    } else if (editingUser.value) {
      if (isAdminEdit.value) {
        await updateUser(editingUser.value.id, { password: form.password })
      } else {
        await updateUser(editingUser.value.id, {
          username: form.username.trim(),
          description: form.description.trim() || undefined,
          roleId: form.roleId,
          password: form.password || undefined,
        })
      }
      ElMessage.success('更新成功')
    }
    dialogVisible.value = false
    await loadData()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

async function onDelete(row: SysUser) {
  try {
    await ElMessageBox.confirm(`确定删除用户「${row.username}」吗？`, '删除确认', {
      type: 'warning',
    })
    await deleteUser(row.id)
    ElMessage.success('已删除')
    await loadData()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败')
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
          @keyup.enter="loadData"
          @clear="loadData"
        />
        <div class="toolbar-actions">
          <el-button @click="loadData">查询</el-button>
          <el-button v-if="canOperate" type="primary" @click="openCreate">
            新建用户
          </el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="users" row-key="id">
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column prop="roleName" label="角色" min-width="120" />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column v-if="canOperate" label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button
              v-if="!row.builtin && row.username.toLowerCase() !== 'admin'"
              link
              type="danger"
              @click="onDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新建用户' : '编辑用户'"
      width="480px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <template v-if="isAdminEdit">
          <el-alert
            title="系统管理员仅支持修改密码"
            type="info"
            :closable="false"
            show-icon
            class="admin-tip"
          />
          <el-form-item label="新密码" prop="password">
            <el-input v-model="form.password" type="password" show-password />
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="用户名" prop="username">
            <el-input
              v-model="form.username"
              :disabled="dialogMode === 'edit'"
              autocomplete="off"
            />
          </el-form-item>
          <el-form-item
            :label="dialogMode === 'create' ? '密码' : '新密码'"
            prop="password"
          >
            <el-input
              v-model="form.password"
              type="password"
              show-password
              :placeholder="dialogMode === 'edit' ? '留空则不修改' : ''"
            />
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="form.description" type="textarea" :rows="3" />
          </el-form-item>
          <el-form-item label="角色" prop="roleId">
            <el-select v-model="form.roleId" placeholder="选择角色" style="width: 100%">
              <el-option
                v-for="role in roles"
                :key="role.id"
                :label="role.name"
                :value="role.id"
              />
            </el-select>
          </el-form-item>
        </template>
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

<style scoped>
.toolbar-actions {
  display: flex;
  gap: var(--dap-space-2);
}

.admin-tip {
  margin-bottom: var(--dap-space-4);
}
</style>

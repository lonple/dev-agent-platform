<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createPosition,
  createSkill,
  deletePosition,
  deleteSkill,
  listPositions,
  updatePosition,
  updateSkill,
  type PositionItem,
  type SkillItem,
} from '../../api/position'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const canOperate = computed(() => auth.hasPermission('position:operate'))

const loading = ref(false)
const positions = ref<PositionItem[]>([])
const selectedId = ref<number | null>(null)

const selected = computed(
  () => positions.value.find((p) => p.id === selectedId.value) ?? null,
)

const posDialog = ref(false)
const posMode = ref<'create' | 'edit'>('create')
const posFormRef = ref<FormInstance>()
const posSubmitting = ref(false)
const posForm = reactive({ name: '', code: '', description: '' })
const posRules: FormRules = {
  name: [{ required: true, message: '请输入岗位名称', trigger: 'blur' }],
}

const skillDialog = ref(false)
const skillMode = ref<'create' | 'edit'>('create')
const editingSkill = ref<SkillItem | null>(null)
const skillFormRef = ref<FormInstance>()
const skillSubmitting = ref(false)
const skillForm = reactive({ name: '', description: '', sortOrder: 0 })
const skillRules: FormRules = {
  name: [{ required: true, message: '请输入技能名称', trigger: 'blur' }],
}

async function load() {
  loading.value = true
  try {
    positions.value = await listPositions(true)
    if (
      selectedId.value == null ||
      !positions.value.some((p) => p.id === selectedId.value)
    ) {
      selectedId.value = positions.value[0]?.id ?? null
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载岗位失败')
  } finally {
    loading.value = false
  }
}

function openCreatePos() {
  posMode.value = 'create'
  posForm.name = ''
  posForm.code = ''
  posForm.description = ''
  posDialog.value = true
}

function openEditPos() {
  if (!selected.value) return
  posMode.value = 'edit'
  posForm.name = selected.value.name
  posForm.code = selected.value.code ?? ''
  posForm.description = selected.value.description ?? ''
  posDialog.value = true
}

async function submitPos() {
  await posFormRef.value?.validate()
  posSubmitting.value = true
  try {
    if (posMode.value === 'create') {
      const res = await createPosition({ ...posForm })
      ElMessage.success('已创建岗位')
      await load()
      selectedId.value = res.id
    } else if (selected.value) {
      await updatePosition(selected.value.id, { ...posForm })
      ElMessage.success('已保存')
      await load()
    }
    posDialog.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    posSubmitting.value = false
  }
}

async function removePos() {
  if (!selected.value) return
  try {
    await ElMessageBox.confirm(
      `确定删除岗位「${selected.value.name}」及其技能？`,
      '提示',
      { type: 'warning' },
    )
    await deletePosition(selected.value.id)
    ElMessage.success('已删除')
    selectedId.value = null
    await load()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败')
    }
  }
}

function openCreateSkill() {
  if (!selected.value) return
  skillMode.value = 'create'
  editingSkill.value = null
  skillForm.name = ''
  skillForm.description = ''
  skillForm.sortOrder = (selected.value.skills?.length ?? 0) + 1
  skillDialog.value = true
}

function openEditSkill(skill: SkillItem) {
  skillMode.value = 'edit'
  editingSkill.value = skill
  skillForm.name = skill.name
  skillForm.description = skill.description ?? ''
  skillForm.sortOrder = skill.sortOrder ?? 0
  skillDialog.value = true
}

async function submitSkill() {
  if (!selected.value) return
  await skillFormRef.value?.validate()
  skillSubmitting.value = true
  try {
    if (skillMode.value === 'create') {
      await createSkill(selected.value.id, { ...skillForm })
      ElMessage.success('已添加技能')
    } else if (editingSkill.value) {
      await updateSkill(selected.value.id, editingSkill.value.id, { ...skillForm })
      ElMessage.success('已保存')
    }
    skillDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    skillSubmitting.value = false
  }
}

async function removeSkill(skill: SkillItem) {
  if (!selected.value) return
  try {
    await ElMessageBox.confirm(`确定删除技能「${skill.name}」？`, '提示', {
      type: 'warning',
    })
    await deleteSkill(selected.value.id, skill.id)
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
      <h2>岗位管理</h2>
      <el-button v-if="canOperate" type="primary" @click="openCreatePos">新增岗位</el-button>
    </div>
    <div class="body">
      <aside class="list">
        <button
          v-for="p in positions"
          :key="p.id"
          type="button"
          class="list-item"
          :class="{ active: p.id === selectedId }"
          @click="selectedId = p.id"
        >
          {{ p.name }}
        </button>
        <div v-if="!positions.length" class="empty">暂无岗位</div>
      </aside>
      <section class="detail" v-if="selected">
        <div class="detail-hd">
          <div>
            <h3>{{ selected.name }}</h3>
            <p class="desc">{{ selected.description || '暂无描述' }}</p>
          </div>
          <div v-if="canOperate" class="actions">
            <el-button @click="openEditPos">编辑</el-button>
            <el-button type="danger" plain @click="removePos">删除</el-button>
          </div>
        </div>
        <div class="skill-hd">
          <span>技能列表</span>
          <el-button v-if="canOperate" size="small" @click="openCreateSkill">添加技能</el-button>
        </div>
        <el-table :data="selected.skills || []" stripe>
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="description" label="描述" min-width="200" />
          <el-table-column prop="sortOrder" label="排序" width="80" />
          <el-table-column v-if="canOperate" label="操作" width="160">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEditSkill(row)">编辑</el-button>
              <el-button link type="danger" @click="removeSkill(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
      <section v-else class="detail empty-detail">请选择左侧岗位</section>
    </div>

    <el-dialog
      v-model="posDialog"
      :title="posMode === 'create' ? '新增岗位' : '编辑岗位'"
      width="480px"
    >
      <el-form ref="posFormRef" :model="posForm" :rules="posRules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="posForm.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="编码">
          <el-input
            v-model="posForm.code"
            maxlength="64"
            placeholder="可选，与任务类型默认岗位匹配用"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="posForm.description" type="textarea" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="posDialog = false">取消</el-button>
        <el-button type="primary" :loading="posSubmitting" @click="submitPos">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="skillDialog"
      :title="skillMode === 'create' ? '添加技能' : '编辑技能'"
      width="480px"
    >
      <el-form ref="skillFormRef" :model="skillForm" :rules="skillRules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="skillForm.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="skillForm.description" type="textarea" maxlength="200" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="skillForm.sortOrder" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="skillDialog = false">取消</el-button>
        <el-button type="primary" :loading="skillSubmitting" @click="submitSkill">保存</el-button>
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
  align-items: center;
  justify-content: space-between;
}
.page-hd h2 {
  margin: 0;
  font-size: 18px;
  color: var(--dap-gray-900);
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
  width: 240px;
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
}
.list-item:hover {
  background: var(--dap-gray-50);
}
.list-item.active {
  background: var(--dap-primary-light);
  color: var(--dap-primary);
}
.detail {
  flex: 1;
  padding: var(--dap-space-5);
  overflow: auto;
}
.detail-hd {
  display: flex;
  justify-content: space-between;
  gap: var(--dap-space-4);
  margin-bottom: var(--dap-space-5);
}
.detail-hd h3 {
  margin: 0 0 6px;
}
.desc {
  margin: 0;
  color: var(--dap-gray-500);
}
.skill-hd {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--dap-space-3);
  font-weight: 500;
}
.empty,
.empty-detail {
  color: var(--dap-gray-500);
  padding: var(--dap-space-4);
}
</style>

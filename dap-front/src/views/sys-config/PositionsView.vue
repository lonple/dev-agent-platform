<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ensureBuiltinPositions, listPositions, updatePosition, type PositionItem } from '../../api/position'
import { ensurePreReviewPrompts, listPrompts, type PromptItem } from '../../api/prompt'
import { useAuthStore } from '../../stores/auth'
import { PIPELINE_PROMPT_GROUPS, type PipelinePromptGroupId } from './pipelinePromptNodes'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const canOperate = computed(() => auth.hasPermission('position:operate'))
const canViewPrompts = computed(() => auth.hasPermission('prompt:view') || auth.hasPermission('work:access'))
const canOpenPrompts = computed(() => auth.hasPermission('prompt:view'))
const canInitializePrompts = computed(() => auth.hasPermission('prompt:operate'))
const initializingPrompts = ref(false)
const builtinCodes = ['PM', 'ARCH', 'DEV', 'QA', 'UI']
const codeOf = (p: PositionItem) => (p.code || '').trim().toUpperCase()
const isBuiltin = (p: PositionItem) => p.builtin ?? builtinCodes.includes(codeOf(p))
const loading = ref(false)
const ensuring = ref(false)
const positions = ref<PositionItem[]>([])
const prompts = ref<PromptItem[]>([])
const promptError = ref(false)
const selectedId = ref<number | null>(typeof route.query.positionId === 'string' ? Number(route.query.positionId) : null)
const activeGroup = ref<PipelinePromptGroupId>('requirement')
const selected = computed(() => positions.value.find(p => p.id === selectedId.value) ?? null)
const builtinCount = computed(() => positions.value.filter(isBuiltin).length)
const taskGroups = computed(() => PIPELINE_PROMPT_GROUPS.map(group => ({
  ...group,
  tasks: group.nodes.flatMap(node => node.slots
    .filter(slot => slot.positionCode === (selected.value ? codeOf(selected.value) : ''))
    .map(slot => ({ nodeId: node.id, nodeName: node.name, taskTypeCode: slot.taskTypeCode,
      prompt: prompts.value.find(p => p.positionId === selectedId.value && p.taskTypeCode === slot.taskTypeCode) }))),
})))
const currentTasks = computed(() => taskGroups.value.find(g => g.id === activeGroup.value)?.tasks ?? [])
const readyCount = computed(() => taskGroups.value.flatMap(g => g.tasks).filter(t => t.prompt?.content?.trim()).length)
const totalTasks = computed(() => taskGroups.value.reduce((sum, group) => sum + group.tasks.length, 0))
const customPrompts = computed(() => prompts.value.filter(p => p.positionId === selectedId.value
  && !taskGroups.value.some(g => g.tasks.some(t => t.taskTypeCode === p.taskTypeCode))))
const orphanPrompts = computed(() => prompts.value.filter(p => !positions.value.some(position => position.id === p.positionId)))
async function loadPrompts() {
  promptError.value = false
  if (!canViewPrompts.value) { prompts.value = []; return }
  try { prompts.value = await listPrompts() }
  catch { prompts.value = []; promptError.value = true }
}
async function load() {
  loading.value = true
  try {
    positions.value = (await listPositions(false)).sort((a, b) => {
      const rank = (p: PositionItem) => { const i = builtinCodes.indexOf(codeOf(p)); return i < 0 ? 99 : i }
      return rank(a) - rank(b) || a.id - b.id
    })
    if (!positions.value.some(p => p.id === selectedId.value)) selectedId.value = positions.value[0]?.id ?? null
    await loadPrompts()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : '加载 Agent 岗位失败') }
  finally { loading.value = false }
}
async function ensurePositions() {
  ensuring.value = true
  try { await ensureBuiltinPositions(); await load(); ElMessage.success('内置 Agent 岗位已补齐，已有配置保留') }
  catch (e) { ElMessage.error(e instanceof Error ? e.message : '补齐失败') }
  finally { ensuring.value = false }
}
async function initializePrompts() {
  initializingPrompts.value = true
  try {
    const result = await ensurePreReviewPrompts()
    await load()
    ElMessage.success(`新增 ${result.created.length} 条，已有 ${result.skipped.length} 条保留${result.missingPositions.length ? '；缺岗位：' + result.missingPositions.join('、') : ''}`)
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : '补齐提示词失败') }
  finally { initializingPrompts.value = false }
}
const editDialog = ref(false)
const editId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ name: '', description: '', code: '' })
const rules: FormRules = { name: [{ required: true, whitespace: true, message: '请输入岗位名称', trigger: 'blur' }] }
function openEdit() {
  if (!selected.value) return
  editId.value = selected.value.id
  Object.assign(form, { name: selected.value.name, code: selected.value.code || '', description: selected.value.description || '' })
  editDialog.value = true
}
async function save() {
  if (!await formRef.value?.validate().catch(() => false) || editId.value == null) return
  submitting.value = true
  try {
    await updatePosition(editId.value, { ...form, name: form.name.trim() })
    editDialog.value = false
    await load()
    ElMessage.success('已保存岗位信息')
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : '保存失败') }
  finally { submitting.value = false }
}
function openPrompt(nodeId: string, prompt?: PromptItem) {
  router.push({ path: '/tenant-config/prompts', query: {
    node: nodeId, positionId: String(selectedId.value), ...(prompt ? { promptId: String(prompt.id) } : {}),
  } })
}
onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page">
    <header class="page-hd">
      <div><h2>Agent 岗位</h2><p class="hint">配置执行角色，查看关联任务与提示词。人员权限由角色与租户权限管理。</p></div>
      <div class="header-actions">
        <el-button v-if="canOpenPrompts && orphanPrompts.length" @click="router.push({ path: '/tenant-config/prompts', query: { node: 'other' } })">未关联岗位的提示词（{{ orphanPrompts.length }}）</el-button>
        <el-button v-if="canOperate" :loading="ensuring" :disabled="loading || initializingPrompts" @click="ensurePositions">补齐内置岗位</el-button>
        <el-button v-if="canInitializePrompts" :loading="initializingPrompts" :disabled="loading || ensuring" @click="initializePrompts">补齐默认提示词</el-button>
      </div>
    </header>
    <div class="body">
      <aside class="list">
        <div class="list-label">执行角色 <span>{{ builtinCount }} / 5 内置</span></div>
        <button v-for="p in positions" :key="p.id" type="button" class="list-item" :class="{ active: p.id === selectedId }" :aria-pressed="p.id === selectedId" @click="selectedId = p.id">
          <span class="role-avatar">{{ p.code || p.name.slice(0, 1) }}</span><span><strong>{{ p.name }}</strong><small>{{ isBuiltin(p) ? '内置岗位' : '历史自定义岗位' }}</small></span>
        </button>
        <div v-if="!positions.length" class="empty">暂无执行岗位，可补齐五个内置岗位。</div>
      </aside>
      <section v-if="selected" class="detail">
        <header class="detail-hd">
          <div><div class="role-title"><h3>{{ selected.name }}</h3><el-tag size="small" effect="plain">{{ selected.code || '未设置编码' }}</el-tag><span v-if="isBuiltin(selected)" class="muted">内置</span></div><p class="desc">{{ selected.description || '通过岗位与任务类型匹配对应的执行提示词。' }}</p></div>
          <el-button v-if="canOperate" @click="openEdit">编辑信息</el-button>
        </header>
        <div class="section-heading"><h4>关联任务与提示词</h4><span v-if="canViewPrompts && !promptError">{{ readyCount }} / {{ totalTasks }} 个标准任务提示词已就绪</span></div>
        <p v-if="!isBuiltin(selected)" class="notice">保留历史配置；自定义岗位不会自动增加流水线节点。</p>
        <el-alert v-if="promptError" title="提示词加载失败，暂时无法判断配置状态。" type="warning" :closable="false" show-icon><el-button link type="primary" @click="loadPrompts">重新加载</el-button></el-alert>
        <p v-if="!canViewPrompts" class="notice">当前账号可查看任务职责，查看提示词配置需相应权限。</p>
        <div class="group-tabs" role="tablist" aria-label="关联任务分组"><button v-for="group in taskGroups" :key="group.id" type="button" role="tab" :aria-selected="activeGroup === group.id" :class="{ active: activeGroup === group.id }" @click="activeGroup = group.id">{{ group.name }}<span>{{ group.tasks.length }}</span></button></div>
        <el-table :data="currentTasks" empty-text="该岗位在此流程中暂无标准任务">
          <el-table-column label="工作节点" min-width="140"><template #default="{ row }"><strong class="task-name">{{ row.nodeName }}</strong><small v-if="row.taskTypeCode === 'GEN_CODING'" class="muted block">编码分阶段提示由平台控制</small></template></el-table-column>
          <el-table-column label="提示词" min-width="180"><template #default="{ row }"><span>{{ !canViewPrompts ? '无查看权限' : promptError ? '加载失败' : row.prompt?.name || '尚未配置' }}</span><small class="muted block">{{ row.taskTypeCode }}</small></template></el-table-column>
          <el-table-column label="状态" width="110"><template #default="{ row }"><span v-if="!canViewPrompts || promptError" class="muted">未知</span><el-tag v-else size="small" :type="row.prompt?.content?.trim() ? 'success' : 'warning'" effect="plain">{{ row.prompt?.content?.trim() ? '已就绪' : row.prompt ? '内容为空' : '未配置' }}</el-tag></template></el-table-column>
          <el-table-column v-if="canOpenPrompts" label="操作" width="110"><template #default="{ row }"><el-button link type="primary" :disabled="promptError" @click="openPrompt(row.nodeId, row.prompt)">{{ row.prompt ? '查看提示词' : '配置提示词' }}</el-button></template></el-table-column>
        </el-table>
        <section v-if="customPrompts.length" class="custom-prompts"><h4>其他已绑定提示词</h4><div v-for="prompt in customPrompts" :key="prompt.id" class="custom-row"><span>{{ prompt.name }}<small class="muted block">{{ prompt.taskTypeName || prompt.taskTypeCode }}</small></span><el-button v-if="canOpenPrompts" link type="primary" @click="openPrompt('other', prompt)">查看提示词</el-button></div></section>
      </section>
      <section v-else class="detail empty">请选择左侧 Agent 岗位</section>
    </div>
    <el-dialog v-model="editDialog" title="编辑 Agent 岗位" width="min(480px, 94vw)" :close-on-click-modal="!submitting" :show-close="!submitting" :close-on-press-escape="!submitting">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="70px" :disabled="submitting">
        <el-form-item label="编码"><el-input :model-value="form.code" disabled /><p class="hint">编码用于匹配任务与提示词，此处保留既有绑定。</p></el-form-item>
        <el-form-item label="名称" prop="name"><el-input v-model="form.name" maxlength="64" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="3" maxlength="200" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="submitting" @click="editDialog = false">取消</el-button><el-button type="primary" :loading="submitting" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { height:100%; display:flex; flex-direction:column; gap:20px; }
.page-hd,.detail-hd,.section-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; }
.header-actions { display:flex; flex-wrap:wrap; gap:8px; }
.header-actions .el-button + .el-button { margin-left:0; }
h2 { margin:0; font-size:20px; color:var(--dap-gray-900); } h4 { margin:0; font-size:14px; font-weight:600; color:var(--dap-gray-900); }
.hint { margin:8px 0 0; color:var(--dap-gray-500); font-size:12px; line-height:1.7; }
.body { display:flex; flex:1; min-height:0; background:white; border:1px solid var(--dap-gray-200); border-radius:10px; overflow:hidden; }
.list { width:226px; flex-shrink:0; overflow:auto; padding:16px 12px; background:var(--dap-gray-50); border-right:1px solid var(--dap-gray-200); }
.list-label { display:flex; justify-content:space-between; padding:0 10px 16px; color:var(--dap-gray-500); font-size:11px; }
.list-item { display:flex; align-items:center; gap:12px; width:100%; text-align:left; border:0; background:none; padding:12px 10px; margin-bottom:5px; border-radius:7px; cursor:pointer; font:inherit; color:var(--dap-gray-700); }
.list-item:hover { background:white; } .list-item.active { background:var(--dap-primary-light); color:var(--dap-primary); }
.list-item strong { display:block; font-size:13px; font-weight:500; } .list-item small { display:block; margin-top:4px; font-size:11px; color:var(--dap-gray-500); }
.role-avatar { width:34px; height:34px; flex-shrink:0; display:grid; place-items:center; border:1px solid var(--dap-gray-200); background:white; border-radius:8px; font-size:11px; font-family:Consolas,monospace; }
.detail { flex:1; min-width:0; overflow:auto; padding:26px; } .detail-hd { margin-bottom:32px; } .role-title { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
.role-title h3 { margin:0; font-size:21px; color:var(--dap-gray-900); } .desc { color:var(--dap-gray-500); font-size:13px; line-height:1.8; margin:12px 0 0; white-space:pre-wrap; }
.section-heading { align-items:center; margin-bottom:18px; flex-wrap:wrap; } .section-heading > span,.muted { color:var(--dap-gray-500); font-size:11px; }
.block { display:block; margin-top:5px; } .task-name { font-weight:500; } .notice { background:var(--dap-gray-50); padding:12px; font-size:12px; color:var(--dap-gray-700); border-radius:6px; }
.group-tabs { display:flex; gap:20px; border-bottom:1px solid var(--dap-gray-200); margin-bottom:16px; }
.group-tabs button { display:flex; align-items:center; gap:8px; padding:12px 0; background:none; border:0; border-bottom:2px solid transparent; cursor:pointer; font:inherit; font-size:13px; color:var(--dap-gray-500); }
.group-tabs button.active { color:var(--dap-primary); border-bottom-color:var(--dap-primary); } .group-tabs button span { font-size:11px; background:var(--dap-gray-100); border-radius:4px; padding:1px 5px; }
.custom-prompts { margin-top:28px; } .custom-row { display:flex; justify-content:space-between; align-items:center; padding:14px 0; border-bottom:1px solid var(--dap-gray-200); font-size:13px; }
.empty { padding:24px; color:var(--dap-gray-500); font-size:13px; }
button:focus-visible { outline:2px solid var(--dap-primary); outline-offset:2px; }
@media(max-width:900px) { .page { height:auto; min-height:100%; } .page-hd,.detail-hd { flex-wrap:wrap; } .body { flex-direction:column; } .list { width:100%; max-height:220px; border-right:0; border-bottom:1px solid var(--dap-gray-200); } .detail { padding:18px; min-height:400px; } }
</style>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createProduct,
  createProductVersion,
  deleteProduct,
  deleteProductVersion,
  listProductManagers,
  listProducts,
  updateProduct,
  updateProductVersion,
  type ProductItem,
  type ProductManager,
  type ProductVersionItem,
  type ProductVersionStatus,
} from '../../api/product'
import {
  DEFAULT_RUNTIME_PROFILE_JSON,
  deleteSandboxProfile,
  ensureSandboxBundle,
  extractEnvVarsFromConfig,
  listSandboxLeases,
  listSandboxProfiles,
  listSandboxResources,
  mergeEnvVarsIntoConfig,
  setDefaultSandboxProfile,
  upsertSandboxProfile,
  type SandboxEnvVar,
  type SandboxProfile,
  type SandboxResource,
} from '../../api/sandbox'
import { listWikiTree, type WikiNodeItem } from '../../api/wiki'
import { useAuthStore } from '../../stores/auth'
import type { ProductRepoKind } from '../../api/product'
import { formatDateTime } from '../../utils/date'
import { readRuntimeProfile, writeRuntimeProfile } from '../../utils/runtimeProfile'
import FieldHelp from '../../components/FieldHelp.vue'

const VERSION_STATUS_OPTIONS: { value: ProductVersionStatus; label: string }[] = [
  { value: 'PLANNING', label: '计划中' },
  { value: 'RELEASED', label: '已发布' },
  { value: 'INVALID', label: '无效' },
]

const VERSION_STATUS_TAG: Record<ProductVersionStatus, '' | 'success' | 'warning' | 'info'> = {
  PLANNING: 'warning',
  RELEASED: 'success',
  INVALID: 'info',
}

const auth = useAuthStore()
const canOperate = computed(() => auth.hasPermission('product:operate'))

const loading = ref(false)
const products = ref<ProductItem[]>([])
const managers = ref<ProductManager[]>([])
const selectedId = ref<number | null>(null)

const selected = computed(
  () => products.value.find((p) => p.id === selectedId.value) ?? null,
)

const sandboxLoading = ref(false)
const sandboxError = ref('')
const sandboxInitializing = ref(false)
const sandboxDetails = ref<string[]>([])
let sandboxRequest = 0
const sandboxResources = ref<SandboxResource[]>([])
const sandboxProfiles = ref<SandboxProfile[]>([])
const sandboxLeases = ref<any[]>([])

async function loadSandbox() {
  const requestId = ++sandboxRequest
  const productId = selectedId.value
  sandboxError.value = ''
  if (!selectedId.value) {
    sandboxLoading.value = false
    sandboxResources.value = []
    sandboxProfiles.value = []
    sandboxLeases.value = []
    return
  }
  sandboxLoading.value = true
  try {
    const [resources, profiles, leases] = await Promise.all([
      listSandboxResources(productId!),
      listSandboxProfiles(productId!),
      listSandboxLeases(productId!),
    ])
    if (requestId !== sandboxRequest) return
    sandboxResources.value = resources
    sandboxProfiles.value = profiles
    sandboxLeases.value = leases
  } catch (e) {
    if (requestId !== sandboxRequest) return
    sandboxError.value = e instanceof Error ? e.message : '执行环境加载失败'
    sandboxResources.value = []
    sandboxProfiles.value = []
    sandboxLeases.value = []
  } finally {
    if (requestId === sandboxRequest) sandboxLoading.value = false
  }
}

async function ensureSandbox() {
  if (!selectedId.value) return
  sandboxInitializing.value = true
  try {
    await ensureSandboxBundle(selectedId.value)
    ElMessage.success('已补齐默认执行环境配置；实际运行仍需执行节点和镜像可用')
    await loadSandbox()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '预置失败')
  } finally { sandboxInitializing.value = false }
}

async function makeDefaultProfile(code: string) {
  if (!selectedId.value) return
  try {
    await setDefaultSandboxProfile(selectedId.value, code)
    ElMessage.success('已设为默认 Profile')
    await loadSandbox()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '设置失败')
  }
}

const profileDialog = ref(false)
const profileMode = ref<'create' | 'edit'>('create')
const profileSubmitting = ref(false)
const profileEditorMode = ref<'form' | 'json'>('form')
const runtimeForm = reactive(readRuntimeProfile(DEFAULT_RUNTIME_PROFILE_JSON))
const profileProductId = ref<number | null>(null)
const profileForm = reactive({
  profileCode: '',
  displayName: '',
  isDefault: false,
  configJson: DEFAULT_RUNTIME_PROFILE_JSON,
  envVars: [] as SandboxEnvVar[],
})

function openCreateProfile() {
  profileProductId.value = selectedId.value
  profileEditorMode.value = 'form'
  Object.assign(runtimeForm, readRuntimeProfile(DEFAULT_RUNTIME_PROFILE_JSON))
  profileMode.value = 'create'
  profileForm.profileCode = ''
  profileForm.displayName = ''
  profileForm.isDefault = sandboxProfiles.value.length === 0
  profileForm.configJson = DEFAULT_RUNTIME_PROFILE_JSON
  profileForm.envVars = []
  profileDialog.value = true
}

function openEditProfile(row: SandboxProfile) {
  profileProductId.value = selectedId.value
  profileMode.value = 'edit'
  profileForm.profileCode = row.profileCode
  profileForm.displayName = row.displayName || row.profileCode
  profileForm.isDefault = !!row.isDefault
  try {
    profileForm.configJson = row.configJson
      ? JSON.stringify(JSON.parse(row.configJson), null, 2)
      : DEFAULT_RUNTIME_PROFILE_JSON
  } catch {
    profileForm.configJson = row.configJson || DEFAULT_RUNTIME_PROFILE_JSON
  }
  profileForm.envVars = extractEnvVarsFromConfig(row.configJson)
  try {
    Object.assign(runtimeForm, readRuntimeProfile(profileForm.configJson))
    profileEditorMode.value = 'form'
  } catch { profileEditorMode.value = 'json' }
  profileDialog.value = true
}

async function resetRuntimeTemplate() {
  try {
    await ElMessageBox.confirm('将清空当前表单的服务端口、依赖变量和高级配置，恢复产品无关的原始模板。方案名称与编码保留；点击保存后才会更新此方案。', '按原始模板重配', {
      type: 'warning', confirmButtonText: '重置表单', cancelButtonText: '保留当前配置',
    })
    profileForm.configJson = DEFAULT_RUNTIME_PROFILE_JSON
    profileForm.envVars = []
    Object.assign(runtimeForm, readRuntimeProfile(DEFAULT_RUNTIME_PROFILE_JSON))
    profileEditorMode.value = 'form'
  } catch { /* 取消时保留现有内容。 */ }
}

function switchProfileEditor(mode: 'form' | 'json') {
  if (mode === profileEditorMode.value) return
  try {
    if (mode === 'json') profileForm.configJson = mergeEnvVarsIntoConfig(writeRuntimeProfile(profileForm.configJson, runtimeForm), profileForm.envVars)
    else {
      Object.assign(runtimeForm, readRuntimeProfile(profileForm.configJson))
      profileForm.envVars = extractEnvVarsFromConfig(profileForm.configJson)
    }
    profileEditorMode.value = mode
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : '配置格式异常') }
}

function runtimeSummary(row: SandboxProfile) {
  try {
    const value = readRuntimeProfile(row.configJson || DEFAULT_RUNTIME_PROFILE_JSON)
    const db = value.dbHost && value.dbName ? `库 ${value.dbHost}/${value.dbName}${value.dbSchema ? '.' + value.dbSchema : ''}` : ''
    const test = value.testEnabled ? `测试 ${value.testModelMode === 'mock' ? 'Mock' : '真实模型'}` : ''
    return [db, value.services.map(s => `${s.key} : ${s.containerPort ?? '待填写'}`).join(' · ') || '不映射业务端口', test, `有效 ${value.ttl} 小时`].filter(Boolean).join(' · ')
  } catch { return '配置格式异常，请编辑检查' }
}

function addRuntimeService() {
  runtimeForm.services.push({ key: '', containerPort: null, protocol: 'http' })
}

/** 执行占用的容器端口 → 宿主映射；数据来自租约的 runtimeInstance.ports。 */
function portMappingText(row: any) {
  let runtime = row?.runtimeInstance
  if (!runtime && typeof row?.runtimeInstanceJson === 'string') {
    try { runtime = JSON.parse(row.runtimeInstanceJson) } catch { return '—' }
  }
  const ports = runtime?.ports
  if (!ports || typeof ports !== 'object') return '—'
  const items = Object.entries(ports as Record<string, any>)
    .map(([key, value]) => `${key} ${value?.container ?? '?'}→${value?.host ?? '?'}`)
  return items.length ? items.join(' · ') : '—'
}

function addProfileEnvRow() {
  profileForm.envVars.push({ name: '', value: '', enabled: true, remark: '' })
}

function removeProfileEnvRow(idx: number) {
  profileForm.envVars.splice(idx, 1)
}

async function submitProfile() {
  if (!profileProductId.value) return
  const code = profileForm.profileCode.trim()
  if (!code) {
    ElMessage.warning('请填写方案编码')
    return
  }
  let configJson: string
  try {
    const jsonMode = profileEditorMode.value === 'json'
    const values = jsonMode ? readRuntimeProfile(profileForm.configJson) : runtimeForm
    const envVars = jsonMode ? extractEnvVarsFromConfig(profileForm.configJson) : profileForm.envVars
    const names = new Set<string>()
    for (const env of envVars) {
      if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(env.name.trim())) throw new Error('环境变量名不能为空，且只能包含字母、数字和下划线，不能以数字开头')
      if (names.has(env.name.trim())) throw new Error('环境变量名不能重复：' + env.name)
      names.add(env.name.trim())
    }
    configJson = mergeEnvVarsIntoConfig(writeRuntimeProfile(profileForm.configJson, values), envVars)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '配置格式异常')
    return
  }
  profileSubmitting.value = true
  try {
    await upsertSandboxProfile(profileProductId.value, {
      profileCode: code,
      displayName: profileForm.displayName.trim() || code,
      isDefault: profileForm.isDefault,
      configJson,
    })
    ElMessage.success(profileMode.value === 'create' ? '已创建运行方案' : '已更新运行方案')
    profileDialog.value = false
    await loadSandbox()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    profileSubmitting.value = false
  }
}

async function removeProfile(row: SandboxProfile) {
  if (!selectedId.value) return
  if (row.isDefault) {
    ElMessage.warning('不能删除默认 Profile，请先把其它套设为默认')
    return
  }
  try {
    await ElMessageBox.confirm(`删除 Profile「${row.profileCode}」？`, '提示', { type: 'warning' })
    await deleteSandboxProfile(selectedId.value, row.profileCode)
    ElMessage.success('已删除')
    await loadSandbox()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败')
    }
  }
}

watch(selectedId, () => {
  void loadSandbox()
})

const productDialog = ref(false)
const productMode = ref<'create' | 'edit'>('create')
const productFormRef = ref<FormInstance>()
const productSubmitting = ref(false)
const productForm = reactive({
  code: '',
  name: '',
  description: '',
  managerUserId: undefined as number | undefined,
  wikiMode: 'existing' as 'existing' | 'create',
  wikiNodeId: undefined as number | undefined,
  wikiParentId: undefined as number | undefined,
  wikiNewName: '',
  codeRepos: [{ localPath: '', label: '', repoKind: 'BIZ' }] as { localPath: string; label: string; repoKind: ProductRepoKind }[],
})
const wikiTree = ref<WikiNodeItem[]>([])
const wikiTreeLoading = ref(false)
const productRules: FormRules = {
  code: [{ required: true, message: '请输入产品编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入产品名', trigger: 'blur' }],
}

function onlyDirs(nodes: WikiNodeItem[]): WikiNodeItem[] {
  return nodes
    .filter((n) => n.nodeType === 'DIR')
    .map((n) => ({
      ...n,
      children: n.children?.length ? onlyDirs(n.children) : undefined,
    }))
}

async function loadWikiTree() {
  wikiTreeLoading.value = true
  try {
    wikiTree.value = onlyDirs(await listWikiTree())
  } catch {
    wikiTree.value = []
  } finally {
    wikiTreeLoading.value = false
  }
}

const versionDialog = ref(false)
const versionMode = ref<'create' | 'edit'>('create')
const editingVersion = ref<ProductVersionItem | null>(null)
const versionFormRef = ref<FormInstance>()
const versionSubmitting = ref(false)
const versionForm = reactive({
  version: '',
  status: 'PLANNING' as ProductVersionStatus,
  planReleaseAt: '' as string,
  description: '',
})
const versionRules: FormRules = {
  version: [{ required: true, message: '请输入版本号', trigger: 'blur' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
  planReleaseAt: [
    {
      validator: (_rule, value, callback) => {
        if (versionForm.status === 'PLANNING' && !value) {
          callback(new Error('请选择计划发布时间'))
        } else {
          callback()
        }
      },
      trigger: 'change',
    },
  ],
}

async function load() {
  loading.value = true
  try {
    ;[products.value, managers.value] = await Promise.all([
      listProducts(),
      listProductManagers(),
    ])
    if (
      selectedId.value == null ||
      !products.value.some((p) => p.id === selectedId.value)
    ) {
      selectedId.value = products.value[0]?.id ?? null
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreateProduct() {
  productMode.value = 'create'
  productForm.code = ''
  productForm.name = ''
  productForm.description = ''
  productForm.managerUserId = undefined
  productForm.wikiMode = 'existing'
  productForm.wikiNodeId = undefined
  productForm.wikiParentId = undefined
  productForm.wikiNewName = ''
  productForm.codeRepos = [{ localPath: '', label: '', repoKind: 'BIZ' }]
  productDialog.value = true
  void loadWikiTree()
}

function openEditProduct() {
  if (!selected.value) return
  productMode.value = 'edit'
  productForm.code = selected.value.code
  productForm.name = selected.value.name
  productForm.description = selected.value.description ?? ''
  productForm.managerUserId = selected.value.managerUserId ?? undefined
  productForm.wikiMode = 'existing'
  productForm.wikiNodeId = selected.value.wikiNodeId ?? undefined
  productForm.wikiParentId = undefined
  productForm.wikiNewName = ''
  productForm.codeRepos = (selected.value.codeRepos?.length
    ? selected.value.codeRepos.map((r) => ({
        localPath: r.localPath,
        label: r.label || '',
        repoKind: r.repoKind || 'BIZ',
      }))
    : [{ localPath: '', label: '', repoKind: 'BIZ' }]) as { localPath: string; label: string; repoKind: ProductRepoKind }[]
  productDialog.value = true
  void loadWikiTree()
}

function addRepoRow() {
  productForm.codeRepos.push({ localPath: '', label: '', repoKind: 'BIZ' })
}

function removeRepoRow(idx: number) {
  productForm.codeRepos.splice(idx, 1)
  if (!productForm.codeRepos.length) {
    productForm.codeRepos.push({ localPath: '', label: '', repoKind: 'BIZ' })
  }
}

async function submitProduct() {
  await productFormRef.value?.validate()
  productSubmitting.value = true
  try {
    const repos = productForm.codeRepos
      .map((r) => ({
        localPath: r.localPath.trim(),
        repoKind: r.repoKind,
        label: r.label.trim() || undefined,
      }))
      .filter((r) => r.localPath)
    const payload: import('../../api/product').ProductSavePayload = {
      code: productForm.code.trim(),
      name: productForm.name.trim(),
      description: productForm.description.trim() || undefined,
      managerUserId: productForm.managerUserId ?? null,
      codeRepos: repos,
    }
    if (productForm.wikiMode === 'create' && productForm.wikiNewName.trim()) {
      payload.wikiCreate = {
        parentId: productForm.wikiParentId ?? null,
        displayName: productForm.wikiNewName.trim(),
      }
      payload.wikiNodeId = null
    } else {
      payload.wikiNodeId = productForm.wikiNodeId ?? null
      payload.wikiCreate = null
    }
    if (productMode.value === 'create') {
      const res = await createProduct(payload)
      ElMessage.success('已创建产品')
      await load()
      selectedId.value = res.id
    } else if (selected.value) {
      await updateProduct(selected.value.id, payload)
      ElMessage.success('已保存')
      await load()
    }
    productDialog.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    productSubmitting.value = false
  }
}

async function removeProduct() {
  if (!selected.value) return
  try {
    await ElMessageBox.confirm(
      `确定删除产品「${selected.value.name}」及其版本？`,
      '提示',
      { type: 'warning' },
    )
    await deleteProduct(selected.value.id)
    ElMessage.success('已删除')
    selectedId.value = null
    await load()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败')
    }
  }
}

function openCreateVersion() {
  if (!selected.value) return
  versionMode.value = 'create'
  editingVersion.value = null
  versionForm.version = ''
  versionForm.status = 'PLANNING'
  versionForm.planReleaseAt = ''
  versionForm.description = ''
  versionDialog.value = true
}

function openEditVersion(ver: ProductVersionItem) {
  versionMode.value = 'edit'
  editingVersion.value = ver
  versionForm.version = ver.version
  versionForm.status = ver.status || 'PLANNING'
  versionForm.planReleaseAt = ver.planReleaseAt ? formatDateTime(ver.planReleaseAt) : ''
  versionForm.description = ver.description ?? ''
  versionDialog.value = true
}

async function submitVersion() {
  if (!selected.value) return
  await versionFormRef.value?.validate()
  versionSubmitting.value = true
  try {
    const payload = {
      version: versionForm.version.trim(),
      status: versionForm.status,
      planReleaseAt: versionForm.planReleaseAt
        ? versionForm.planReleaseAt.replace(' ', 'T')
        : null,
      description: versionForm.description.trim() || undefined,
    }
    if (versionMode.value === 'create') {
      await createProductVersion(selected.value.id, payload)
      ElMessage.success('已添加版本')
    } else if (editingVersion.value) {
      await updateProductVersion(selected.value.id, editingVersion.value.id, payload)
      ElMessage.success('已保存')
    }
    versionDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    versionSubmitting.value = false
  }
}

async function removeVersion(ver: ProductVersionItem) {
  if (!selected.value) return
  try {
    await ElMessageBox.confirm(`确定删除版本「${ver.version}」？`, '提示', {
      type: 'warning',
    })
    await deleteProductVersion(selected.value.id, ver.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败')
    }
  }
}

onMounted(async () => {
  await load()
  await loadSandbox()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-hd">
      <h2>产品管理</h2>
      <el-button v-if="canOperate" type="primary" @click="openCreateProduct">新增产品</el-button>
    </div>
    <div class="body">
      <aside class="list">
        <button
          v-for="p in products"
          :key="p.id"
          type="button"
          class="list-item"
          :class="{ active: p.id === selectedId }"
          @click="selectedId = p.id"
        >
          <span class="list-name">{{ p.name }}</span>
          <span class="list-code">{{ p.code }}</span>
        </button>
        <div v-if="!products.length" class="empty">暂无产品</div>
      </aside>
      <section class="detail" v-if="selected">
        <div class="detail-hd">
          <div>
            <h3>{{ selected.name }}</h3>
            <p class="desc">
              编码：{{ selected.code }}
              <span class="sep">·</span>
              产品经理：{{ selected.managerUsername || '未设置' }}
              <span class="sep">·</span>
              Wiki：{{ selected.wikiPath || '未绑定' }}
              <span class="sep">·</span>
              仓库：{{ selected.codeRepos?.length || 0 }} 个
              <span class="sep">·</span>
              {{ selected.description || '暂无描述' }}
            </p>
          </div>
          <div v-if="canOperate" class="actions">
            <el-button @click="openEditProduct">编辑</el-button>
            <el-button type="danger" plain @click="removeProduct">删除</el-button>
          </div>
        </div>
        <div class="version-hd">
          <span>版本列表</span>
          <el-button v-if="canOperate" size="small" @click="openCreateVersion">
            添加版本
          </el-button>
        </div>
        <el-table :data="selected.versions || []" stripe>
          <el-table-column prop="version" label="版本号" min-width="120" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag
                :type="VERSION_STATUS_TAG[(row.status as ProductVersionStatus) || 'PLANNING']"
                size="small"
              >
                {{ row.statusLabel || row.status || '计划中' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="计划发布时间" min-width="160">
            <template #default="{ row }">
              {{ row.planReleaseAt ? formatDateTime(row.planReleaseAt) : '—' }}
            </template>
          </el-table-column>
          <el-table-column prop="description" label="说明" min-width="160" />
          <el-table-column v-if="canOperate" label="操作" width="140">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEditVersion(row)">编辑</el-button>
              <el-button link type="danger" @click="removeVersion(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!(selected.versions && selected.versions.length)" class="empty">
          暂无版本，请点击「添加版本」
        </div>

        <div class="version-hd" style="margin-top: 20px">
          <span>执行环境</span>
          <el-button v-if="canOperate" size="small" :loading="sandboxInitializing" :disabled="sandboxLoading || !!sandboxError" @click="ensureSandbox">
            补齐默认配置
          </el-button>
        </div>
        <div v-loading="sandboxLoading" class="sandbox-block">
          <p class="form-tip">
            配置 AI 编码、构建和测试时使用的隔离环境。每个产品独立保存方案；新方案从空白模板开始，不预填其他产品的端口或依赖地址。仅保存配置不会启动服务。
          </p>
          <el-alert v-if="sandboxError" :title="sandboxError" type="error" :closable="false" show-icon><el-button link type="primary" @click="loadSandbox">重新加载</el-button></el-alert>
          <div v-else-if="!sandboxLoading" class="environment-summary">
            <div><small>默认运行方案</small><strong>{{ sandboxProfiles.find(p => p.isDefault)?.displayName || sandboxProfiles.find(p => p.isDefault)?.profileCode || '尚未配置' }}</strong></div>
            <div><small>执行资源</small><strong>{{ sandboxResources.length }} 组配置</strong></div>
            <div><small>当前占用</small><strong>{{ sandboxLeases.length }} 个任务</strong></div>
          </div>
          <el-collapse v-model="sandboxDetails"><el-collapse-item name="resources" title="高级：执行资源与镜像">
          <p class="form-tip">由部署人员维护的容器镜像与并发名额。存在资源配置不代表执行节点或镜像已可用。</p>
          <el-table :data="sandboxResources" size="small" stripe empty-text="暂无资源，请点预置">
            <el-table-column prop="resourceCode" label="编码" min-width="100" />
            <el-table-column prop="image" label="镜像" min-width="180" />
            <el-table-column prop="maxSlots" label="并发名额" width="90" />
            <el-table-column prop="status" label="状态" width="90" />
          </el-table>
          </el-collapse-item></el-collapse>
          <div class="version-hd" style="margin-top: 4px">
            <h4 class="sandbox-sub" style="margin: 0">运行方案</h4>
            <el-button v-if="canOperate" size="small" :disabled="sandboxLoading || !!sandboxError" @click="openCreateProfile">新增运行方案</el-button>
          </div>
          <p class="form-tip">
            运行方案是一组端口、环境变量和执行时长设置。流水线未指定方案时使用默认项，可按需要配置多套。
          </p>
          <el-table :data="sandboxProfiles" size="small" stripe :empty-text="sandboxError ? '加载失败，请重试' : '还没有运行方案，点击「补齐默认配置」开始'">
            <el-table-column label="方案" min-width="160"><template #default="{ row }"><strong>{{ row.displayName || row.profileCode }}</strong><div class="form-tip">{{ row.profileCode }}</div></template></el-table-column>
            <el-table-column label="配置摘要" min-width="220"><template #default="{ row }">{{ runtimeSummary(row) }}</template></el-table-column>
            <el-table-column label="默认" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.isDefault" type="success" size="small">是</el-tag>
                <span v-else>否</span>
              </template>
            </el-table-column>
            <el-table-column v-if="canOperate" label="操作" width="220">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEditProfile(row)">编辑</el-button>
                <el-button
                  v-if="!row.isDefault"
                  link
                  type="primary"
                  @click="makeDefaultProfile(row.profileCode)"
                >
                  设默认
                </el-button>
                <el-button
                  v-if="!row.isDefault"
                  link
                  type="danger"
                  @click="removeProfile(row)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-collapse><el-collapse-item name="leases" :title="`执行占用明细（${sandboxLeases.length}）`">
          <el-table :data="sandboxLeases" size="small" stripe empty-text="无占用">
            <el-table-column prop="id" label="执行编号" width="90" />
            <el-table-column label="用途" width="100"><template #default="{ row }">{{ ({ PIPE_CODE: '开发编码', PIPE_IT: '集成测试' } as Record<string, string>)[row.purpose] || row.purpose }}</template></el-table-column>
            <el-table-column prop="sessionId" label="会话" width="90" />
            <el-table-column prop="worktreeHostPath" label="代码工作目录" min-width="200" show-overflow-tooltip />
            <el-table-column label="端口映射（容器→宿主）" min-width="180">
              <template #default="{ row }">{{ portMappingText(row) }}</template>
            </el-table-column>
            <el-table-column prop="expiresAt" label="过期" min-width="150" />
          </el-table>
          </el-collapse-item></el-collapse>
        </div>
      </section>
      <section v-else class="detail empty-detail">请选择左侧产品</section>
    </div>

    <el-dialog
      v-model="profileDialog"
      :title="profileMode === 'create' ? '新增运行方案' : '编辑运行方案'"
      width="min(860px, 96vw)"
      destroy-on-close
      :close-on-click-modal="!profileSubmitting"
      :close-on-press-escape="!profileSubmitting"
      :show-close="!profileSubmitting"
    >
      <el-alert type="info" :closable="false" show-icon class="runtime-product-note" :title="`当前产品：${products.find(p => p.id === profileProductId)?.name || '—'}。配置仅保存到此产品；新建方案不复制其他产品。`" />
      <el-form label-width="110px" :disabled="profileSubmitting">
        <el-form-item required>
          <template #label><span class="field-label">编码<FieldHelp content="方案唯一标识，流水线用它选择方案；创建后不可修改。不同产品可使用相同编码。" /></span></template>
          <el-input
            v-model="profileForm.profileCode"
            :disabled="profileMode === 'edit'"
            maxlength="64"
            placeholder="方案标识，例如 default、backend、integration"
          />
        </el-form-item>
        <el-form-item>
          <template #label><span class="field-label">名称<FieldHelp content="便于识别的名称，不影响项目、分支或程序启动参数。" /></span></template>
          <el-input v-model="profileForm.displayName" maxlength="128" placeholder="例如：日常研发环境、接口测试环境" />
        </el-form-item>
        <el-form-item>
          <template #label><span class="field-label">设为默认<FieldHelp content="流水线未指定方案时使用此项；修改对后续申请生效。" /></span></template>
          <el-switch v-model="profileForm.isDefault" />
        </el-form-item>
        <div class="runtime-editor-mode"><el-radio-group :model-value="profileEditorMode" @change="switchProfileEditor"><el-radio-button value="form">常用配置</el-radio-button><el-radio-button value="json">高级 JSON</el-radio-button></el-radio-group></div>
        <template v-if="profileEditorMode === 'form'">
        <el-divider content-position="left">数据库</el-divider>
        <el-form-item>
          <template #label><span class="field-label">连接<FieldHelp content="IP 需能从容器内访问（127.0.0.1 指容器自己，本机库填 host.docker.internal）。这里只提供取值，平台不预设变量名：在「其它变量」用 {database.host}、{database.port}、{database.name}、{database.username}、{database.password}、{database.schema} 引用，连接串直接在变量值里拼。模式留空时 {schema} 按 it_{productCode}_{reqNoSafe} 生成。" /></span></template>
          <div class="runtime-grid">
            <el-input v-model="runtimeForm.dbHost" placeholder="IP，如 10.0.0.12" aria-label="数据库 IP" />
            <el-input-number v-model="runtimeForm.dbPort" :min="1" :max="65535" :precision="0" placeholder="端口 5432" aria-label="数据库端口" controls-position="right" />
            <el-input v-model="runtimeForm.dbName" placeholder="库名，如 dap_dev" aria-label="库名" />
            <el-input v-model="runtimeForm.dbSchema" placeholder="模式，如 dap" aria-label="模式" />
            <el-input v-model="runtimeForm.dbUsername" placeholder="账号" aria-label="数据库账号" autocomplete="off" />
            <el-input v-model="runtimeForm.dbPassword" type="password" show-password placeholder="密码" aria-label="数据库密码" autocomplete="new-password" />
          </div>
        </el-form-item>

        <el-divider content-position="left">服务</el-divider>
        <el-form-item>
          <template #label><span class="field-label">端口<FieldHelp content="一行一个容器内服务：标识用于识别与被测试引用，容器端口是它监听的端口。平台为每行分配宿主端口并映射，实际值见「执行占用明细」。端口与地址变量在「其它变量」里用 {ports.<标识>}、{ports.<标识>.host} 声明。" /></span></template>
          <div style="width: 100%">
            <div class="service-port-labels">
              <span>服务标识</span><span>容器端口</span><span />
            </div>
            <div v-for="(service, index) in runtimeForm.services" :key="index" class="service-port-row">
              <el-input v-model="service.key" placeholder="如 api" aria-label="服务标识" />
              <el-input-number v-model="service.containerPort" :min="1" :max="65535" :precision="0" placeholder="如 8200" aria-label="容器端口" controls-position="right" />
              <el-button link type="danger" @click="runtimeForm.services.splice(index, 1)">删除</el-button>
            </div>
            <el-button size="small" @click="addRuntimeService">添加服务端口</el-button>
          </div>
        </el-form-item>
        <el-divider content-position="left">测试</el-divider>
        <el-form-item>
          <template #label><span class="field-label">启用测试<FieldHelp content="用 dap-test 在容器内测试这套环境：接口地址取服务标识 api 的端口，页面地址取 web 的。" /></span></template>
          <el-switch v-model="runtimeForm.testEnabled" />
        </el-form-item>
        <template v-if="runtimeForm.testEnabled">
          <el-form-item>
            <template #label><span class="field-label">管理员账号<FieldHelp content="测试会用它创建本次专用的用户和租户。" /></span></template>
            <div class="runtime-grid two">
              <el-input v-model="runtimeForm.testUsername" placeholder="账号" aria-label="测试账号" autocomplete="off" />
              <el-input v-model="runtimeForm.testPassword" type="password" show-password placeholder="密码" aria-label="测试账号密码" autocomplete="new-password" />
            </div>
          </el-form-item>
          <el-form-item>
            <template #label><span class="field-label">模型<FieldHelp content="真实模型只测接口和页面；Mock 不消耗额度且能测完整流程。服务标识不是 api / web 时，可在 JSON 的 testing.apiUrl、testing.uiUrl 直接写地址。" /></span></template>
            <el-radio-group v-model="runtimeForm.testModelMode"><el-radio value="real">真实模型</el-radio><el-radio value="mock">Mock</el-radio></el-radio-group>
          </el-form-item>
        </template>

        <el-collapse class="runtime-more">
          <el-collapse-item title="更多设置（一般不用改）" name="more">
            <el-form-item>
              <template #label><span class="field-label">测试命令<FieldHelp content="流水线「集成测试」节点在沙箱中执行的命令，退出码 0 为通过。留空时按仓库自动选择：有 package.json 执行 npm test，有 Gradle 执行 gradle test。" /></span></template>
              <el-input v-model="runtimeForm.testCommand" placeholder="留空自动识别" />
            </el-form-item>
            <el-form-item>
              <template #label><span class="field-label">命令超时<FieldHelp content="秒；构建或测试超过此时间判为失败。" /></span></template>
              <el-input-number v-model="runtimeForm.actionTimeoutSec" :min="1" :precision="0" placeholder="180" />
            </el-form-item>
            <el-form-item v-if="runtimeForm.testEnabled && runtimeForm.testModelMode === 'mock'" label="Mock 端口"><el-input-number v-model="runtimeForm.testMockPort" :min="1" :max="65535" :precision="0" placeholder="18202" /></el-form-item>
            <el-form-item label="容器镜像"><el-input v-model="runtimeForm.image" placeholder="留空使用执行资源的默认镜像" /></el-form-item>
            <el-form-item>
              <template #label><span class="field-label">有效时长<FieldHelp content="小时；到期回收执行环境。" /></span></template>
              <el-input-number v-model="runtimeForm.ttl" :min="1" :precision="0" />
            </el-form-item>
            <el-form-item label="宿主机端口池"><div class="runtime-port-pool"><el-input-number v-model="runtimeForm.portStart" :min="1" :max="65535" :precision="0" /> 至 <el-input-number v-model="runtimeForm.portEnd" :min="1" :max="65535" :precision="0" /></div></el-form-item>
            <el-form-item label="依赖缓存"><el-checkbox v-model="runtimeForm.cacheM2">Maven</el-checkbox><el-checkbox v-model="runtimeForm.cacheNpm">npm</el-checkbox></el-form-item>
            <el-form-item>
              <template #label><span class="field-label">其它变量<FieldHelp content="如 Redis 地址、模型密钥。勾选才注入。值支持占位符：{productCode}、{reqNo}、{reqNoSafe}、{tenantId}、{schema}、{redisKeyPrefix}、{mqPrefix}、{database.host/port/name/schema/username/password}、{ports.<服务标识>}（容器端口）、{ports.<服务标识>.host}（宿主端口）。" /></span></template>
              <div style="width: 100%">
                <div v-for="(row, idx) in profileForm.envVars" :key="idx" class="env-row">
                  <el-checkbox v-model="row.enabled" />
                  <el-input v-model="row.name" placeholder="变量名" style="width: 180px" />
                  <el-input v-model="row.value" :type="/password|secret|token|key/i.test(row.name) ? 'password' : 'text'" :show-password="/password|secret|token|key/i.test(row.name)" placeholder="值" style="flex: 1" />
                  <el-input v-model="row.remark" placeholder="备注" style="width: 100px" />
                  <el-button link type="danger" @click="removeProfileEnvRow(idx)">删</el-button>
                </div>
                <el-button size="small" @click="addProfileEnvRow">添加变量</el-button>
              </div>
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
        </template>
        <el-form-item v-else>
          <template #label><span class="field-label">完整配置 JSON<FieldHelp content="面向高级配置；切回常用配置会同步表单，保存使用当前模式内容。此处配置不等于数据库连通性检查。" /></span></template>
          <el-input
            v-model="profileForm.configJson"
            type="textarea"
            :rows="12"
            placeholder="端口、schema 模式、缓存等"
          />
          <details class="runtime-field-help"><summary>高级属性含义与生效边界</summary>
            <p><b>imageOverride：</b>本方案指定的镜像；null 表示使用产品执行资源的镜像。</p>
            <p><b>sandbox.leaseTtlHours：</b>执行环境的有效小时数。preferredResourceCodes 是优先尝试的资源编码列表，空数组表示不指定偏好。</p>
            <p><b>ports.services：</b>端口清单，是容器的唯一端口来源。key 是服务标识（同时用于测试引用），containerPort 是容器内监听端口；同一容器端口只映射一次。端口/地址变量在 envVars 里用 {ports.&lt;key&gt;}、{ports.&lt;key&gt;.host} 声明，protocol 不是启动命令，也不自动开启 HTTPS。</p>
            <p><b>ports.pool：</b>宿主机可分配的端口范围。实际分配结果写进租约的 runtimeInstance.ports（key → host / container）。</p>
            <p><b>envVars：</b>项目实际使用的环境变量；name 为名称，value 为值，enabled 控制是否注入，remark 仅作备注。envTemplates 是变量模板，同名时 envVars 优先；端口相关变量以 ports.services 为准。</p>
            <p><b>占位符：</b>envVars / envTemplates 的 value 支持 {productCode}、{reqNo}、{reqNoSafe}、{tenantId}、{schema}、{redisKeyPrefix}、{mqPrefix}、{database.host}、{database.port}、{database.name}、{database.schema}、{database.username}、{database.password}、{ports.&lt;key&gt;}、{ports.&lt;key&gt;.host}。未知占位符原样保留。</p>
            <p><b>database：</b>通用连接信息（host、port、name、schema、username、password），只作为占位符取值，不生成任何变量名。{schema} 取 database.schema；未填时按 it_{productCode}_{reqNoSafe} 生成（JSON 里可用 database.schemaPattern 覆盖）。不会仅凭此配置创建数据库或完成迁移。</p>
            <p><b>middleware：</b>redisKeyPrefixPattern、mqPrefixPattern 生成 {redisKeyPrefix}、{mqPrefix}，不是 Redis 或消息队列的连接地址。</p>
            <p><b>cacheMounts：</b>m2、npm 决定是否挂载依赖缓存。</p>
            <p><b>execution：</b>testCommand 为集成测试命令；buildCommand、startCommand 替换 Agent 调用的构建、启动动作（一般不需要）；actionTimeoutSec 为命令超时秒数。</p>
            <p><b>testing：</b>平台集成测试（dap-test）开关：username、password、modelMode（real/mock）、mockPort；接口/页面地址默认取服务标识 api / web 的容器端口，可在 JSON 里用 apiUrl、uiUrl 直接指定。相关 DAP_TEST_* 变量是测试约定，不属于产品运行时配置。</p>
            <p><b>未知字段：</b>startTemplates、flywayEnabled、dbHostRef、releaseWorktreeOnClose、autoCommitOnRelease 等历史字段在当前执行实现中未形成对应自动操作，不应当作已生效开关。</p>
          </details>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="profileSubmitting" style="float: left" @click="resetRuntimeTemplate">按原始模板重配</el-button>
        <el-button :disabled="profileSubmitting" @click="profileDialog = false">取消</el-button>
        <el-button type="primary" :loading="profileSubmitting" @click="submitProfile">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="productDialog"
      :title="productMode === 'create' ? '新增产品' : '编辑产品'"
      width="640px"
    >
      <el-form
        ref="productFormRef"
        :model="productForm"
        :rules="productRules"
        label-width="100px"
      >
        <el-form-item label="编码" prop="code">
          <el-input v-model="productForm.code" maxlength="64" placeholder="租户内唯一，如 DAP" />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="productForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="产品经理">
          <el-select
            v-model="productForm.managerUserId"
            clearable
            filterable
            placeholder="选择租户内用户"
            style="width: 100%"
          >
            <el-option
              v-for="m in managers"
              :key="m.id"
              :label="m.username"
              :value="m.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="productForm.description"
            type="textarea"
            maxlength="500"
            :rows="2"
          />
        </el-form-item>
        <el-form-item label="Wiki 上下文">
          <el-radio-group v-model="productForm.wikiMode" style="margin-bottom: 8px">
            <el-radio-button value="existing">选择已有目录</el-radio-button>
            <el-radio-button value="create">新建目录</el-radio-button>
          </el-radio-group>
          <div v-loading="wikiTreeLoading">
            <el-tree-select
              v-if="productForm.wikiMode === 'existing'"
              v-model="productForm.wikiNodeId"
              :data="wikiTree"
              check-strictly
              clearable
              filterable
              :props="{ label: 'displayName', value: 'id', children: 'children' }"
              placeholder="选择 Wiki 目录（可空）"
              style="width: 100%"
            />
            <template v-else>
              <el-tree-select
                v-model="productForm.wikiParentId"
                :data="wikiTree"
                check-strictly
                clearable
                filterable
                :props="{ label: 'displayName', value: 'id', children: 'children' }"
                placeholder="父目录（空=Wiki 根）"
                style="width: 100%; margin-bottom: 8px"
              />
              <el-input v-model="productForm.wikiNewName" maxlength="200" placeholder="新建目录名" />
            </template>
          </div>
          <div class="form-tip">产品 Wiki 上下文根目录；需求文档可按「需求编码」分子目录存放</div>
        </el-form-item>
        <el-form-item label="代码仓库">
          <div v-for="(row, idx) in productForm.codeRepos" :key="idx" class="repo-row">
            <el-input
              v-model="row.localPath"
              placeholder="本地 Git 仓库绝对路径（须已 git init/clone；编码按需求编号建 feat/需求号 分支）"
            />
            <el-input v-model="row.label" placeholder="备注" style="width: 120px" />
            <el-select v-model="row.repoKind" aria-label="仓库用途" style="width: 140px; flex-shrink: 0">
              <el-option label="业务代码" value="BIZ" />
              <el-option label="自动化测试" value="TEST" />
              <el-option label="其他" value="OTHER" />
            </el-select>
            <el-button link type="danger" @click="removeRepoRow(idx)">删</el-button>
          </div>
          <el-button size="small" @click="addRepoRow">添加仓库</el-button>
          <div class="form-tip">自动化测试仓库会被存量集成测试优先选用。这里填写 Git 仓库根目录；若测试位于同一仓库的 dap-test 子目录，仍需另行适配测试入口，不能直接把普通子目录当作独立仓库。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="productDialog = false">取消</el-button>
        <el-button type="primary" :loading="productSubmitting" @click="submitProduct">
          保存
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="versionDialog"
      :title="versionMode === 'create' ? '添加版本' : '编辑版本'"
      width="480px"
    >
      <el-form
        ref="versionFormRef"
        :model="versionForm"
        :rules="versionRules"
        label-width="112px"
      >
        <el-form-item label="版本号" prop="version">
          <el-input v-model="versionForm.version" maxlength="64" placeholder="如 1.0.0" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="versionForm.status" style="width: 100%">
            <el-option
              v-for="opt in VERSION_STATUS_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="versionForm.status === 'PLANNING'"
          label="计划发布时间"
          prop="planReleaseAt"
        >
          <el-date-picker
            v-model="versionForm.planReleaseAt"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="选择计划发布时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="说明">
          <el-input
            v-model="versionForm.description"
            type="textarea"
            maxlength="500"
            :rows="3"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="versionDialog = false">取消</el-button>
        <el-button type="primary" :loading="versionSubmitting" @click="submitVersion">
          保存
        </el-button>
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
.list-name {
  font-weight: 500;
}
.list-code {
  font-size: 12px;
  opacity: 0.7;
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
.sep {
  margin: 0 6px;
}
.version-hd {
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
.form-tip {
  margin-top: 6px;
  color: var(--dap-gray-500);
  font-size: 12px;
}
.repo-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  width: 100%;
}
.repo-row .el-input:first-child {
  flex: 1;
}
.sandbox-block {
  margin-bottom: var(--dap-space-4);
}
.environment-summary { display:grid; grid-template-columns:repeat(3, minmax(0,1fr)); gap:16px; margin:18px 0; padding:18px; border:1px solid var(--dap-gray-200); border-radius:8px; background:var(--dap-gray-50); }
.environment-summary small { display:block; font-size:11px; color:var(--dap-gray-500); margin-bottom:8px; }
.environment-summary strong { font-size:14px; font-weight:500; color:var(--dap-gray-900); overflow-wrap:anywhere; }
.runtime-editor-mode { margin:8px 0 24px 110px; }
.field-label { display:inline-flex; align-items:center; }
.runtime-product-note { margin-bottom:20px; }
.service-port-labels { display:grid; grid-template-columns:minmax(120px,1fr) 150px auto; gap:8px; margin-top:16px; font-size:11px; color:var(--dap-gray-500); }
.runtime-field-help { margin-top:12px; padding:12px 16px; background:var(--dap-gray-50); border-radius:6px; font-size:12px; line-height:1.8; color:var(--dap-gray-700); }
.runtime-field-help p { margin:4px 0; }
.env-labels { display:flex; gap:18px; width:100%; margin:8px 0; font-size:11px; color:var(--dap-gray-500); }
.env-labels span:nth-child(2) { width:160px; } .env-labels span:nth-child(3) { flex:1; }
.service-port-row { display:grid; grid-template-columns:minmax(120px,1fr) 150px auto; gap:8px; margin:10px 0; align-items:center; }
.runtime-grid { display:grid; grid-template-columns:repeat(3, minmax(0,1fr)); gap:8px; width:100%; }
.runtime-grid.two { grid-template-columns:repeat(2, minmax(0,1fr)); }
.runtime-grid .el-input-number,
.runtime-grid .el-select { width:100%; }
.runtime-more { margin:8px 0 0; border-top:none; }
.runtime-port-pool { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.env-row { width:100%; flex-wrap:wrap; }
@media(max-width:700px) { .environment-summary { grid-template-columns:1fr; } .service-port-row,.service-port-labels { grid-template-columns:1fr 1fr; } .runtime-editor-mode { margin-left:0; } .env-labels { display:none; } }
.sandbox-sub {
  margin: 12px 0 8px;
  font-size: 13px;
  font-weight: 500;
  color: var(--dap-gray-700);
}
.env-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.env-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
</style>

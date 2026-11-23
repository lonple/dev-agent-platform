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
import { formatDateTime } from '../../utils/date'
import { readRuntimeProfile, writeRuntimeProfile } from '../../utils/runtimeProfile'

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
    return `${value.services.map(s => `${s.key} : ${s.containerPort}`).join(' · ') || '未配置服务端口'} · 有效 ${value.ttl} 小时`
  } catch { return '配置格式异常，请编辑检查' }
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
  codeRepos: [{ localPath: '', label: '' }] as { localPath: string; label: string }[],
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
  productForm.codeRepos = [{ localPath: '', label: '' }]
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
      }))
    : [{ localPath: '', label: '' }]) as { localPath: string; label: string }[]
  productDialog.value = true
  void loadWikiTree()
}

function addRepoRow() {
  productForm.codeRepos.push({ localPath: '', label: '' })
}

function removeRepoRow(idx: number) {
  productForm.codeRepos.splice(idx, 1)
  if (!productForm.codeRepos.length) {
    productForm.codeRepos.push({ localPath: '', label: '' })
  }
}

async function submitProduct() {
  await productFormRef.value?.validate()
  productSubmitting.value = true
  try {
    const repos = productForm.codeRepos
      .map((r) => ({
        localPath: r.localPath.trim(),
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
            配置 AI 编码、构建和测试时使用的隔离环境。先补齐默认配置，再按项目修改端口与依赖地址；仅保存配置不会启动服务。
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
      <el-form label-width="110px" :disabled="profileSubmitting">
        <el-form-item label="编码" required>
          <el-input
            v-model="profileForm.profileCode"
            :disabled="profileMode === 'edit'"
            maxlength="64"
            placeholder="方案标识，例如 default、backend、integration"
          />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="profileForm.displayName" maxlength="128" placeholder="例如：日常研发环境、接口测试环境" />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="profileForm.isDefault" />
          <span class="form-tip" style="margin-left: 12px">流水线未指定方案时使用此项；修改对后续申请生效。</span>
        </el-form-item>
        <div class="runtime-editor-mode"><el-radio-group :model-value="profileEditorMode" @change="switchProfileEditor"><el-radio-button value="form">常用配置</el-radio-button><el-radio-button value="json">高级 JSON</el-radio-button></el-radio-group></div>
        <template v-if="profileEditorMode === 'form'">
        <el-form-item label="容器镜像"><el-input v-model="runtimeForm.image" placeholder="留空使用执行资源的默认镜像" /></el-form-item>
        <el-form-item label="有效时长"><el-input-number v-model="runtimeForm.ttl" :min="1" :precision="0" /><span class="form-tip" style="margin-left: 12px">小时，任务申请的执行环境到期后回收。</span></el-form-item>
        <el-form-item label="服务端口">
          <div style="width: 100%"><p class="form-tip">填写程序在容器内监听的端口；对外访问端口由平台从下方范围自动分配。</p>
          <div v-for="(service, index) in runtimeForm.services" :key="index" class="service-port-row">
            <el-input v-model="service.key" placeholder="服务标识，如 api" aria-label="服务标识" />
            <el-input-number v-model="service.containerPort" :min="1" :max="65535" :precision="0" aria-label="容器内端口" />
            <el-input v-model="service.env" placeholder="端口变量，如 SERVER_PORT" aria-label="端口环境变量名" />
            <el-button link type="danger" @click="runtimeForm.services.splice(index, 1)">删除</el-button>
          </div>
          <el-button size="small" @click="runtimeForm.services.push({ key: '', containerPort: 8080, env: '', protocol: 'http' })">添加服务端口</el-button></div>
        </el-form-item>
        <el-form-item label="宿主机端口池"><div class="runtime-port-pool"><el-input-number v-model="runtimeForm.portStart" :min="1" :max="65535" :precision="0" /> 至 <el-input-number v-model="runtimeForm.portEnd" :min="1" :max="65535" :precision="0" /></div><div class="form-tip">通常保留默认范围；无需给每个任务手动指定访问端口。</div></el-form-item>
        <el-form-item label="依赖缓存"><el-checkbox v-model="runtimeForm.cacheM2">复用 Maven 缓存</el-checkbox><el-checkbox v-model="runtimeForm.cacheNpm">复用 npm 缓存</el-checkbox></el-form-item>
        <el-form-item label="依赖与变量">
          <div class="form-tip" style="margin-bottom: 8px">
            按项目实际读取的变量名配置数据库、Redis 或外部接口地址，例如 SPRING_DATASOURCE_URL。地址必须能从容器内访问，localhost 指容器自身。填写变量不会自动启动依赖服务。
          </div>
          <div v-for="(row, idx) in profileForm.envVars" :key="idx" class="env-row">
            <el-checkbox v-model="row.enabled" />
            <el-input v-model="row.name" placeholder="变量名" style="width: 180px" />
            <el-input v-model="row.value" :type="/password|secret|token|key/i.test(row.name) ? 'password' : 'text'" :show-password="/password|secret|token|key/i.test(row.name)" placeholder="容器内可访问的地址或配置值" style="flex: 1" />
            <el-input v-model="row.remark" placeholder="备注" style="width: 100px" />
            <el-button link type="danger" @click="removeProfileEnvRow(idx)">删</el-button>
          </div>
          <el-button size="small" @click="addProfileEnvRow">添加变量</el-button>
        </el-form-item>
        </template>
        <el-form-item v-else label="完整配置 JSON">
          <el-input
            v-model="profileForm.configJson"
            type="textarea"
            :rows="12"
            placeholder="端口、schema 模式、缓存等"
          />
          <div class="form-tip">
            面向高级配置，数据库隔离模板等额外字段会保留。切回常用配置会同步表单；保存使用当前模式内容。此处配置不等于数据库连通性检查。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
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
            <el-button link type="danger" @click="removeRepoRow(idx)">删</el-button>
          </div>
          <el-button size="small" @click="addRepoRow">添加仓库</el-button>
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
.service-port-row { display:grid; grid-template-columns:minmax(90px,1fr) 150px minmax(120px,1.5fr) auto; gap:8px; margin:10px 0; align-items:center; }
.runtime-port-pool { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.env-row { width:100%; flex-wrap:wrap; }
@media(max-width:700px) { .environment-summary { grid-template-columns:1fr; } .service-port-row { grid-template-columns:1fr 1fr; } .runtime-editor-mode { margin-left:0; } }
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

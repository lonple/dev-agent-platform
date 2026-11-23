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
const sandboxResources = ref<SandboxResource[]>([])
const sandboxProfiles = ref<SandboxProfile[]>([])
const sandboxLeases = ref<any[]>([])

async function loadSandbox() {
  if (!selectedId.value) {
    sandboxResources.value = []
    sandboxProfiles.value = []
    sandboxLeases.value = []
    return
  }
  sandboxLoading.value = true
  try {
    ;[sandboxResources.value, sandboxProfiles.value, sandboxLeases.value] = await Promise.all([
      listSandboxResources(selectedId.value),
      listSandboxProfiles(selectedId.value),
      listSandboxLeases(selectedId.value),
    ])
  } catch {
    sandboxResources.value = []
    sandboxProfiles.value = []
    sandboxLeases.value = []
  } finally {
    sandboxLoading.value = false
  }
}

async function ensureSandbox() {
  if (!selectedId.value) return
  try {
    await ensureSandboxBundle(selectedId.value)
    ElMessage.success('已预置 webdev 资源池与默认 Profile')
    await loadSandbox()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '预置失败')
  }
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
const profileForm = reactive({
  profileCode: '',
  displayName: '',
  isDefault: false,
  configJson: DEFAULT_RUNTIME_PROFILE_JSON,
  envVars: [] as SandboxEnvVar[],
})

function openCreateProfile() {
  profileMode.value = 'create'
  profileForm.profileCode = ''
  profileForm.displayName = ''
  profileForm.isDefault = sandboxProfiles.value.length === 0
  profileForm.configJson = DEFAULT_RUNTIME_PROFILE_JSON
  profileForm.envVars = []
  profileDialog.value = true
}

function openEditProfile(row: SandboxProfile) {
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
  profileDialog.value = true
}

function addProfileEnvRow() {
  profileForm.envVars.push({ name: '', value: '', enabled: true, remark: '' })
}

function removeProfileEnvRow(idx: number) {
  profileForm.envVars.splice(idx, 1)
}

async function submitProfile() {
  if (!selectedId.value) return
  const code = profileForm.profileCode.trim()
  if (!code) {
    ElMessage.warning('请填写 Profile 编码')
    return
  }
  let configJson: string
  try {
    configJson = mergeEnvVarsIntoConfig(profileForm.configJson, profileForm.envVars)
  } catch {
    ElMessage.error('高级配置 JSON 不合法')
    return
  }
  profileSubmitting.value = true
  try {
    await upsertSandboxProfile(selectedId.value, {
      profileCode: code,
      displayName: profileForm.displayName.trim() || code,
      isDefault: profileForm.isDefault,
      configJson,
    })
    ElMessage.success(profileMode.value === 'create' ? '已创建 Profile' : '已更新 Profile')
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
          <span>沙箱与运行时</span>
          <el-button v-if="canOperate" size="small" :loading="sandboxLoading" @click="ensureSandbox">
            预置 webdev 默认
          </el-button>
        </div>
        <div v-loading="sandboxLoading" class="sandbox-block">
          <p class="form-tip">
            只读查代码走本机；编码/编译/启动申请 webdev 容器。
            依赖环境变量在各自 Profile 里配（编辑 Profile），申请该 Profile 时 -e 注入。
          </p>
          <h4 class="sandbox-sub">资源池</h4>
          <el-table :data="sandboxResources" size="small" stripe empty-text="暂无资源，请点预置">
            <el-table-column prop="resourceCode" label="编码" min-width="100" />
            <el-table-column prop="image" label="镜像" min-width="180" />
            <el-table-column prop="maxSlots" label="席位" width="80" />
            <el-table-column prop="status" label="状态" width="90" />
          </el-table>
          <div class="version-hd" style="margin-top: 4px">
            <h4 class="sandbox-sub" style="margin: 0">运行时 Profile</h4>
            <el-button v-if="canOperate" size="small" @click="openCreateProfile">新增 Profile</el-button>
          </div>
          <p class="form-tip">
            每个产品可多套（如 default / 仅后端 / IT 多端口）。流水线不指定时用「默认」那套；不同产品各自维护，互不影响。
          </p>
          <el-table :data="sandboxProfiles" size="small" stripe empty-text="暂无 Profile，可预置或新增">
            <el-table-column prop="profileCode" label="编码" min-width="100" />
            <el-table-column prop="displayName" label="名称" min-width="120" />
            <el-table-column label="默认" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.isDefault" type="success" size="small">是</el-tag>
                <span v-else>否</span>
              </template>
            </el-table-column>
            <el-table-column prop="version" label="版本" width="70" />
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
          <h4 class="sandbox-sub">当前占用</h4>
          <el-table :data="sandboxLeases" size="small" stripe empty-text="无占用">
            <el-table-column prop="id" label="lease" width="90" />
            <el-table-column prop="purpose" label="用途" width="100" />
            <el-table-column prop="sessionId" label="会话" width="90" />
            <el-table-column prop="worktreeHostPath" label="worktree" min-width="200" show-overflow-tooltip />
            <el-table-column prop="expiresAt" label="过期" min-width="150" />
          </el-table>
        </div>
      </section>
      <section v-else class="detail empty-detail">请选择左侧产品</section>
    </div>

    <el-dialog
      v-model="profileDialog"
      :title="profileMode === 'create' ? '新增运行时 Profile' : '编辑运行时 Profile'"
      width="720px"
      destroy-on-close
    >
      <el-form label-width="100px">
        <el-form-item label="编码" required>
          <el-input
            v-model="profileForm.profileCode"
            :disabled="profileMode === 'edit'"
            maxlength="64"
            placeholder="如 default / java-api / it-full"
          />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="profileForm.displayName" maxlength="128" placeholder="展示名" />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="profileForm.isDefault" />
        </el-form-item>
        <el-form-item label="环境变量">
          <div class="form-tip" style="margin-bottom: 8px">
            跟本 Profile 走。例：USER_CENTER_URL、SPRING_DATASOURCE_URL；开发初期可空。
          </div>
          <div v-for="(row, idx) in profileForm.envVars" :key="idx" class="env-row">
            <el-checkbox v-model="row.enabled" />
            <el-input v-model="row.name" placeholder="变量名" style="width: 180px" />
            <el-input v-model="row.value" placeholder="值" style="flex: 1" />
            <el-input v-model="row.remark" placeholder="备注" style="width: 100px" />
            <el-button link type="danger" @click="removeProfileEnvRow(idx)">删</el-button>
          </div>
          <el-button size="small" @click="addProfileEnvRow">添加变量</el-button>
        </el-form-item>
        <el-form-item label="高级 JSON">
          <el-input
            v-model="profileForm.configJson"
            type="textarea"
            :rows="12"
            placeholder="端口、schema 模式、缓存等"
          />
          <div class="form-tip">
            改 ports / schemaPattern / cacheMounts 等；保存时会把上方环境变量写入 envVars。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="profileDialog = false">取消</el-button>
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

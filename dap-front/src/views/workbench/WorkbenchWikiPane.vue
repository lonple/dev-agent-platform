<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Document, Folder, MoreFilled, Plus, Refresh } from '@element-plus/icons-vue'
import {
  createWikiDir,
  createWikiFile,
  deleteWikiNode,
  getWikiFileContent,
  listWikiTree,
  updateWikiFileContent,
  type WikiFileContent,
  type WikiNodeItem,
} from '../../api/wiki'
import MarkdownBody from '../../components/MarkdownBody.vue'
import HtmlPreview from '../../components/HtmlPreview.vue'
import { isHtmlPreview, isMdFile } from '../../utils/fileKind'

const fileTree = ref<WikiNodeItem[]>([])
const loadingTree = ref(false)
const selectedDirId = ref<number | null>(null)
const activeFile = ref<WikiFileContent | null>(null)
const loadingFile = ref(false)
/** preview：渲染 Markdown / HTML；edit：源码编辑 */
const fileViewMode = ref<'preview' | 'edit'>('preview')
const editingContent = ref('')
const savingFile = ref(false)

const nodeDialogVisible = ref(false)
const nodeDialogMode = ref<'dir' | 'file'>('dir')
const nodeName = ref('')
const nodeSubmitting = ref(false)
const createParentId = ref<number | null>(null)
const mdUploadInputRef = ref<HTMLInputElement | null>(null)
const mdUploadParentId = ref<number | null>(null)
/** 重新上传：覆盖指定文件内容，保留原 displayName */
const reuploadNodeId = ref<number | null>(null)
const reuploadDisplayName = ref('')
const reuploadInputRef = ref<HTMLInputElement | null>(null)

async function refreshFileTree() {
  loadingTree.value = true
  try {
    fileTree.value = await listWikiTree()
  } catch (e) {
    fileTree.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载 Wiki 失败')
  } finally {
    loadingTree.value = false
  }
}

async function onFileTreeClick(data: WikiNodeItem) {
  if (data.nodeType === 'DIR') {
    selectedDirId.value = data.id
    return
  }
  loadingFile.value = true
  try {
    activeFile.value = await getWikiFileContent(data.id)
    editingContent.value = activeFile.value.content
    fileViewMode.value = 'preview'
  } catch (e) {
    activeFile.value = null
    ElMessage.error(e instanceof Error ? e.message : '打开文件失败')
  } finally {
    loadingFile.value = false
  }
}

function enterEdit() {
  if (!activeFile.value) return
  editingContent.value = activeFile.value.content
  fileViewMode.value = 'edit'
}

function cancelEdit() {
  if (!activeFile.value) return
  editingContent.value = activeFile.value.content
  fileViewMode.value = 'preview'
}

async function saveFile() {
  if (!activeFile.value) return
  savingFile.value = true
  try {
    await updateWikiFileContent(activeFile.value.id, editingContent.value)
    activeFile.value = { ...activeFile.value, content: editingContent.value }
    fileViewMode.value = 'preview'
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    savingFile.value = false
  }
}

async function removeNode(node: WikiNodeItem) {
  try {
    await ElMessageBox.confirm(`确定删除「${node.displayName}」？`, '提示', { type: 'warning' })
    await deleteWikiNode(node.id)
    if (activeFile.value?.id === node.id) {
      activeFile.value = null
      fileViewMode.value = 'preview'
    }
    if (selectedDirId.value === node.id) selectedDirId.value = null
    ElMessage.success('已删除')
    await refreshFileTree()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

function openCreateNode(mode: 'dir' | 'file', parentId: number | null = null) {
  createParentId.value = parentId
  nodeDialogMode.value = mode
  nodeName.value = mode === 'file' ? '未命名.md' : ''
  nodeDialogVisible.value = true
}

function openCreateRootDir() {
  selectedDirId.value = null
  openCreateNode('dir', null)
}

function onNodeMenu(cmd: string, data: WikiNodeItem) {
  if (cmd === 'new-dir') {
    openCreateNode('dir', data.nodeType === 'DIR' ? data.id : data.parentId ?? null)
  } else if (cmd === 'new-file') {
    openCreateNode('file', data.nodeType === 'DIR' ? data.id : data.parentId ?? null)
  } else if (cmd === 'upload-md') {
    openUploadMd(data.nodeType === 'DIR' ? data.id : data.parentId ?? null)
  } else if (cmd === 'reupload') {
    openReupload(data)
  } else if (cmd === 'delete') {
    void removeNode(data)
  }
}

function openUploadMd(parentId: number | null) {
  mdUploadParentId.value = parentId
  const input = mdUploadInputRef.value
  if (!input) return
  input.value = ''
  input.click()
}

function openReupload(node: WikiNodeItem) {
  if (node.nodeType !== 'FILE') return
  reuploadNodeId.value = node.id
  reuploadDisplayName.value = node.displayName
  const input = reuploadInputRef.value
  if (!input) return
  input.value = ''
  input.click()
}

async function onReuploadChange(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const nodeId = reuploadNodeId.value
  const keepName = reuploadDisplayName.value
  reuploadNodeId.value = null
  if (!file || nodeId == null) return

  const lower = file.name.trim().toLowerCase()
  if (!lower.endsWith('.md') && !lower.endsWith('.html') && !lower.endsWith('.htm')) {
    ElMessage.warning('仅支持选择 .md / .html 文件覆盖内容')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将用本地文件覆盖「${keepName}」的内容（文件名不变），是否继续？`,
      '重新上传',
      { type: 'warning', confirmButtonText: '覆盖', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    const content = await readFileAsText(file)
    await updateWikiFileContent(nodeId, content)
    if (activeFile.value?.id === nodeId) {
      activeFile.value = { ...activeFile.value, content }
      editingContent.value = content
      fileViewMode.value = 'preview'
    }
    ElMessage.success(`已覆盖「${keepName}」`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重新上传失败')
  }
}

function readFileAsText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(reader.error ?? new Error('读取文件失败'))
    reader.readAsText(file, 'UTF-8')
  })
}

async function onMdUploadChange(ev: Event) {
  const input = ev.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return

  const parentId = mdUploadParentId.value
  let ok = 0
  for (const file of files) {
    const name = file.name.trim()
    const lower = name.toLowerCase()
    if (!lower.endsWith('.md') && !lower.endsWith('.html') && !lower.endsWith('.htm')) {
      ElMessage.warning(`仅支持 .md / .html：${name || file.name}`)
      continue
    }
    try {
      const content = await readFileAsText(file)
      await createWikiFile({ parentId, displayName: name, content })
      ok += 1
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : `上传失败：${name}`)
    }
  }
  if (ok) {
    ElMessage.success(ok === 1 ? '已上传' : `已上传 ${ok} 个文件`)
    await refreshFileTree()
  }
}

async function submitCreateNode() {
  let name = nodeName.value.trim()
  if (!name) {
    ElMessage.warning('请输入名称')
    return
  }
  if (nodeDialogMode.value === 'file') {
    if (!isMdFile(name) && !name.includes('.')) name = `${name}.md`
    if (!isMdFile(name)) {
      ElMessage.warning('新建仅支持 Markdown（.md）文件；HTML 请通过上传或 Agent 输出')
      return
    }
  }
  nodeSubmitting.value = true
  try {
    const parentId = createParentId.value == null ? null : createParentId.value
    if (nodeDialogMode.value === 'dir') {
      await createWikiDir({ parentId, displayName: name })
    } else {
      await createWikiFile({ parentId, displayName: name, content: '' })
    }
    nodeDialogVisible.value = false
    ElMessage.success('已创建')
    await refreshFileTree()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    nodeSubmitting.value = false
  }
}

onMounted(() => {
  void refreshFileTree()
})
</script>

<template>
  <div class="wiki-pane">
    <aside class="col left">
      <div class="pane-hd">
        <span class="title">Work Wiki</span>
        <el-button class="icon-btn" text :icon="Plus" title="新建根目录" @click="openCreateRootDir" />
        <el-button class="icon-btn" text :icon="Refresh" title="刷新" @click="refreshFileTree" />
      </div>
      <div class="left-body" v-loading="loadingTree">
        <el-tree
          class="file-tree"
          :data="fileTree"
          node-key="id"
          default-expand-all
          highlight-current
          :current-node-key="selectedDirId ?? undefined"
          :props="{ label: 'displayName', children: 'children' }"
          @node-click="onFileTreeClick"
        >
          <template #default="{ data }">
            <div class="tree-node">
              <el-icon><Folder v-if="data.nodeType === 'DIR'" /><Document v-else /></el-icon>
              <span class="tree-label">{{ data.displayName }}</span>
              <el-dropdown
                class="row-actions"
                trigger="click"
                @command="(c: string) => onNodeMenu(c, data)"
                @click.stop
              >
                <button type="button" class="more-btn" title="更多" @click.stop>
                  <el-icon class="more-v"><MoreFilled /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-if="data.nodeType === 'DIR'" command="new-dir">
                      新增文件夹
                    </el-dropdown-item>
                    <el-dropdown-item v-if="data.nodeType === 'DIR'" command="new-file">
                      新增 md
                    </el-dropdown-item>
                    <el-dropdown-item v-if="data.nodeType === 'DIR'" command="upload-md">
                      上传 md/html
                    </el-dropdown-item>
                    <el-dropdown-item v-if="data.nodeType === 'FILE'" command="reupload">
                      重新上传
                    </el-dropdown-item>
                    <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-tree>
        <div v-if="!fileTree.length && !loadingTree" class="empty">暂无 Wiki 文档，可点击 + 新建</div>
      </div>
    </aside>

    <section class="col main" v-loading="loadingFile">
      <template v-if="activeFile">
        <div class="pane-hd">
          <span class="title">{{ activeFile.displayName }}</span>
          <template v-if="fileViewMode === 'preview'">
            <el-button
              size="small"
              @click="
                openReupload({
                  id: activeFile.id,
                  displayName: activeFile.displayName,
                  nodeType: 'FILE',
                })
              "
            >
              重新上传
            </el-button>
            <el-button type="primary" size="small" @click="enterEdit">编辑</el-button>
          </template>
          <template v-else>
            <el-button size="small" @click="cancelEdit">取消</el-button>
            <el-button type="primary" size="small" :loading="savingFile" @click="saveFile">保存</el-button>
          </template>
        </div>
        <div class="main-body" :class="{ editing: fileViewMode === 'edit' }">
          <div v-if="fileViewMode === 'preview'" class="md-preview">
            <HtmlPreview
              v-if="isHtmlPreview(activeFile.displayName)"
              :content="activeFile.content"
            />
            <MarkdownBody v-else :content="activeFile.content" />
          </div>
          <el-input
            v-else
            v-model="editingContent"
            type="textarea"
            class="file-editor"
            resize="none"
          />
        </div>
      </template>
      <div v-else class="mid-empty">
        <p>从左侧选择 md / html 文件</p>
        <p class="sub">租户级 Work Wiki，供会话上下文与任务输入引用；新建仅支持 md</p>
      </div>
    </section>

    <input
      ref="mdUploadInputRef"
      type="file"
      accept=".md,.html,.htm,text/markdown,text/html"
      multiple
      class="hidden-file-input"
      @change="onMdUploadChange"
    />
    <input
      ref="reuploadInputRef"
      type="file"
      accept=".md,.html,.htm,text/markdown,text/html"
      class="hidden-file-input"
      @change="onReuploadChange"
    />

    <el-dialog
      v-model="nodeDialogVisible"
      :title="nodeDialogMode === 'dir' ? '新建目录' : '新建 Markdown'"
      width="400px"
      destroy-on-close
    >
      <el-input v-model="nodeName" :placeholder="nodeDialogMode === 'file' ? '文件名（仅 .md）' : '名称'" />
      <template #footer>
        <el-button @click="nodeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="nodeSubmitting" @click="submitCreateNode">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hidden-file-input {
  display: none;
}
.wiki-pane {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  background: var(--dap-gray-50);
}
.col {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: var(--dap-gray-0);
  border-right: 1px solid var(--dap-gray-200);
}
.left {
  width: 300px;
  flex-shrink: 0;
}
.main {
  flex: 1;
  border-right: none;
}
.pane-hd {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--dap-gray-200);
  flex-shrink: 0;
}
.title {
  font-weight: 600;
  flex: 1;
}
.left-body {
  flex: 1;
  overflow: auto;
  padding: 8px;
}
.main-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 0;
}
.main-body.editing {
  padding: 0;
}
.md-preview {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
}
.md-preview :deep(.html-preview) {
  flex: 1;
  min-height: 480px;
}
.file-editor {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
}
.file-editor :deep(.el-textarea) {
  flex: 1;
  height: 100%;
}
.file-editor :deep(.el-textarea__inner) {
  height: 100% !important;
  min-height: 100% !important;
  border: none;
  border-radius: 0;
  box-shadow: none;
  padding: 16px 20px;
  resize: none;
}
.icon-btn {
  padding: 4px !important;
  margin: 0 !important;
  border: none !important;
  background: transparent !important;
}
.file-tree :deep(.el-tree-node__content) {
  height: 32px;
  padding-right: 4px;
}
.tree-node {
  display: flex;
  flex: 1 1 auto;
  width: 0;
  min-width: 0;
  align-items: center;
  gap: 4px;
}
.tree-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-actions {
  opacity: 0;
  pointer-events: none;
  flex-shrink: 0;
}
.file-tree :deep(.el-tree-node__content:hover) .row-actions,
.row-actions:focus-within {
  opacity: 1;
  pointer-events: auto;
}
.more-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  border-radius: 4px;
}
.more-v {
  transform: rotate(90deg);
}
.mid-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--dap-gray-500);
}
.mid-empty .sub {
  font-size: 12px;
  margin-top: 6px;
}
.file-editor :deep(textarea) {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
}
.empty {
  padding: 16px;
  color: var(--dap-gray-500);
  text-align: center;
}
</style>

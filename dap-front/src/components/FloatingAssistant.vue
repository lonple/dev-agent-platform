<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Close, Delete, Plus } from '@element-plus/icons-vue'
import {
  deleteSession,
  listMessages,
  listSessions,
  stopGeneration,
  streamCompletion,
  type ChatMessage,
  type ChatSession,
} from '../api/chat'
import { useAuthStore } from '../stores/auth'
import { useWorkbenchUiStore } from '../stores/workbenchUi'
import MarkdownBody from './MarkdownBody.vue'

interface Tab {
  key: string
  sessionId: number | null
  draft: boolean
  clientTempId?: string
  title: string
}

const auth = useAuthStore()
const workbenchUi = useWorkbenchUiStore()

const visible = computed(
  () =>
    auth.isLoggedIn &&
    auth.currentTenantId != null &&
    auth.hasPermission('work:access') &&
    workbenchUi.showAssistant,
)

const open = ref(false)
const panelX = ref(0)
const panelY = ref(0)
const draggingPanel = ref(false)
const dragOffset = ref({ x: 0, y: 0 })

const sessions = ref<ChatSession[]>([])
const tabs = ref<Tab[]>([])
const activeKey = ref<string | null>(null)
const messages = ref<ChatMessage[]>([])
const inputText = ref('')
const streaming = ref(false)
const currentRunId = ref<string | null>(null)
const abortCtrl = ref<AbortController | null>(null)
const chatScrollRef = ref<HTMLElement | null>(null)
const historyOpen = ref(true)

const activeTab = computed(() => tabs.value.find((t) => t.key === activeKey.value) ?? null)

function placeDefault() {
  panelX.value = Math.max(24, window.innerWidth - 440)
  panelY.value = Math.max(24, window.innerHeight - 620)
}

function toggle() {
  if (!open.value) {
    open.value = true
    placeDefault()
    refreshSessions()
    if (!tabs.value.length) {
      startNewTab()
    }
  } else {
    open.value = false
  }
}

function onHeaderDown(e: MouseEvent) {
  draggingPanel.value = true
  dragOffset.value = { x: e.clientX - panelX.value, y: e.clientY - panelY.value }
  const onMove = (ev: MouseEvent) => {
    if (!draggingPanel.value) return
    panelX.value = Math.min(window.innerWidth - 80, Math.max(0, ev.clientX - dragOffset.value.x))
    panelY.value = Math.min(window.innerHeight - 48, Math.max(0, ev.clientY - dragOffset.value.y))
  }
  const onUp = () => {
    draggingPanel.value = false
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

async function refreshSessions() {
  try {
    sessions.value = await listSessions()
  } catch {
    sessions.value = []
  }
}

function startNewTab() {
  const key = `draft-${crypto.randomUUID()}`
  tabs.value.push({
    key,
    sessionId: null,
    draft: true,
    clientTempId: crypto.randomUUID(),
    title: '新对话',
  })
  activeKey.value = key
  messages.value = []
  inputText.value = ''
}

async function openSession(session: ChatSession) {
  const existing = tabs.value.find((t) => t.sessionId === session.id)
  if (existing) {
    activeKey.value = existing.key
    await loadMessages(session.id)
    return
  }
  const key = `s-${session.id}`
  tabs.value.push({
    key,
    sessionId: session.id,
    draft: false,
    title: session.title || '对话',
  })
  activeKey.value = key
  await loadMessages(session.id)
}

async function selectTab(key: string) {
  if (activeKey.value === key) return
  activeKey.value = key
  const tab = tabs.value.find((t) => t.key === key)
  if (!tab) return
  if (tab.sessionId) {
    await loadMessages(tab.sessionId)
  } else {
    messages.value = []
  }
}

function closeTab(key: string) {
  const idx = tabs.value.findIndex((t) => t.key === key)
  if (idx < 0) return
  tabs.value.splice(idx, 1)
  if (activeKey.value === key) {
    const next = tabs.value[Math.max(0, idx - 1)]
    activeKey.value = next?.key ?? null
    if (next?.sessionId) {
      loadMessages(next.sessionId)
    } else {
      messages.value = []
    }
  }
}

async function loadMessages(sessionId: number) {
  try {
    messages.value = await listMessages(sessionId)
    await scrollBottom()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载消息失败')
  }
}

async function removeSession(id: number) {
  try {
    await ElMessageBox.confirm('确定删除该会话？', '提示', { type: 'warning' })
    await deleteSession(id)
    tabs.value = tabs.value.filter((t) => t.sessionId !== id)
    if (activeTab.value?.sessionId === id) {
      messages.value = []
      activeKey.value = tabs.value[0]?.key ?? null
    }
    await refreshSessions()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败')
    }
  }
}

async function scrollBottom() {
  await nextTick()
  if (chatScrollRef.value) {
    chatScrollRef.value.scrollTop = chatScrollRef.value.scrollHeight
  }
}

function unwrapSseData(data: unknown): any {
  let cur: any = data
  for (let i = 0; i < 2; i++) {
    if (typeof cur !== 'string') break
    try {
      cur = JSON.parse(cur)
    } catch {
      break
    }
  }
  return cur ?? {}
}

async function send() {
  const content = inputText.value.trim()
  if (!content || streaming.value || !activeTab.value) return

  const tab = activeTab.value
  messages.value.push({
    id: Date.now(),
    sessionId: tab.sessionId ?? 0,
    role: 'user',
    content,
    status: 'done',
  })
  messages.value.push({
    id: Date.now() + 1,
    sessionId: tab.sessionId ?? 0,
    role: 'assistant',
    content: '',
    status: 'streaming',
  })
  const aiIdx = messages.value.length - 1
  inputText.value = ''
  streaming.value = true
  await scrollBottom()

  const ctrl = new AbortController()
  abortCtrl.value = ctrl

  const patchAi = (partial: Partial<ChatMessage>) => {
    const cur = messages.value[aiIdx]
    if (!cur) return
    messages.value[aiIdx] = { ...cur, ...partial }
  }

  try {
    await streamCompletion(
      {
        sessionId: tab.draft ? null : tab.sessionId,
        clientTempId: tab.draft ? tab.clientTempId : undefined,
        content,
      },
      (event, data) => {
        const payload = unwrapSseData(data)
        if (event === 'meta') {
          currentRunId.value = payload.runId
          if (payload.sessionId) {
            tab.sessionId = payload.sessionId
            tab.draft = false
            tab.key = `s-${payload.sessionId}`
            activeKey.value = tab.key
            patchAi({ sessionId: payload.sessionId })
            if (!sessions.value.some((s) => s.id === payload.sessionId)) {
              sessions.value.unshift({
                id: payload.sessionId,
                title: tab.title || '新对话',
              })
            }
          }
          if (payload.messageId) {
            patchAi({ id: payload.messageId })
          }
        } else if (event === 'content') {
          const delta = payload?.delta ?? ''
          if (!delta) return
          const prev = messages.value[aiIdx]?.content || ''
          patchAi({ content: prev + delta, status: 'streaming' })
          scrollBottom()
        } else if (event === 'title') {
          tab.title = payload.title
          const s = sessions.value.find((x) => x.id === payload.sessionId)
          if (s) s.title = payload.title
        } else if (event === 'error') {
          ElMessage.error(payload.message || '生成失败')
          patchAi({ status: 'error' })
        } else if (event === 'done') {
          patchAi({ status: 'done' })
        }
      },
      ctrl.signal,
    )
  } catch (e) {
    if ((e as Error).name !== 'AbortError') {
      ElMessage.error(e instanceof Error ? e.message : '发送失败')
      patchAi({ status: 'error' })
    } else {
      patchAi({ status: 'stopped' })
    }
  } finally {
    streaming.value = false
    currentRunId.value = null
    abortCtrl.value = null
    await refreshSessions()
  }
}

async function stop() {
  const sid = activeTab.value?.sessionId
  if (!sid || !currentRunId.value) {
    abortCtrl.value?.abort()
    return
  }
  try {
    await stopGeneration(sid, currentRunId.value)
  } catch {
    /* ignore */
  }
  abortCtrl.value?.abort()
  streaming.value = false
}

watch(
  () => auth.currentTenantId,
  () => {
    tabs.value = []
    activeKey.value = null
    messages.value = []
    sessions.value = []
    if (open.value && auth.currentTenantId != null) {
      refreshSessions()
      startNewTab()
    }
  },
)

onMounted(() => {
  placeDefault()
})
</script>

<template>
  <Teleport to="body">
    <template v-if="visible">
      <button
        v-if="!open"
        type="button"
        class="fab"
        title="智能助手"
        @click="toggle"
      >
        <el-icon :size="22"><ChatDotRound /></el-icon>
      </button>

      <div
        v-else
        class="panel"
        :style="{ left: panelX + 'px', top: panelY + 'px' }"
      >
        <div class="panel-hd" @mousedown="onHeaderDown">
          <span class="panel-title">智能助手</span>
          <el-button v-if="streaming" type="warning" size="small" @click.stop="stop">停止</el-button>
          <el-button circle size="small" :icon="Close" @click.stop="toggle" />
        </div>
        <div class="panel-body">
          <aside v-if="historyOpen" class="history">
            <div class="history-hd">
              <span>历史</span>
              <el-button link type="primary" :icon="Plus" @click="startNewTab">新建</el-button>
            </div>
            <div class="history-list">
              <div
                v-for="s in sessions"
                :key="s.id"
                class="history-item"
                :class="{ active: activeTab?.sessionId === s.id }"
                @click="openSession(s)"
              >
                <span class="history-title">{{ s.title || '对话' }}</span>
                <el-button
                  class="history-del"
                  link
                  type="danger"
                  :icon="Delete"
                  @click.stop="removeSession(s.id)"
                />
              </div>
              <div v-if="!sessions.length" class="empty">暂无历史</div>
            </div>
          </aside>
          <div class="chat">
            <div class="tabs">
              <button
                v-for="t in tabs"
                :key="t.key"
                type="button"
                class="tab"
                :class="{ active: t.key === activeKey }"
                @click="selectTab(t.key)"
              >
                <span class="tab-label">{{ t.title }}</span>
                <span class="tab-close" @click.stop="closeTab(t.key)">×</span>
              </button>
              <button type="button" class="tab add" title="新对话" @click="startNewTab">+</button>
              <button
                type="button"
                class="hist-toggle"
                @click="historyOpen = !historyOpen"
              >
                {{ historyOpen ? '隐历史' : '历史' }}
              </button>
            </div>
            <div ref="chatScrollRef" class="messages">
              <div v-if="!activeTab" class="empty">新建或打开一个对话</div>
              <div
                v-for="m in messages"
                :key="m.id"
                class="bubble"
                :class="m.role"
              >
                <div class="bubble-role">{{ m.role === 'user' ? '我' : '助手' }}</div>
                <div
                  class="bubble-content"
                  :class="{
                    pending: m.role === 'assistant' && m.status === 'streaming' && !m.content,
                    md: m.role === 'assistant' && !!m.content,
                  }"
                >
                  <MarkdownBody v-if="m.role === 'assistant' && m.content" :content="m.content" />
                  <template v-else-if="m.content">{{ m.content }}</template>
                  <span v-else-if="m.status === 'streaming'" class="typing">正在回复</span>
                  <span v-else-if="m.status === 'error'" class="err">生成失败</span>
                </div>
              </div>
            </div>
            <div class="composer">
              <el-input
                v-model="inputText"
                type="textarea"
                :rows="2"
                placeholder="输入消息，Enter 发送"
                :disabled="!activeTab"
                @keydown.enter.exact.prevent="send"
              />
              <div class="composer-actions">
                <el-button
                  type="primary"
                  size="small"
                  :disabled="streaming || !inputText.trim() || !activeTab"
                  @click="send"
                >
                  发送
                </el-button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </Teleport>
</template>

<style scoped>
.fab {
  position: fixed;
  right: 28px;
  bottom: 28px;
  z-index: 4000;
  width: 52px;
  height: 52px;
  border: none;
  border-radius: 50%;
  background: var(--dap-primary);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 14px rgba(43, 90, 237, 0.35);
}
.fab:hover {
  filter: brightness(1.05);
}
.panel {
  position: fixed;
  z-index: 4000;
  width: 420px;
  height: 560px;
  background: var(--dap-gray-0);
  border: 1px solid var(--dap-gray-200);
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(15, 23, 42, 0.16);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.panel-hd {
  height: 44px;
  padding: 0 10px 0 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid var(--dap-gray-200);
  cursor: move;
  user-select: none;
  flex-shrink: 0;
}
.panel-title {
  flex: 1;
  font-weight: 600;
  font-size: 14px;
}
.panel-body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.history {
  width: 128px;
  border-right: 1px solid var(--dap-gray-200);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}
.history-hd {
  padding: 8px 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--dap-gray-500);
  border-bottom: 1px solid var(--dap-gray-100);
}
.history-list {
  flex: 1;
  overflow: auto;
  padding: 6px;
}
.history-item {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
}
.history-item:hover {
  background: var(--dap-gray-50);
}
.history-item.active {
  background: var(--dap-primary-light);
  color: var(--dap-primary);
}
.history-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.history-del {
  opacity: 0;
}
.history-item:hover .history-del {
  opacity: 1;
}
.chat {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--dap-gray-100);
  overflow-x: auto;
  flex-shrink: 0;
}
.tab {
  border: none;
  background: var(--dap-gray-100);
  border-radius: 6px;
  padding: 4px 8px;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 120px;
  color: var(--dap-gray-700);
}
.tab.active {
  background: var(--dap-primary-light);
  color: var(--dap-primary);
}
.tab.add {
  background: transparent;
  color: var(--dap-gray-500);
  max-width: none;
}
.tab-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tab-close {
  opacity: 0.6;
}
.hist-toggle {
  margin-left: auto;
  border: none;
  background: transparent;
  color: var(--dap-primary);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  flex-shrink: 0;
}
.messages {
  flex: 1;
  overflow: auto;
  padding: 12px;
  min-height: 0;
}
.empty {
  color: var(--dap-gray-500);
  font-size: 13px;
  text-align: center;
  padding: 20px 8px;
}
.bubble {
  display: flex;
  flex-direction: column;
  width: fit-content;
  max-width: 92%;
  margin-bottom: 12px;
}
.bubble.user {
  margin-left: auto;
  align-items: flex-end;
}
.bubble.assistant {
  margin-right: auto;
  align-items: flex-start;
}
.bubble-role {
  font-size: 11px;
  color: var(--dap-gray-500);
  margin-bottom: 2px;
}
.bubble-content {
  display: inline-block;
  max-width: 100%;
  padding: 8px 12px;
  border-radius: 14px;
  background: var(--dap-gray-100);
  overflow-wrap: anywhere;
  word-break: break-word;
  line-height: 1.55;
  font-size: 13px;
  box-sizing: border-box;
}
.bubble-content:not(.md) {
  white-space: pre-wrap;
}
.bubble-content.md {
  white-space: normal;
}
.bubble.user .bubble-content {
  background: var(--dap-primary-light);
  border-bottom-right-radius: 4px;
}
.bubble.assistant .bubble-content {
  border-bottom-left-radius: 4px;
}
.bubble-content.pending {
  color: var(--dap-gray-500);
}
.typing::after {
  content: '…';
  animation: typing-dot 1.2s steps(3, end) infinite;
}
.err {
  color: #cf1322;
}
@keyframes typing-dot {
  0% { content: '.'; }
  33% { content: '..'; }
  66% { content: '...'; }
}
.composer {
  border-top: 1px solid var(--dap-gray-200);
  padding: 8px 10px;
  flex-shrink: 0;
}
.composer-actions {
  margin-top: 6px;
  display: flex;
  justify-content: flex-end;
}
</style>

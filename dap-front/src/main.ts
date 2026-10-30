import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from './App.vue'
import router from './router'

import './styles/tokens.css'
import './styles/index.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')

/** 输入框：允许选区与光标 */
function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  const tag = target.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  if (target.isContentEditable) return true
  return Boolean(
    target.closest(
      'input, textarea, select, [contenteditable="true"], .el-input, .el-textarea, .el-select',
    ),
  )
}

/**
 * 点击非编辑区域时去掉按钮等残留焦点（避免焦点框“停住”）。
 */
document.addEventListener('mousedown', (e) => {
  if (isEditableTarget(e.target)) return
  const active = document.activeElement
  if (
    active instanceof HTMLElement &&
    active !== document.body &&
    !isEditableTarget(active)
  ) {
    active.blur()
  }
})

/**
 * 文案可划选复制；单击产生的折叠选区（插入光标）在松开时清掉，避免光标停在那里。
 */
document.addEventListener('mouseup', (e) => {
  if (isEditableTarget(e.target)) return
  const sel = window.getSelection()
  if (sel && sel.isCollapsed) {
    sel.removeAllRanges()
  }
})

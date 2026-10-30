import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 工作台局部 UI 状态（供布局级组件如智能助手读取） */
export const useWorkbenchUiStore = defineStore('workbenchUi', () => {
  const showAssistant = ref(false)

  function setShowAssistant(v: boolean) {
    showAssistant.value = v
  }

  return { showAssistant, setShowAssistant }
})

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listPositions, type PositionItem } from '../api/position'
import {
  getTenantContext,
  updateTenantContext,
  type TenantContext,
} from '../api/tenantContext'
import { useAuthStore } from './auth'

export const usePositionContextStore = defineStore('positionContext', () => {
  const auth = useAuthStore()

  const positions = ref<PositionItem[]>([])
  const context = ref<TenantContext | null>(null)
  const loading = ref(false)

  const navVisible = ref(false)
  const navStep = ref<1 | 2>(1)
  const navPositionId = ref<number | null>(null)
  const navSkillIds = ref<number[]>([])
  const navCustomOn = ref(false)
  const navCustom = ref('')
  const navSubmitting = ref(false)
  const navForce = ref(false)

  const currentPosition = computed(
    () => positions.value.find((p) => p.id === context.value?.positionId) ?? null,
  )

  const skillSummary = computed(() => {
    const names = [...(context.value?.skillNames ?? [])]
    if (context.value?.customSkillText) names.push(context.value.customSkillText)
    return names.length ? names.join('、') : '未设置技能'
  })

  const navSkills = computed(() => {
    const pos = positions.value.find((p) => p.id === navPositionId.value)
    return pos?.skills ?? []
  })

  async function reload() {
    if (auth.currentTenantId == null || !auth.hasPermission('work:access')) {
      positions.value = []
      context.value = null
      return
    }
    loading.value = true
    try {
      positions.value = await listPositions(true)
      context.value = await getTenantContext()
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '加载岗位上下文失败')
    } finally {
      loading.value = false
    }
  }

  function openNav(options?: {
    step?: 1 | 2
    positionId?: number | null
    force?: boolean
  }) {
    navForce.value = Boolean(options?.force)
    navPositionId.value =
      options?.positionId !== undefined
        ? options.positionId
        : (context.value?.positionId ?? null)
    navSkillIds.value = [...(context.value?.skillIds ?? [])]
    navCustom.value = context.value?.customSkillText ?? ''
    navCustomOn.value = Boolean(context.value?.customSkillText)
    if (options?.step) {
      navStep.value = options.step
    } else {
      navStep.value = navPositionId.value ? 2 : 1
    }
    // 切到其他岗位时清空技能
    if (
      options?.positionId != null &&
      options.positionId !== context.value?.positionId
    ) {
      navSkillIds.value = []
      navCustomOn.value = false
      navCustom.value = ''
    }
    navVisible.value = true
  }

  function closeNav() {
    if (navForce.value && !context.value?.positionId) return
    navVisible.value = false
  }

  function selectPosition(id: number) {
    if (navPositionId.value !== id) {
      navPositionId.value = id
      navSkillIds.value = []
      navCustomOn.value = false
      navCustom.value = ''
    }
  }

  function toggleSkill(id: number) {
    const set = new Set(navSkillIds.value)
    if (set.has(id)) set.delete(id)
    else set.add(id)
    navSkillIds.value = [...set]
  }

  function toggleCustom() {
    navCustomOn.value = !navCustomOn.value
    if (!navCustomOn.value) navCustom.value = ''
  }

  function nextStep() {
    if (!navPositionId.value) {
      ElMessage.warning('请选择岗位')
      return
    }
    navStep.value = 2
  }

  function backStep() {
    navStep.value = 1
  }

  async function confirmNav(): Promise<boolean> {
    if (!navPositionId.value) {
      ElMessage.warning('请选择岗位')
      return false
    }
    const custom = navCustomOn.value ? navCustom.value.trim() : ''
    if (!navSkillIds.value.length && !custom) {
      ElMessage.warning('请至少选择一个技能')
      return false
    }
    if (navCustomOn.value && !custom) {
      ElMessage.warning('请填写自定义技能')
      return false
    }
    navSubmitting.value = true
    try {
      context.value = await updateTenantContext({
        positionId: navPositionId.value,
        skillIds: navSkillIds.value,
        customSkillText: custom || null,
      })
      navVisible.value = false
      navForce.value = false
      return true
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '保存失败')
      return false
    } finally {
      navSubmitting.value = false
    }
  }

  function onPositionSelect(positionId: number) {
    if (positionId === context.value?.positionId) return
    openNav({ step: 2, positionId, force: false })
  }

  return {
    positions,
    context,
    loading,
    currentPosition,
    skillSummary,
    navVisible,
    navStep,
    navPositionId,
    navSkillIds,
    navCustomOn,
    navCustom,
    navSubmitting,
    navForce,
    navSkills,
    reload,
    openNav,
    closeNav,
    selectPosition,
    toggleSkill,
    toggleCustom,
    nextStep,
    backStep,
    confirmNav,
    onPositionSelect,
  }
})

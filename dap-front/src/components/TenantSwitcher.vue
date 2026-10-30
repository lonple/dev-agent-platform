<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getMe, getMyTenants } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import type { TenantOption } from '../types/api'

const auth = useAuthStore()
const tenants = ref<TenantOption[]>([])
const loading = ref(false)
const popoverVisible = ref(false)

const multi = computed(() => tenants.value.length > 1)

const currentLabel = computed(() => {
  const id = auth.currentTenantId
  if (id == null) return '未选择租户'
  const t = tenants.value.find((x) => x.id === id)
  if (t) return t.name
  return auth.currentTenantName || '未选择租户'
})

async function refreshSession() {
  try {
    const me = await getMe()
    auth.applySession(me)
  } catch {
    /* ignore */
  }
}

async function loadTenants() {
  loading.value = true
  try {
    tenants.value = await getMyTenants()
    if (
      auth.currentTenantId != null &&
      !tenants.value.some((t) => t.id === auth.currentTenantId)
    ) {
      auth.switchTenant(null, '')
      await refreshSession()
    }
    // 仅一个租户：自动选中，不展示下拉
    if (tenants.value.length === 1) {
      const only = tenants.value[0]
      if (auth.currentTenantId !== only.id) {
        auth.switchTenant(only.id, only.name)
        await refreshSession()
      }
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载租户失败')
  } finally {
    loading.value = false
  }
}

async function selectTenant(tenant: TenantOption) {
  if (auth.currentTenantId === tenant.id) {
    popoverVisible.value = false
    return
  }
  auth.switchTenant(tenant.id, tenant.name)
  popoverVisible.value = false
  await refreshSession()
}

onMounted(() => {
  void loadTenants()
})

defineExpose({ reload: loadTenants })
</script>

<template>
  <div class="tenant-switcher" v-loading="loading">
    <!-- 单租户：只展示名称 -->
    <span v-if="!multi" class="tenant-label single" :title="currentLabel">
      {{ currentLabel }}
    </span>

    <!-- 多租户：点击弹出选择 -->
    <el-popover
      v-else
      v-model:visible="popoverVisible"
      placement="bottom-end"
      :width="260"
      trigger="click"
      popper-class="tenant-switcher-popper"
      @show="loadTenants"
    >
      <template #reference>
        <button type="button" class="tenant-trigger" :title="currentLabel">
          <span class="tenant-label">{{ currentLabel }}</span>
          <el-icon class="tenant-caret"><ArrowDown /></el-icon>
        </button>
      </template>
      <div class="tenant-menu">
        <button
          v-for="item in tenants"
          :key="item.id"
          type="button"
          class="tenant-option"
          :class="{ active: item.id === auth.currentTenantId }"
          @click="selectTenant(item)"
        >
          <span class="name">{{ item.name }}</span>
          <span class="code">{{ item.code }}</span>
        </button>
      </div>
    </el-popover>
  </div>
</template>

<style scoped>
.tenant-switcher {
  min-width: 0;
  max-width: 220px;
}
.tenant-label {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--dap-gray-800, #1f2937);
}
.tenant-label.single {
  padding: 0 4px;
  line-height: 32px;
  color: var(--dap-gray-600, #4b5563);
}
.tenant-trigger {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 220px;
  min-height: 32px;
  padding: 0 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  font: inherit;
}
.tenant-trigger:hover {
  background: var(--dap-gray-100, #f3f4f6);
}
.tenant-caret {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--dap-gray-500, #6b7280);
}
.tenant-menu {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 280px;
  overflow: auto;
}
.tenant-option {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  font: inherit;
  text-align: left;
}
.tenant-option:hover {
  background: var(--dap-gray-50, #f9fafb);
}
.tenant-option.active {
  background: var(--dap-primary-light, #eff6ff);
}
.tenant-option .name {
  font-size: 13px;
  color: var(--dap-gray-900, #111827);
}
.tenant-option .code {
  font-size: 12px;
  color: var(--dap-gray-500, #6b7280);
}
</style>

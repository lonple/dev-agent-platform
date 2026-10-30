<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getMe, getMyTenants } from '../../api/auth'
import { useAuthStore } from '../../stores/auth'
import type { TenantOption } from '../../types/api'

const auth = useAuthStore()
const loading = ref(false)
const tenants = ref<TenantOption[]>([])

const needPickTenant = computed(
  () => tenants.value.length > 1 && auth.currentTenantId == null,
)

const noTenant = computed(() => !loading.value && tenants.value.length === 0)

async function loadAndResolveTenant() {
  loading.value = true
  try {
    tenants.value = await getMyTenants()

    if (
      auth.currentTenantId != null &&
      !tenants.value.some((t) => t.id === auth.currentTenantId)
    ) {
      auth.switchTenant(null, '')
    }

    // 仅一个租户：直接进入，无需选择
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

async function refreshSession() {
  try {
    const me = await getMe()
    auth.applySession(me)
  } catch {
    /* ignore */
  }
}

async function enterTenant(tenant: TenantOption) {
  auth.switchTenant(tenant.id, tenant.name)
  await refreshSession()
  ElMessage.success(`已进入租户：${tenant.name}`)
}

onMounted(() => {
  loadAndResolveTenant()
})
</script>

<template>
  <div v-loading="loading" class="page-card home-page">
    <h1 class="page-title">欢迎，{{ auth.user?.username ?? '用户' }}</h1>

    <template v-if="noTenant">
      <p class="hint">您尚未加入任何租户。可在用户中心的租户管理中添加成员后，再回到首页进入。</p>
    </template>

    <template v-else-if="needPickTenant">
      <p class="section-desc">请选择要进入的租户：</p>
      <div class="tenant-grid">
        <button
          v-for="item in tenants"
          :key="item.id"
          type="button"
          class="tenant-card"
          @click="enterTenant(item)"
        >
          <span class="tenant-name">{{ item.name }}</span>
          <span class="tenant-code">{{ item.code }}</span>
        </button>
      </div>
    </template>

    <template v-else>
      <p class="tenant-line">
        当前租户：
        <span v-if="auth.currentTenantName" class="tenant-current">{{
          auth.currentTenantName
        }}</span>
        <span v-else class="muted">未选择租户</span>
      </p>
      <p class="hint">
        可从顶部导航进入用户中心；也可随时在右上角租户下拉中切换。
      </p>
      <div v-if="tenants.length > 1" class="switch-hint">
        <p class="section-desc">切换进入其他租户：</p>
        <div class="tenant-grid">
          <button
            v-for="item in tenants"
            :key="item.id"
            type="button"
            class="tenant-card"
            :class="{ active: item.id === auth.currentTenantId }"
            @click="enterTenant(item)"
          >
            <span class="tenant-name">{{ item.name }}</span>
            <span class="tenant-code">{{ item.code }}</span>
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.home-page {
  max-width: 720px;
}

.section-desc {
  margin: 0 0 var(--dap-space-4);
  color: var(--dap-gray-700);
}

.tenant-line {
  margin: 0 0 var(--dap-space-3);
  color: var(--dap-gray-700);
}

.tenant-current {
  color: var(--dap-gray-900);
  font-weight: 500;
}

.muted {
  color: var(--dap-gray-500);
}

.hint {
  margin: 0;
  color: var(--dap-gray-500);
  font-size: 13px;
}

.switch-hint {
  margin-top: var(--dap-space-5);
}

.tenant-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: var(--dap-space-3);
}

.tenant-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--dap-space-1);
  padding: var(--dap-space-4);
  border: 1px solid var(--dap-gray-200);
  border-radius: var(--dap-radius-md);
  background: var(--dap-gray-0);
  cursor: pointer;
  text-align: left;
  font: inherit;
}

.tenant-card:hover {
  border-color: var(--dap-primary);
  background: var(--dap-primary-light);
}

.tenant-card.active {
  border-color: var(--dap-primary);
  background: var(--dap-primary-light);
}

.tenant-name {
  color: var(--dap-gray-900);
  font-weight: 500;
}

.tenant-code {
  color: var(--dap-gray-500);
  font-size: 12px;
}
</style>

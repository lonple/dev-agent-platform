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
const entries = computed(() => [
  { title: '需求管理', description: '从业务目标出发，推进每一次交付。', path: '/requirement/apply', permission: 'requirement:view', mark: '01', action: '查看需求' },
  { title: '问题追踪', description: '跟进问题分析、修复与验证。', path: '/issues', permission: 'issue:view', mark: '02', action: '查看问题' },
  { title: '研发工作台', description: '处理研发任务，查看材料与执行进展。', path: '/workbench', permission: 'work:access', mark: '03', action: '进入工作台' },
].filter(item => auth.currentTenantId != null && auth.hasPermission(item.permission)))

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
  <div v-loading="loading" class="home-page">
    <div class="home-heading">
      <span class="eyebrow">WORKSPACE / OVERVIEW</span>
      <h1>工作总览<span class="heading-dot">.</span></h1>
      <p>你好，{{ auth.user?.username ?? '用户' }}。从这里开始今天的研发工作。</p>
    </div>
    <div v-if="entries.length" class="entry-grid">
      <router-link v-for="entry in entries" :key="entry.path" :to="entry.path" class="entry-card">
        <span class="entry-mark">{{ entry.mark }}</span><h2>{{ entry.title }}</h2>
        <p>{{ entry.description }}</p><span class="entry-action">{{ entry.action }} <span>↗</span></span>
      </router-link>
    </div>
    <section class="workspace-panel">
    <div class="panel-heading"><h2>工作空间</h2><span>组织与协作范围</span></div>

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
        从左侧导航进入业务模块；可在右上角切换工作空间。
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
    </section>
  </div>
</template>

<style scoped>
.home-page {
  max-width: 1180px;
  margin: 20px auto;
}
.home-heading { margin-bottom: 36px; }
.eyebrow { font-size: 10px; letter-spacing: 2px; color: var(--dap-gray-500); }
.home-heading h1 { font-size: 30px; color: var(--dap-gray-900); letter-spacing: -1px; margin: 12px 0; font-weight: 600; }
.heading-dot { color: var(--dap-primary); }
.home-heading p { color: var(--dap-gray-500); font-size: 13px; }
.entry-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px; margin-bottom: 32px; }
.entry-card { display: flex; flex-direction: column; padding: 26px; border: 1px solid var(--dap-gray-200); border-radius: 10px; background: white; color: var(--dap-gray-900); transition: border-color .15s, transform .15s; }
.entry-card:hover { border-color: var(--dap-primary); transform: translateY(-2px); }
.entry-mark { color: var(--dap-gray-500); font-family: monospace; font-size: 12px; }
.entry-card h2 { margin: 28px 0 8px; font-size: 18px; font-weight: 600; }
.entry-card p { color: var(--dap-gray-500); font-size: 12px; line-height: 1.8; margin: 0 0 32px; }
.entry-action { margin-top: auto; display:flex; justify-content:space-between; font-size:12px; color:var(--dap-primary); }
.workspace-panel { background:white; border:1px solid var(--dap-gray-200); border-radius:10px; padding:24px; }
.panel-heading { display:flex; align-items:center; justify-content:space-between; margin-bottom:24px; }
.panel-heading h2 { margin:0; font-size:14px; color:var(--dap-gray-900); font-weight:600; }
.panel-heading > span { font-size:11px; color:var(--dap-gray-500); }
@media(max-width:900px) { .entry-grid { grid-template-columns:1fr; gap:12px; } .entry-card { padding:20px; } .entry-card h2 { margin-top:12px; } .entry-card p { margin-bottom:16px; } .home-page { margin-top:0; } }

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

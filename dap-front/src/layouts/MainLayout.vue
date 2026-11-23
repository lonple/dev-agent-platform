<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { logout } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import TenantSwitcher from '../components/TenantSwitcher.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const userCenterMenus = [
  { label: '用户管理', path: '/user-center/users', permission: 'user:view' },
  { label: '角色管理', path: '/user-center/roles', permission: 'role:view' },
  {
    label: '权限管理',
    path: '/user-center/permissions',
    permission: 'permission:view',
  },
  { label: '租户管理', path: '/user-center/tenants', permission: 'tenant:view' },
]

const tenantConfigMenus = [
  { label: '用户管理', path: '/tenant-config/users', permission: 'tenant-user:view' },
  { label: '岗位管理', path: '/tenant-config/positions', permission: 'position:view' },
  { label: '产品管理', path: '/tenant-config/products', permission: 'product:view' },
  { label: '提示词管理', path: '/tenant-config/prompts', permission: 'prompt:view' },
]

const auditMenus = [
  { label: '会话日志', path: '/audit/sessions', permission: 'audit:view' },
  { label: 'Token消耗', path: '/audit/tokens', permission: 'audit:view' },
]

const requirementMenus = [
  { label: '需求申请', path: '/requirement/apply', permission: 'requirement:view' },
]

const userCenterPermissions = userCenterMenus.map((m) => m.permission)
const tenantConfigPermissions = tenantConfigMenus.map((m) => m.permission)
const auditPermissions = auditMenus.map((m) => m.permission)

const topMenus = computed(() => {
  const menus = [
    { label: '首页', path: '/home', always: true },
    {
      label: '需求管理',
      path: '/requirement/apply',
      needTenant: true,
      permission: 'requirement:view',
    },
    {
      label: '问题单管理',
      path: '/issues',
      needTenant: true,
      permission: 'issue:view',
    },
    {
      label: '工作台',
      path: '/workbench',
      needTenant: true,
      permission: 'work:access',
    },
    {
      label: '运维中心',
      path: '/audit/sessions',
      needTenant: true,
      anyPermissions: auditPermissions,
    },
    {
      label: '租户配置',
      path: '/tenant-config/users',
      needTenant: true,
      anyPermissions: tenantConfigPermissions,
    },
    {
      label: '用户中心',
      path: '/user-center/users',
      anyPermissions: userCenterPermissions,
    },
  ]
  return menus.filter((m) => {
    if (m.needTenant && auth.currentTenantId == null) return false
    if (m.permission && !auth.hasPermission(m.permission)) return false
    if (m.anyPermissions && !auth.hasAnyPermission(m.anyPermissions)) return false
    return true
  })
})

const showUserCenterSide = computed(() => route.path.startsWith('/user-center'))
const showTenantConfigSide = computed(() => route.path.startsWith('/tenant-config'))
const showRequirementSide = computed(() => route.path.startsWith('/requirement'))
const showAuditSide = computed(() => route.path.startsWith('/audit'))

const visibleSideMenus = computed(() => {
  if (showAuditSide.value) {
    return auditMenus.filter((m) => auth.hasPermission(m.permission))
  }
  if (showTenantConfigSide.value) {
    return tenantConfigMenus.filter((m) => auth.hasPermission(m.permission))
  }
  if (showRequirementSide.value) {
    return requirementMenus.filter((m) => auth.hasPermission(m.permission))
  }
  if (showUserCenterSide.value) {
    return userCenterMenus.filter((m) => auth.hasPermission(m.permission))
  }
  return []
})

const showSide = computed(() => visibleSideMenus.value.length > 0)

function isActive(path: string) {
  return route.path === path || route.path.startsWith(`${path}/`)
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      type: 'warning',
    })
    try {
      await logout()
    } catch {
      /* ignore */
    }
    auth.clearAuth()
    router.push('/login')
  } catch {
    /* cancelled */
  }
}

function onUserCommand(command: string) {
  if (command === 'logout') {
    handleLogout()
  }
}

function navigateTop(path: string) {
  if (path.startsWith('/user-center')) {
    const first = userCenterMenus.filter((m) => auth.hasPermission(m.permission))[0]
    if (first) {
      router.push(first.path)
      return
    }
    ElMessage.warning('暂无用户中心访问权限')
    return
  }
  if (path.startsWith('/tenant-config')) {
    const first = tenantConfigMenus.filter((m) => auth.hasPermission(m.permission))[0]
    if (first) {
      router.push(first.path)
      return
    }
    ElMessage.warning('暂无租户配置访问权限')
    return
  }
  if (path.startsWith('/requirement')) {
    const first = requirementMenus.filter((m) => auth.hasPermission(m.permission))[0]
    if (first) {
      router.push(first.path)
      return
    }
    ElMessage.warning('暂无需求管理访问权限')
    return
  }
  if (path.startsWith('/audit')) {
    const first = auditMenus.filter((m) => auth.hasPermission(m.permission))[0]
    if (first) {
      router.push(first.path)
      return
    }
    ElMessage.warning('暂无运维中心访问权限')
    return
  }
  router.push(path)
}
</script>

<template>
  <div class="main-layout">
    <header class="top-bar">
      <div class="top-left">
        <div class="brand">
          <span class="logo-square">D</span>
          <span class="brand-text">DAP</span>
        </div>
        <nav class="top-nav">
          <button
            v-for="item in topMenus"
            :key="item.path"
            type="button"
            class="nav-link"
            :class="{ active: isActive(item.path) }"
            @click="navigateTop(item.path)"
          >
            {{ item.label }}
          </button>
        </nav>
      </div>
      <div class="top-right">
        <TenantSwitcher />
        <el-dropdown trigger="click" @command="onUserCommand">
          <span class="user-trigger">
            {{ auth.user?.username ?? '用户' }}
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">登出</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div class="body">
      <aside v-if="showSide" class="side-menu">
        <router-link
          v-for="item in visibleSideMenus"
          :key="item.path"
          :to="item.path"
          class="side-link"
          :class="{ active: route.path === item.path }"
        >
          {{ item.label }}
        </router-link>
      </aside>
      <main
        class="content"
        :class="{
          'with-side': showSide,
          'flush':
            route.path.startsWith('/workbench') || route.path.includes('/pipeline'),
        }"
      >
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.main-layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--dap-gray-50);
}

.top-bar {
  height: 56px;
  padding: 0 var(--dap-space-5);
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--dap-gray-0);
  border-bottom: 1px solid var(--dap-gray-200);
}

.top-left {
  display: flex;
  align-items: center;
  gap: var(--dap-space-6);
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--dap-space-2);
}

.logo-square {
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--dap-primary);
  color: var(--dap-gray-0);
  font-weight: 600;
  border-radius: var(--dap-radius-sm);
}

.brand-text {
  font-size: 16px;
  font-weight: 600;
  color: var(--dap-gray-900);
}

.top-nav {
  display: flex;
  gap: var(--dap-space-2);
}

.nav-link {
  border: none;
  background: transparent;
  padding: var(--dap-space-2) var(--dap-space-3);
  font: inherit;
  color: var(--dap-gray-700);
  cursor: pointer;
  border-radius: var(--dap-radius-sm);
}

.nav-link:hover {
  background: var(--dap-gray-100);
}

.nav-link.active {
  color: var(--dap-primary);
  background: var(--dap-primary-light);
}

.top-right {
  display: flex;
  align-items: center;
  gap: var(--dap-space-4);
}

.user-trigger {
  display: inline-flex;
  align-items: center;
  gap: var(--dap-space-1);
  cursor: pointer;
  color: var(--dap-gray-900);
}

.body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.side-menu {
  width: 200px;
  flex-shrink: 0;
  padding: var(--dap-space-4);
  background: var(--dap-gray-0);
  border-right: 1px solid var(--dap-gray-200);
  display: flex;
  flex-direction: column;
  gap: var(--dap-space-1);
}

.side-link {
  display: block;
  padding: var(--dap-space-3) var(--dap-space-4);
  border-radius: var(--dap-radius-sm);
  color: var(--dap-gray-700);
}

.side-link:hover {
  background: var(--dap-gray-50);
}

.side-link.active {
  color: var(--dap-primary);
  background: var(--dap-primary-light);
  font-weight: 500;
}

.content {
  flex: 1;
  padding: var(--dap-space-5);
  overflow: auto;
}

.content.with-side {
  padding: var(--dap-space-5);
}

.content.flush {
  padding: 0;
  overflow: hidden;
}
</style>

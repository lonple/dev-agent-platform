<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, House, Document, Warning, Monitor, DataAnalysis, Setting, User, Menu, Close } from '@element-plus/icons-vue'
import { logout } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import TenantSwitcher from '../components/TenantSwitcher.vue'

const sidebarOpen = ref(false)
const menuIcons = [House, Document, Warning, Monitor, DataAnalysis, Setting, User]
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
  { label: '用户管理', path: '/tenant-config/users', permissions: ['tenant-user:view'] },
  { label: 'Agent 岗位', path: '/tenant-config/positions', permissions: ['position:view', 'prompt:view'] },
  { label: '提示词管理', path: '/tenant-config/prompts', permissions: ['prompt:view'] },
  { label: '产品管理', path: '/tenant-config/products', permissions: ['product:view'] },
]

const auditMenus = [
  { label: '会话日志', path: '/audit/sessions', permission: 'audit:view' },
  { label: 'Token消耗', path: '/audit/tokens', permission: 'audit:view' },
]

const requirementMenus = [
  { label: '需求申请', path: '/requirement/apply', permission: 'requirement:view' },
]

const userCenterPermissions = userCenterMenus.map((m) => m.permission)
const tenantConfigPermissions = tenantConfigMenus.flatMap((m) => m.permissions)
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
    return tenantConfigMenus.filter((m) => auth.hasAnyPermission(m.permissions))
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
  return route.path === path || route.path.startsWith(`${path}/`) || (path === '/workbench' && route.path.includes('/pipeline'))
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
    const first = tenantConfigMenus.filter((m) => auth.hasAnyPermission(m.permissions))[0]
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
const currentSection = computed(() => topMenus.value.find(m => isActive(m.path) || (m.path.startsWith('/audit') && showAuditSide.value) || (m.path.startsWith('/tenant-config') && showTenantConfigSide.value) || (m.path.startsWith('/user-center') && showUserCenterSide.value))?.label || '研发工作台')
const activeMenuPath = computed(() => route.path)
</script>

<template>
  <div class="main-layout" @keyup.esc="sidebarOpen = false">
    <button v-if="sidebarOpen" class="nav-backdrop" aria-label="关闭导航" @click="sidebarOpen = false" />
    <aside class="app-sidebar" :class="{ 'is-open': sidebarOpen }">
      <router-link to="/home" class="brand" @click="sidebarOpen = false">
        <span class="logo-square">D</span>
        <span><strong>DAP</strong><small>研发协作平台</small></span>
      </router-link>
      <div class="workspace-label">工作空间</div>
      <nav class="app-nav" aria-label="主导航">
        <template v-for="(item, index) in topMenus" :key="item.path">
          <div v-if="['运维中心', '租户配置', '用户中心'].includes(item.label) && !['运维中心', '租户配置', '用户中心'].includes(topMenus[index - 1]?.label || '')" class="workspace-label management-label">管理与设置</div>
          <button class="nav-link" :class="{ active: currentSection === item.label }" :aria-current="currentSection === item.label ? 'page' : undefined" @click="navigateTop(item.path); sidebarOpen = false">
            <el-icon><component :is="menuIcons[['首页','需求管理','问题单管理','工作台','运维中心','租户配置','用户中心'].indexOf(item.label)] || Document" /></el-icon>
            <span>{{ item.label === '首页' ? '工作总览' : item.label === '工作台' ? '研发工作台' : item.label }}</span>
          </button>
          <div v-if="currentSection === item.label && showSide && visibleSideMenus.length > 1" class="sub-nav">
            <router-link v-for="child in visibleSideMenus" :key="child.path" :to="child.path" :class="{ selected: activeMenuPath === child.path }" @click="sidebarOpen = false">{{ child.label }}</router-link>
          </div>
        </template>
      </nav>
      <div class="sidebar-footer"><span class="workspace-dot" />{{ auth.currentTenantName || '请选择工作空间' }}<span class="footer-caption">DAP WORKSPACE</span></div>
    </aside>
    <div class="app-shell">
      <header class="top-bar">
        <div class="breadcrumb">
          <button class="mobile-toggle" :aria-label="sidebarOpen ? '关闭导航' : '打开导航'" @click="sidebarOpen = !sidebarOpen"><el-icon><component :is="sidebarOpen ? Close : Menu" /></el-icon></button>
          <span class="breadcrumb-root">研发中心</span><span class="breadcrumb-divider">/</span><span>{{ currentSection === '首页' ? '工作总览' : currentSection }}</span>
          <template v-if="visibleSideMenus.length > 1"><span class="breadcrumb-divider">/</span><span class="breadcrumb-root">{{ visibleSideMenus.find(m => m.path === activeMenuPath)?.label }}</span></template>
        </div>
        <div class="top-right">
          <TenantSwitcher />
          <span class="header-divider" />
          <el-dropdown trigger="click" @command="onUserCommand">
            <button class="user-trigger"><span class="avatar">{{ (auth.user?.username || 'U').slice(0, 1).toUpperCase() }}</span><span class="username">{{ auth.user?.username ?? '用户' }}</span><el-icon><ArrowDown /></el-icon></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </header>
      <main class="content" :class="{ flush: route.path.startsWith('/workbench') || route.path.includes('/pipeline') }"><router-view /></main>
    </div>
  </div>
</template>

<style scoped>
.main-layout { display:flex; height:100dvh; overflow:hidden; background:var(--dap-gray-50); }
.app-sidebar { width:216px; flex-shrink:0; display:flex; flex-direction:column; padding:26px 14px 16px; background:#f8fafc; border-right:1px solid var(--dap-gray-200); overflow:auto; }
.brand { display:flex; align-items:center; gap:11px; padding:0 10px 30px; color:var(--dap-gray-900); }
.logo-square { display:grid; place-items:center; width:34px; height:36px; border-radius:10px; background:var(--dap-gray-900); color:white; font-size:22px; font-weight:700; }
.brand strong { display:block; font-size:18px; letter-spacing:1px; line-height:1.3; }
.brand small { display:block; margin-top:3px; color:var(--dap-gray-500); font-size:11px; }
.workspace-label { padding:0 12px 10px; color:var(--dap-gray-500); font-size:11px; font-weight:500; letter-spacing:1px; }
.management-label { margin-top:28px; }
.app-nav { display:flex; flex-direction:column; gap:5px; }
.nav-link { display:flex; align-items:center; gap:12px; width:100%; padding:11px 13px; border:0; border-radius:7px; background:transparent; color:var(--dap-gray-700); font:inherit; font-size:13px; cursor:pointer; text-align:left; transition:background .15s; }
.nav-link .el-icon { font-size:17px; color:#8590a2; }
.nav-link:hover { background:#edf1f6; }
.nav-link.active { background:var(--dap-primary-light); color:var(--dap-primary); font-weight:600; }
.nav-link.active .el-icon { color:var(--dap-primary); }
.sub-nav { display:flex; flex-direction:column; gap:3px; margin:1px 0 8px 33px; border-left:1px solid var(--dap-gray-200); padding-left:10px; }
.sub-nav a { padding:7px 10px; font-size:12px; color:var(--dap-gray-500); border-radius:5px; }
.sub-nav a:hover,.sub-nav a.selected { color:var(--dap-primary); background:var(--dap-primary-light); }
.sidebar-footer { margin-top:auto; padding:24px 12px 0; color:var(--dap-gray-700); font-size:12px; }
.workspace-dot { display:inline-block; width:6px; height:6px; border-radius:50%; background:var(--dap-primary); margin-right:8px; }
.footer-caption { display:block; margin-top:9px; font-size:9px; letter-spacing:1.5px; color:var(--dap-gray-500); }
.app-shell { flex:1; min-width:0; display:flex; flex-direction:column; }
.top-bar { height:56px; flex-shrink:0; padding:0 28px; display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--dap-gray-200); background:white; gap:12px; }
.breadcrumb { display:flex; align-items:center; gap:12px; font-size:12px; min-width:0; }
.breadcrumb-root { color:var(--dap-gray-500); }
.breadcrumb-divider { color:var(--dap-gray-300); }
.top-right { display:flex; align-items:center; gap:16px; flex-shrink:0; }
.header-divider { height:20px; border-left:1px solid var(--dap-gray-200); }
.user-trigger { display:flex; align-items:center; gap:9px; border:0; background:none; font:inherit; font-size:12px; cursor:pointer; color:var(--dap-gray-700); }
.avatar { display:grid; place-items:center; width:28px; height:28px; border-radius:50%; background:#edf0f5; color:#56637b; font-size:11px; font-weight:600; }
.content { flex:1; min-height:0; padding:28px; overflow:auto; }
.content.flush { padding:0; overflow:hidden; }
.mobile-toggle { display:none; border:0; background:none; cursor:pointer; }
.nav-backdrop { display:none; }
@media(max-width:900px) { .app-sidebar { width:184px; padding-inline:8px; } .top-bar { padding-inline:18px; } .content { padding:20px; } .breadcrumb-root:first-of-type,.breadcrumb-divider:first-of-type { display:none; } }
@media(max-width:680px) { .app-sidebar { position:fixed; inset:0 auto 0 0; z-index:101; width:216px; transform:translateX(-100%); transition:transform .2s; } .app-sidebar.is-open { transform:translateX(0); } .nav-backdrop { display:block; position:fixed; inset:0; z-index:100; border:0; background:#17223566; } .mobile-toggle { display:block; } .username,.header-divider { display:none; } .top-bar { padding-inline:12px; } .top-right { gap:6px; } .breadcrumb { gap:7px; } .content { padding:16px; } }
</style>

import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { getMe } from '../api/auth'
import MainLayout from '../layouts/MainLayout.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/login/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: MainLayout,
      meta: { requiresAuth: true },
      children: [
        { path: '', redirect: '/home' },
        {
          path: 'home',
          name: 'home',
          component: () => import('../views/home/HomeView.vue'),
        },
        {
          path: 'user-center/users',
          name: 'user-center-users',
          component: () => import('../views/user-center/UsersView.vue'),
          meta: { permission: 'user:view' },
        },
        {
          path: 'user-center/roles',
          name: 'user-center-roles',
          component: () => import('../views/user-center/RolesView.vue'),
          meta: { permission: 'role:view' },
        },
        {
          path: 'user-center/permissions',
          name: 'user-center-permissions',
          component: () => import('../views/user-center/PermissionsView.vue'),
          meta: { permission: 'permission:view' },
        },
        {
          path: 'user-center/tenants',
          name: 'user-center-tenants',
          component: () => import('../views/user-center/TenantsView.vue'),
          meta: { permission: 'tenant:view' },
        },
        {
          path: 'workbench',
          name: 'workbench',
          component: () => import('../views/workbench/WorkbenchView.vue'),
          meta: { permission: 'work:access', requiresTenant: true },
        },
        {
          path: 'audit/sessions',
          name: 'audit-sessions',
          component: () => import('../views/audit/SessionAuditView.vue'),
          meta: { permission: 'audit:view', requiresTenant: true },
        },
        {
          path: 'audit/tokens',
          name: 'audit-tokens',
          component: () => import('../views/audit/TokenUsageView.vue'),
          meta: { permission: 'audit:view', requiresTenant: true },
        },
        {
          path: 'audit/workflow',
          redirect: '/audit/tokens',
        },
        {
          path: 'tenant-config/users',
          name: 'tenant-config-users',
          component: () => import('../views/sys-config/TenantUsersView.vue'),
          meta: { permission: 'tenant-user:view', requiresTenant: true },
        },
        {
          path: 'tenant-config/positions',
          name: 'tenant-config-positions',
          component: () => import('../views/sys-config/PositionsView.vue'),
          meta: { permission: 'position:view', requiresTenant: true },
        },
        {
          path: 'tenant-config/products',
          name: 'tenant-config-products',
          component: () => import('../views/sys-config/ProductsView.vue'),
          meta: { permission: 'product:view', requiresTenant: true },
        },
        {
          path: 'tenant-config/prompts',
          name: 'tenant-config-prompts',
          component: () => import('../views/sys-config/PromptsView.vue'),
          meta: { permission: 'prompt:view', requiresTenant: true },
        },
        {
          path: 'sys-config/positions',
          redirect: '/tenant-config/positions',
        },
        {
          path: 'sys-config/products',
          redirect: '/tenant-config/products',
        },
        {
          path: 'requirement/apply',
          name: 'requirement-apply',
          component: () => import('../views/requirement/RequirementApplyView.vue'),
          meta: { permission: 'requirement:view', requiresTenant: true },
        },
        {
          path: 'requirement/submit',
          redirect: '/requirement/apply',
        },
        {
          path: 'issues',
          name: 'issues',
          component: () => import('../views/issue/IssueListView.vue'),
          meta: { permission: 'issue:view', requiresTenant: true },
        },
        {
          path: 'requirements/:requirementId/pipeline',
          name: 'requirement-pipeline',
          component: () => import('../views/pipeline/PipelineWorkbenchView.vue'),
          // 从工作台进入，与 workbench 同权；避免无 pipeline:view 时被拦到首页
          meta: { permission: 'work:access', requiresTenant: true },
        },
        {
          path: 'issues/:issueId/pipeline',
          name: 'issue-pipeline',
          component: () => import('../views/pipeline/PipelineWorkbenchView.vue'),
          meta: { permission: 'work:access', requiresTenant: true },
        },
      ],
    },
  ],
})

let meLoaded = false

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public) {
    if (auth.isLoggedIn && to.path === '/login') {
      return { path: '/home' }
    }
    return true
  }

  if (!auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (!meLoaded && !auth.user) {
    try {
      const me = await getMe()
      auth.applySession(me)
      meLoaded = true
    } catch {
      auth.clearAuth()
      meLoaded = false
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  const permission = to.meta.permission as string | undefined
  if (permission && !auth.hasPermission(permission)) {
    return { path: '/home' }
  }

  if (to.meta.requiresTenant && auth.currentTenantId == null) {
    return { path: '/home' }
  }

  return true
})

export default router

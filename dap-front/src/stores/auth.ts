import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { AuthUser, LoginResult, MeResult } from '../types/api'

const TOKEN_KEY = 'dap_token'
const TENANT_ID_KEY = 'dap_tenant_id'
const TENANT_NAME_KEY = 'dap_tenant_name'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '')
  const user = ref<AuthUser | null>(null)
  const permissions = ref<string[]>([])
  const currentTenantId = ref<number | null>(
    readTenantId(localStorage.getItem(TENANT_ID_KEY)),
  )
  const currentTenantName = ref<string>(
    localStorage.getItem(TENANT_NAME_KEY) ?? '',
  )

  const isLoggedIn = computed(() => Boolean(token.value))

  function readTenantId(raw: string | null): number | null {
    if (!raw) return null
    const n = Number(raw)
    return Number.isFinite(n) ? n : null
  }

  function persistToken(value: string) {
    token.value = value
    if (value) {
      localStorage.setItem(TOKEN_KEY, value)
    } else {
      localStorage.removeItem(TOKEN_KEY)
    }
  }

  function persistTenant(id: number | null, name: string) {
    currentTenantId.value = id
    currentTenantName.value = name
    if (id != null) {
      localStorage.setItem(TENANT_ID_KEY, String(id))
      localStorage.setItem(TENANT_NAME_KEY, name)
    } else {
      localStorage.removeItem(TENANT_ID_KEY)
      localStorage.removeItem(TENANT_NAME_KEY)
    }
  }

  function applySession(payload: LoginResult | MeResult, sessionToken?: string) {
    if (sessionToken) {
      persistToken(sessionToken)
    }
    user.value = payload.user
    permissions.value = payload.permissions ?? []
    if ('currentTenantId' in payload) {
      persistTenant(payload.currentTenantId ?? null, payload.currentTenantName ?? '')
    }
  }

  function setLoginResult(result: LoginResult) {
    applySession(result, result.token)
  }

  function hasPermission(code: string): boolean {
    return permissions.value.includes(code)
  }

  function hasAnyPermission(codes: string[]): boolean {
    return codes.some((c) => permissions.value.includes(c))
  }

  function clearAuth() {
    persistToken('')
    user.value = null
    permissions.value = []
    persistTenant(null, '')
  }

  function switchTenant(id: number | null, name: string) {
    persistTenant(id, name)
  }

  return {
    token,
    user,
    permissions,
    currentTenantId,
    currentTenantName,
    isLoggedIn,
    persistToken,
    applySession,
    setLoginResult,
    hasPermission,
    hasAnyPermission,
    clearAuth,
    switchTenant,
  }
})

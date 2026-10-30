export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
}

export interface AuthUser {
  id: number
  username: string
  description?: string
  roleId: number
  roleName: string
  builtin: boolean
}

export interface LoginResult {
  token: string
  user: AuthUser
  permissions: string[]
  currentTenantId?: number | null
  currentTenantName?: string | null
}

export interface MeResult {
  user: AuthUser
  permissions: string[]
  currentTenantId?: number | null
  currentTenantName?: string | null
}

export interface TenantOption {
  id: number
  code: string
  name: string
}

export interface SysUser {
  id: number
  username: string
  description?: string
  roleId: number
  roleName: string
  builtin: boolean
}

export interface SysRole {
  id: number
  name: string
  description?: string
  builtin: boolean
}

export interface SysPermission {
  id: number
  code: string
  name: string
  module: string
  description?: string
}

export interface SysTenant {
  id: number
  code: string
  name: string
  description?: string
  status: number
}

export interface PageResult<T> {
  records: T[]
  total: number
}

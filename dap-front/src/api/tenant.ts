import request from '../utils/request'
import type { SysUser } from '../types/api'

export interface TenantAdminUser {
  id: number
  username: string
  description?: string
  roleId?: number
  roleName?: string
  builtin?: boolean
  tenantAdmin?: boolean
}

export interface TenantItem {
  id: number
  code: string
  name: string
  description?: string
  status: number
  admins?: TenantAdminUser[]
  createdAt?: string
  updatedAt?: string
}

export interface TenantSavePayload {
  code: string
  name: string
  description?: string
  status: number
  adminUserIds: number[]
}

export function listTenants() {
  return request.get<unknown, TenantItem[]>('/tenants')
}

export function getTenant(id: number) {
  return request.get<unknown, TenantItem>(`/tenants/${id}`)
}

export function createTenant(data: TenantSavePayload) {
  return request.post<unknown, { id: number }>('/tenants', data)
}

export function updateTenant(id: number, data: TenantSavePayload) {
  return request.put<unknown, void>(`/tenants/${id}`, data)
}

export function deleteTenant(id: number) {
  return request.delete<unknown, void>(`/tenants/${id}`)
}

/** 平台用户列表，用于选择租户管理员 */
export function listAdminCandidates() {
  return request.get<unknown, SysUser[]>('/users/all')
}

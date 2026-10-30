import request from '../utils/request'
import type { SysUser } from '../types/api'

export type TenantRoleCode = 'ADMIN' | 'OPERATOR'

export interface TenantRoleOption {
  code: TenantRoleCode
  label: string
}

export interface TenantUserItem extends SysUser {
  tenantRole?: TenantRoleCode
  tenantRoleLabel?: string
  tenantAdmin?: boolean
}

export interface TenantUserAddPayload {
  userId: number
  tenantRole: TenantRoleCode
}

export interface TenantUserUpdatePayload {
  tenantRole: TenantRoleCode
}

export function listTenantUsers() {
  return request.get<unknown, TenantUserItem[]>('/tenant-users')
}

export function listTenantUserCandidates() {
  return request.get<unknown, SysUser[]>('/tenant-users/candidates')
}

export function listTenantRoles() {
  return request.get<unknown, TenantRoleOption[]>('/tenant-users/roles')
}

export function addTenantUser(data: TenantUserAddPayload) {
  return request.post<unknown, { id: number }>('/tenant-users', data)
}

export function updateTenantUser(id: number, data: TenantUserUpdatePayload) {
  return request.put<unknown, void>(`/tenant-users/${id}`, data)
}

export function removeTenantUser(id: number) {
  return request.delete<unknown, void>(`/tenant-users/${id}`)
}

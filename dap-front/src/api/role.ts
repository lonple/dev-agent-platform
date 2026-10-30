import request from '../utils/request'
import type { SysRole } from '../types/api'

export interface CreateRolePayload {
  name: string
  description?: string
}

export interface UpdateRolePayload {
  name?: string
  description?: string
}

export function listRoles() {
  return request.get<unknown, SysRole[]>('/roles')
}

export function createRole(data: CreateRolePayload) {
  return request.post<unknown, SysRole>('/roles', data)
}

export function updateRole(id: number, data: UpdateRolePayload) {
  return request.put<unknown, SysRole>(`/roles/${id}`, data)
}

export function deleteRole(id: number) {
  return request.delete<unknown, void>(`/roles/${id}`)
}

export function getRolePermissions(id: number) {
  return request.get<unknown, string[]>(`/roles/${id}/permissions`)
}

export function saveRolePermissions(id: number, permissionCodes: string[]) {
  return request.put<unknown, void>(`/roles/${id}/permissions`, {
    permissionCodes,
  })
}

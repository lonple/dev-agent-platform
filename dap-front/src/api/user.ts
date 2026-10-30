import request from '../utils/request'
import type { PageResult, SysUser } from '../types/api'

export interface UserQuery {
  username?: string
  page?: number
  size?: number
}

export interface CreateUserPayload {
  username: string
  password: string
  description?: string
  roleId: number
}

export interface UpdateUserPayload {
  username?: string
  password?: string
  description?: string
  roleId?: number
}

export async function listUsers(params?: UserQuery): Promise<SysUser[]> {
  const data = await request.get<unknown, SysUser[] | PageResult<SysUser>>(
    '/users',
    { params: { page: 1, size: 200, ...params } },
  )
  if (Array.isArray(data)) {
    return data
  }
  return data.records ?? []
}

export function listAllUsers() {
  return request.get<unknown, SysUser[]>('/users/all')
}

export function createUser(data: CreateUserPayload) {
  return request.post<unknown, SysUser>('/users', data)
}

export function updateUser(id: number, data: UpdateUserPayload) {
  return request.put<unknown, SysUser>(`/users/${id}`, data)
}

export function deleteUser(id: number) {
  return request.delete<unknown, void>(`/users/${id}`)
}

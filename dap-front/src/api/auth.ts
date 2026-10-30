import request from '../utils/request'
import type { LoginResult, MeResult, TenantOption } from '../types/api'

export interface LoginPayload {
  username: string
  password: string
}

export function login(data: LoginPayload) {
  return request.post<unknown, LoginResult>('/auth/login', data)
}

export function logout() {
  return request.post<unknown, void>('/auth/logout')
}

export function getMe() {
  return request.get<unknown, MeResult>('/auth/me')
}

export function getMyTenants() {
  return request.get<unknown, TenantOption[]>('/auth/my-tenants')
}

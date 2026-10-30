import request from '../utils/request'

export interface TenantContext {
  positionId?: number | null
  positionName?: string | null
  skillIds?: number[]
  skillNames?: string[]
  customSkillText?: string | null
}

export interface TenantContextUpdatePayload {
  positionId: number
  skillIds?: number[]
  customSkillText?: string | null
}

export function getTenantContext() {
  return request.get<any, TenantContext>('/tenant/context')
}

export function updateTenantContext(data: TenantContextUpdatePayload) {
  return request.put<any, TenantContext>('/tenant/context', data)
}

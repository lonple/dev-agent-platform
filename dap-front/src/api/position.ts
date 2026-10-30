import request from '../utils/request'

export interface SkillItem {
  id: number
  positionId: number
  name: string
  description?: string
  sortOrder?: number
}

export interface PositionItem {
  id: number
  name: string
  code?: string
  description?: string
  skills?: SkillItem[]
}

export interface PositionSavePayload {
  name: string
  code?: string
  description?: string
}

export interface SkillSavePayload {
  name: string
  description?: string
  sortOrder?: number
}

export function listPositions(withSkills = true) {
  return request.get<any, PositionItem[]>('/positions', {
    params: { withSkills },
  })
}

export function getPosition(id: number) {
  return request.get<any, PositionItem>(`/positions/${id}`)
}

export function createPosition(data: PositionSavePayload) {
  return request.post<any, { id: number }>('/positions', data)
}

export function updatePosition(id: number, data: PositionSavePayload) {
  return request.put<any, void>(`/positions/${id}`, data)
}

export function deletePosition(id: number) {
  return request.delete<any, void>(`/positions/${id}`)
}

export function createSkill(positionId: number, data: SkillSavePayload) {
  return request.post<any, { id: number }>(`/positions/${positionId}/skills`, data)
}

export function updateSkill(
  positionId: number,
  skillId: number,
  data: SkillSavePayload,
) {
  return request.put<any, void>(`/positions/${positionId}/skills/${skillId}`, data)
}

export function deleteSkill(positionId: number, skillId: number) {
  return request.delete<any, void>(`/positions/${positionId}/skills/${skillId}`)
}

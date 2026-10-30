import request from '../utils/request'

export interface PromptItem {
  id: number
  code: string
  name: string
  positionId: number
  positionName?: string
  positionCode?: string
  taskTypeCode: string
  taskTypeName?: string
  taskTypeKind?: string
  content?: string
  createdAt?: string
  updatedAt?: string
}

export interface PromptSavePayload {
  code: string
  name: string
  positionId: number
  taskTypeCode: string
  content?: string
}

export function listPrompts() {
  return request.get<any, PromptItem[]>('/prompts')
}

export function getPrompt(id: number) {
  return request.get<any, PromptItem>(`/prompts/${id}`)
}

export function resolvePrompt(positionId: number, taskTypeCode: string) {
  return request.get<any, PromptItem | null>('/prompts/resolve', {
    params: { positionId, taskTypeCode },
  })
}

export function createPrompt(data: PromptSavePayload) {
  return request.post<any, { id: number }>('/prompts', data)
}

export function updatePrompt(id: number, data: PromptSavePayload) {
  return request.put<any, void>(`/prompts/${id}`, data)
}

export function deletePrompt(id: number) {
  return request.delete<any, void>(`/prompts/${id}`)
}

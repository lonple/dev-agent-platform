import request from '../utils/request'

export interface ChatStepItem {
  eventId: string
  category?: string
  tenantId?: number
  userId?: number
  username?: string
  workspaceId?: number
  sessionId?: number
  sessionTitle?: string
  requirementId?: number
  taskTypeCode?: string
  positionId?: number
  messageId?: number
  runId?: string
  seq?: number
  llmRound?: number
  step?: string
  message?: string
  detail?: Record<string, unknown>
  status?: string
  durationMs?: number
  occurredAt?: string
  pipeInstanceId?: number
  pipeStepInstanceId?: number
  pipeStepCode?: string
}

export interface PageResult<T> {
  total: number
  records: T[]
}

export function searchChatSteps(params: {
  page?: number
  size?: number
  from?: string
  to?: string
  sessionId?: number
  runId?: string
  messageId?: number
  pipeInstanceId?: number
  pipeStepInstanceId?: number
  requirementId?: number
  userId?: number
  taskTypeCode?: string
  keyword?: string
  includeDetail?: boolean
}) {
  return request.get<any, PageResult<ChatStepItem>>('/audit/chat-steps', { params })
}

export function getChatStep(eventId: string) {
  return request.get<any, ChatStepItem>(`/audit/chat-steps/${encodeURIComponent(eventId)}`)
}

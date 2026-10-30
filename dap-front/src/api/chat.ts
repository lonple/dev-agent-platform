import request from '../utils/request'
import { useAuthStore } from '../stores/auth'

export interface ChatSession {
  id: number
  positionId?: number | null
  title: string
  createdAt?: string
  updatedAt?: string
}

export interface ChatMessage {
  id: number
  sessionId: number
  role: 'user' | 'assistant' | string
  thinking?: string | null
  content?: string | null
  status?: string
  createdAt?: string
  updatedAt?: string
}

export function listSessions() {
  return request.get<any, ChatSession[]>('/chat/sessions')
}

export function deleteSession(id: number) {
  return request.delete<any, void>(`/chat/sessions/${id}`)
}

export function listMessages(sessionId: number) {
  return request.get<any, ChatMessage[]>(`/chat/sessions/${sessionId}/messages`)
}

export interface ChatStepBrief {
  eventId?: string
  runId?: string
  messageId?: number
  seq?: number
  llmRound?: number
  step?: string
  message?: string
  occurredAt?: string
}

export function listSessionSteps(sessionId: number, runId?: string) {
  return request.get<any, ChatStepBrief[]>(`/chat/sessions/${sessionId}/steps`, {
    params: { runId, includeDetail: false },
  })
}

export function stopGeneration(sessionId: number, runId: string) {
  return request.post<any, void>(`/chat/sessions/${sessionId}/stop`, { runId })
}

export type SseHandler = (event: string, data: any) => void

export async function streamCompletion(
  body: { sessionId?: number | null; clientTempId?: string; content: string },
  onEvent: SseHandler,
  signal?: AbortSignal,
) {
  const auth = useAuthStore()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
  }
  if (auth.token) {
    headers.Authorization = `Bearer ${auth.token}`
  }
  if (auth.currentTenantId != null) {
    headers['X-Tenant-Id'] = String(auth.currentTenantId)
  }

  const response = await fetch('/api/chat/completions', {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    signal,
  })

  if (!response.ok || !response.body) {
    let message = `请求失败 (${response.status})`
    try {
      const err = await response.json()
      message = err.message || message
    } catch {
      /* ignore */
    }
    throw new Error(message)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = 'message'
  let dataLines: string[] = []

  const flush = () => {
    if (!dataLines.length) return
    const raw = dataLines.join('\n')
    dataLines = []
    let parsed: any = raw
    for (let i = 0; i < 2; i++) {
      if (typeof parsed !== 'string') break
      try {
        parsed = JSON.parse(parsed)
      } catch {
        break
      }
    }
    onEvent(eventName, parsed)
    eventName = 'message'
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const parts = buffer.split(/\r?\n/)
    buffer = parts.pop() ?? ''
    for (const line of parts) {
      if (line === '') {
        flush()
        continue
      }
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trim())
      }
    }
  }
  flush()
}

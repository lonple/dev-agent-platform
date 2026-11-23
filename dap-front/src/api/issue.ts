import request from '../utils/request'

export type IssueStatus = 'OPEN' | 'IN_PROGRESS' | 'DONE'

export interface IssueItem {
  id: number
  issueNo?: string
  productId: number
  productName?: string
  productCode?: string
  requirementId?: number | null
  requirementNo?: string | null
  requirementName?: string | null
  description: string
  steps?: string
  expectedResult?: string
  solution?: string
  status: IssueStatus
  statusLabel?: string
  handlerId?: number | null
  handlerUsername?: string | null
  createdBy?: number
  createdByUsername?: string
  createdAt?: string
  updatedAt?: string
}

export interface IssueSavePayload {
  productId: number
  requirementId?: number | null
  description: string
  steps?: string
  expectedResult?: string
  solution?: string
  status?: IssueStatus
  handlerId?: number | null
}

export interface IssueHandlerCandidate {
  id: number
  username: string
}

export function listIssues(status?: IssueStatus | '') {
  return request.get<any, IssueItem[]>('/issues', {
    params: status ? { status } : undefined,
  })
}

export type IssueWorkbenchScope = 'todo' | 'all'

export function listIssueWorkbench(scope: IssueWorkbenchScope = 'todo') {
  return request.get<any, IssueItem[]>('/issues/workbench', {
    params: { scope },
  })
}

export function listIssueHandlerCandidates() {
  return request.get<any, IssueHandlerCandidate[]>('/issues/handler-candidates')
}

export function getIssue(id: number) {
  return request.get<any, IssueItem>(`/issues/${id}`)
}

export function createIssue(data: IssueSavePayload) {
  return request.post<any, { id: number }>('/issues', data)
}

export function updateIssue(id: number, data: IssueSavePayload) {
  return request.put<any, void>(`/issues/${id}`, data)
}

export function changeIssueStatus(id: number, status: IssueStatus) {
  return request.post<any, void>(`/issues/${id}/status`, { status })
}

export function deleteIssue(id: number) {
  return request.delete<any, void>(`/issues/${id}`)
}

export function uploadIssueAttachment(file: File) {
  const form = new FormData()
  form.append('file', file)
  return request.post<any, { fileName: string; url: string }>('/issues/attachments', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

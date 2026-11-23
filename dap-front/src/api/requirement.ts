import request from '../utils/request'

export type RequirementStatus =
  | 'DRAFT'
  | 'APPROVING'
  | 'REJECTED'
  | 'PENDING_PLAN'
  | 'PLANNING'
  | 'DEVELOPING'
  | 'RELEASED'

export type WorkbenchScope = 'todo' | 'all'

export interface RequirementFlowItem {
  id: number
  nodeType: string
  nodeTypeLabel?: string
  fromStatus?: string
  fromStatusLabel?: string
  toStatus?: string
  toStatusLabel?: string
  operatorId?: number
  operatorUsername?: string
  commentText?: string
  extraJson?: string
  createdAt?: string
}

export interface RequirementHandlerItem {
  id?: number
  userId: number
  username?: string
  positionId?: number | null
  roleLabel?: string
  applicant?: boolean
  currentHandler?: boolean
}

export interface RequirementItem {
  id: number
  reqNo?: string
  name: string
  productId: number
  productName?: string
  productVersionId?: number | null
  productVersion: string
  description: string
  status: RequirementStatus
  statusLabel?: string
  approverId?: number | null
  approverUsername?: string | null
  currentHandlerId?: number | null
  currentHandlerUsername?: string | null
  rejectReason?: string | null
  planDate?: string | null
  releaseVersion?: string | null
  createdBy?: number
  createdByUsername?: string
  createdAt?: string
  updatedAt?: string
  handlers?: RequirementHandlerItem[]
  flows?: RequirementFlowItem[]
}

export interface RequirementSavePayload {
  name: string
  productId: number
  productVersionId: number
  description: string
}

export interface ApproverCandidate {
  id: number
  username: string
  productManager?: boolean
}

export interface ApproverCandidatesResult {
  productManagerId?: number | null
  productManagerUsername?: string | null
  defaultApproverId?: number | null
  candidates: ApproverCandidate[]
}

export function listRequirements() {
  return request.get<any, RequirementItem[]>('/requirements')
}

export function listRequirementWorkbench(scope: WorkbenchScope = 'todo') {
  return request.get<any, RequirementItem[]>('/requirements/workbench', {
    params: { scope },
  })
}

/** @deprecated 使用 listRequirementWorkbench('todo') */
export function listRequirementInbox() {
  return listRequirementWorkbench('todo')
}

export function listApproverCandidates(productId: number) {
  return request.get<any, ApproverCandidatesResult>('/requirements/approver-candidates', {
    params: { productId },
  })
}

export function getRequirement(id: number) {
  return request.get<any, RequirementItem>(`/requirements/${id}`)
}

export function createRequirement(data: RequirementSavePayload) {
  return request.post<any, { id: number }>('/requirements', data)
}

export function updateRequirement(id: number, data: RequirementSavePayload) {
  return request.put<any, void>(`/requirements/${id}`, data)
}

export function submitRequirement(id: number, data?: { approverId?: number }) {
  return request.post<any, void>(`/requirements/${id}/submit`, data ?? {})
}

export function approveRequirement(id: number) {
  return request.post<any, void>(`/requirements/${id}/approve`)
}

export function rejectRequirement(id: number, data: { reason: string }) {
  return request.post<any, void>(`/requirements/${id}/reject`, data)
}

export function planRequirement(
  id: number,
  data: { releaseVersionId: number; comment?: string },
) {
  return request.post<any, void>(`/requirements/${id}/plan`, data)
}

export function developRequirement(id: number) {
  return request.post<any, void>(`/requirements/${id}/develop`)
}

export function completeRequirement(id: number) {
  return request.post<any, void>(`/requirements/${id}/complete`)
}

export function addRequirementHandlers(
  id: number,
  data: {
    items?: { positionId: number; userIds: number[] }[]
    positionId?: number
    userId?: number
    userIds?: number[]
  },
) {
  return request.post<any, void>(`/requirements/${id}/handlers`, data)
}

/** handlerId = 处理人记录 id（同一用户多岗位时各一条） */
export function removeRequirementHandler(id: number, handlerId: number) {
  return request.delete<any, void>(`/requirements/${id}/handlers/${handlerId}`)
}

export function deleteRequirement(id: number) {
  return request.delete<any, void>(`/requirements/${id}`)
}

import request from '../utils/request'

export type WorkspaceSpaceType = 'PERSONAL'

export interface TenantWorkspaceItem {
  id: number
  spaceType?: WorkspaceSpaceType
  spaceTypeLabel?: string
  name: string
  ownerUserId?: number
  ownerUsername?: string
  workDir: string
  status: number
  statusLabel?: string
  createdAt?: string
  updatedAt?: string
  /** @deprecated 旧工作台遗留字段，已不再返回 */
  associatedSharedIds?: number[]
  description?: string | null
}

export interface WorkspaceCreatePayload {
  name: string
  /** 可选；不传则服务端自动生成磁盘目录名 */
  workDir?: string
}

export function listWorkbenchPersonalWorkspaces() {
  return request.get<any, TenantWorkspaceItem[]>('/workbench/workspaces/personal')
}

export function createWorkbenchWorkspace(data: WorkspaceCreatePayload) {
  return request.post<any, { id: number }>('/workbench/workspaces', data)
}

export interface WorkspaceNodeItem {
  id: number
  parentId?: number | null
  nodeType: 'DIR' | 'FILE'
  displayName: string
  requirementId?: number | null
  disabled?: boolean
  children?: WorkspaceNodeItem[]
}

export interface WorkspaceFileContent {
  id: number
  displayName: string
  content: string
  requirementId?: number | null
}

export function importWorkbenchRequirement(workspaceId: number, requirementId: number) {
  return request.post<any, { id: number }>(`/workbench/workspaces/${workspaceId}/import-requirement`, {
    requirementId,
  })
}

export function listWorkbenchWorkspaceTree(workspaceId: number) {
  return request.get<any, WorkspaceNodeItem[]>(`/workbench/workspaces/${workspaceId}/tree`)
}

export function createWorkbenchWorkspaceDir(
  workspaceId: number,
  data: { parentId?: number | null; displayName: string },
) {
  return request.post<any, { id: number }>(`/workbench/workspaces/${workspaceId}/dirs`, data)
}

export function createWorkbenchWorkspaceFile(
  workspaceId: number,
  data: { parentId?: number | null; displayName: string; content?: string },
) {
  return request.post<any, { id: number }>(`/workbench/workspaces/${workspaceId}/files`, data)
}

export function getWorkbenchWorkspaceFileContent(workspaceId: number, nodeId: number) {
  return request.get<any, WorkspaceFileContent>(
    `/workbench/workspaces/${workspaceId}/nodes/${nodeId}/content`,
  )
}

export function updateWorkbenchWorkspaceFileContent(
  workspaceId: number,
  nodeId: number,
  content: string,
) {
  return request.put<any, void>(`/workbench/workspaces/${workspaceId}/nodes/${nodeId}/content`, {
    content,
  })
}

export function deleteWorkbenchWorkspaceNode(workspaceId: number, nodeId: number) {
  return request.delete<any, void>(`/workbench/workspaces/${workspaceId}/nodes/${nodeId}`)
}

export type WorkspaceSessionType = 'NORMAL' | 'TASK'

export interface WorkspaceSessionContextItem {
  id?: number
  wikiNodeId: number
  nodeType?: 'DIR' | 'FILE'
  displayPath?: string
}

export interface WorkspaceSessionInputItem {
  id?: number
  wikiNodeId: number
  displayPath?: string
}

export interface WorkspaceSessionCodeRepoItem {
  id?: number
  localPath: string
  sortNo?: number
}

export interface WorkspaceSessionItem {
  id: number
  workspaceId: number
  sessionType: WorkspaceSessionType
  title: string
  requirementId?: number | null
  taskTypeCode?: string | null
  taskTypeName?: string | null
  taskTypeKind?: string | null
  positionId?: number | null
  outputDirNodeId?: number | null
  outputDirLabel?: string | null
  /** 输出参考模板（Wiki「模板」下文件） */
  outputTemplateWikiNodeId?: number | null
  outputTemplatePath?: string | null
  /** 规范（Wiki「规范」下目录） */
  standardsWikiNodeId?: number | null
  standardsPath?: string | null
  contexts?: WorkspaceSessionContextItem[]
  inputs?: WorkspaceSessionInputItem[]
  codeRepos?: WorkspaceSessionCodeRepoItem[]
  createdAt?: string
  updatedAt?: string
}

export interface WorkspaceFileRevisionItem {
  versionNo: number
  sessionId?: number | null
  createdAt?: string
  createdBy?: number | null
}

export interface WorkspaceFileDiff {
  nodeId: number
  displayName: string
  previousVersionNo?: number | null
  previousContent: string
  currentContent: string
}

export function listWorkbenchWorkspaceSessions(workspaceId: number) {
  return request.get<any, WorkspaceSessionItem[]>(`/workbench/workspaces/${workspaceId}/sessions`)
}

export function createWorkbenchWorkspaceSession(
  workspaceId: number,
  data: {
    sessionType: WorkspaceSessionType
    title: string
    requirementId?: number
    taskTypeCode?: string
    positionId?: number
    outputDirNodeId?: number | null
    outputTemplateWikiNodeId?: number | null
    standardsWikiNodeId?: number | null
    contexts?: { wikiNodeId: number }[]
    inputs?: { wikiNodeId: number }[]
    codeRepos?: string[]
  },
) {
  return request.post<any, { id: number }>(`/workbench/workspaces/${workspaceId}/sessions`, data)
}

export function updateWorkbenchWorkspaceSession(
  workspaceId: number,
  sessionId: number,
  data: {
    title: string
    outputDirNodeId?: number | null
    outputTemplateWikiNodeId?: number | null
    standardsWikiNodeId?: number | null
    contexts?: { wikiNodeId: number }[]
    inputs?: { wikiNodeId: number }[]
    codeRepos?: string[]
  },
) {
  return request.put<any, void>(`/workbench/workspaces/${workspaceId}/sessions/${sessionId}`, data)
}

export function listWorkbenchWorkspaceFileRevisions(workspaceId: number, nodeId: number) {
  return request.get<any, WorkspaceFileRevisionItem[]>(
    `/workbench/workspaces/${workspaceId}/nodes/${nodeId}/revisions`,
  )
}

export function getWorkbenchWorkspaceFileDiff(
  workspaceId: number,
  nodeId: number,
  versionNo?: number | null,
) {
  return request.get<any, WorkspaceFileDiff>(
    `/workbench/workspaces/${workspaceId}/nodes/${nodeId}/diff`,
    { params: versionNo != null ? { versionNo } : undefined },
  )
}

export function deleteWorkbenchWorkspaceSession(workspaceId: number, sessionId: number) {
  return request.delete<any, void>(`/workbench/workspaces/${workspaceId}/sessions/${sessionId}`)
}

export function getWorkbenchWorkspaceSession(workspaceId: number, sessionId: number) {
  return request.get<any, WorkspaceSessionItem>(
    `/workbench/workspaces/${workspaceId}/sessions/${sessionId}`,
  )
}

export function deleteWorkbenchPersonalWorkspace(id: number) {
  return request.delete<any, void>(`/workbench/workspaces/${id}`)
}

export interface SessionTaskTypeOption {
  code: string
  name: string
  kind: string // GEN | REVIEW
  defaultPositionCode: string
}

export function listSessionTaskTypes() {
  return request.get<any, SessionTaskTypeOption[]>('/workbench/workspaces/session-task-types')
}

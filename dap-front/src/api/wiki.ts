import request from '../utils/request'

export interface WikiNodeItem {
  id: number
  parentId?: number | null
  nodeType: 'DIR' | 'FILE'
  displayName: string
  children?: WikiNodeItem[]
}

export interface WikiFileContent {
  id: number
  displayName: string
  content: string
}

export function listWikiTree() {
  return request.get<any, WikiNodeItem[]>('/workbench/wiki/tree')
}

export function createWikiDir(data: { parentId?: number | null; displayName: string }) {
  return request.post<any, { id: number }>('/workbench/wiki/dirs', data)
}

export function createWikiFile(data: {
  parentId?: number | null
  displayName: string
  content?: string
}) {
  return request.post<any, { id: number }>('/workbench/wiki/files', data)
}

export function getWikiFileContent(nodeId: number) {
  return request.get<any, WikiFileContent>(`/workbench/wiki/nodes/${nodeId}/content`)
}

export function updateWikiFileContent(nodeId: number, content: string) {
  return request.put<any, void>(`/workbench/wiki/nodes/${nodeId}/content`, { content })
}

export function deleteWikiNode(nodeId: number) {
  return request.delete<any, void>(`/workbench/wiki/nodes/${nodeId}`)
}

export function publishWiki(data: {
  sourceWorkspaceId: number
  sourceNodeId: number
  targetParentId?: number | null
}) {
  return request.post<any, { id: number }>('/workbench/wiki/publish', data)
}

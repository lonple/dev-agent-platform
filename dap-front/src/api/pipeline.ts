import request from '../utils/request'

export interface PipelineUnitItem {
  id: number
  unitCode: string
  unitName: string
  positionCode: string
  taskTypeCode: string
  status: string
  roundTier: string
  maxRound: number
  confirmRound: number
  repoKind: string
  sessionId?: number | null
  outputWorkspaceNodeId?: number | null
  outputWikiNodeId?: number | null
  errorMessage?: string | null
  /** 会话实际绑定的上下文 Wiki 路径 */
  contextPaths?: string[]
}

export interface PipelineNodeItem {
  id: number
  nodeCode: string
  nodeName: string
  kind: string
  status: string
  optionalFlag?: boolean
  enabledFlag?: boolean
  sortOrder: number
  units: PipelineUnitItem[]
}

export interface PipelineWorktreeItem {
  id: number
  productCodeRepoId: number
  repoKind: string
  branchName: string
  worktreePath: string
  baseLocalPath: string
  status: string
}

export interface PipelineTrackMergeItem {
  id: string
  title: string
  severity?: string
  suggestion?: string
  target?: string
  valuable?: boolean | null
  reviewers?: string[]
  sources?: Array<{ position?: string; unitCode?: string; originalId?: string; title?: string }>
}

export interface PipelineTrackMerge {
  id: number
  trackCode: string
  label?: string
  reviewNodeCode?: string
  mergeFileName?: string
  status: string
  llmMergeJson?: string | null
  itemsJson?: string | null
  mergedWikiNodeId?: number | null
  confirmedAt?: string | null
  createdAt?: string
}

export interface PipelineInstance {
  id: number
  requirementId?: number | null
  issueId?: number | null
  issueNo?: string | null
  productId?: number
  reqNo?: string
  requirementName?: string
  definitionId: number
  definitionCode?: string
  definitionName?: string
  status: string
  waitingReason?: string | null
  optionsJson?: string | null
  startedAt?: string
  finishedAt?: string
  nodes: PipelineNodeItem[]
  worktrees: PipelineWorktreeItem[]
  trackMerges?: PipelineTrackMerge[]
}

export interface PipelineConfirmRound {
  id: number
  unitInstanceId: number
  roundNo: number
  phase: string
  findingsJson?: string | null
  openQuestionsJson?: string | null
  humanAnswersJson?: string | null
}

export function startPipeline(requirementId: number, definitionCode?: string) {
  return request.post<any, { id: number }>('/pipeline/instances', {
    requirementId,
    definitionCode,
  })
}

export function startIssuePipeline(issueId: number, definitionCode = 'ISSUE_AUTO') {
  return request.post<any, { id: number }>('/pipeline/instances', {
    issueId,
    definitionCode,
  })
}

export function getPipelineByRequirement(requirementId: number) {
  return request.get<any, PipelineInstance | null>(
    `/pipeline/instances/by-requirement/${requirementId}`,
  )
}

export function getPipelineByIssue(issueId: number) {
  return request.get<any, PipelineInstance | null>(`/pipeline/instances/by-issue/${issueId}`)
}

export function getPipeline(id: number) {
  return request.get<any, PipelineInstance>(`/pipeline/instances/${id}`)
}

export function cancelPipeline(id: number) {
  return request.post<any, void>(`/pipeline/instances/${id}/cancel`)
}

export function pmSecondPipeline(
  id: number,
  data: {
    action: 'PASS' | 'PASS_AND_DEV'
    releaseVersionId: number
    releaseTime?: string | null
    enableArch: boolean
    enableUi: boolean
  },
) {
  return request.post<any, void>(`/pipeline/instances/${id}/pm-second`, data)
}

export function ensurePipelineWorktrees(id: number) {
  return request.post<any, PipelineWorktreeItem[]>(`/pipeline/instances/${id}/ensure-worktrees`)
}

export function triggerPipelineUnit(unitId: number) {
  return request.post<any, void>(`/pipeline/units/${unitId}/trigger`)
}

export interface CodingProgress {
  unitId: number
  unitName?: string
  unitStatus?: string
  currentStage?: string | null
  currentSessionId?: number | null
  errorMessage?: string | null
  sessions: Array<{ sessionId: number; title?: string; createdAt?: string }>
  jobs: Array<{
    id: number
    jobType?: string
    status?: string
    lastError?: string | null
    payloadJson?: string | null
    updatedAt?: string
  }>
  planTasks: Array<{
    id?: string
    title?: string
    module?: string
    status?: string
    sessionId?: number | null
  }>
}

export function getCodingProgress(unitId: number) {
  return request.get<any, CodingProgress>(`/pipeline/units/${unitId}/coding`)
}

/** 停止正在执行的 Agent 单元 */
export function stopPipelineUnit(unitId: number) {
  return request.post<any, void>(`/pipeline/units/${unitId}/stop`)
}

export function getPipelineUnitConfirm(unitId: number) {
  return request.get<any, PipelineConfirmRound | null>(`/pipeline/units/${unitId}/confirm`)
}

export function confirmPipelineUnit(
  unitId: number,
  data: {
    findingAnswers: Array<Record<string, unknown>>
    questionAnswers: Array<Record<string, unknown>>
  },
) {
  return request.post<any, void>(`/pipeline/units/${unitId}/confirm`, data)
}

/** 完成输出后手动发布到 Wiki */
export function publishPipelineUnit(unitId: number) {
  return request.post<any, void>(`/pipeline/units/${unitId}/publish`)
}

export function getPipelineTrackMerge(instanceId: number, trackCode: string) {
  return request.get<any, PipelineTrackMerge | null>(
    `/pipeline/instances/${instanceId}/track-merge/${trackCode}`,
  )
}

export function confirmPipelineTrackMerge(
  instanceId: number,
  trackCode: string,
  items: PipelineTrackMergeItem[],
) {
  return request.post<any, void>(
    `/pipeline/instances/${instanceId}/track-merge/${trackCode}/confirm`,
    { items },
  )
}

export interface PipelineWikiDoc {
  id: number
  displayName: string
  content: string
}

export function openPipelineDoc(instanceId: number, name: string) {
  return request.get<any, PipelineWikiDoc>(`/pipeline/instances/${instanceId}/docs`, {
    params: { name },
  })
}

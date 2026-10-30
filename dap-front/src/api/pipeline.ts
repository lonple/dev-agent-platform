import request from '../utils/request'

export interface PipelineAssignee {
  userId: number
  username?: string
  status: string
  actedAt?: string
}

export interface PipelineReviewItem {
  id?: number
  ordinal?: number
  content: string
  checked: boolean
  source?: string
}

export interface PipelineReviewDoc {
  reviewId: number
  stepInstanceId?: number
  stepName?: string
  roleHint?: string
  wikiNodeId?: number
  wikiPath?: string
}

export interface PipelineStep {
  stepInstanceId: number
  stepCode: string
  stepName: string
  status: string
  round: number
  executor: string
  handlerUserId?: number
  sessionId?: number
  outputArtifactId?: number
  reviewId?: number
  errorMessage?: string
  startedAt?: string
  finishedAt?: string
  durationMs: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  assignees?: PipelineAssignee[]
  inputPaths?: string[]
  contextPaths?: string[]
  outputTemplatePath?: string
  outputDirPath?: string
  codeRepoPaths?: string[]
  reviewItems?: PipelineReviewItem[]
  reviewWikiNodeId?: number
  reviewWikiPath?: string
  reviewDocs?: PipelineReviewDoc[]
}

export interface PipelineAiPrepare {
  stepInstanceId: number
  sessionId: number
  workspaceId?: number
  kickoffPrompt: string
  instance: PipelineInstance
}

export interface PipelineInstance {
  instanceId: number
  requirementId: number
  definitionCode?: string
  definitionVersion?: number
  status: string
  waitingReason?: string
  currentStepCodes?: string[]
  totalDurationMs: number
  totalPromptTokens: number
  totalCompletionTokens: number
  totalTokens: number
  startedAt?: string
  finishedAt?: string
  steps: PipelineStep[]
}

export function getPipeline(requirementId: number) {
  return request.get<any, PipelineInstance | null>(`/requirements/${requirementId}/pipeline`)
}

export function startPipeline(requirementId: number, fromStepCode?: string) {
  return request.post<any, PipelineInstance>(`/requirements/${requirementId}/pipeline/start`, {
    fromStepCode: fromStepCode || null,
  })
}

export function cancelPipeline(requirementId: number) {
  return request.post<any, PipelineInstance>(`/requirements/${requirementId}/pipeline/cancel`)
}

export function approvePipelineStep(
  requirementId: number,
  stepInstanceId: number,
  comment?: string,
) {
  return request.post<any, PipelineInstance>(
    `/requirements/${requirementId}/pipeline/steps/${stepInstanceId}/approve`,
    { comment },
  )
}

export function rejectPipelineStep(
  requirementId: number,
  stepInstanceId: number,
  comment?: string,
) {
  return request.post<any, PipelineInstance>(
    `/requirements/${requirementId}/pipeline/steps/${stepInstanceId}/reject`,
    { comment },
  )
}

export function completePipelineAiStep(
  requirementId: number,
  stepInstanceId: number,
  usage?: { promptTokens?: number; completionTokens?: number; totalTokens?: number },
) {
  return request.post<any, PipelineInstance>(
    `/requirements/${requirementId}/pipeline/steps/${stepInstanceId}/complete-ai`,
    usage || {},
  )
}

export function retryPipelineAiStep(requirementId: number, stepInstanceId: number) {
  return request.post<any, PipelineInstance>(
    `/requirements/${requirementId}/pipeline/steps/${stepInstanceId}/retry-ai`,
  )
}

export function preparePipelineAi(requirementId: number, stepInstanceId: number) {
  return request.post<any, PipelineAiPrepare>(
    `/requirements/${requirementId}/pipeline/steps/${stepInstanceId}/prepare-ai`,
  )
}

export function finalizePipelineAi(requirementId: number, stepInstanceId: number) {
  return request.post<any, PipelineInstance>(
    `/requirements/${requirementId}/pipeline/steps/${stepInstanceId}/finalize-ai`,
  )
}

export function updatePipelineReviewItems(
  requirementId: number,
  stepInstanceId: number,
  items: { id?: number; checked: boolean; content?: string }[],
) {
  return request.put<any, void>(
    `/requirements/${requirementId}/pipeline/steps/${stepInstanceId}/review-items`,
    items,
  )
}

export function formatDuration(ms?: number | null) {
  const n = ms ?? 0
  if (n < 1000) return `${n} ms`
  const s = Math.round(n / 1000)
  if (s < 60) return `${s} s`
  const m = Math.floor(s / 60)
  const rs = s % 60
  return `${m}m ${rs}s`
}

export function formatTokens(n?: number | null) {
  const v = n ?? 0
  if (v >= 10000) return `${(v / 1000).toFixed(1)}k`
  return String(v)
}

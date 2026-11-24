import request from '../utils/request'

export interface SandboxResource {
  id?: number
  resourceCode: string
  image?: string
  maxSlots?: number
  status?: string
  nodeId?: number
}

export interface SandboxProfile {
  id?: number
  profileCode: string
  displayName?: string
  isDefault?: boolean
  status?: string
  version?: number
  configJson?: string
  updatedAt?: string
}

export type { SandboxEnvVar } from '../utils/runtimeEnvironment'
export { extractEnvVarsFromConfig, mergeEnvVarsIntoConfig } from '../utils/runtimeEnvironment'

export function ensureSandboxBundle(productId: number) {
  return request.post<any, any>(`/work/products/${productId}/sandbox/ensure`)
}

export function listSandboxResources(productId: number) {
  return request.get<any, SandboxResource[]>(`/work/products/${productId}/sandbox/resources`)
}

export function upsertSandboxResource(productId: number, data: SandboxResource) {
  return request.put<any, any>(`/work/products/${productId}/sandbox/resources`, data)
}

export function listSandboxProfiles(productId: number) {
  return request.get<any, SandboxProfile[]>(`/work/products/${productId}/sandbox/profiles`)
}

export function upsertSandboxProfile(productId: number, data: Partial<SandboxProfile> & { profileCode: string }) {
  return request.put<any, SandboxProfile>(`/work/products/${productId}/sandbox/profiles`, data)
}

export function setDefaultSandboxProfile(productId: number, code: string) {
  return request.post<any, SandboxProfile>(`/work/products/${productId}/sandbox/profiles/${code}/default`)
}

export function deleteSandboxProfile(productId: number, code: string) {
  return request.delete<any, void>(`/work/products/${productId}/sandbox/profiles/${code}`)
}

export function previewSandboxProfile(productId: number, code: string, reqNo?: string) {
  return request.get<any, Record<string, unknown>>(
    `/work/products/${productId}/sandbox/profiles/${code}/preview`,
    { params: { reqNo } },
  )
}

export function listSandboxLeases(productId: number) {
  return request.get<any, any[]>(`/work/sandbox/leases`, { params: { productId } })
}

/** 产品无关的原始模板；业务端口和依赖变量由各产品自行配置。 */
export const DEFAULT_RUNTIME_PROFILE_JSON = `{
  "imageOverride": null,
  "sandbox": { "preferredResourceCodes": [], "leaseTtlHours": 8 },
  "ports": { "pool": { "start": 18000, "end": 18999 }, "services": [] },
  "envTemplates": [],
  "startTemplates": [],
  "cacheMounts": { "m2": false, "npm": false },
  "envVars": []
}`

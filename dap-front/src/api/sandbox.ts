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

export interface SandboxEnvVar {
  name: string
  value?: string
  enabled?: boolean
  remark?: string
}

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

/** 新建 Profile 起步模板；依赖地址写在 envVars，跟本 Profile 走 */
export const DEFAULT_RUNTIME_PROFILE_JSON = `{
  "imageOverride": null,
  "sandbox": {
    "preferredResourceCodes": [],
    "leaseTtlHours": 8,
    "releaseWorktreeOnClose": false,
    "autoCommitOnRelease": false
  },
  "database": {
    "schemaPattern": "it_{productCode}_{reqNoSafe}",
    "urlTemplate": "jdbc:postgresql://{dbHost}:{dbPort}/{dbName}?currentSchema={schema}",
    "flywayEnabled": true
  },
  "ports": {
    "pool": { "start": 18000, "end": 18999 },
    "services": [
      { "key": "web", "protocol": "http", "containerPort": 5173, "env": "WEB_PORT" },
      { "key": "api", "protocol": "http", "containerPort": 8080, "env": "SERVER_PORT" }
    ]
  },
  "middleware": {
    "redisKeyPrefixPattern": "{tenantId}:{productCode}:{reqNoSafe}:",
    "mqPrefixPattern": "{productCode}.{reqNoSafe}."
  },
  "envTemplates": [
    { "name": "SPRING_PROFILES_ACTIVE", "value": "sandbox" },
    { "name": "DB_SCHEMA", "value": "{schema}" }
  ],
  "startTemplates": [
    { "key": "api", "actionHint": "start_maven", "workdir": "backend" },
    { "key": "web", "actionHint": "start_vite", "workdir": "frontend" }
  ],
  "cacheMounts": { "m2": true, "npm": true },
  "envVars": []
}`

export function extractEnvVarsFromConfig(configJson?: string): SandboxEnvVar[] {
  if (!configJson) return []
  try {
    const cfg = JSON.parse(configJson)
    const list: SandboxEnvVar[] = []
    if (Array.isArray(cfg.envVars)) {
      for (const it of cfg.envVars) {
        const name = it?.name || it?.envName
        if (!name) continue
        list.push({
          name,
          value: it.value ?? it.envValue ?? '',
          enabled: it.enabled !== false,
          remark: it.remark ?? '',
        })
      }
    } else if (cfg.env && typeof cfg.env === 'object') {
      for (const [name, value] of Object.entries(cfg.env)) {
        list.push({ name, value: String(value ?? ''), enabled: true })
      }
    }
    return list
  } catch {
    return []
  }
}

/** 把表格里的环境变量写回 configJson.envVars */
export function mergeEnvVarsIntoConfig(configJson: string, envVars: SandboxEnvVar[]): string {
  const cfg = configJson?.trim() ? JSON.parse(configJson) : {}
  cfg.envVars = envVars
    .filter((r) => r.name?.trim())
    .map((r) => ({
      name: r.name.trim(),
      value: r.value ?? '',
      enabled: r.enabled !== false,
      remark: r.remark || undefined,
    }))
  delete cfg.env
  return JSON.stringify(cfg, null, 2)
}

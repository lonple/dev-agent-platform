export interface RuntimeServicePort {
  [key: string]: unknown
  key: string
  containerPort: number
  env: string
}
export interface RuntimeProfileForm {
  image: string
  ttl: number
  portStart: number
  portEnd: number
  services: RuntimeServicePort[]
  cacheM2: boolean
  cacheNpm: boolean
}
function object(value: unknown, label: string): Record<string, any> {
  if (value === undefined) return {}
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}必须是 JSON 对象`)
  return value as Record<string, any>
}
export function readRuntimeProfile(json: string): RuntimeProfileForm {
  const cfg = object(JSON.parse(json), '运行方案')
  const sandbox = object(cfg.sandbox, 'sandbox')
  const ports = object(cfg.ports, 'ports')
  const pool = object(ports.pool, 'ports.pool')
  const cache = object(cfg.cacheMounts, 'cacheMounts')
  if (ports.services !== undefined && !Array.isArray(ports.services)) throw new Error('服务端口必须是数组')
  return {
    image: cfg.imageOverride ?? '', ttl: sandbox.leaseTtlHours ?? 8,
    portStart: pool.start ?? 18000, portEnd: pool.end ?? 18999,
    services: (ports.services ?? []).map((item: unknown) => {
      const s = object(item, '服务端口')
      return { ...s, key: s.key ?? '', containerPort: s.containerPort ?? 8080, env: s.env ?? '' }
    }),
    cacheM2: cache.m2 ?? true, cacheNpm: cache.npm ?? true,
  }
}
export function writeRuntimeProfile(json: string, form: RuntimeProfileForm): string {
  const cfg = object(JSON.parse(json), '运行方案')
  const port = (n: number) => Number.isInteger(n) && n >= 1 && n <= 65535
  if (!Number.isInteger(form.ttl) || form.ttl < 1) throw new Error('有效时长必须是大于零的整数小时')
  if (!port(form.portStart) || !port(form.portEnd) || form.portStart > form.portEnd) throw new Error('宿主机端口范围应在 1–65535 之间，起始端口不能大于结束端口')
  const keys = new Set<string>()
  for (const service of form.services) {
    if (!service.key.trim() || keys.has(service.key.trim())) throw new Error('服务标识不能为空或重复')
    if (!port(service.containerPort)) throw new Error('容器内端口应为 1–65535 的整数')
    if (service.env && !/^[A-Za-z_][A-Za-z0-9_]*$/.test(service.env.trim())) throw new Error('端口变量名只能包含字母、数字和下划线，且不能以数字开头')
    keys.add(service.key.trim())
  }
  cfg.imageOverride = form.image.trim() || null
  cfg.sandbox = { ...object(cfg.sandbox, 'sandbox'), leaseTtlHours: form.ttl }
  const ports = object(cfg.ports, 'ports')
  cfg.ports = { ...ports, pool: { ...object(ports.pool, 'ports.pool'), start: form.portStart, end: form.portEnd },
    services: form.services.map(s => ({ ...s, key: s.key.trim(), env: s.env.trim() })) }
  cfg.cacheMounts = { ...object(cfg.cacheMounts, 'cacheMounts'), m2: form.cacheM2, npm: form.cacheNpm }
  return JSON.stringify(cfg, null, 2)
}

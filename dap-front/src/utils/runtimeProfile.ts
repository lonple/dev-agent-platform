/** 运行方案端口清单中的一项：key 是服务标识，containerPort 是容器内端口。 */
export interface RuntimeServicePort {
  [key: string]: unknown
  key: string
  containerPort: number | null
  protocol: string
}
export interface RuntimeProfileForm {
  image: string
  ttl: number
  portStart: number
  portEnd: number
  services: RuntimeServicePort[]
  cacheM2: boolean
  cacheNpm: boolean
  /** 通用输入：数据库连接信息（只作为占位符取值，不自动生成变量） */
  dbHost: string
  dbPort: number | null
  dbName: string
  dbSchema: string
  dbUsername: string
  dbPassword: string
  /** 沙箱执行配置 */
  buildCommand: string
  startCommand: string
  testCommand: string
  actionTimeoutSec: number | null
  /** 测试配置（平台测试约定） */
  testEnabled: boolean
  testApiUrl: string
  testUiUrl: string
  testUsername: string
  testPassword: string
  testModelMode: 'real' | 'mock'
  testMockPort: number | null
}

const SERVICE_KEY = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/

function object(value: unknown, label: string): Record<string, any> {
  if (value === undefined) return {}
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}必须是 JSON 对象`)
  return value as Record<string, any>
}

/** 端口清单：同一容器端口只保留一次，key 冲突时追加端口号。 */
function readServices(ports: Record<string, any>): RuntimeServicePort[] {
  const out: RuntimeServicePort[] = []
  const usedPorts = new Set<number>()
  const usedKeys = new Set<string>()
  const push = (key: unknown, containerPort: unknown, protocol: unknown) => {
    const name = String(key ?? '').trim()
    if (!name || !Number.isInteger(containerPort) || (containerPort as number) < 1) return
    const port = containerPort as number
    if (usedPorts.has(port)) return
    let unique = name
    while (usedKeys.has(unique)) unique = `${unique}-${port}`
    usedKeys.add(unique)
    usedPorts.add(port)
    out.push({
      key: unique,
      containerPort: port,
      protocol: String(protocol ?? '').trim() || 'http',
    })
  }
  if (Array.isArray(ports.services)) {
    for (const item of ports.services) {
      if (!item || typeof item !== 'object' || Array.isArray(item)) continue
      const row = item as Record<string, any>
      push(row.key, row.containerPort, row.protocol)
    }
  }
  return out
}

export function readRuntimeProfile(json: string): RuntimeProfileForm {
  const cfg = object(JSON.parse(json), '运行方案')
  const sandbox = object(cfg.sandbox, 'sandbox')
  const ports = object(cfg.ports, 'ports')
  const pool = object(ports.pool, 'ports.pool')
  const cache = object(cfg.cacheMounts, 'cacheMounts')
  if (ports.services !== undefined && !Array.isArray(ports.services)) throw new Error('服务端口必须是数组')
  const database = object(cfg.database, 'database')
  const execution = object(cfg.execution, 'execution')
  const testing = object(cfg.testing, 'testing')
  return {
    image: cfg.imageOverride ?? '', ttl: sandbox.leaseTtlHours ?? 8,
    portStart: pool.start ?? 18000, portEnd: pool.end ?? 18999,
    services: readServices(ports),
    cacheM2: cache.m2 ?? true, cacheNpm: cache.npm ?? true,
    dbHost: database.host ?? '', dbPort: database.port ?? null, dbName: database.name ?? '',
    dbSchema: database.schema ?? '', dbUsername: database.username ?? '', dbPassword: database.password ?? '',
    buildCommand: execution.buildCommand ?? '', startCommand: execution.startCommand ?? '', testCommand: execution.testCommand ?? '',
    actionTimeoutSec: execution.actionTimeoutSec ?? null,
    testEnabled: cfg.testing !== undefined,
    testApiUrl: testing.apiUrl ?? '', testUiUrl: testing.uiUrl ?? '',
    testUsername: testing.username ?? '', testPassword: testing.password ?? '',
    testModelMode: testing.modelMode === 'mock' ? 'mock' : 'real', testMockPort: testing.mockPort ?? null,
  }
}

export function writeRuntimeProfile(json: string, form: RuntimeProfileForm): string {
  const cfg = object(JSON.parse(json), '运行方案')
  const port = (n: unknown): n is number => typeof n === 'number' && Number.isInteger(n) && n >= 1 && n <= 65535
  if (!Number.isInteger(form.ttl) || form.ttl < 1) throw new Error('有效时长必须是大于零的整数小时')
  if (!port(form.portStart) || !port(form.portEnd) || form.portStart > form.portEnd) throw new Error('宿主机端口范围应在 1–65535 之间，起始端口不能大于结束端口')
  const keys = new Set<string>()
  const containerPorts = new Set<number>()
  const services = form.services.map((service) => {
    const key = String(service.key ?? '').trim()
    if (!SERVICE_KEY.test(key)) throw new Error('服务标识不能为空，且只能包含字母、数字、下划线、点和连字符')
    if (keys.has(key)) throw new Error('服务标识不能重复：' + key)
    keys.add(key)
    if (!port(service.containerPort)) throw new Error(`服务「${key}」的容器端口应为 1–65535 的整数`)
    if (containerPorts.has(service.containerPort)) throw new Error(`容器端口 ${service.containerPort} 被多个服务使用，同一容器端口只能映射一次`)
    containerPorts.add(service.containerPort)
    const protocol = String(service.protocol ?? '').trim() || 'http'
    return { key, containerPort: service.containerPort, protocol }
  })
  cfg.imageOverride = form.image.trim() || null
  cfg.sandbox = { ...object(cfg.sandbox, 'sandbox'), leaseTtlHours: form.ttl }
  const ports = object(cfg.ports, 'ports')
  cfg.ports = { ...ports, pool: { ...object(ports.pool, 'ports.pool'), start: form.portStart, end: form.portEnd }, services }
  cfg.cacheMounts = { ...object(cfg.cacheMounts, 'cacheMounts'), m2: form.cacheM2, npm: form.cacheNpm }
  const optionalPort = (n: number | null, label: string) => { if (n !== null && n !== undefined && !port(n)) throw new Error(`${label}应为 1–65535 的整数`) }
  optionalPort(form.dbPort, '数据库端口')
  optionalPort(form.testMockPort, 'Mock 端口')
  const name = /^[A-Za-z_][A-Za-z0-9_]*$/
  if (form.dbSchema.trim() && !name.test(form.dbSchema.trim())) throw new Error('数据库模式只能包含字母、数字和下划线')
  if (form.dbName.trim() && !/^[A-Za-z0-9_-]+$/.test(form.dbName.trim())) throw new Error('数据库名只能包含字母、数字、下划线和连字符')
  if (!!form.dbHost.trim() !== !!form.dbName.trim()) throw new Error('数据库 IP 和库名需同时填写')
  if (form.actionTimeoutSec !== null && (!Number.isInteger(form.actionTimeoutSec) || form.actionTimeoutSec < 1)) throw new Error('执行超时必须是大于零的整数秒')
  const compact = (value: Record<string, unknown>) => Object.fromEntries(Object.entries(value).filter(([, v]) => v !== '' && v !== null && v !== undefined))
  // 通用数据库输入：只提供取值，不生成变量名；JSON 里手写的其它 database 字段原样保留
  const database = compact({
    ...object(cfg.database, 'database'),
    host: form.dbHost.trim(), port: form.dbPort, name: form.dbName.trim(),
    schema: form.dbSchema.trim(), username: form.dbUsername.trim(), password: form.dbPassword,
  })
  if (Object.keys(database).length) cfg.database = database; else delete cfg.database
  const execution = compact({ buildCommand: form.buildCommand.trim(), startCommand: form.startCommand.trim(), testCommand: form.testCommand.trim(), actionTimeoutSec: form.actionTimeoutSec })
  const testing = compact({
    ...object(cfg.testing, 'testing'),
    apiUrl: form.testApiUrl.trim(), uiUrl: form.testUiUrl.trim(),
    username: form.testUsername.trim(), password: form.testPassword,
    modelMode: form.testModelMode, mockPort: form.testModelMode === 'mock' ? form.testMockPort : null,
  })
  // 历史字段清理：旧版产品专用结构已废弃，保存时移除
  delete cfg.dapService
  if (Object.keys(execution).length) cfg.execution = execution; else delete cfg.execution
  if (form.testEnabled) cfg.testing = testing; else delete cfg.testing
  return JSON.stringify(cfg, null, 2)
}

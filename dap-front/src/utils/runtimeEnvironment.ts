export interface SandboxEnvVar {
  name: string
  value?: string
  enabled?: boolean
  remark?: string
  /** 有值时，此变量同时声明服务监听端口和 Docker 端口映射。 */
  serviceKey?: string
}

/** 旧数据库/端口配置无损转成变量；保存后只保留一份变量值。 */
export function extractEnvVarsFromConfig(configJson?: string): SandboxEnvVar[] {
  if (!configJson) return []
  const cfg = JSON.parse(configJson)
  const list: SandboxEnvVar[] = (cfg.envVars ?? Object.entries(cfg.env ?? {}).map(([name, value]) => ({ name, value })))
    .map((r: any) => ({ ...r, name: r.name ?? r.envName, value: String(r.value ?? r.envValue ?? ''), enabled: r.enabled !== false }))
  for (const r of cfg.envTemplates ?? []) {
    if (!list.some(v => v.name === r.name)) list.push({ ...r, enabled: true })
  }
  const replacements = new Map<string, string>()
  const add = (name: string, value: string) => {
    let unique = name
    while (list.some(r => r.name === unique)) unique += '_LEGACY'
    list.push({ name: unique, value, enabled: true })
    return unique
  }
  for (const [key, value] of Object.entries(cfg.database ?? {})) {
    if (key === 'schemaPattern' || value === null || value === '') continue
    const name = add(`DB_${key.toUpperCase()}`, String(value))
    replacements.set(`{database.${key}}`, `{env.${name}}`)
    if (key === 'host') replacements.set('{dbHost}', `{env.${name}}`)
    if (key === 'port') replacements.set('{dbPort}', `{env.${name}}`)
    if (key === 'name') replacements.set('{dbName}', `{env.${name}}`)
  }
  if (!replacements.has('{database.schema}')) replacements.set('{database.schema}', '{schema}')
  for (const service of cfg.ports?.services ?? []) {
    const token = `{ports.${service.key}}`
    let row = list.find(r => r.enabled !== false && (r.serviceKey === service.key || r.value === token))
    if (!row) {
      const name = add(`SERVICE_${String(service.key).replace(/[^A-Za-z0-9_]/g, '_').toUpperCase()}_PORT`, String(service.containerPort))
      row = list.find(r => r.name === name)!
    }
    if (row.value === token) row.value = String(service.containerPort)
    row.serviceKey = service.key
    replacements.set(token, `{env.${row.name}}`)
  }
  for (const row of list) for (const [from, to] of replacements) row.value = (row.value ?? '').split(from).join(to)
  return list
}

export function mergeEnvVarsIntoConfig(configJson: string, rows: SandboxEnvVar[]): string {
  const cfg = JSON.parse(configJson || '{}')
  const names = new Set<string>(), services = new Set<string>(), ports = new Set<number>()
  cfg.envVars = rows.map(r => {
    const name = r.name.trim(), serviceKey = r.serviceKey?.trim() || undefined
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name) || names.has(name)) throw new Error('环境变量名非法或重复：' + name)
    names.add(name)
    if (serviceKey && r.enabled !== false) {
      if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(serviceKey) || services.has(serviceKey)) throw new Error('服务标识非法或重复：' + serviceKey)
      if (!/^\d+$/.test(r.value ?? '') || Number(r.value) < 1 || Number(r.value) > 65535) throw new Error('服务端口变量必须是 1–65535 的整数：' + name)
      if (ports.has(Number(r.value))) throw new Error('容器端口不能重复：' + r.value)
      ports.add(Number(r.value))
      services.add(serviceKey)
    }
    return { name, value: r.value ?? '', enabled: r.enabled !== false, remark: r.remark, serviceKey }
  })
  delete cfg.env
  delete cfg.envTemplates
  delete cfg.ports?.services
  if (cfg.database?.schema) {
    const schemaRow = rows.find(r => r.enabled !== false && /^DB_SCHEMA(?:_LEGACY)*$/.test(r.name) && r.value === String(cfg.database.schema))
    if (schemaRow) cfg.schemaEnv = schemaRow.name
  }
  // schemaPattern 是隔离标识的模板，不是连接参数；保留兼容。
  if (cfg.database?.schemaPattern) cfg.database = { schemaPattern: cfg.database.schemaPattern }
  else delete cfg.database
  return JSON.stringify(cfg, null, 2)
}

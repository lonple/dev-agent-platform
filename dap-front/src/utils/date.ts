/** 统一展示：yyyy-MM-dd HH:mm:ss */
export function formatDateTime(value?: string | Date | null): string {
  if (value == null || value === '') return ''
  const d = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(d.getTime())) {
    // 已是目标格式或无法解析时原样返回（去掉 T / 毫秒）
    const s = String(value).trim()
    const m = s.match(/^(\d{4}-\d{2}-\d{2})[T\s](\d{2}:\d{2}:\d{2})/)
    return m ? `${m[1]} ${m[2]}` : s
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

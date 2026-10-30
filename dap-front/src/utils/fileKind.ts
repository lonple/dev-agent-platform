/** 工作台 / Wiki 可预览的文本文件类型 */

export function fileExt(name: string | null | undefined): string {
  const n = (name || '').trim()
  const i = n.lastIndexOf('.')
  if (i <= 0 || i >= n.length - 1) return ''
  return n.slice(i + 1).toLowerCase()
}

export function isMdFile(name: string | null | undefined): boolean {
  return fileExt(name) === 'md'
}

export function isHtmlFile(name: string | null | undefined): boolean {
  const ext = fileExt(name)
  return ext === 'html' || ext === 'htm'
}

/** 预览态：html 走原生渲染，其余按 Markdown */
export function isHtmlPreview(name: string | null | undefined): boolean {
  return isHtmlFile(name)
}

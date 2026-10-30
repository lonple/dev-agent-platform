import { marked } from 'marked'
import DOMPurify from 'dompurify'

marked.setOptions({
  gfm: true,
  breaks: true,
})

export function renderMarkdown(source: string): string {
  const text = source ?? ''
  if (!text.trim()) return ''
  const html = marked.parse(text, { async: false }) as string
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
  })
}

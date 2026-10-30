import { diffLines } from 'diff'

export type DiffSideCell = {
  lineNo: number | null
  text: string
  kind: 'equal' | 'add' | 'remove' | 'empty'
}

export type DiffPairRow = {
  left: DiffSideCell
  right: DiffSideCell
}

/** 将新旧文本做成左右并排、可高亮的行对 */
export function buildSideBySideDiff(previous: string, current: string): DiffPairRow[] {
  const parts = diffLines(previous ?? '', current ?? '')
  const rows: DiffPairRow[] = []
  let leftNo = 1
  let rightNo = 1

  for (let i = 0; i < parts.length; i++) {
    const part = parts[i]
    const lines = splitKeepContent(part.value)
    if (!lines.length) continue

    if (part.added) {
      for (const text of lines) {
        rows.push({
          left: { lineNo: null, text: '', kind: 'empty' },
          right: { lineNo: rightNo++, text, kind: 'add' },
        })
      }
      continue
    }

    if (part.removed) {
      const next = parts[i + 1]
      // 删除紧跟新增 → 视为同行替换，左右对齐
      if (next?.added) {
        const oldLines = lines
        const newLines = splitKeepContent(next.value)
        const n = Math.max(oldLines.length, newLines.length)
        for (let j = 0; j < n; j++) {
          const leftText = oldLines[j]
          const rightText = newLines[j]
          rows.push({
            left:
              leftText == null
                ? { lineNo: null, text: '', kind: 'empty' }
                : { lineNo: leftNo++, text: leftText, kind: 'remove' },
            right:
              rightText == null
                ? { lineNo: null, text: '', kind: 'empty' }
                : { lineNo: rightNo++, text: rightText, kind: 'add' },
          })
        }
        i++
        continue
      }
      for (const text of lines) {
        rows.push({
          left: { lineNo: leftNo++, text, kind: 'remove' },
          right: { lineNo: null, text: '', kind: 'empty' },
        })
      }
      continue
    }

    for (const text of lines) {
      rows.push({
        left: { lineNo: leftNo++, text, kind: 'equal' },
        right: { lineNo: rightNo++, text, kind: 'equal' },
      })
    }
  }

  return rows
}

function splitKeepContent(value: string): string[] {
  if (value === '') return []
  const lines = value.split('\n')
  // diffLines 常在末尾带换行，产生空尾项
  if (lines.length && lines[lines.length - 1] === '') {
    lines.pop()
  }
  return lines
}

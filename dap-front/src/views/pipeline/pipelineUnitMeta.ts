/** 对齐 Mock / 《流水线节点与Agent配置》的单元展示元数据 */

export interface UnitDisplayMeta {
  agent: string
  template: string
  writeLabel: string
  inputs: string[]
  contexts: string[]
  confirmDocPattern: string
  outputDocName: string
  promptHint: string
}

const ROLE_AGENT: Record<string, string> = {
  PM: 'AI 产品经理',
  ARCH: 'AI 架构师',
  UI: 'AI UI设计师',
  DEV: 'AI 研发',
  QA: 'AI 测试',
}

const BY_TASK: Record<string, Partial<UnitDisplayMeta>> = {
  REVIEW_PRE: {
    agent: 'AI 产品经理',
    template: '原始需求评估模板-业务.md',
    writeLabel: '原始需求-业务评估',
    inputs: ['原始需求'],
    contexts: ['归档/需求'],
    confirmDocPattern: '原始需求-业务确认.md',
    outputDocName: '原始需求-业务评估.md',
    promptHint: 'REVIEW_PRE · PM',
  },
  REVIEW_PRE_TECH: {
    agent: 'AI 架构师',
    template: '原始需求评估模板-技术.md',
    writeLabel: '原始需求-技术评估',
    inputs: ['原始需求'],
    contexts: ['归档/需求'],
    confirmDocPattern: '原始需求-技术确认.md',
    outputDocName: '原始需求-技术评估.md',
    promptHint: 'REVIEW_PRE_TECH · ARCH',
  },
  GEN_PRD: {
    agent: 'AI 产品经理',
    template: '需求设计PRD模板 → 产品需求说明书',
    writeLabel: '产品需求说明书',
    inputs: ['原始需求', '需求设计PRD模板'],
    contexts: ['wiki/{产品}/{版本}/{需求}', 'wiki/{产品}/归档/需求'],
    confirmDocPattern: '产品需求说明书-确认-r{n}.md',
    outputDocName: '产品需求说明书.md',
    promptHint: 'GEN_PRD · PM',
  },
  GEN_ARCH: {
    agent: 'AI 架构师',
    template: '架构设计文档模板 → 架构设计文档',
    writeLabel: '架构设计文档',
    inputs: ['产品需求说明书', '架构设计文档模板'],
    contexts: ['wiki/{产品}/{版本}/{需求}', 'wiki/{产品}/归档/架构设计'],
    confirmDocPattern: '架构设计文档-确认-r{n}.md',
    outputDocName: '架构设计文档.md',
    promptHint: 'GEN_ARCH · ARCH',
  },
  GEN_UI: {
    agent: 'AI UI设计师',
    template: '规范/UI设计规范 → UI设计.html',
    writeLabel: 'UI设计.html',
    inputs: ['产品需求说明书'],
    contexts: ['wiki/{产品}/{版本}/{需求}', 'wiki/{产品}/归档/UI设计'],
    confirmDocPattern: 'UI设计-确认-r{n}.md',
    outputDocName: 'UI设计.html',
    promptHint: 'GEN_UI · UI · 规范/UI设计规范.md',
  },
  GEN_DETAIL: {
    agent: 'AI 研发',
    template: '详细设计文档模板 → 详细设计文档',
    writeLabel: '详细设计文档',
    inputs: ['产品需求说明书', '架构设计（如有）', '详细设计模板'],
    contexts: ['业务代码仓（主路径）'],
    confirmDocPattern: '详细设计-确认-r{n}.md',
    outputDocName: '详细设计文档.md',
    promptHint: 'GEN_DETAIL · DEV',
  },
  GEN_CASES: {
    agent: 'AI 测试',
    template: '需求测试用例模板 → 需求测试用例',
    writeLabel: '需求测试用例',
    inputs: ['产品需求说明书', '测试用例模板'],
    contexts: ['业务代码仓（主路径）'],
    confirmDocPattern: '测试用例-确认-r{n}.md',
    outputDocName: '需求测试用例.md',
    promptHint: 'GEN_CASES · QA',
  },
  GEN_CODING: {
    agent: 'AI 研发',
    template: '详细设计 → 编码完成报告',
    writeLabel: '编码完成报告',
    inputs: ['详细设计文档', '需求测试用例'],
    contexts: ['feat/{需求号} worktree（业务仓）'],
    confirmDocPattern: '编码-确认-r{n}.md',
    outputDocName: '编码完成报告.md',
    promptHint: 'GEN_CODING · DEV',
  },
  GEN_CODE_REVIEW: {
    agent: '代码评审',
    template: 'feat worktree 相对默认分支',
    writeLabel: '代码评审报告',
    inputs: ['业务仓 worktree diff'],
    contexts: ['上一轮不修改原因'],
    confirmDocPattern: '代码评审报告-确认.md',
    outputDocName: '代码评审报告.md',
    promptHint: 'GEN_CODE_REVIEW · DEV',
  },
  GEN_INT_TEST: {
    agent: 'AI 测试',
    template: '可自动化用例 → 集成测试报告',
    writeLabel: '集成测试报告',
    inputs: ['需求测试用例', '编码完成报告'],
    contexts: ['feat/{需求号} worktree（测试仓）'],
    confirmDocPattern: '集成测试-确认-r{n}.md',
    outputDocName: '集成测试报告.md',
    promptHint: 'GEN_INT_TEST · QA',
  },
  GEN_ISSUE_ANALYSIS: {
    agent: 'AI 研发',
    template: '问题原因分析模板.md',
    writeLabel: '问题原因分析',
    inputs: ['问题单.md'],
    contexts: ['wiki/{产品}/归档', '关联需求目录（可选）'],
    confirmDocPattern: '问题分析-确认-r{n}.md',
    outputDocName: '问题原因分析.md',
    promptHint: 'GEN_ISSUE_ANALYSIS · DEV',
  },
  GEN_ISSUE_FIX: {
    agent: 'AI 研发',
    template: '问题原因分析 → 问题修复报告',
    writeLabel: '问题修复报告',
    inputs: ['问题原因分析.md'],
    contexts: ['fix/{问题单号} worktree（业务仓）'],
    confirmDocPattern: '问题修复-确认-r{n}.md',
    outputDocName: '问题修复报告.md',
    promptHint: 'GEN_ISSUE_FIX · DEV',
  },
  GEN_ISSUE_CODE_REVIEW: {
    agent: '代码评审',
    template: 'fix worktree 相对默认分支',
    writeLabel: '代码评审报告',
    inputs: ['业务仓 worktree diff'],
    contexts: ['上一轮不修改原因'],
    confirmDocPattern: '代码评审报告-确认.md',
    outputDocName: '代码评审报告.md',
    promptHint: 'GEN_ISSUE_CODE_REVIEW · DEV',
  },
  GEN_ISSUE_INT_TEST: {
    agent: 'AI 测试',
    template: '存量自动化 → 集成测试报告',
    writeLabel: '集成测试报告',
    inputs: ['仓库内历史测试（无 Wiki 用例输入）'],
    contexts: ['fix/{问题单号} worktree（测试/业务仓）'],
    confirmDocPattern: '集成测试-确认-r{n}.md',
    outputDocName: '集成测试报告.md',
    promptHint: 'GEN_ISSUE_INT_TEST · QA',
  },
  REVISE_PRD: {
    agent: 'AI 产品修订',
    template: 'PRD合并评审 + 产品需求说明书',
    writeLabel: '产品需求说明书',
    inputs: ['PRD合并评审.md', '产品需求说明书.md'],
    contexts: [],
    confirmDocPattern: '修订PRD-确认-r{n}.md',
    outputDocName: '产品需求说明书.md',
    promptHint: 'REVISE_PRD · PM',
  },
  REVISE_ARCH: {
    agent: 'AI 架构修订',
    template: '架构合并评审 + 架构设计文档',
    writeLabel: '架构设计文档',
    inputs: ['架构合并评审.md', '架构设计文档.md'],
    contexts: [],
    confirmDocPattern: '修订架构-确认-r{n}.md',
    outputDocName: '架构设计文档.md',
    promptHint: 'REVISE_ARCH · ARCH',
  },
  REVISE_UI: {
    agent: 'AI UI修订',
    template: 'UI合并评审 + UI设计',
    writeLabel: 'UI设计.html',
    inputs: ['UI合并评审.md', 'UI设计.html'],
    contexts: [],
    confirmDocPattern: '修订UI-确认-r{n}.md',
    outputDocName: 'UI设计.html',
    promptHint: 'REVISE_UI · UI',
  },
  REVISE_DETAIL: {
    agent: 'AI 详设修订',
    template: '详设合并评审 + 详细设计',
    writeLabel: '详细设计文档',
    inputs: ['详设合并评审.md', '详细设计文档.md', '产品需求说明书.md'],
    contexts: ['业务代码仓'],
    confirmDocPattern: '修订详设-确认-r{n}.md',
    outputDocName: '详细设计文档.md',
    promptHint: 'REVISE_DETAIL · DEV',
  },
  REVISE_CASES: {
    agent: 'AI 用例修订',
    template: '用例合并评审 + 测试用例',
    writeLabel: '需求测试用例',
    inputs: ['用例合并评审.md', '需求测试用例.md', '产品需求说明书.md'],
    contexts: ['测试代码仓'],
    confirmDocPattern: '修订用例-确认-r{n}.md',
    outputDocName: '需求测试用例.md',
    promptHint: 'REVISE_CASES · QA',
  },
  REVIEW_PRD_DEV: {
    agent: 'AI 研发',
    template: '评审模板.md',
    writeLabel: 'PRD评审-研发',
    inputs: ['产品需求说明书'],
    contexts: ['业务代码仓'],
    confirmDocPattern: 'PRD评审-研发-确认.md',
    outputDocName: 'PRD评审-研发.md',
    promptHint: 'REVIEW_PRD_DEV · DEV',
  },
  REVIEW_PRD_QA: {
    agent: 'AI 测试',
    template: '评审模板.md',
    writeLabel: 'PRD评审-测试',
    inputs: ['产品需求说明书'],
    contexts: [],
    confirmDocPattern: 'PRD评审-测试-确认.md',
    outputDocName: 'PRD评审-测试.md',
    promptHint: 'REVIEW_PRD_QA · QA',
  },
  REVIEW_ARCH_DEV: {
    agent: 'AI 研发',
    template: '评审模板.md',
    writeLabel: '架构设计评审-研发',
    inputs: ['架构设计文档', '产品需求说明书'],
    contexts: ['业务代码仓'],
    confirmDocPattern: '架构设计评审-研发-确认.md',
    outputDocName: '架构设计评审-研发.md',
    promptHint: 'REVIEW_ARCH_DEV · DEV',
  },
  REVIEW_ARCH_QA: {
    agent: 'AI 测试',
    template: '评审模板.md',
    writeLabel: '架构设计评审-测试',
    inputs: ['架构设计文档'],
    contexts: [],
    confirmDocPattern: '架构设计评审-测试-确认.md',
    outputDocName: '架构设计评审-测试.md',
    promptHint: 'REVIEW_ARCH_QA · QA',
  },
  REVIEW_UI: {
    agent: 'AI 产品经理',
    template: '评审模板.md',
    writeLabel: 'UI设计评审-产品',
    inputs: ['UI设计.html', '产品需求说明书'],
    contexts: [],
    confirmDocPattern: 'UI设计评审-产品-确认.md',
    outputDocName: 'UI设计评审-产品.md',
    promptHint: 'REVIEW_UI · PM',
  },
  REVIEW_UI_DEV: {
    agent: 'AI 研发',
    template: '评审模板.md',
    writeLabel: 'UI设计评审-研发',
    inputs: ['UI设计.html'],
    contexts: ['业务代码仓'],
    confirmDocPattern: 'UI设计评审-研发-确认.md',
    outputDocName: 'UI设计评审-研发.md',
    promptHint: 'REVIEW_UI_DEV · DEV',
  },
  REVIEW_UI_QA: {
    agent: 'AI 测试',
    template: '评审模板.md',
    writeLabel: 'UI设计评审-测试',
    inputs: ['UI设计.html'],
    contexts: [],
    confirmDocPattern: 'UI设计评审-测试-确认.md',
    outputDocName: 'UI设计评审-测试.md',
    promptHint: 'REVIEW_UI_QA · QA',
  },
  REVIEW_DETAIL_ARCH: {
    agent: 'AI 架构师',
    template: '评审模板.md',
    writeLabel: '详细设计评审-架构',
    inputs: ['详细设计文档'],
    contexts: [],
    confirmDocPattern: '详细设计评审-架构-确认.md',
    outputDocName: '详细设计评审-架构.md',
    promptHint: 'REVIEW_DETAIL_ARCH · ARCH',
  },
  REVIEW_DETAIL_PM: {
    agent: 'AI 产品经理',
    template: '评审模板.md',
    writeLabel: '详细设计评审-产品',
    inputs: ['详细设计文档', '产品需求说明书'],
    contexts: [],
    confirmDocPattern: '详细设计评审-产品-确认.md',
    outputDocName: '详细设计评审-产品.md',
    promptHint: 'REVIEW_DETAIL_PM · PM',
  },
  REVIEW_DETAIL_QA: {
    agent: 'AI 测试',
    template: '评审模板.md',
    writeLabel: '详细设计评审-测试',
    inputs: ['详细设计文档'],
    contexts: [],
    confirmDocPattern: '详细设计评审-测试-确认.md',
    outputDocName: '详细设计评审-测试.md',
    promptHint: 'REVIEW_DETAIL_QA · QA',
  },
  REVIEW_CASE_ARCH: {
    agent: 'AI 架构师',
    template: '评审模板.md',
    writeLabel: '测试用例评审-架构',
    inputs: ['需求测试用例'],
    contexts: [],
    confirmDocPattern: '测试用例评审-架构-确认.md',
    outputDocName: '测试用例评审-架构.md',
    promptHint: 'REVIEW_CASE_ARCH · ARCH',
  },
  REVIEW_CASE_PM: {
    agent: 'AI 产品经理',
    template: '评审模板.md',
    writeLabel: '测试用例评审-产品',
    inputs: ['需求测试用例', '产品需求说明书'],
    contexts: [],
    confirmDocPattern: '测试用例评审-产品-确认.md',
    outputDocName: '测试用例评审-产品.md',
    promptHint: 'REVIEW_CASE_PM · PM',
  },
  REVIEW_CASE_DEV: {
    agent: 'AI 研发',
    template: '评审模板.md',
    writeLabel: '测试用例评审-研发',
    inputs: ['需求测试用例'],
    contexts: [],
    confirmDocPattern: '测试用例评审-研发-确认.md',
    outputDocName: '测试用例评审-研发.md',
    promptHint: 'REVIEW_CASE_DEV · DEV',
  },
}

export function unitMeta(taskTypeCode: string, positionCode?: string): UnitDisplayMeta {
  const base = BY_TASK[taskTypeCode] || {}
  return {
    agent: base.agent || ROLE_AGENT[positionCode || ''] || 'AI Agent',
    template: base.template || taskTypeCode,
    writeLabel: base.writeLabel || '产出文档',
    inputs: base.inputs || [],
    contexts: base.contexts || [],
    confirmDocPattern: base.confirmDocPattern || '确认-r{n}.md',
    outputDocName: base.outputDocName || `${taskTypeCode}.md`,
    promptHint: base.promptHint || `${taskTypeCode} · ${positionCode || ''}`,
  }
}

/** 展示用文档：label → Wiki 文件名 */
const DOC_FILE: Record<string, string> = {
  原始需求: '原始需求.md',
  '原始需求评估模板-业务': '原始需求评估模板-业务.md',
  '原始需求评估模板-技术': '原始需求评估模板-技术.md',
  需求设计PRD模板: '需求设计PRD模板.md',
  产品需求说明书: '产品需求说明书.md',
  架构设计文档模板: '架构设计文档模板.md',
  架构设计文档: '架构设计文档.md',
  '架构设计（如有）': '架构设计文档.md',
  UI设计规范: 'UI设计规范.md',
  'UI设计.html': 'UI设计.html',
  详细设计模板: '详细设计文档模板.md',
  详细设计文档模板: '详细设计文档模板.md',
  详细设计文档: '详细设计文档.md',
  测试用例模板: '需求测试用例.md',
  需求测试用例: '需求测试用例.md',
  评审模板: '评审模板.md',
  编码完成报告: '编码完成报告.md',
  集成测试报告: '集成测试报告.md',
  问题单: '问题单.md',
  问题原因分析模板: '问题原因分析模板.md',
  问题原因分析: '问题原因分析.md',
}

export function docFileName(label: string): string | null {
  if (!label) return null
  if (DOC_FILE[label]) return DOC_FILE[label]
  // 「模板 → 产出」取箭头左侧模板名
  if (label.includes('→')) {
    const left = label.split('→')[0].trim()
    return DOC_FILE[left] || (left.endsWith('.md') || left.endsWith('.html') ? left : `${left}.md`)
  }
  if (label.endsWith('.md') || label.endsWith('.html')) return label
  return `${label}.md`
}

export type UnitFlowStepKey = 'start' | 'run' | 'confirm' | 'output' | 'publish'

export const UNIT_FLOW_STEPS: { key: UnitFlowStepKey; label: string }[] = [
  { key: 'start', label: '启动' },
  { key: 'run', label: '执行中' },
  { key: 'confirm', label: '问题确认' },
  { key: 'output', label: '完成输出' },
  { key: 'publish', label: '发布文档' },
]

/** 当前高亮步骤下标；已完成步骤为 < current；全部完成 current=4 且 done=true */
export function unitFlowIndex(status: string, confirmRound?: number | null): {
  current: number
  failed: boolean
  allDone: boolean
} {
  const round = confirmRound || 0
  if (status === 'SKIPPED') return { current: -1, failed: false, allDone: false }
  if (status === 'SUCCEEDED') return { current: 4, failed: false, allDone: true }
  if (status === 'WAITING_PUBLISH') return { current: 4, failed: false, allDone: false }
  if (status === 'FAILED') {
    if (round > 0) return { current: 3, failed: true, allDone: false }
    return { current: 1, failed: true, allDone: false }
  }
  if (status === 'WAITING_CONFIRM') return { current: 2, failed: false, allDone: false }
  if (status === 'RUNNING') return { current: 1, failed: false, allDone: false }
  // PENDING / WAITING_TRIGGER
  return { current: 0, failed: false, allDone: false }
}

/** 产出/确认文档是否已落个人空间或 Wiki，可展示可点击链接 */
export function canOpenUnitDoc(status: string, hasWorkspaceNode?: number | null, hasWikiNode?: number | null) {
  if (hasWikiNode || hasWorkspaceNode) return true
  return status === 'WAITING_PUBLISH' || status === 'SUCCEEDED'
}

export function nodeFlowNote(nodeCode: string): { title: string; html: string } | null {
  const map: Record<string, { title: string; html: string }> = {
    ai_pre: {
      title: 'AI 初审 · 两段式',
      html: '① 有不确定 → 问题 JSON → 人确认（确认档先落<strong>个人空间·隐藏</strong>）→ 再写评审书<br />② <strong>完成输出</strong>后确认档可见 → 手动<strong>发布文档</strong>到 Wiki<br />③ 两侧齐后可进产品经理二审',
    },
    gen_prd: {
      title: '生成需求分析 · 两段式',
      html: '① 有不确定 → 问题确认（确认档个人空间隐藏）→ 写出《产品需求说明书》到个人空间<br />② <strong>完成输出</strong> → 手动<strong>发布文档</strong>到 Wiki<br />③ 再进二审勾选架构/UI',
    },
    gen_arch: {
      title: '生成架构设计 · 两段式',
      html: '① 有不确定 → 架构师确认（确认档个人空间隐藏）→ 输出《架构设计文档》到个人空间<br />② 完成输出后手动发布到 Wiki',
    },
    gen_ui: {
      title: '生成 UI 设计 · 两段式',
      html: '① 有不确定 → UI 确认（确认档个人空间隐藏）→ 输出 UI设计.html 到个人空间<br />② 完成输出后手动发布到 Wiki',
    },
    prd_review: {
      title: '需求设计PRD评审 · 多岗两段式',
      html: '各岗位独立：有不确定 → 问题 JSON → 本岗确认并归档 → 再输出本岗评审书<br /><b>本轨</b>各岗齐 → 立刻本轨合并 → 文档修订',
    },
    arch_review: {
      title: '架构设计评审 · 多岗两段式',
      html: '各岗位独立两段式；本轨齐后立刻合并确认，再进入文档修订。',
    },
    ui_review: {
      title: 'UI设计评审 · 多岗两段式',
      html: '各岗位独立两段式；本轨齐后立刻合并确认，再进入文档修订。',
    },
    doc_revise: {
      title: '文档修订 · 修订 Agent',
      html: '岗位：PRD→产品经理；架构→架构师；UI→UI设计师。<br />交互：确认问题项 → 勾选需改 → <strong>自动执行修订</strong>；不需改则直接结束该 Agent。',
    },
    gen_detail: {
      title: '详细设计 · 两段式',
      html: '① 有不确定 → 问题 JSON → 研发确认并归档<br />② 确认后输出《详细设计文档》；本节点可绑业务仓主路径（不建 worktree）',
    },
    gen_cases: {
      title: '测试用例 · 两段式',
      html: '① 有不确定 → 问题 JSON → 测试确认并归档<br />② 确认后输出《需求测试用例》',
    },
    detail_review: {
      title: '详细设计评审 · 多岗两段式',
      html: '各岗独立两段式；本轨齐 → 合并 → 详设修订。',
    },
    cases_review: {
      title: '测试用例评审 · 多岗两段式',
      html: '各岗独立两段式；本轨齐 → 合并 → 用例修订。',
    },
    doc_revise_dt: {
      title: '文档修订（详设/用例）',
      html: '详设/用例修订齐 → 开发编码 → 集成测试 → 结束',
    },
    gen_coding: {
      title: '开发编码 · 两段式',
      html: '① 有不确定 → 问题 JSON → 研发确认<br />② 按详细设计在 <strong>feat/需求号 worktree</strong> 实现；完成后进代码评审',
    },
    code_review: {
      title: '代码评审',
      html: '开发编码完成后会自动 commit。对 feat worktree 与 <strong>master</strong> 做评审。每条问题选择<strong>修改</strong>或<strong>不修改</strong>（不修改必须写原因）。有修改则只改这些项后再评；全部不修改或没有问题则进入集成测试。',
    },
    issue_code_review: {
      title: '代码评审',
      html: '问题修复完成后会自动 commit。对 fix worktree 与 <strong>master</strong> 做评审。每条问题选择修改或不修改。有修改则只改这些项后再评；否则进入存量集成测试。',
    },
    gen_int_test: {
      title: '集成测试 · 两段式',
      html: '① 有不确定 → 问题 JSON → 测试确认<br />② 在测试仓 worktree 跑可自动化用例；不通过则修复闭环；通过后输出《集成测试报告》',
    },
  }
  return map[nodeCode] || null
}

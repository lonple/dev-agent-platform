/** 流水线工作节点 ↔ 各岗位任务类型（提示词管理左侧导航） */
export interface PipelinePromptSlot {
  taskTypeCode: string
  positionCode: string
  roleLabel: string
}

export interface PipelinePromptNode {
  id: string
  name: string
  slots: PipelinePromptSlot[]
}

export const PIPELINE_PROMPT_NODES: PipelinePromptNode[] = [
  {
    id: 'ai_pre',
    name: 'AI 初审',
    slots: [
      { taskTypeCode: 'REVIEW_PRE', positionCode: 'PM', roleLabel: '产品经理' },
      { taskTypeCode: 'REVIEW_PRE_TECH', positionCode: 'ARCH', roleLabel: '架构师' },
    ],
  },
  {
    id: 'gen_prd',
    name: '生成需求分析',
    slots: [{ taskTypeCode: 'GEN_PRD', positionCode: 'PM', roleLabel: '产品经理' }],
  },
  {
    id: 'gen_arch',
    name: '生成架构设计',
    slots: [{ taskTypeCode: 'GEN_ARCH', positionCode: 'ARCH', roleLabel: '架构师' }],
  },
  {
    id: 'gen_ui',
    name: '生成 UI 设计',
    slots: [{ taskTypeCode: 'GEN_UI', positionCode: 'UI', roleLabel: 'UI设计师' }],
  },
  {
    id: 'prd_review',
    name: '需求设计PRD评审',
    slots: [
      { taskTypeCode: 'REVIEW_PRD_DEV', positionCode: 'DEV', roleLabel: '研发' },
      { taskTypeCode: 'REVIEW_PRD_QA', positionCode: 'QA', roleLabel: '测试' },
    ],
  },
  {
    id: 'arch_review',
    name: '架构设计评审',
    slots: [
      { taskTypeCode: 'REVIEW_ARCH_DEV', positionCode: 'DEV', roleLabel: '研发' },
      { taskTypeCode: 'REVIEW_ARCH_QA', positionCode: 'QA', roleLabel: '测试' },
    ],
  },
  {
    id: 'ui_review',
    name: 'UI设计评审',
    slots: [
      { taskTypeCode: 'REVIEW_UI', positionCode: 'PM', roleLabel: '产品经理' },
      { taskTypeCode: 'REVIEW_UI_DEV', positionCode: 'DEV', roleLabel: '研发' },
      { taskTypeCode: 'REVIEW_UI_QA', positionCode: 'QA', roleLabel: '测试' },
    ],
  },
  {
    id: 'doc_revise',
    name: '文档修订',
    slots: [
      { taskTypeCode: 'REVISE_PRD', positionCode: 'PM', roleLabel: '产品·修订需求分析' },
      { taskTypeCode: 'REVISE_ARCH', positionCode: 'ARCH', roleLabel: '架构·修订架构设计' },
      { taskTypeCode: 'REVISE_UI', positionCode: 'UI', roleLabel: 'UI设计师·修订UI设计' },
    ],
  },
  {
    id: 'gen_detail',
    name: '详细设计',
    slots: [{ taskTypeCode: 'GEN_DETAIL', positionCode: 'DEV', roleLabel: '研发' }],
  },
  {
    id: 'gen_cases',
    name: '测试用例',
    slots: [{ taskTypeCode: 'GEN_CASES', positionCode: 'QA', roleLabel: '测试' }],
  },
  {
    id: 'detail_review',
    name: '详细设计评审',
    slots: [
      { taskTypeCode: 'REVIEW_DETAIL_ARCH', positionCode: 'ARCH', roleLabel: '架构师' },
      { taskTypeCode: 'REVIEW_DETAIL_PM', positionCode: 'PM', roleLabel: '产品经理' },
      { taskTypeCode: 'REVIEW_DETAIL_QA', positionCode: 'QA', roleLabel: '测试' },
    ],
  },
  {
    id: 'cases_review',
    name: '测试用例评审',
    slots: [
      { taskTypeCode: 'REVIEW_CASE_ARCH', positionCode: 'ARCH', roleLabel: '架构师' },
      { taskTypeCode: 'REVIEW_CASE_PM', positionCode: 'PM', roleLabel: '产品经理' },
      { taskTypeCode: 'REVIEW_CASE_DEV', positionCode: 'DEV', roleLabel: '研发' },
    ],
  },
  {
    id: 'doc_revise_dt',
    name: '文档修订（详设/用例）',
    slots: [
      { taskTypeCode: 'REVISE_DETAIL', positionCode: 'DEV', roleLabel: '研发·修订详细设计' },
      { taskTypeCode: 'REVISE_CASES', positionCode: 'QA', roleLabel: '测试·修订测试用例' },
    ],
  },
  {
    id: 'gen_coding',
    name: '开发编码',
    slots: [{ taskTypeCode: 'GEN_CODING', positionCode: 'DEV', roleLabel: '研发' }],
  },
  {
    id: 'code_review',
    name: '代码评审',
    slots: [{ taskTypeCode: 'GEN_CODE_REVIEW', positionCode: 'DEV', roleLabel: '研发' }],
  },
  {
    id: 'gen_int_test',
    name: '集成测试',
    slots: [{ taskTypeCode: 'GEN_INT_TEST', positionCode: 'QA', roleLabel: '测试' }],
  },
  // —— 问题单流水 ISSUE_AUTO ——
  {
    id: 'issue_analyze',
    name: '问题分析',
    slots: [{ taskTypeCode: 'GEN_ISSUE_ANALYSIS', positionCode: 'DEV', roleLabel: '研发' }],
  },
  {
    id: 'issue_fix',
    name: '问题修复',
    slots: [{ taskTypeCode: 'GEN_ISSUE_FIX', positionCode: 'DEV', roleLabel: '研发' }],
  },
  {
    id: 'issue_code_review',
    name: '代码评审',
    slots: [{ taskTypeCode: 'GEN_ISSUE_CODE_REVIEW', positionCode: 'DEV', roleLabel: '研发' }],
  },
  {
    id: 'issue_int_test',
    name: '存量集成测试',
    slots: [{ taskTypeCode: 'GEN_ISSUE_INT_TEST', positionCode: 'QA', roleLabel: '测试' }],
  },
]

export const ALL_PIPELINE_TASK_CODES = Array.from(
  new Set(PIPELINE_PROMPT_NODES.flatMap((n) => n.slots.map((s) => s.taskTypeCode))),
)

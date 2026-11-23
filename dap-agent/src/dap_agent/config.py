from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    dap_internal_token: str = "dev-internal-token"
    dap_server_base_url: str = "http://127.0.0.1:8200"
    llm_base_url: str = "https://api.openai.com/v1"
    llm_api_key: str = ""
    llm_model: str = "gpt-4o-mini"
    # 流式响应「相邻两个 chunk」最长空闲秒数；思考/工具规划模型偶发停顿 >120s。
    # 设为 0 或负数可关闭该超时（仍受底层 TCP/网关限制）。
    llm_stream_chunk_timeout_s: float = 600
    host: str = "0.0.0.0"
    port: int = 8202
    # 工具调用轮次上限（可用环境变量覆盖，如 MAX_TOOL_ROUNDS_CODE=500）
    max_tool_rounds_default: int = 20
    max_tool_rounds_task: int = 48
    # 绑定代码仓库的开发任务：一轮≈一次模型调用+工具，256 足够搭骨架+写主流程
    max_tool_rounds_code: int = 256
    # 编码·任务拆分 CODING_PLAN：短会话，禁止按 256 空转逛仓
    max_tool_rounds_coding_plan: int = 32
    # 编码·汇总 CODING_INTEGRATE
    max_tool_rounds_coding_integrate: int = 48
    # 编码·单任务卡 CODING_TASK：按卡实现，禁止按 256 空转逛仓
    max_tool_rounds_coding_task: int = 96
    # 问题修复 GEN_ISSUE_FIX：探+改 + 已改收尾；总预算=explore+wrap
    max_tool_rounds_issue_fix_explore: int = 40
    max_tool_rounds_issue_fix_wrap: int = 16
    max_tool_rounds_issue_fix: int = 56
    # 问题分析 GEN_ISSUE_ANALYSIS：少探早写
    max_tool_rounds_issue_analysis: int = 24
    # PLAN 阶段 list_dir 全会话上限（防换目录无限逛）
    coding_plan_max_list_dir: int = 8
    # 单任务卡 list_dir 全会话上限
    coding_task_max_list_dir: int = 16
    # 问题修复 list_dir 全会话上限（EXPLORE）
    issue_fix_max_list_dir: int = 12
    # PATCHED 后允许的未结算校验 read 次数（search/list 一律禁止）
    issue_fix_patched_read_keep: int = 3
    # 问题分析 list_dir 上限
    issue_analysis_max_list_dir: int = 6
    # 单轮发给 LLM 的最大估算 token（超限走降级阶梯）；字符近似 token≈chars/2
    context_max_round_tokens: int = 32000
    context_soft_ratio: float = 0.50
    context_hard_ratio: float = 0.75
    context_template_ratio: float = 0.85
    context_sliding_k_coding: int = 8
    context_sliding_k_design: int = 10
    context_sliding_k_min: int = 4
    # 证据卡：最近 N 个 LLM 轮保留 search 原文；再早的 search → 索引卡
    # 2：允许「搜完再看一轮 hits 再决定读谁」，减少早收卡后重搜
    context_evidence_fresh_search_turns: int = 2
    # 同文件/多文件工作区：最近 N 个 LLM 轮内读过的文件保留正文；更早 → 阅读证据卡
    # 5：与评审「连读一窗≈5 轮」对齐；超时压缩。评审任务另要求收窗前必须输出一次
    context_evidence_read_keep_turns: int = 5


settings = Settings()

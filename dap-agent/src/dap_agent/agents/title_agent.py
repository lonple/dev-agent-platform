from __future__ import annotations

from langchain_core.messages import HumanMessage, SystemMessage

from dap_agent.llm.factory import create_chat_model


async def summarize_title(question: str) -> str:
    from dap_agent.config import settings

    text = (question or "").strip()
    if not text:
        return "新对话"
    if not (settings.llm_api_key or "").strip():
        return text[:30]
    model = create_chat_model(temperature=0)
    messages = [
        SystemMessage(
            content="你是会话标题助手。根据用户首问生成不超过30个汉字的简短标题，"
            "不要引号，不要标点堆叠，不要解释。"
        ),
        HumanMessage(content=text[:500]),
    ]
    result = await model.ainvoke(messages)
    title = (result.content or "").strip().replace("\n", " ")
    if len(title) > 30:
        title = title[:30]
    return title or text[:30]

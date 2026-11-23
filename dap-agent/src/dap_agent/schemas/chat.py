from __future__ import annotations

from typing import Any, Literal, Optional

from pydantic import BaseModel, Field


class ChatImagePart(BaseModel):
    """多模态附图；与正文【附图N】同号对应。"""

    url: str = ""
    label: str = ""
    alt: str = ""


class ChatMessage(BaseModel):
    role: Literal["user", "assistant", "system"]
    content: str = ""
    thinking: Optional[str] = None
    images: list[ChatImagePart] = Field(default_factory=list)


class PositionInfo(BaseModel):
    id: Optional[int] = None
    name: str = ""


class SkillInfo(BaseModel):
    id: Optional[int] = None
    name: Optional[str] = None
    custom: Optional[str] = None


class FileIndexEntry(BaseModel):
    source: str = ""
    nodeId: Optional[int] = None
    displayPath: str = ""
    role: str = ""
    approxChars: Optional[int] = None


class FileIndex(BaseModel):
    sessionId: Optional[int] = None
    sessionType: Optional[str] = None
    title: Optional[str] = None
    workspaceId: Optional[int] = None
    outputDirNodeId: Optional[int] = None
    outputDirLabel: Optional[str] = None
    taskTypeCode: Optional[str] = None
    taskTypeName: Optional[str] = None
    taskRemark: Optional[str] = None
    requireDeliverable: Optional[bool] = None
    # 编码四阶段：CODING_PLAN / CODING_TASK / CODING_INTEGRATE
    codingStage: Optional[str] = None
    codingTaskId: Optional[str] = None
    stageDeliverableName: Optional[str] = None
    deliverableTemplate: Optional[str] = None
    outputTemplateWikiNodeId: Optional[int] = None
    outputTemplatePath: Optional[str] = None
    standardsWikiNodeId: Optional[int] = None
    standardsPath: Optional[str] = None
    # 规范正文：交付物撰写时必须遵守
    standardsContent: Optional[str] = None
    codeRepos: list[str] = Field(default_factory=list)
    files: list[FileIndexEntry] = Field(default_factory=list)


class ChatStreamRequest(BaseModel):
    run_id: str
    tenant_id: Optional[int] = None
    user_id: Optional[int] = None
    session_id: Optional[int] = None
    workspace_id: Optional[int] = None
    session_type: Optional[str] = None
    output_dir_node_id: Optional[int] = None
    position: PositionInfo = Field(default_factory=PositionInfo)
    skills: list[SkillInfo] = Field(default_factory=list)
    # 兼容旧单技能字段
    skill: Optional[SkillInfo] = None
    messages: list[ChatMessage] = Field(default_factory=list)
    file_index: Optional[FileIndex | dict[str, Any]] = None
    tools_enabled: bool = False


class TitleRequest(BaseModel):
    question: str = ""


class TitleResponse(BaseModel):
    title: str


class StopRequest(BaseModel):
    run_id: str


class StopResponse(BaseModel):
    ok: bool
    cancelled: bool

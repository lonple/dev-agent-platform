package com.lonple.dap.server.modules.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.chat.dto.ChatSessionVO;
import com.lonple.dap.server.modules.chat.dto.WorkspaceSessionCreateRequest;
import com.lonple.dap.server.modules.chat.dto.WorkspaceSessionUpdateRequest;
import com.lonple.dap.server.modules.chat.entity.WbChatMessage;
import com.lonple.dap.server.modules.chat.entity.WbChatSession;
import com.lonple.dap.server.modules.chat.entity.WbSessionCodeRepo;
import com.lonple.dap.server.modules.chat.entity.WbSessionContext;
import com.lonple.dap.server.modules.chat.entity.WbSessionInput;
import com.lonple.dap.server.modules.chat.mapper.ChatMessageMapper;
import com.lonple.dap.server.modules.chat.mapper.ChatSessionMapper;
import com.lonple.dap.server.modules.chat.mapper.SessionCodeRepoMapper;
import com.lonple.dap.server.modules.chat.mapper.SessionContextMapper;
import com.lonple.dap.server.modules.chat.mapper.SessionInputMapper;
import com.lonple.dap.server.modules.position.PositionService;
import com.lonple.dap.server.modules.position.entity.SysPosition;
import com.lonple.dap.server.modules.issue.entity.IssIssue;
import com.lonple.dap.server.modules.issue.mapper.IssueMapper;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirement;
import com.lonple.dap.server.modules.requirement.mapper.RequirementHandlerMapper;
import com.lonple.dap.server.modules.requirement.mapper.RequirementMapper;
import com.lonple.dap.server.modules.tnworkspace.WorkspaceNodeType;
import com.lonple.dap.server.modules.tnworkspace.WorkspaceSpaceType;
import com.lonple.dap.server.modules.tnworkspace.entity.SysWorkspace;
import com.lonple.dap.server.modules.tnworkspace.entity.SysWorkspaceNode;
import com.lonple.dap.server.modules.tnworkspace.mapper.SysWorkspaceMapper;
import com.lonple.dap.server.modules.tnworkspace.mapper.SysWorkspaceNodeMapper;
import com.lonple.dap.server.modules.wiki.WikiFsService;
import com.lonple.dap.server.modules.wiki.entity.SysWikiNode;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WorkspaceSessionService {

    private final ChatSessionMapper sessionMapper;
    private final SessionContextMapper contextMapper;
    private final SessionInputMapper inputMapper;
    private final SessionCodeRepoMapper codeRepoMapper;
    private final ChatMessageMapper messageMapper;
    private final SysWorkspaceMapper workspaceMapper;
    private final SysWorkspaceNodeMapper nodeMapper;
    private final RequirementMapper requirementMapper;
    private final RequirementHandlerMapper handlerMapper;
    private final IssueMapper issueMapper;
    private final PositionService positionService;
    private final WikiFsService wikiFsService;

    public List<ChatSessionVO> listByWorkspace(Long workspaceId) {
        SysWorkspace ws = requireOwnedPersonal(workspaceId);
        Long userId = TenantSupport.requireUserId();
        return sessionMapper.selectList(new LambdaQueryWrapper<WbChatSession>()
                        .eq(WbChatSession::getWorkspaceId, ws.getId())
                        .eq(WbChatSession::getUserId, userId)
                        .orderByDesc(WbChatSession::getUpdatedAt))
                .stream()
                .map(this::toVO)
                .toList();
    }

    /** 任务会话可选类型（生成 / 评审） */
    public List<?> listTaskTypeOptions() {
        return SessionTaskType.options();
    }

    @Transactional
    public Long create(Long workspaceId, WorkspaceSessionCreateRequest req) {
        SysWorkspace ws = requireOwnedPersonal(workspaceId);
        String type = req.getSessionType() == null ? "" : req.getSessionType().trim().toUpperCase();
        if (!"NORMAL".equals(type) && !"TASK".equals(type)) {
            throw new BizException("会话类型无效");
        }
        String title = req.getTitle().trim();
        Long outputDirId = validateOutputDir(ws.getId(), req.getOutputDirNodeId());

        Long requirementId = null;
        Long issueId = null;
        String taskTypeCode = null;
        Long positionId = null;
        SessionTaskType taskType = null;
        if ("TASK".equals(type)) {
            taskType = SessionTaskType.fromCode(req.getTaskTypeCode());
            taskTypeCode = taskType.name();
            positionId = resolvePositionId(req.getPositionId(), taskType);
            boolean issueTask = taskType == SessionTaskType.GEN_ISSUE_ANALYSIS
                    || taskType == SessionTaskType.GEN_ISSUE_FIX
                    || taskType == SessionTaskType.GEN_ISSUE_CODE_REVIEW
                    || taskType == SessionTaskType.GEN_ISSUE_INT_TEST;
            if (req.getIssueId() != null) {
                IssIssue issue = issueMapper.selectById(req.getIssueId());
                if (issue == null || !Objects.equals(issue.getTenantId(), ws.getTenantId())) {
                    throw new BizException("问题单不存在");
                }
                issueId = issue.getId();
            }
            if (req.getRequirementId() != null) {
                ReqRequirement requirement = requirementMapper.selectById(req.getRequirementId());
                if (requirement == null || !Objects.equals(requirement.getTenantId(), ws.getTenantId())) {
                    throw new BizException("需求不存在");
                }
                assertCanCreateTaskSession(requirement);
                requirementId = requirement.getId();
            } else if (!issueTask) {
                throw new BizException("请选择关联需求");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        WbChatSession session = new WbChatSession();
        session.setTenantId(ws.getTenantId());
        session.setUserId(TenantSupport.requireUserId());
        session.setWorkspaceId(ws.getId());
        session.setSessionType(type);
        session.setTitle(title);
        session.setRequirementId(requirementId);
        session.setIssueId(issueId);
        session.setTaskTypeCode(taskTypeCode);
        session.setPositionId(positionId);
        session.setOutputDirNodeId(outputDirId);
        session.setOutputTemplateWikiNodeId(
                validateOutputTemplateWikiNode(ws.getTenantId(), type, req.getOutputTemplateWikiNodeId()));
        session.setStandardsWikiNodeId(
                validateStandardsWikiNode(ws.getTenantId(), type, req.getStandardsWikiNodeId()));
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);
        replaceContexts(session, req.getContexts());
        replaceInputs(session, req.getInputs());
        replaceCodeRepos(session, taskTypeCode, req.getCodeRepos());
        return session.getId();
    }

    @Transactional
    public void update(Long workspaceId, Long sessionId, WorkspaceSessionUpdateRequest req) {
        WbChatSession session = requireOwnedSession(workspaceId, sessionId);
        session.setTitle(req.getTitle().trim());
        session.setOutputDirNodeId(validateOutputDir(session.getWorkspaceId(), req.getOutputDirNodeId()));
        session.setOutputTemplateWikiNodeId(validateOutputTemplateWikiNode(
                session.getTenantId(), session.getSessionType(), req.getOutputTemplateWikiNodeId()));
        session.setStandardsWikiNodeId(validateStandardsWikiNode(
                session.getTenantId(), session.getSessionType(), req.getStandardsWikiNodeId()));
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        contextMapper.delete(new LambdaQueryWrapper<WbSessionContext>()
                .eq(WbSessionContext::getSessionId, session.getId()));
        replaceContexts(session, req.getContexts());
        inputMapper.delete(new LambdaQueryWrapper<WbSessionInput>()
                .eq(WbSessionInput::getSessionId, session.getId()));
        replaceInputs(session, req.getInputs());
        replaceCodeRepos(session, session.getTaskTypeCode(), req.getCodeRepos());
    }

    @Transactional
    public void delete(Long workspaceId, Long sessionId) {
        WbChatSession session = requireOwnedSession(workspaceId, sessionId);
        messageMapper.delete(new LambdaQueryWrapper<WbChatMessage>()
                .eq(WbChatMessage::getSessionId, session.getId()));
        contextMapper.delete(new LambdaQueryWrapper<WbSessionContext>()
                .eq(WbSessionContext::getSessionId, session.getId()));
        inputMapper.delete(new LambdaQueryWrapper<WbSessionInput>()
                .eq(WbSessionInput::getSessionId, session.getId()));
        codeRepoMapper.delete(new LambdaQueryWrapper<WbSessionCodeRepo>()
                .eq(WbSessionCodeRepo::getSessionId, session.getId()));
        sessionMapper.deleteById(session.getId());
    }

    public ChatSessionVO get(Long workspaceId, Long sessionId) {
        return toVO(requireOwnedSession(workspaceId, sessionId));
    }

    private void assertCanCreateTaskSession(ReqRequirement requirement) {
        Long userId = TenantSupport.requireUserId();
        if (Objects.equals(requirement.getCreatedBy(), userId)
                || Objects.equals(requirement.getCurrentHandlerId(), userId)
                || handlerMapper.exists(requirement.getId(), userId)) {
            return;
        }
        throw new BizException(403, "仅该需求的处理人可创建任务会话");
    }

    private Long resolvePositionId(Long positionId, SessionTaskType taskType) {
        if (positionId != null) {
            return positionService.requireOwned(positionId).getId();
        }
        SysPosition byCode = positionService.findByCode(taskType.getDefaultPositionCode());
        if (byCode == null) {
            throw new BizException("请选择岗位，或先在岗位配置中设置编码 "
                    + taskType.getDefaultPositionCode() + "（任务类型「" + taskType.getLabel() + "」默认岗位）");
        }
        return byCode.getId();
    }

    private void replaceContexts(WbChatSession session, List<WorkspaceSessionCreateRequest.SessionContextRef> refs) {
        if (CollectionUtils.isEmpty(refs)) {
            return;
        }
        Set<Long> seen = new LinkedHashSet<>();
        LocalDateTime now = LocalDateTime.now();
        for (WorkspaceSessionCreateRequest.SessionContextRef ref : refs) {
            if (ref == null || ref.getWikiNodeId() == null) {
                continue;
            }
            if (!seen.add(ref.getWikiNodeId())) {
                continue;
            }
            SysWikiNode node = wikiFsService.requireNode(ref.getWikiNodeId());
            if (!Objects.equals(node.getTenantId(), session.getTenantId())) {
                throw new BizException("上下文必须选择本租户 Wiki 中的文件或目录");
            }
            WbSessionContext row = new WbSessionContext();
            row.setSessionId(session.getId());
            row.setWikiNodeId(node.getId());
            row.setNodeType(node.getNodeType());
            row.setDisplayPath(wikiFsService.buildDisplayPath(node));
            row.setCreatedAt(now);
            contextMapper.insert(row);
        }
    }

    private void replaceInputs(WbChatSession session, List<WorkspaceSessionCreateRequest.SessionInputRef> refs) {
        if (CollectionUtils.isEmpty(refs)) {
            return;
        }
        Set<Long> seen = new LinkedHashSet<>();
        LocalDateTime now = LocalDateTime.now();
        for (WorkspaceSessionCreateRequest.SessionInputRef ref : refs) {
            if (ref == null || ref.getWikiNodeId() == null) {
                continue;
            }
            if (!seen.add(ref.getWikiNodeId())) {
                continue;
            }
            SysWikiNode node = wikiFsService.requireNode(ref.getWikiNodeId());
            if (!Objects.equals(node.getTenantId(), session.getTenantId())) {
                throw new BizException("输入文件必须选择本租户 Wiki 中的文件");
            }
            if (!WorkspaceNodeType.FILE.name().equals(node.getNodeType())) {
                throw new BizException("输入材料只能选择 Wiki 文件，不能选目录");
            }
            WbSessionInput row = new WbSessionInput();
            row.setSessionId(session.getId());
            row.setWikiNodeId(node.getId());
            row.setDisplayPath(wikiFsService.buildDisplayPath(node));
            row.setCreatedAt(now);
            inputMapper.insert(row);
        }
    }

    private void replaceCodeRepos(WbChatSession session, String taskTypeCode, List<String> rawPaths) {
        codeRepoMapper.delete(new LambdaQueryWrapper<WbSessionCodeRepo>()
                .eq(WbSessionCodeRepo::getSessionId, session.getId()));
        boolean required = CodingTaskSupport.needsLocalCodeRepo(taskTypeCode);
        boolean hasAny = rawPaths != null && rawPaths.stream().anyMatch(StringUtils::hasText);
        if (!required && !hasAny) {
            return;
        }
        List<String> paths = LocalCodeRepoPaths.normalizeAndValidate(rawPaths, required);
        LocalDateTime now = LocalDateTime.now();
        int sort = 0;
        for (String path : paths) {
            WbSessionCodeRepo row = new WbSessionCodeRepo();
            row.setSessionId(session.getId());
            row.setLocalPath(path);
            row.setSortNo(sort++);
            row.setCreatedAt(now);
            codeRepoMapper.insert(row);
        }
    }

    /** 任务会话：输出目录只能是个人工作空间目录。 */
    private Long validateOutputDir(Long workspaceId, Long outputDirNodeId) {
        if (outputDirNodeId == null) {
            return null;
        }
        SysWorkspaceNode node = nodeMapper.selectById(outputDirNodeId);
        if (node == null || !Objects.equals(node.getWorkspaceId(), workspaceId)) {
            throw new BizException("输出目录不存在（手动任务只能选个人空间目录）");
        }
        if (!"DIR".equals(node.getNodeType())) {
            throw new BizException("输出目录必须是文件夹");
        }
        return node.getId();
    }

    /**
     * 输出参考模板仅任务会话可选；须为本租户 Wiki「模板」目录下的文件。
     * null 表示未配置 / 清除。
     */
    private Long validateOutputTemplateWikiNode(Long tenantId, String sessionType, Long wikiNodeId) {
        if (wikiNodeId == null) {
            return null;
        }
        if (!"TASK".equals(sessionType)) {
            throw new BizException("仅任务会话可配置输出参考模板");
        }
        SysWikiNode node = wikiFsService.requireNode(wikiNodeId);
        if (!Objects.equals(node.getTenantId(), tenantId)) {
            throw new BizException("输出参考模板必须选择本租户 Wiki 中的文件");
        }
        if (!WorkspaceNodeType.FILE.name().equals(node.getNodeType())) {
            throw new BizException("输出参考模板请选择 Wiki「模板」目录下的文件，不能选目录");
        }
        SysWikiNode templateRoot = wikiFsService.findTopLevelDir(tenantId, "模板");
        if (templateRoot == null) {
            throw new BizException("Wiki 中尚无「模板」目录，请先在知识库创建后再选择");
        }
        if (!wikiFsService.isSelfOrDescendant(node, templateRoot.getId())) {
            throw new BizException("输出参考模板须选择 Wiki「模板」目录下的文件");
        }
        return node.getId();
    }

    /**
     * 规范仅任务会话可选；须为本租户 Wiki「规范」目录、其子目录或其中的文件。
     */
    private Long validateStandardsWikiNode(Long tenantId, String sessionType, Long wikiNodeId) {
        if (wikiNodeId == null) {
            return null;
        }
        if (!"TASK".equals(sessionType)) {
            throw new BizException("仅任务会话可配置规范");
        }
        SysWikiNode node = wikiFsService.requireNode(wikiNodeId);
        if (!Objects.equals(node.getTenantId(), tenantId)) {
            throw new BizException("规范必须选择本租户 Wiki 中的节点");
        }
        SysWikiNode standardsRoot = wikiFsService.findTopLevelDir(tenantId, "规范");
        if (standardsRoot == null) {
            throw new BizException("Wiki 中尚无「规范」目录，请先在知识库创建后再选择");
        }
        if (!wikiFsService.isSelfOrDescendant(node, standardsRoot.getId())) {
            throw new BizException("规范须选择 Wiki「规范」目录或其下的文件/子目录");
        }
        return node.getId();
    }

    private SysWorkspace requireOwnedPersonal(Long workspaceId) {
        Long tenantId = TenantSupport.requireTenantId();
        Long userId = TenantSupport.requireUserId();
        SysWorkspace ws = workspaceMapper.selectById(workspaceId);
        if (ws == null || !Objects.equals(ws.getTenantId(), tenantId)) {
            throw new BizException(404, "工作空间不存在");
        }
        if (WorkspaceSpaceType.fromCode(ws.getSpaceType()) != WorkspaceSpaceType.PERSONAL) {
            throw new BizException("仅个人工作空间支持会话");
        }
        if (!Objects.equals(ws.getOwnerUserId(), userId)) {
            throw new BizException(403, "只能操作自己的个人工作空间");
        }
        return ws;
    }

    private WbChatSession requireOwnedSession(Long workspaceId, Long sessionId) {
        requireOwnedPersonal(workspaceId);
        WbChatSession session = sessionMapper.selectById(sessionId);
        if (session == null
                || !Objects.equals(session.getWorkspaceId(), workspaceId)
                || !Objects.equals(session.getUserId(), TenantSupport.requireUserId())) {
            throw new BizException(404, "会话不存在");
        }
        return session;
    }

    private ChatSessionVO toVO(WbChatSession session) {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setId(session.getId());
        vo.setWorkspaceId(session.getWorkspaceId());
        vo.setSessionType(session.getSessionType());
        vo.setTitle(session.getTitle());
        vo.setRequirementId(session.getRequirementId());
        vo.setTaskTypeCode(session.getTaskTypeCode());
        if (StringUtils.hasText(session.getTaskTypeCode())) {
            try {
                SessionTaskType t = SessionTaskType.fromCode(session.getTaskTypeCode());
                vo.setTaskTypeName(t.getLabel());
                vo.setTaskTypeKind(t.getKind());
            } catch (Exception ignored) {
                vo.setTaskTypeName(session.getTaskTypeCode());
            }
        }
        vo.setOutputDirNodeId(session.getOutputDirNodeId());
        if (session.getOutputDirNodeId() != null) {
            SysWorkspaceNode n = nodeMapper.selectById(session.getOutputDirNodeId());
            vo.setOutputDirLabel(n == null ? null : n.getDisplayName());
        } else {
            vo.setOutputDirLabel("（根目录）");
        }
        vo.setOutputTemplateWikiNodeId(session.getOutputTemplateWikiNodeId());
        if (session.getOutputTemplateWikiNodeId() != null) {
            try {
                SysWikiNode tn = wikiFsService.requireNode(session.getOutputTemplateWikiNodeId());
                vo.setOutputTemplatePath(wikiFsService.buildDisplayPath(tn));
            } catch (Exception ignored) {
                vo.setOutputTemplatePath(null);
            }
        }
        vo.setStandardsWikiNodeId(session.getStandardsWikiNodeId());
        if (session.getStandardsWikiNodeId() != null) {
            try {
                SysWikiNode sn = wikiFsService.requireNode(session.getStandardsWikiNodeId());
                vo.setStandardsPath(wikiFsService.buildDisplayPath(sn));
            } catch (Exception ignored) {
                vo.setStandardsPath(null);
            }
        }
        vo.setPositionId(session.getPositionId());
        vo.setCreatedAt(session.getCreatedAt());
        vo.setUpdatedAt(session.getUpdatedAt());
        List<WbSessionContext> contexts = contextMapper.selectList(new LambdaQueryWrapper<WbSessionContext>()
                .eq(WbSessionContext::getSessionId, session.getId())
                .orderByAsc(WbSessionContext::getId));
        vo.setContexts(contexts.stream().map(c -> {
            ChatSessionVO.SessionContextVO cv = new ChatSessionVO.SessionContextVO();
            cv.setId(c.getId());
            cv.setWikiNodeId(c.getWikiNodeId());
            cv.setNodeType(c.getNodeType());
            cv.setDisplayPath(c.getDisplayPath());
            return cv;
        }).toList());
        List<WbSessionInput> inputs = inputMapper.selectList(new LambdaQueryWrapper<WbSessionInput>()
                .eq(WbSessionInput::getSessionId, session.getId())
                .orderByAsc(WbSessionInput::getId));
        vo.setInputs(inputs.stream().map(i -> {
            ChatSessionVO.SessionInputVO iv = new ChatSessionVO.SessionInputVO();
            iv.setId(i.getId());
            iv.setWikiNodeId(i.getWikiNodeId());
            iv.setDisplayPath(i.getDisplayPath());
            return iv;
        }).toList());
        List<WbSessionCodeRepo> repos = codeRepoMapper.selectList(new LambdaQueryWrapper<WbSessionCodeRepo>()
                .eq(WbSessionCodeRepo::getSessionId, session.getId())
                .orderByAsc(WbSessionCodeRepo::getSortNo)
                .orderByAsc(WbSessionCodeRepo::getId));
        vo.setCodeRepos(repos.stream().map(r -> {
            ChatSessionVO.SessionCodeRepoVO rv = new ChatSessionVO.SessionCodeRepoVO();
            rv.setId(r.getId());
            rv.setLocalPath(r.getLocalPath());
            rv.setSortNo(r.getSortNo());
            return rv;
        }).toList());
        return vo;
    }
}

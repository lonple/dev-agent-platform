package com.lonple.dap.server.modules.agenttool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.agenttool.dto.AgentFileHitVO;
import com.lonple.dap.server.modules.agenttool.dto.AgentFileIndexVO;
import com.lonple.dap.server.modules.agenttool.dto.AgentReadResultVO;
import com.lonple.dap.server.modules.agenttool.dto.AgentSearchResultVO;
import com.lonple.dap.server.modules.agenttool.dto.AgentWriteResultVO;
import com.lonple.dap.server.modules.chat.entity.WbChatSession;
import com.lonple.dap.server.modules.chat.entity.WbSessionCodeRepo;
import com.lonple.dap.server.modules.chat.entity.WbSessionContext;
import com.lonple.dap.server.modules.chat.entity.WbSessionInput;
import com.lonple.dap.server.modules.chat.mapper.ChatSessionMapper;
import com.lonple.dap.server.modules.chat.mapper.SessionCodeRepoMapper;
import com.lonple.dap.server.modules.chat.mapper.SessionContextMapper;
import com.lonple.dap.server.modules.chat.mapper.SessionInputMapper;
import com.lonple.dap.server.modules.chat.SessionTaskType;
import com.lonple.dap.server.modules.tnworkspace.WorkspaceFsService;
import com.lonple.dap.server.modules.tnworkspace.WorkspaceNodeType;
import com.lonple.dap.server.modules.tnworkspace.entity.SysWorkspace;
import com.lonple.dap.server.modules.tnworkspace.entity.SysWorkspaceNode;
import com.lonple.dap.server.modules.tnworkspace.entity.SysWorkspaceNodeRevision;
import com.lonple.dap.server.modules.tnworkspace.mapper.SysWorkspaceMapper;
import com.lonple.dap.server.modules.tnworkspace.mapper.SysWorkspaceNodeMapper;
import com.lonple.dap.server.modules.tnworkspace.mapper.SysWorkspaceNodeRevisionMapper;
import com.lonple.dap.server.modules.wiki.WikiFsService;
import com.lonple.dap.server.modules.wiki.entity.SysWikiNode;
import com.lonple.dap.server.modules.wiki.mapper.SysWikiNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 文档工作区工具：搜 / 读片段 / 写输出目录。范围锁定在会话材料内。
 * 搜索优先 ripgrep，不可用时回退行扫描。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentFsToolService {

    private static final int MAX_SEARCH_FILES = 500;
    private static final int MAX_HITS = 40;
    private static final int MAX_FILE_SCAN_CHARS = 200_000;
    private static final int MAX_HIT_SNIPPET_CHARS = 400;
    private static final int MAX_READ_CHARS = 12_000;
    private static final int CONTEXT_LINES = 1;

    private final ChatSessionMapper sessionMapper;
    private final SessionContextMapper contextMapper;
    private final SessionInputMapper inputMapper;
    private final SessionCodeRepoMapper codeRepoMapper;
    private final SysWikiNodeMapper wikiNodeMapper;
    private final WikiFsService wikiFsService;
    private final SysWorkspaceMapper workspaceMapper;
    private final SysWorkspaceNodeMapper workspaceNodeMapper;
    private final SysWorkspaceNodeRevisionMapper revisionMapper;
    private final WorkspaceFsService workspaceFsService;
    private final RipgrepSearchService ripgrepSearchService;

    public WbChatSession requireSession(Long sessionId) {
        if (sessionId == null) {
            throw new BizException("sessionId 无效");
        }
        WbChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException(404, "会话不存在");
        }
        return session;
    }

    /** 会话材料索引：输入文件 + Wiki 上下文 + 输出目录已有文件 */
    public AgentFileIndexVO buildIndex(Long sessionId) {
        WbChatSession session = requireSession(sessionId);
        AgentFileIndexVO vo = new AgentFileIndexVO();
        vo.setSessionId(session.getId());
        vo.setSessionType(session.getSessionType());
        vo.setTitle(session.getTitle());
        vo.setWorkspaceId(session.getWorkspaceId());
        vo.setOutputDirNodeId(session.getOutputDirNodeId());
        vo.setOutputDirLabel(resolveOutputDirLabel(session));
        vo.setOutputWikiDirNodeId(session.getOutputWikiDirNodeId());
        if (session.getOutputWikiDirNodeId() != null) {
            try {
                SysWikiNode wd = wikiNodeMapper.selectById(session.getOutputWikiDirNodeId());
                if (wd != null) {
                    vo.setOutputWikiDirPath(wikiFsService.buildDisplayPath(wd));
                }
            } catch (Exception ignored) {
                // ignore
            }
        }

        if (StringUtils.hasText(session.getTaskTypeCode())) {
            vo.setTaskTypeCode(session.getTaskTypeCode());
            try {
                SessionTaskType t = SessionTaskType.fromCode(session.getTaskTypeCode());
                vo.setTaskTypeName(t.getLabel());
            } catch (Exception ignored) {
                vo.setTaskTypeName(session.getTaskTypeCode());
            }
        }
        // 会话绑定的 Wiki 输出参考模板 + 规范
        applySessionOutputTemplate(session, vo);
        applySessionStandards(session, vo);

        List<WbSessionCodeRepo> repos = codeRepoMapper.selectList(new LambdaQueryWrapper<WbSessionCodeRepo>()
                .eq(WbSessionCodeRepo::getSessionId, session.getId())
                .orderByAsc(WbSessionCodeRepo::getSortNo)
                .orderByAsc(WbSessionCodeRepo::getId));
        for (WbSessionCodeRepo repo : repos) {
            if (StringUtils.hasText(repo.getLocalPath())) {
                vo.getCodeRepos().add(repo.getLocalPath());
            }
        }

        List<AllowedFile> allowed = listAllowedFiles(session);
        for (AllowedFile f : allowed) {
            AgentFileIndexVO.Entry e = new AgentFileIndexVO.Entry();
            e.setSource(f.source);
            e.setNodeId(f.nodeId);
            e.setDisplayPath(f.displayPath);
            e.setRole(f.role);
            e.setApproxChars(f.approxChars);
            vo.getFiles().add(e);
        }
        return vo;
    }

    public AgentSearchResultVO search(Long sessionId, String query) {
        if (!StringUtils.hasText(query)) {
            throw new BizException("请提供搜索关键词");
        }
        WbChatSession session = requireSession(sessionId);
        List<AllowedFile> files = listAllowedFiles(session);
        if (files.size() > MAX_SEARCH_FILES) {
            files = files.subList(0, MAX_SEARCH_FILES);
        }
        AgentSearchResultVO result = new AgentSearchResultVO();
        result.setTotalAllowedFiles(files.size());

        if (ripgrepSearchService.isAvailable()) {
            AgentSearchResultVO rg = searchWithRipgrep(query, files);
            if (rg != null) {
                return rg;
            }
            log.info("ripgrep search failed, fallback to line-scan sessionId={}", sessionId);
        }

        return searchWithLineScan(query, files);
    }

    private AgentSearchResultVO searchWithRipgrep(String query, List<AllowedFile> files) {
        Map<String, AllowedFile> byPath = new LinkedHashMap<>();
        List<Path> paths = new ArrayList<>();
        for (AllowedFile f : files) {
            if (f.path == null || !Files.isRegularFile(f.path)) {
                continue;
            }
            Path abs = f.path.toAbsolutePath().normalize();
            byPath.put(pathKey(abs), f);
            paths.add(abs);
        }
        if (paths.isEmpty()) {
            return null;
        }
        List<RipgrepSearchService.Hit> rgHits = ripgrepSearchService.search(query, paths);
        if (rgHits == null) {
            return null;
        }
        AgentSearchResultVO result = new AgentSearchResultVO();
        result.setEngine("ripgrep");
        result.setScannedFiles(paths.size());
        result.setTotalAllowedFiles(files.size());
        for (RipgrepSearchService.Hit h : rgHits) {
            if (result.getHits().size() >= MAX_HITS) {
                result.setTruncated(true);
                break;
            }
            AllowedFile file = byPath.get(pathKey(h.path));
            if (file == null) {
                continue;
            }
            AgentFileHitVO hit = new AgentFileHitVO();
            hit.setSource(file.source);
            hit.setNodeId(file.nodeId);
            hit.setDisplayPath(file.displayPath);
            hit.setLine(h.line);
            String snippet = h.snippet == null ? "" : h.snippet;
            if (snippet.length() > MAX_HIT_SNIPPET_CHARS) {
                snippet = snippet.substring(0, MAX_HIT_SNIPPET_CHARS) + "…";
            }
            hit.setSnippet(snippet);
            result.getHits().add(hit);
        }
        return result;
    }

    private AgentSearchResultVO searchWithLineScan(String query, List<AllowedFile> files) {
        String[] keywords = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        AgentSearchResultVO result = new AgentSearchResultVO();
        result.setEngine("line-scan");
        result.setTotalAllowedFiles(files.size());
        int scanned = 0;
        for (AllowedFile file : files) {
            if (scanned >= MAX_SEARCH_FILES || result.getHits().size() >= MAX_HITS) {
                result.setTruncated(true);
                break;
            }
            scanned++;
            try {
                searchOneFile(file, keywords, result.getHits());
            } catch (Exception ignored) {
                // skip unreadable
            }
        }
        result.setScannedFiles(scanned);
        return result;
    }

    private static String pathKey(Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    public AgentReadResultVO read(Long sessionId, String source, Long nodeId, Integer startLine, Integer endLine) {
        WbChatSession session = requireSession(sessionId);
        AllowedFile file = findAllowed(session, source, nodeId);
        if (file == null) {
            throw new BizException(403, "该文件不在本会话允许范围内");
        }
        String content = readFull(file);
        List<String> lines = content.lines().toList();
        int from = startLine == null || startLine < 1 ? 1 : startLine;
        int to = endLine == null || endLine < from ? lines.size() : Math.min(endLine, lines.size());
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to && i <= lines.size(); i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i - 1));
            if (sb.length() >= MAX_READ_CHARS) {
                sb.append("\n…(已截断)");
                break;
            }
        }
        AgentReadResultVO vo = new AgentReadResultVO();
        vo.setSource(file.source);
        vo.setNodeId(file.nodeId);
        vo.setDisplayPath(file.displayPath);
        vo.setStartLine(from);
        vo.setEndLine(Math.min(to, lines.size()));
        vo.setTotalLines(lines.size());
        vo.setContent(sb.toString());
        return vo;
    }

    @Transactional
    public AgentWriteResultVO write(Long sessionId, String displayName, String content) {
        WbChatSession session = requireSession(sessionId);
        String name = normalizeMdName(displayName);
        String body = content == null ? "" : content;

        // 流水线任务：输出写到 Wiki 目录
        if (session.getOutputWikiDirNodeId() != null) {
            SysWikiNode parent = wikiNodeMapper.selectById(session.getOutputWikiDirNodeId());
            if (parent == null || !Objects.equals(parent.getTenantId(), session.getTenantId())
                    || !WorkspaceNodeType.DIR.name().equals(parent.getNodeType())) {
                throw new BizException("Wiki 输出目录无效");
            }
            Long nodeId = wikiFsService.upsertMarkdownFile(parent.getId(), name, body);
            SysWikiNode file = wikiNodeMapper.selectById(nodeId);
            AgentWriteResultVO vo = new AgentWriteResultVO();
            vo.setWorkspaceId(session.getWorkspaceId());
            vo.setNodeId(nodeId);
            vo.setDisplayName(name);
            vo.setDisplayPath(file != null ? wikiFsService.buildDisplayPath(file) : name);
            vo.setVersionNo(1);
            vo.setCreated(true);
            return vo;
        }

        // 手动任务：仅个人空间
        SysWorkspace ws = workspaceMapper.selectById(session.getWorkspaceId());
        if (ws == null || !Objects.equals(ws.getOwnerUserId(), session.getUserId())) {
            throw new BizException(403, "无权写入该工作空间");
        }
        Long parentId = session.getOutputDirNodeId();
        if (parentId != null) {
            SysWorkspaceNode parent = workspaceNodeMapper.selectById(parentId);
            if (parent == null || !Objects.equals(parent.getWorkspaceId(), ws.getId())
                    || !WorkspaceNodeType.DIR.name().equals(parent.getNodeType())) {
                throw new BizException("输出目录无效");
            }
        }

        SysWorkspaceNode existing = findChildFile(ws.getId(), parentId, name);
        Integer newVersion = null;
        Long nodeId;
        if (existing != null) {
            String old = workspaceFsService.readFile(ws.getId(), existing.getId()).getContent();
            newVersion = saveRevision(session, ws, existing, old);
            workspaceFsService.updateFileContent(ws.getId(), existing.getId(), body);
            nodeId = existing.getId();
        } else {
            nodeId = workspaceFsService.createMarkdownFile(ws.getId(), parentId, name, body);
            newVersion = 1;
        }
        AgentWriteResultVO vo = new AgentWriteResultVO();
        vo.setWorkspaceId(ws.getId());
        vo.setNodeId(nodeId);
        vo.setDisplayName(name);
        vo.setDisplayPath(buildWorkspaceDisplayPath(ws.getId(), nodeId));
        vo.setVersionNo(newVersion);
        vo.setCreated(existing == null);
        return vo;
    }

    public Map<String, Object> getDiff(Long workspaceId, Long nodeId, Integer versionNo) {
        SysWorkspaceNode node = workspaceNodeMapper.selectById(nodeId);
        if (node == null || !Objects.equals(node.getWorkspaceId(), workspaceId)) {
            throw new BizException(404, "文件不存在");
        }
        String current = workspaceFsService.readFile(workspaceId, nodeId).getContent();
        String previous = "";
        Integer prevVer = null;
        if (versionNo != null) {
            SysWorkspaceNodeRevision rev = revisionMapper.selectOne(new LambdaQueryWrapper<SysWorkspaceNodeRevision>()
                    .eq(SysWorkspaceNodeRevision::getNodeId, nodeId)
                    .eq(SysWorkspaceNodeRevision::getVersionNo, versionNo)
                    .last("LIMIT 1"));
            if (rev == null) {
                throw new BizException(404, "版本不存在");
            }
            previous = rev.getContent();
            prevVer = rev.getVersionNo();
        } else {
            SysWorkspaceNodeRevision latest = revisionMapper.selectOne(new LambdaQueryWrapper<SysWorkspaceNodeRevision>()
                    .eq(SysWorkspaceNodeRevision::getNodeId, nodeId)
                    .orderByDesc(SysWorkspaceNodeRevision::getVersionNo)
                    .last("LIMIT 1"));
            if (latest != null) {
                previous = latest.getContent();
                prevVer = latest.getVersionNo();
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", nodeId);
        map.put("displayName", node.getDisplayName());
        map.put("previousVersionNo", prevVer);
        map.put("previousContent", previous);
        map.put("currentContent", current);
        return map;
    }

    public List<Map<String, Object>> listRevisions(Long workspaceId, Long nodeId) {
        SysWorkspaceNode node = workspaceNodeMapper.selectById(nodeId);
        if (node == null || !Objects.equals(node.getWorkspaceId(), workspaceId)) {
            throw new BizException(404, "文件不存在");
        }
        return revisionMapper.selectList(new LambdaQueryWrapper<SysWorkspaceNodeRevision>()
                        .eq(SysWorkspaceNodeRevision::getNodeId, nodeId)
                        .orderByDesc(SysWorkspaceNodeRevision::getVersionNo))
                .stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("versionNo", r.getVersionNo());
                    m.put("sessionId", r.getSessionId());
                    m.put("createdAt", r.getCreatedAt());
                    m.put("createdBy", r.getCreatedBy());
                    return m;
                })
                .toList();
    }

    private int saveRevision(WbChatSession session, SysWorkspace ws, SysWorkspaceNode node, String oldContent) {
        Integer max = revisionMapper.selectList(new LambdaQueryWrapper<SysWorkspaceNodeRevision>()
                        .eq(SysWorkspaceNodeRevision::getNodeId, node.getId())
                        .orderByDesc(SysWorkspaceNodeRevision::getVersionNo)
                        .last("LIMIT 1"))
                .stream()
                .map(SysWorkspaceNodeRevision::getVersionNo)
                .findFirst()
                .orElse(0);
        int next = max + 1;
        SysWorkspaceNodeRevision rev = new SysWorkspaceNodeRevision();
        rev.setTenantId(ws.getTenantId());
        rev.setWorkspaceId(ws.getId());
        rev.setNodeId(node.getId());
        rev.setVersionNo(next);
        rev.setContent(oldContent == null ? "" : oldContent);
        rev.setSessionId(session.getId());
        rev.setCreatedBy(session.getUserId());
        rev.setCreatedAt(LocalDateTime.now());
        revisionMapper.insert(rev);
        return next;
    }

    private AllowedFile findAllowed(WbChatSession session, String source, Long nodeId) {
        for (AllowedFile f : listAllowedFiles(session)) {
            if (f.nodeId.equals(nodeId) && f.source.equalsIgnoreCase(source)) {
                return f;
            }
        }
        return null;
    }

    private List<AllowedFile> listAllowedFiles(WbChatSession session) {
        Map<String, AllowedFile> map = new LinkedHashMap<>();
        // 输入文件
        List<WbSessionInput> inputs = inputMapper.selectList(new LambdaQueryWrapper<WbSessionInput>()
                .eq(WbSessionInput::getSessionId, session.getId()));
        for (WbSessionInput in : inputs) {
            addWikiFile(map, in.getWikiNodeId(), in.getDisplayPath(), "input");
        }
        // Wiki 上下文：DIR 展开 FILE
        List<WbSessionContext> contexts = contextMapper.selectList(new LambdaQueryWrapper<WbSessionContext>()
                .eq(WbSessionContext::getSessionId, session.getId()));
        for (WbSessionContext ctx : contexts) {
            SysWikiNode node = wikiNodeMapper.selectById(ctx.getWikiNodeId());
            if (node == null) {
                continue;
            }
            if (WorkspaceNodeType.FILE.name().equals(node.getNodeType())) {
                addWikiFile(map, node.getId(), wikiFsService.buildDisplayPath(node), "context");
            } else {
                collectWikiFilesUnder(map, node.getId(), "context");
            }
        }
        // 规范：目录展开 / 单文件直接加入
        if (session.getStandardsWikiNodeId() != null) {
            SysWikiNode standardsNode = wikiNodeMapper.selectById(session.getStandardsWikiNodeId());
            if (standardsNode != null) {
                if (WorkspaceNodeType.FILE.name().equals(standardsNode.getNodeType())) {
                    addWikiFile(map, standardsNode.getId(), wikiFsService.buildDisplayPath(standardsNode), "standards");
                } else {
                    collectWikiFilesUnder(map, standardsNode.getId(), "standards");
                }
            }
        }
        // 流水线 Wiki 输出目录
        if (session.getOutputWikiDirNodeId() != null) {
            collectWikiFilesUnder(map, session.getOutputWikiDirNodeId(), "output");
        } else {
            // 手动任务：个人空间输出目录已有文件
            SysWorkspace ws = workspaceMapper.selectById(session.getWorkspaceId());
            if (ws != null) {
                Long parentId = session.getOutputDirNodeId();
                List<SysWorkspaceNode> outs = workspaceNodeMapper.selectList(new LambdaQueryWrapper<SysWorkspaceNode>()
                        .eq(SysWorkspaceNode::getWorkspaceId, ws.getId())
                        .eq(SysWorkspaceNode::getNodeType, WorkspaceNodeType.FILE.name()));
                for (SysWorkspaceNode n : outs) {
                    if (!isUnderOutput(n, parentId, ws.getId())) {
                        continue;
                    }
                    AllowedFile f = new AllowedFile();
                    f.source = "workspace";
                    f.nodeId = n.getId();
                    f.displayPath = buildWorkspaceDisplayPath(ws.getId(), n.getId());
                    f.role = "output";
                    try {
                        Path p = workspaceFsService.resolveNodeAbsolutePath(ws, n);
                        f.path = p;
                        f.approxChars = Files.exists(p) ? (int) Math.min(Files.size(p), Integer.MAX_VALUE) : 0;
                    } catch (Exception e) {
                        f.approxChars = 0;
                    }
                    map.putIfAbsent(f.source + ":" + f.nodeId, f);
                }
            }
        }
        return new ArrayList<>(map.values());
    }

    private boolean isUnderOutput(SysWorkspaceNode file, Long outputDirId, Long workspaceId) {
        if (outputDirId == null) {
            return Objects.equals(file.getWorkspaceId(), workspaceId);
        }
        Long cur = file.getParentId();
        int guard = 0;
        while (cur != null && guard++ < 64) {
            if (Objects.equals(cur, outputDirId)) {
                return true;
            }
            SysWorkspaceNode p = workspaceNodeMapper.selectById(cur);
            if (p == null) {
                return false;
            }
            cur = p.getParentId();
        }
        return false;
    }

    private void collectWikiFilesUnder(Map<String, AllowedFile> map, Long dirId, String role) {
        List<SysWikiNode> children = wikiNodeMapper.selectList(new LambdaQueryWrapper<SysWikiNode>()
                .eq(SysWikiNode::getParentId, dirId));
        for (SysWikiNode child : children) {
            if (WorkspaceNodeType.FILE.name().equals(child.getNodeType())) {
                addWikiFile(map, child.getId(), wikiFsService.buildDisplayPath(child), role);
            } else {
                collectWikiFilesUnder(map, child.getId(), role);
            }
        }
    }

    private void addWikiFile(Map<String, AllowedFile> map, Long nodeId, String displayPath, String role) {
        SysWikiNode node = wikiNodeMapper.selectById(nodeId);
        if (node == null || !WorkspaceNodeType.FILE.name().equals(node.getNodeType())) {
            return;
        }
        AllowedFile f = new AllowedFile();
        f.source = "wiki";
        f.nodeId = node.getId();
        f.displayPath = displayPath;
        f.role = role;
        try {
            Path p = wikiFsService.resolveNodeAbsolutePath(node);
            f.path = p;
            f.approxChars = Files.exists(p) ? (int) Math.min(Files.size(p), Integer.MAX_VALUE) : 0;
        } catch (Exception e) {
            f.approxChars = 0;
        }
        map.putIfAbsent(f.source + ":" + f.nodeId, f);
    }

    private String readFull(AllowedFile file) {
        if (file.cachedContent != null) {
            return file.cachedContent;
        }
        if ("wiki".equals(file.source)) {
            return wikiFsService.readFile(file.nodeId).getContent();
        }
        SysWorkspaceNode node = workspaceNodeMapper.selectById(file.nodeId);
        SysWorkspace ws = workspaceMapper.selectById(node.getWorkspaceId());
        return workspaceFsService.readFile(ws.getId(), node.getId()).getContent();
    }

    private void searchOneFile(AllowedFile file, String[] keywords, List<AgentFileHitVO> hits) throws Exception {
        String content;
        if (file.cachedContent != null) {
            content = file.cachedContent;
        } else if (file.path != null && Files.isRegularFile(file.path)) {
            // stream from path
            List<String> lines = new ArrayList<>();
            int totalChars = 0;
            try (BufferedReader reader = Files.newBufferedReader(file.path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                    totalChars += line.length() + 1;
                    if (totalChars >= MAX_FILE_SCAN_CHARS) {
                        break;
                    }
                }
            }
            matchLines(file, lines, keywords, hits);
            return;
        } else {
            content = readFull(file);
        }
        if (content == null) {
            return;
        }
        if (content.length() > MAX_FILE_SCAN_CHARS) {
            content = content.substring(0, MAX_FILE_SCAN_CHARS);
        }
        matchLines(file, content.lines().toList(), keywords, hits);
    }

    private void matchLines(AllowedFile file, List<String> lines, String[] keywords, List<AgentFileHitVO> hits) {
        for (int i = 0; i < lines.size(); i++) {
            if (hits.size() >= MAX_HITS) {
                return;
            }
            String lower = lines.get(i).toLowerCase(Locale.ROOT);
            boolean match = false;
            for (String kw : keywords) {
                if (StringUtils.hasText(kw) && lower.contains(kw)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                continue;
            }
            int from = Math.max(0, i - CONTEXT_LINES);
            int to = Math.min(lines.size() - 1, i + CONTEXT_LINES);
            StringBuilder snip = new StringBuilder();
            for (int j = from; j <= to; j++) {
                if (snip.length() > 0) {
                    snip.append('\n');
                }
                snip.append(j + 1).append('|').append(lines.get(j));
            }
            String snippet = snip.toString();
            if (snippet.length() > MAX_HIT_SNIPPET_CHARS) {
                snippet = snippet.substring(0, MAX_HIT_SNIPPET_CHARS) + "…";
            }
            AgentFileHitVO hit = new AgentFileHitVO();
            hit.setSource(file.source);
            hit.setNodeId(file.nodeId);
            hit.setDisplayPath(file.displayPath);
            hit.setLine(i + 1);
            hit.setSnippet(snippet);
            hits.add(hit);
        }
    }

    private SysWorkspaceNode findChildFile(Long workspaceId, Long parentId, String displayName) {
        LambdaQueryWrapper<SysWorkspaceNode> q = new LambdaQueryWrapper<SysWorkspaceNode>()
                .eq(SysWorkspaceNode::getWorkspaceId, workspaceId)
                .eq(SysWorkspaceNode::getNodeType, WorkspaceNodeType.FILE.name())
                .eq(SysWorkspaceNode::getDisplayName, displayName)
                .last("LIMIT 1");
        if (parentId == null) {
            q.isNull(SysWorkspaceNode::getParentId);
        } else {
            q.eq(SysWorkspaceNode::getParentId, parentId);
        }
        return workspaceNodeMapper.selectOne(q);
    }

    private String buildWorkspaceDisplayPath(Long workspaceId, Long nodeId) {
        List<String> parts = new ArrayList<>();
        SysWorkspaceNode cur = workspaceNodeMapper.selectById(nodeId);
        int guard = 0;
        while (cur != null && guard++ < 64) {
            parts.add(0, cur.getDisplayName());
            if (cur.getParentId() == null) {
                break;
            }
            cur = workspaceNodeMapper.selectById(cur.getParentId());
        }
        return String.join("/", parts);
    }

    private String resolveOutputDirLabel(WbChatSession session) {
        if (session.getOutputWikiDirNodeId() != null) {
            SysWikiNode w = wikiNodeMapper.selectById(session.getOutputWikiDirNodeId());
            return w == null ? "（Wiki 输出目录）" : "wiki:" + wikiFsService.buildDisplayPath(w);
        }
        if (session.getOutputDirNodeId() == null) {
            return "（个人空间根目录）";
        }
        SysWorkspaceNode n = workspaceNodeMapper.selectById(session.getOutputDirNodeId());
        return n == null ? null : n.getDisplayName();
    }

    private static final int MAX_OUTPUT_TEMPLATE_CHARS = 40_000;
    private static final int MAX_STANDARDS_CHARS = 60_000;

    private void applySessionOutputTemplate(WbChatSession session, AgentFileIndexVO vo) {
        Long nodeId = session.getOutputTemplateWikiNodeId();
        if (nodeId == null) {
            return;
        }
        try {
            SysWikiNode node = wikiNodeMapper.selectById(nodeId);
            if (node == null || !Objects.equals(node.getTenantId(), session.getTenantId())) {
                return;
            }
            if (!WorkspaceNodeType.FILE.name().equals(node.getNodeType())) {
                return;
            }
            vo.setOutputTemplateWikiNodeId(node.getId());
            vo.setOutputTemplatePath(wikiFsService.buildDisplayPath(node));
            var contentVo = wikiFsService.readFile(node.getId());
            String content = contentVo == null ? null : contentVo.getContent();
            if (!StringUtils.hasText(content)) {
                return;
            }
            if (content.length() > MAX_OUTPUT_TEMPLATE_CHARS) {
                content = content.substring(0, MAX_OUTPUT_TEMPLATE_CHARS) + "\n…（模板过长已截断）";
            }
            vo.setDeliverableTemplate(content);
        } catch (Exception e) {
            log.warn(
                    "load session output template failed sessionId={} nodeId={}: {}",
                    session.getId(),
                    nodeId,
                    e.getMessage());
        }
    }

    /** 将会话绑定的规范正文写入 file_index，供 Agent 撰写交付物时强制遵守。 */
    private void applySessionStandards(WbChatSession session, AgentFileIndexVO vo) {
        Long nodeId = session.getStandardsWikiNodeId();
        if (nodeId == null) {
            return;
        }
        try {
            SysWikiNode node = wikiNodeMapper.selectById(nodeId);
            if (node == null || !Objects.equals(node.getTenantId(), session.getTenantId())) {
                return;
            }
            vo.setStandardsWikiNodeId(node.getId());
            vo.setStandardsPath(wikiFsService.buildDisplayPath(node));
            StringBuilder sb = new StringBuilder();
            if (WorkspaceNodeType.FILE.name().equals(node.getNodeType())) {
                appendStandardsFile(sb, node);
            } else {
                appendStandardsUnder(sb, node.getId());
            }
            if (sb.isEmpty()) {
                return;
            }
            String content = sb.toString();
            if (content.length() > MAX_STANDARDS_CHARS) {
                content = content.substring(0, MAX_STANDARDS_CHARS) + "\n…（规范过长已截断）";
            }
            vo.setStandardsContent(content);
        } catch (Exception e) {
            log.warn(
                    "load session standards failed sessionId={} nodeId={}: {}",
                    session.getId(),
                    nodeId,
                    e.getMessage());
        }
    }

    private void appendStandardsUnder(StringBuilder sb, Long dirId) {
        List<SysWikiNode> children = wikiNodeMapper.selectList(new LambdaQueryWrapper<SysWikiNode>()
                .eq(SysWikiNode::getParentId, dirId)
                .orderByAsc(SysWikiNode::getNodeType)
                .orderByAsc(SysWikiNode::getDisplayName));
        for (SysWikiNode child : children) {
            if (sb.length() >= MAX_STANDARDS_CHARS) {
                return;
            }
            if (WorkspaceNodeType.FILE.name().equals(child.getNodeType())) {
                appendStandardsFile(sb, child);
            } else {
                appendStandardsUnder(sb, child.getId());
            }
        }
    }

    private void appendStandardsFile(StringBuilder sb, SysWikiNode file) {
        try {
            var contentVo = wikiFsService.readFile(file.getId());
            String content = contentVo == null ? null : contentVo.getContent();
            if (!StringUtils.hasText(content)) {
                return;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("### 规范文件：").append(wikiFsService.buildDisplayPath(file)).append("\n\n");
            sb.append(content.trim());
        } catch (Exception e) {
            log.warn("read standards file failed nodeId={}: {}", file.getId(), e.getMessage());
        }
    }

    /** 输出文件名：允许 .md / .html；无扩展名默认 .md */
    private String normalizeMdName(String displayName) {
        if (!StringUtils.hasText(displayName)) {
            throw new BizException("请输入文件名");
        }
        String name = displayName.trim().replace('\\', '/');
        if (name.contains("..") || name.startsWith("/")) {
            throw new BizException("文件名非法");
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".html") || lower.endsWith(".htm")) {
            return name;
        }
        if (name.contains(".")) {
            throw new BizException("输出文件仅支持 .md 或 .html：" + name);
        }
        return name + ".md";
    }

    private static final class AllowedFile {
        String source;
        Long nodeId;
        String displayPath;
        String role;
        int approxChars;
        Path path;
        String cachedContent;
    }
}

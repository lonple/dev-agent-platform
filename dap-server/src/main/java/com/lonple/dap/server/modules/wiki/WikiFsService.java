package com.lonple.dap.server.modules.wiki;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.config.DataRootProperties;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirement;
import com.lonple.dap.server.modules.tenant.entity.SysTenant;
import com.lonple.dap.server.modules.tenant.mapper.TenantMapper;
import com.lonple.dap.server.modules.tnworkspace.WorkspaceFsService;
import com.lonple.dap.server.modules.tnworkspace.WorkspaceNodeType;
import com.lonple.dap.server.modules.tnworkspace.entity.SysWorkspace;
import com.lonple.dap.server.modules.tnworkspace.entity.SysWorkspaceNode;
import com.lonple.dap.server.modules.tnworkspace.mapper.SysWorkspaceMapper;
import com.lonple.dap.server.modules.tnworkspace.mapper.SysWorkspaceNodeMapper;
import com.lonple.dap.server.modules.wiki.dto.WikiFileContentVO;
import com.lonple.dap.server.modules.wiki.dto.WikiNodeVO;
import com.lonple.dap.server.modules.wiki.entity.SysWikiNode;
import com.lonple.dap.server.modules.wiki.mapper.SysWikiNodeMapper;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class WikiFsService {

    public static final String DAP_META_DIR = ".dap";
    public static final String MANIFEST_FILE = "manifest.json";
    public static final String DEFAULT_REQ_MARKDOWN_NAME = "原始需求.md";

    private final DataRootProperties dataRootProperties;
    private final TenantMapper tenantMapper;
    private final SysWikiNodeMapper wikiNodeMapper;
    private final SysWorkspaceMapper workspaceMapper;
    private final SysWorkspaceNodeMapper workspaceNodeMapper;
    private final WorkspaceFsService workspaceFsService;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<Long, Object> manifestLocks = new ConcurrentHashMap<>();

    public Path resolveWikiRoot(Long tenantId) {
        SysTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || !StringUtils.hasText(tenant.getCode())) {
            throw new BizException("租户不存在");
        }
        Path dataRoot = Paths.get(dataRootProperties.getRoot()).toAbsolutePath().normalize();
        Path root = dataRoot.resolve(tenant.getCode()).resolve("wiki").normalize();
        if (!root.startsWith(dataRoot)) {
            throw new BizException("Wiki 路径非法");
        }
        return root;
    }

    public void ensureWikiRoot(Long tenantId) {
        try {
            Path root = resolveWikiRoot(tenantId);
            Files.createDirectories(root);
            Files.createDirectories(root.resolve(DAP_META_DIR));
        } catch (Exception e) {
            throw new BizException("创建 Wiki 目录失败");
        }
    }

    public List<WikiNodeVO> listTree() {
        Long tenantId = TenantSupport.requireTenantId();
        ensureWikiRoot(tenantId);
        List<SysWikiNode> nodes = wikiNodeMapper.selectList(new LambdaQueryWrapper<SysWikiNode>()
                .eq(SysWikiNode::getTenantId, tenantId)
                .orderByAsc(SysWikiNode::getNodeType)
                .orderByAsc(SysWikiNode::getDisplayName));
        return buildTree(nodes);
    }

    public WikiFileContentVO readFile(Long nodeId) {
        SysWikiNode node = requireNode(nodeId);
        if (!WorkspaceNodeType.FILE.name().equals(node.getNodeType())) {
            throw new BizException("只能打开文件");
        }
        try {
            String content = Files.readString(resolveNodePath(node), StandardCharsets.UTF_8);
            WikiFileContentVO vo = new WikiFileContentVO();
            vo.setId(node.getId());
            vo.setDisplayName(node.getDisplayName());
            vo.setContent(content);
            return vo;
        } catch (Exception e) {
            throw new BizException("读取文件失败");
        }
    }

    @Transactional
    public Long createDirectory(Long parentId, String displayName) {
        Long tenantId = TenantSupport.requireTenantId();
        ensureWikiRoot(tenantId);
        String name = normalizeDisplayName(displayName, false);
        Long parent = resolveParentDirId(tenantId, parentId);
        assertDisplayUnique(tenantId, parent, name);

        LocalDateTime now = LocalDateTime.now();
        SysWikiNode dir = new SysWikiNode();
        dir.setTenantId(tenantId);
        dir.setParentId(parent);
        dir.setNodeType(WorkspaceNodeType.DIR.name());
        dir.setDisplayName(name);
        dir.setStorageName(randomStorageName(null));
        dir.setCreatedBy(TenantSupport.requireUserId());
        dir.setCreatedAt(now);
        dir.setUpdatedAt(now);
        wikiNodeMapper.insert(dir);

        try {
            Files.createDirectories(resolveNodePath(dir));
        } catch (Exception e) {
            throw new BizException("创建目录失败");
        }
        scheduleRewriteManifest(tenantId);
        return dir.getId();
    }

    /**
     * 创建文本文件：允许 .md / .html；无扩展名默认 .md。
     * Wiki「新建」UI 仍只建 md；html 来自上传或 Agent 写入。
     */
    @Transactional
    public Long createMarkdownFile(Long parentId, String displayName, String content) {
        Long tenantId = TenantSupport.requireTenantId();
        ensureWikiRoot(tenantId);
        String name = normalizeTextFileName(displayName);
        Long parent = resolveParentDirId(tenantId, parentId);
        assertDisplayUnique(tenantId, parent, name);

        LocalDateTime now = LocalDateTime.now();
        String storage = randomStorageName(extOf(name));
        SysWikiNode file = new SysWikiNode();
        file.setTenantId(tenantId);
        file.setParentId(parent);
        file.setNodeType(WorkspaceNodeType.FILE.name());
        file.setDisplayName(name);
        file.setStorageName(storage);
        file.setCreatedBy(TenantSupport.requireUserId());
        file.setCreatedAt(now);
        file.setUpdatedAt(now);
        wikiNodeMapper.insert(file);

        Path parentPath = parent == null
                ? resolveWikiRoot(tenantId)
                : resolveNodePath(requireNode(parent));
        try {
            Files.createDirectories(parentPath);
            Files.writeString(parentPath.resolve(storage), content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("创建文件失败");
        }
        scheduleRewriteManifest(tenantId);
        return file.getId();
    }

    @Transactional
    public void updateFileContent(Long nodeId, String content) {
        SysWikiNode node = requireNode(nodeId);
        if (!WorkspaceNodeType.FILE.name().equals(node.getNodeType())) {
            throw new BizException("只能编辑文件");
        }
        try {
            Files.writeString(resolveNodePath(node), content == null ? "" : content, StandardCharsets.UTF_8);
            node.setUpdatedAt(LocalDateTime.now());
            wikiNodeMapper.updateById(node);
        } catch (Exception e) {
            throw new BizException("保存文件失败");
        }
    }

    @Transactional
    public void deleteNode(Long nodeId) {
        SysWikiNode node = requireNode(nodeId);
        Long tenantId = node.getTenantId();
        deleteNodeRecursive(node);
        scheduleRewriteManifest(tenantId);
    }

    /** 将个人工作空间中的文件发布到 Wiki 指定目录 */
    @Transactional
    public Long publishFromPersonal(Long sourceWorkspaceId, Long sourceNodeId, Long targetParentId) {
        Long tenantId = TenantSupport.requireTenantId();
        Long userId = TenantSupport.requireUserId();
        SysWorkspace ws = workspaceMapper.selectById(sourceWorkspaceId);
        if (ws == null || !Objects.equals(ws.getTenantId(), tenantId)
                || !Objects.equals(ws.getOwnerUserId(), userId)) {
            throw new BizException(403, "只能发布自己的个人工作空间文件");
        }
        SysWorkspaceNode src = workspaceNodeMapper.selectById(sourceNodeId);
        if (src == null || !Objects.equals(src.getWorkspaceId(), ws.getId())) {
            throw new BizException("源文件不存在");
        }
        if (!WorkspaceNodeType.FILE.name().equals(src.getNodeType())) {
            throw new BizException("只能发布文件");
        }

        ensureWikiRoot(tenantId);
        Long parent = resolveParentDirId(tenantId, targetParentId);
        String displayName = src.getDisplayName();
        assertDisplayUnique(tenantId, parent, displayName);

        String content;
        try {
            Path srcPath = workspaceFsService.resolveNodeAbsolutePath(ws, src);
            content = Files.readString(srcPath, StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("读取源文件失败");
        }

        LocalDateTime now = LocalDateTime.now();
        String storage = randomStorageName(extOf(displayName));
        SysWikiNode file = new SysWikiNode();
        file.setTenantId(tenantId);
        file.setParentId(parent);
        file.setNodeType(WorkspaceNodeType.FILE.name());
        file.setDisplayName(displayName);
        file.setStorageName(storage);
        file.setCreatedBy(userId);
        file.setCreatedAt(now);
        file.setUpdatedAt(now);
        wikiNodeMapper.insert(file);

        Path parentPath = parent == null
                ? resolveWikiRoot(tenantId)
                : resolveNodePath(requireNode(parent));
        try {
            Files.createDirectories(parentPath);
            Files.writeString(parentPath.resolve(storage), content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("写入 Wiki 失败");
        }
        scheduleRewriteManifest(tenantId);
        return file.getId();
    }

    /** 投入研发：确保 Wiki 下存在以产品编码命名的目录 */
    @Transactional
    public Long ensureProductDir(SysProduct product) {
        if (product == null || !StringUtils.hasText(product.getCode())) {
            throw new BizException("产品无效");
        }
        Long tenantId = TenantSupport.requireTenantId();
        if (!Objects.equals(product.getTenantId(), tenantId)) {
            throw new BizException("产品不属于当前租户");
        }
        ensureWikiRoot(tenantId);
        String name = product.getCode().trim();
        SysWikiNode existing = findChildDir(tenantId, null, name);
        if (existing != null) {
            return existing.getId();
        }
        return createDirectory(null, name);
    }

    /**
     * 投入研发：在产品目录下写入「需求编号/原始需求.md」。
     * @return 需求目录节点 ID
     */
    @Transactional
    public Long ensureRequirementArtifacts(SysProduct product, ReqRequirement requirement) {
        if (requirement == null || !StringUtils.hasText(requirement.getReqNo())) {
            throw new BizException("需求尚未生成编号");
        }
        Long productDirId = product.getWikiNodeId() != null
                ? product.getWikiNodeId()
                : ensureProductDir(product);
        return ensureRequirementDir(productDirId, requirement);
    }

    /** 在指定 Wiki 父目录下确保「需求编号」目录及原始需求.md，返回需求目录 ID */
    @Transactional
    public Long ensureRequirementDir(Long parentWikiNodeId, ReqRequirement requirement) {
        if (requirement == null || !StringUtils.hasText(requirement.getReqNo())) {
            throw new BizException("需求尚未生成编号");
        }
        if (parentWikiNodeId == null) {
            throw new BizException("Wiki 父目录无效");
        }
        Long tenantId = TenantSupport.requireTenantId();
        requireNode(parentWikiNodeId);
        String dirDisplay = requirement.getReqNo().trim();
        SysWikiNode existingDir = findChildDir(tenantId, parentWikiNodeId, dirDisplay);
        Long reqDirId;
        if (existingDir != null) {
            reqDirId = existingDir.getId();
        } else {
            reqDirId = createDirectory(parentWikiNodeId, dirDisplay);
        }
        SysWikiNode raw = findChildFile(reqDirId, DEFAULT_REQ_MARKDOWN_NAME);
        if (raw == null) {
            createMarkdownFile(reqDirId, DEFAULT_REQ_MARKDOWN_NAME, buildRequirementMarkdown(requirement));
        }
        return reqDirId;
    }

    public SysWikiNode requireNode(Long nodeId) {
        Long tenantId = TenantSupport.requireTenantId();
        SysWikiNode node = wikiNodeMapper.selectById(nodeId);
        if (node == null || !Objects.equals(node.getTenantId(), tenantId)) {
            throw new BizException(404, "节点不存在");
        }
        return node;
    }

    /** 列出目录下文件，按更新时间倒序 */
    public List<SysWikiNode> listChildFiles(Long parentId) {
        Long tenantId = TenantSupport.requireTenantId();
        LambdaQueryWrapper<SysWikiNode> q = new LambdaQueryWrapper<SysWikiNode>()
                .eq(SysWikiNode::getTenantId, tenantId)
                .eq(SysWikiNode::getNodeType, WorkspaceNodeType.FILE.name())
                .orderByDesc(SysWikiNode::getUpdatedAt)
                .orderByDesc(SysWikiNode::getId);
        if (parentId == null) {
            q.isNull(SysWikiNode::getParentId);
        } else {
            q.eq(SysWikiNode::getParentId, parentId);
        }
        return wikiNodeMapper.selectList(q);
    }

    /** 在指定目录下查找同名文件（不存在返回 null）；无扩展名时按 .md 查找 */
    public SysWikiNode findChildFile(Long parentId, String displayName) {
        Long tenantId = TenantSupport.requireTenantId();
        String name = displayName == null ? "" : displayName.trim();
        if (!StringUtils.hasText(name)) {
            return null;
        }
        try {
            name = normalizeTextFileName(name);
        } catch (BizException e) {
            return null;
        }
        LambdaQueryWrapper<SysWikiNode> q = new LambdaQueryWrapper<SysWikiNode>()
                .eq(SysWikiNode::getTenantId, tenantId)
                .eq(SysWikiNode::getNodeType, WorkspaceNodeType.FILE.name())
                .eq(SysWikiNode::getDisplayName, name)
                .last("LIMIT 1");
        if (parentId == null) {
            q.isNull(SysWikiNode::getParentId);
        } else {
            q.eq(SysWikiNode::getParentId, parentId);
        }
        return wikiNodeMapper.selectOne(q);
    }

    /** 创建或覆盖同名 .md/.html；返回文件节点 ID */
    @Transactional
    public Long upsertMarkdownFile(Long parentId, String displayName, String content) {
        return upsertTextFile(parentId, displayName, content).id();
    }

    /**
     * 上传/重新上传：同目录同名则覆盖正文，否则新建。
     */
    @Transactional
    public UpsertTextFileResult upsertTextFile(Long parentId, String displayName, String content) {
        SysWikiNode existing = findChildFile(parentId, displayName);
        if (existing != null) {
            updateFileContent(existing.getId(), content);
            return new UpsertTextFileResult(existing.getId(), true);
        }
        Long id = createMarkdownFile(parentId, displayName, content);
        return new UpsertTextFileResult(id, false);
    }

    public record UpsertTextFileResult(Long id, boolean overwritten) {}

    public String buildDisplayPath(SysWikiNode node) {
        List<String> parts = new ArrayList<>();
        SysWikiNode cur = node;
        int guard = 0;
        while (cur != null && guard++ < 64) {
            parts.add(0, cur.getDisplayName());
            if (cur.getParentId() == null) {
                break;
            }
            cur = wikiNodeMapper.selectById(cur.getParentId());
        }
        return String.join("/", parts);
    }

    /** 租户 Wiki 根下按显示名查找顶级目录（如「模板」「规范」） */
    public SysWikiNode findTopLevelDir(Long tenantId, String displayName) {
        if (tenantId == null || !StringUtils.hasText(displayName)) {
            return null;
        }
        return findChildDir(tenantId, null, displayName.trim());
    }

    /** 判断节点是否为 ancestor 自身或其子孙 */
    public boolean isSelfOrDescendant(SysWikiNode node, Long ancestorId) {
        if (node == null || ancestorId == null) {
            return false;
        }
        if (Objects.equals(node.getId(), ancestorId)) {
            return true;
        }
        Long cur = node.getParentId();
        int guard = 0;
        while (cur != null && guard++ < 64) {
            if (Objects.equals(cur, ancestorId)) {
                return true;
            }
            SysWikiNode p = wikiNodeMapper.selectById(cur);
            if (p == null) {
                return false;
            }
            cur = p.getParentId();
        }
        return false;
    }

    /** 供 Agent 搜索等跨模块场景解析 Wiki 节点绝对路径 */
    public Path resolveNodeAbsolutePath(SysWikiNode node) {
        return resolveNodePath(node);
    }

    private SysWikiNode findChildDir(Long tenantId, Long parentId, String displayName) {
        LambdaQueryWrapper<SysWikiNode> q = new LambdaQueryWrapper<SysWikiNode>()
                .eq(SysWikiNode::getTenantId, tenantId)
                .eq(SysWikiNode::getNodeType, WorkspaceNodeType.DIR.name())
                .eq(SysWikiNode::getDisplayName, displayName)
                .last("LIMIT 1");
        if (parentId == null) {
            q.isNull(SysWikiNode::getParentId);
        } else {
            q.eq(SysWikiNode::getParentId, parentId);
        }
        return wikiNodeMapper.selectOne(q);
    }

    private void deleteNodeRecursive(SysWikiNode node) {
        if (WorkspaceNodeType.DIR.name().equals(node.getNodeType())) {
            List<SysWikiNode> children = wikiNodeMapper.selectList(new LambdaQueryWrapper<SysWikiNode>()
                    .eq(SysWikiNode::getTenantId, node.getTenantId())
                    .eq(SysWikiNode::getParentId, node.getId()));
            for (SysWikiNode child : children) {
                deleteNodeRecursive(child);
            }
        }
        Path path = resolveNodePath(node);
        try {
            if (Files.exists(path)) {
                if (Files.isDirectory(path)) {
                    try (var walk = Files.walk(path)) {
                        walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                            }
                        });
                    }
                } else {
                    Files.deleteIfExists(path);
                }
            }
        } catch (Exception e) {
            log.warn("删除 Wiki 磁盘失败: {}", path, e);
        }
        wikiNodeMapper.deleteById(node.getId());
    }

    private Path resolveNodePath(SysWikiNode node) {
        List<String> parts = new ArrayList<>();
        SysWikiNode cur = node;
        int guard = 0;
        while (cur != null && guard++ < 256) {
            parts.add(0, cur.getStorageName());
            if (cur.getParentId() == null) {
                break;
            }
            cur = wikiNodeMapper.selectById(cur.getParentId());
        }
        Path root = resolveWikiRoot(node.getTenantId());
        Path path = root;
        for (String part : parts) {
            path = path.resolve(part);
        }
        Path normalized = path.normalize();
        if (!normalized.startsWith(root)) {
            throw new BizException("文件路径非法");
        }
        return normalized;
    }

    private Long resolveParentDirId(Long tenantId, Long parentId) {
        if (parentId == null) {
            return null;
        }
        SysWikiNode parent = requireNode(parentId);
        if (!Objects.equals(parent.getTenantId(), tenantId)) {
            throw new BizException("父节点无效");
        }
        if (!WorkspaceNodeType.DIR.name().equals(parent.getNodeType())) {
            throw new BizException("父节点必须是目录");
        }
        return parent.getId();
    }

    private void assertDisplayUnique(Long tenantId, Long parentId, String displayName) {
        LambdaQueryWrapper<SysWikiNode> q = new LambdaQueryWrapper<SysWikiNode>()
                .eq(SysWikiNode::getTenantId, tenantId)
                .eq(SysWikiNode::getDisplayName, displayName);
        if (parentId == null) {
            q.isNull(SysWikiNode::getParentId);
        } else {
            q.eq(SysWikiNode::getParentId, parentId);
        }
        Long count = wikiNodeMapper.selectCount(q);
        if (count != null && count > 0) {
            throw new BizException("目录或文件显示名已存在: " + displayName);
        }
    }

    private String normalizeDisplayName(String displayName, boolean markdownFile) {
        if (!StringUtils.hasText(displayName)) {
            throw new BizException("请输入名称");
        }
        String name = displayName.trim();
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new BizException("名称非法");
        }
        if (markdownFile) {
            return normalizeTextFileName(name);
        }
        return name;
    }

    /** 允许 .md / .html / .htm；其它扩展名拒绝；无扩展名默认 .md */
    private String normalizeTextFileName(String displayName) {
        if (!StringUtils.hasText(displayName)) {
            throw new BizException("请输入名称");
        }
        String name = displayName.trim();
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new BizException("名称非法");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".html") || lower.endsWith(".htm")) {
            return name;
        }
        if (name.contains(".")) {
            throw new BizException("仅支持 .md 或 .html 文件：" + name);
        }
        return name + ".md";
    }

    private String randomStorageName(String ext) {
        String id = UUID.randomUUID().toString().replace("-", "");
        return StringUtils.hasText(ext) ? id + "." + ext : id;
    }

    private String extOf(String displayName) {
        int i = displayName.lastIndexOf('.');
        if (i > 0 && i < displayName.length() - 1) {
            return displayName.substring(i + 1);
        }
        return "md";
    }

    private String buildRequirementMarkdown(ReqRequirement requirement) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 原始需求\n\n");
        sb.append("- 需求编号：").append(nullToEmpty(requirement.getReqNo())).append('\n');
        sb.append("- 需求名称：").append(nullToEmpty(requirement.getName())).append('\n');
        sb.append("- 产品版本：").append(nullToEmpty(requirement.getProductVersion())).append("\n\n");
        sb.append("---\n\n");
        String desc = requirement.getDescription();
        if (StringUtils.hasText(desc)) {
            sb.append(desc.trim());
            if (!desc.trim().endsWith("\n")) {
                sb.append('\n');
            }
        } else {
            sb.append("（无描述）\n");
        }
        return sb.toString();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private List<WikiNodeVO> buildTree(List<SysWikiNode> nodes) {
        Map<Long, WikiNodeVO> map = new LinkedHashMap<>();
        for (SysWikiNode n : nodes) {
            WikiNodeVO vo = new WikiNodeVO();
            vo.setId(n.getId());
            vo.setParentId(n.getParentId());
            vo.setNodeType(n.getNodeType());
            vo.setDisplayName(n.getDisplayName());
            map.put(n.getId(), vo);
        }
        List<WikiNodeVO> roots = new ArrayList<>();
        for (WikiNodeVO vo : map.values()) {
            if (vo.getParentId() == null) {
                roots.add(vo);
            } else {
                WikiNodeVO parent = map.get(vo.getParentId());
                if (parent != null) {
                    parent.getChildren().add(vo);
                } else {
                    roots.add(vo);
                }
            }
        }
        sortTree(roots);
        return roots;
    }

    private void sortTree(List<WikiNodeVO> list) {
        list.sort(Comparator
                .comparing((WikiNodeVO v) -> !"DIR".equals(v.getNodeType()))
                .thenComparing(WikiNodeVO::getDisplayName, String.CASE_INSENSITIVE_ORDER));
        for (WikiNodeVO v : list) {
            if (v.getChildren() != null && !v.getChildren().isEmpty()) {
                sortTree(v.getChildren());
            }
        }
    }

    private void scheduleRewriteManifest(Long tenantId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            String key = "wiki.manifest." + tenantId;
            if (TransactionSynchronizationManager.hasResource(key)) {
                return;
            }
            TransactionSynchronizationManager.bindResource(key, Boolean.TRUE);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeManifestAtomic(tenantId);
                }
            });
            return;
        }
        writeManifestAtomic(tenantId);
    }

    private void writeManifestAtomic(Long tenantId) {
        Object lock = manifestLocks.computeIfAbsent(tenantId, id -> new Object());
        synchronized (lock) {
            try {
                Path root = resolveWikiRoot(tenantId);
                Path metaDir = root.resolve(DAP_META_DIR);
                Files.createDirectories(metaDir);
                List<SysWikiNode> nodes = wikiNodeMapper.selectList(new LambdaQueryWrapper<SysWikiNode>()
                        .eq(SysWikiNode::getTenantId, tenantId)
                        .orderByAsc(SysWikiNode::getId));
                Map<Long, SysWikiNode> byId = new HashMap<>();
                for (SysWikiNode n : nodes) {
                    byId.put(n.getId(), n);
                }
                List<Map<String, Object>> entries = new ArrayList<>();
                for (SysWikiNode n : nodes) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", n.getId());
                    row.put("parentId", n.getParentId());
                    row.put("type", n.getNodeType());
                    row.put("name", n.getDisplayName());
                    row.put("path", buildLogicalPath(n, byId, true));
                    row.put("storage", n.getStorageName());
                    row.put("storagePath", buildLogicalPath(n, byId, false));
                    entries.add(row);
                }
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("version", 1);
                doc.put("tenantId", tenantId);
                doc.put("updatedAt", Instant.now().toString());
                doc.put("nodes", entries);
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
                Path manifest = metaDir.resolve(MANIFEST_FILE);
                Path tmp = metaDir.resolve(MANIFEST_FILE + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, manifest, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                log.warn("写入 Wiki 清单失败: tenantId={}", tenantId, e);
            }
        }
    }

    private String buildLogicalPath(SysWikiNode node, Map<Long, SysWikiNode> byId, boolean display) {
        List<String> parts = new ArrayList<>();
        SysWikiNode cur = node;
        int guard = 0;
        while (cur != null && guard++ < 256) {
            String part = display ? cur.getDisplayName() : cur.getStorageName();
            if (StringUtils.hasText(part)) {
                parts.add(part);
            }
            if (cur.getParentId() == null) {
                break;
            }
            cur = byId.get(cur.getParentId());
        }
        java.util.Collections.reverse(parts);
        return String.join("/", parts);
    }
}

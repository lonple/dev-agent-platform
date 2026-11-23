package com.lonple.dap.server.modules.wiki;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.config.DataRootProperties;
import com.lonple.dap.server.modules.issue.IssueMarkdownImages;
import com.lonple.dap.server.modules.issue.entity.IssIssue;
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
    public static final String DEFAULT_ISSUE_MARKDOWN_NAME = "问题单.md";
    private static final String WIKI_TEMPLATE_CLASSPATH = "wiki-templates/";
    public static final String DEFAULT_REQ_MARKDOWN_NAME = "原始需求.md";

    private final DataRootProperties dataRootProperties;
    private final TenantMapper tenantMapper;
    private final SysWikiNodeMapper wikiNodeMapper;
    private final SysWorkspaceMapper workspaceMapper;
    private final SysWorkspaceNodeMapper workspaceNodeMapper;
    private final WorkspaceFsService workspaceFsService;
    private final ObjectMapper objectMapper;
    private final IssueMarkdownImages issueMarkdownImages;

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
     * 创建文本文件：允许 .md / .html / .json；无扩展名默认 .md。
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

    /** 将个人工作空间中的文件发布到 Wiki 指定目录（同名则覆盖） */
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

        String content;
        try {
            Path srcPath = workspaceFsService.resolveNodeAbsolutePath(ws, src);
            content = Files.readString(srcPath, StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("读取源文件失败");
        }

        SysWikiNode existing = findChildFile(parent, displayName);
        if (existing != null) {
            updateFileContent(existing.getId(), content);
            return existing.getId();
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
     * 投入研发：在「{产品}/原始需求/{需求编号}/」写入「原始需求.md」（不再建产品下直属需求目录）。
     * @return 需求目录节点 ID
     */
    @Transactional
    public Long ensureRequirementArtifacts(SysProduct product, ReqRequirement requirement) {
        if (product == null || requirement == null || !StringUtils.hasText(requirement.getReqNo())) {
            throw new BizException("需求尚未生成编号");
        }
        if (product.getWikiNodeId() == null) {
            Long productDirId = ensureProductDir(product);
            product.setWikiNodeId(productDirId);
        }
        return ensureRawRequirementPublishDir(product, requirement);
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

    /** 列出目录下子目录 */
    public List<SysWikiNode> listChildDirs(Long parentId) {
        Long tenantId = TenantSupport.requireTenantId();
        LambdaQueryWrapper<SysWikiNode> q = new LambdaQueryWrapper<SysWikiNode>()
                .eq(SysWikiNode::getTenantId, tenantId)
                .eq(SysWikiNode::getNodeType, WorkspaceNodeType.DIR.name())
                .orderByAsc(SysWikiNode::getDisplayName)
                .orderByAsc(SysWikiNode::getId);
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

    /** 创建或覆盖同名 .md/.html/.json；返回文件节点 ID */
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

    /** 查找子目录（不存在返回 null） */
    public SysWikiNode findChildDirectory(Long parentId, String displayName) {
        Long tenantId = TenantSupport.requireTenantId();
        if (!StringUtils.hasText(displayName)) {
            return null;
        }
        return findChildDir(tenantId, parentId, displayName.trim());
    }

    /** 确保子目录存在，返回目录节点 ID */
    @Transactional
    public Long ensureChildDir(Long parentId, String displayName) {
        SysWikiNode existing = findChildDirectory(parentId, displayName);
        if (existing != null) {
            return existing.getId();
        }
        return createDirectory(parentId, displayName);
    }

    /**
     * 将目录节点挂到新父目录下（更新 parentId + 磁盘目录搬迁）。
     * 子节点 storageName 不变，随父目录一起移动；节点 ID 保持不变。
     * @return 被移动的目录节点 ID
     */
    @Transactional
    public Long moveDirectory(Long dirNodeId, Long newParentId) {
        SysWikiNode dir = requireNode(dirNodeId);
        if (!WorkspaceNodeType.DIR.name().equals(dir.getNodeType())) {
            throw new BizException("只能移动目录");
        }
        if (Objects.equals(dir.getParentId(), newParentId)) {
            return dir.getId();
        }
        if (isSelfOrDescendant(requireNode(newParentId), dir.getId())) {
            throw new BizException("不能将目录移动到自身或其子目录下");
        }
        Long tenantId = dir.getTenantId();
        Long parent = resolveParentDirId(tenantId, newParentId);
        SysWikiNode clash = findChildDir(tenantId, parent, dir.getDisplayName());
        if (clash != null && !Objects.equals(clash.getId(), dir.getId())) {
            throw new BizException("目标位置已存在同名目录：" + dir.getDisplayName());
        }

        Path oldPath = resolveNodePath(dir);
        dir.setParentId(parent);
        dir.setUpdatedAt(LocalDateTime.now());
        wikiNodeMapper.updateById(dir);
        Path newPath = resolveNodePath(dir);
        try {
            Files.createDirectories(newPath.getParent());
            if (Files.exists(oldPath)) {
                if (Files.exists(newPath)) {
                    throw new BizException("目标磁盘路径已存在，无法移动");
                }
                try {
                    Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.createDirectories(newPath);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("移动 Wiki 目录失败：" + e.getMessage());
        }
        scheduleRewriteManifest(tenantId);
        return dir.getId();
    }

    /**
     * 二审选定计划版本后：在产品下创建「版本号」目录，
     * 将产品下的「需求编号」目录迁入版本下；并把「原始需求/{需求}」中的文件合并进去。
     * @return 版本下的需求目录节点 ID
     */
    @Transactional
    public Long promoteRequirementFolderToVersion(
            SysProduct product, ReqRequirement requirement, String planVersion) {
        if (product == null || product.getWikiNodeId() == null || requirement == null
                || !StringUtils.hasText(requirement.getReqNo()) || !StringUtils.hasText(planVersion)) {
            throw new BizException("产品/需求/计划版本无效，无法迁移 Wiki 目录");
        }
        Long productDirId = product.getWikiNodeId();
        String reqNo = requirement.getReqNo().trim();
        String version = planVersion.trim();

        Long versionDirId = ensureChildDir(productDirId, version);
        SysWikiNode underVersion = findChildDirectory(versionDirId, reqNo);
        SysWikiNode underProduct = findChildDirectory(productDirId, reqNo);

        Long targetReqDirId;
        if (underVersion != null) {
            targetReqDirId = underVersion.getId();
            // 若产品下仍有同名需求目录，合并后删除源目录
            if (underProduct != null && !Objects.equals(underProduct.getId(), underVersion.getId())) {
                mergeDirectoryContents(underProduct.getId(), targetReqDirId);
                deleteNodeRecursive(underProduct);
                scheduleRewriteManifest(product.getTenantId());
            }
        } else if (underProduct != null) {
            targetReqDirId = moveDirectory(underProduct.getId(), versionDirId);
        } else {
            targetReqDirId = ensureChildDir(versionDirId, reqNo);
        }

        // 合并 AI 初审目录 原始需求/{需求} → 版本/{需求}
        SysWikiNode rawRoot = findChildDirectory(productDirId, "原始需求");
        if (rawRoot != null) {
            SysWikiNode rawReq = findChildDirectory(rawRoot.getId(), reqNo);
            if (rawReq != null && !Objects.equals(rawReq.getId(), targetReqDirId)) {
                mergeDirectoryContents(rawReq.getId(), targetReqDirId);
                deleteNodeRecursive(rawReq);
                scheduleRewriteManifest(product.getTenantId());
            }
        }

        // 保证有原始需求.md
        if (findChildFile(targetReqDirId, DEFAULT_REQ_MARKDOWN_NAME) == null) {
            createMarkdownFile(targetReqDirId, DEFAULT_REQ_MARKDOWN_NAME, buildRequirementMarkdown(requirement));
        }
        return targetReqDirId;
    }

    /** 将 sourceDir 下直接子节点合并到 targetDir（同名文件跳过已存在；同名目录递归合并） */
    @Transactional
    public void mergeDirectoryContents(Long sourceDirId, Long targetDirId) {
        if (sourceDirId == null || targetDirId == null || Objects.equals(sourceDirId, targetDirId)) {
            return;
        }
        requireNode(sourceDirId);
        requireNode(targetDirId);
        Long tenantId = TenantSupport.requireTenantId();
        List<SysWikiNode> children = wikiNodeMapper.selectList(new LambdaQueryWrapper<SysWikiNode>()
                .eq(SysWikiNode::getTenantId, tenantId)
                .eq(SysWikiNode::getParentId, sourceDirId));
        for (SysWikiNode child : children) {
            if (WorkspaceNodeType.DIR.name().equals(child.getNodeType())) {
                SysWikiNode existing = findChildDirectory(targetDirId, child.getDisplayName());
                if (existing != null) {
                    mergeDirectoryContents(child.getId(), existing.getId());
                    deleteNodeRecursive(child);
                } else {
                    moveDirectory(child.getId(), targetDirId);
                }
            } else {
                SysWikiNode existing = findChildFile(targetDirId, child.getDisplayName());
                if (existing != null) {
                    // 目标已有同名文件：保留目标，删除源
                    deleteNodeRecursive(child);
                } else {
                    moveFileNode(child.getId(), targetDirId);
                }
            }
        }
    }

    @Transactional
    public Long moveFileNode(Long fileNodeId, Long newParentId) {
        SysWikiNode file = requireNode(fileNodeId);
        if (!WorkspaceNodeType.FILE.name().equals(file.getNodeType())) {
            throw new BizException("只能移动文件");
        }
        if (Objects.equals(file.getParentId(), newParentId)) {
            return file.getId();
        }
        Long tenantId = file.getTenantId();
        Long parent = resolveParentDirId(tenantId, newParentId);
        if (findChildFile(parent, file.getDisplayName()) != null) {
            throw new BizException("目标位置已存在同名文件：" + file.getDisplayName());
        }
        Path oldPath = resolveNodePath(file);
        file.setParentId(parent);
        file.setUpdatedAt(LocalDateTime.now());
        wikiNodeMapper.updateById(file);
        Path newPath = resolveNodePath(file);
        try {
            Files.createDirectories(newPath.getParent());
            if (Files.exists(oldPath)) {
                try {
                    Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception e) {
            throw new BizException("移动 Wiki 文件失败：" + e.getMessage());
        }
        scheduleRewriteManifest(tenantId);
        return file.getId();
    }

    /** 收集目录自身及全部子孙节点 ID */
    public List<Long> listSubtreeNodeIds(Long rootDirId) {
        List<Long> ids = new ArrayList<>();
        if (rootDirId == null) {
            return ids;
        }
        collectSubtree(rootDirId, ids);
        return ids;
    }

    private void collectSubtree(Long nodeId, List<Long> out) {
        out.add(nodeId);
        Long tenantId = TenantSupport.requireTenantId();
        List<SysWikiNode> children = wikiNodeMapper.selectList(new LambdaQueryWrapper<SysWikiNode>()
                .eq(SysWikiNode::getTenantId, tenantId)
                .eq(SysWikiNode::getParentId, nodeId));
        for (SysWikiNode c : children) {
            if (WorkspaceNodeType.DIR.name().equals(c.getNodeType())) {
                collectSubtree(c.getId(), out);
            } else {
                out.add(c.getId());
            }
        }
    }

    /**
     * AI 初审发布/输入根：`{产品}/原始需求/{需求编号}`。
     * 若不存在则创建；若仍存在旧布局「{产品}/{需求编号}」则合并进来并删除旧目录。
     */
    @Transactional
    public Long ensureRawRequirementPublishDir(SysProduct product, ReqRequirement requirement) {
        if (product == null || product.getWikiNodeId() == null || requirement == null
                || !StringUtils.hasText(requirement.getReqNo())) {
            throw new BizException("产品 Wiki 或需求编号无效，无法解析原始需求目录");
        }
        Long productDirId = product.getWikiNodeId();
        String reqNo = requirement.getReqNo().trim();
        Long rawRoot = ensureChildDir(productDirId, "原始需求");
        Long reqDir = ensureChildDir(rawRoot, reqNo);
        ensureRawRequirementFile(product, requirement, reqDir);
        // 清掉旧布局：产品下直属「需求编号」目录（历史投入研发/打开文档误建）
        migrateAndRemoveLegacyProductReqDir(product, reqNo, reqDir);
        return reqDir;
    }

    /**
     * 将「{产品}/{需求编号}」内容合并到「{产品}/原始需求/{需求编号}」后删除旧目录。
     * 注意：不得误删「原始需求」「归档」等固定目录。
     */
    private void migrateAndRemoveLegacyProductReqDir(SysProduct product, String reqNo, Long targetReqDirId) {
        if (product.getWikiNodeId() == null || !StringUtils.hasText(reqNo) || targetReqDirId == null) {
            return;
        }
        if ("原始需求".equals(reqNo) || "归档".equals(reqNo) || "模板".equals(reqNo) || "规范".equals(reqNo)) {
            return;
        }
        SysWikiNode legacy = findChildDirectory(product.getWikiNodeId(), reqNo);
        if (legacy == null || Objects.equals(legacy.getId(), targetReqDirId)) {
            return;
        }
        // 防御：旧目录若其实挂在「原始需求」下则不是旧布局
        if (legacy.getParentId() != null && !Objects.equals(legacy.getParentId(), product.getWikiNodeId())) {
            return;
        }
        try {
            mergeDirectoryContents(legacy.getId(), targetReqDirId);
            deleteNodeRecursive(legacy);
            scheduleRewriteManifest(product.getTenantId());
            log.info("removed legacy wiki dir product/{}/ → 原始需求/{}/", reqNo, reqNo);
        } catch (Exception e) {
            log.warn("migrate legacy product/{} failed: {}", reqNo, e.getMessage());
        }
    }

    /**
     * 问题单发布根：`{产品}/issues/{问题单号}`。
     */
    @Transactional
    public Long ensureIssuePublishDir(SysProduct product, String issueNo) {
        if (product == null || product.getWikiNodeId() == null || !StringUtils.hasText(issueNo)) {
            throw new BizException("产品 Wiki 或问题单编号无效，无法解析问题发布目录");
        }
        Long issuesRoot = ensureChildDir(product.getWikiNodeId(), "issues");
        return ensureChildDir(issuesRoot, issueNo.trim());
    }

    /**
     * 在问题发布目录写入/刷新「问题单.md」（描述、实现步骤含图片 Markdown、预期结果、已有方案）。
     */
    @Transactional
    public Long ensureIssueTicketMarkdown(SysProduct product, IssIssue issue) {
        if (product == null || issue == null || !StringUtils.hasText(issue.getIssueNo())) {
            throw new BizException("问题单无效，无法写入问题单.md");
        }
        if (product.getWikiNodeId() == null) {
            Long productDirId = ensureProductDir(product);
            product.setWikiNodeId(productDirId);
        }
        Long issueDirId = ensureIssuePublishDir(product, issue.getIssueNo());
        upsertMarkdownFile(issueDirId, DEFAULT_ISSUE_MARKDOWN_NAME, buildIssueMarkdown(issue));
        return issueDirId;
    }

    /**
     * 确保 Wiki「模板」下存在指定模板文件；缺失时从 classpath {@code wiki-templates/} 种子写入。
     * @return 模板文件节点 ID
     */
    @Transactional
    public Long ensureBuiltinTemplateFile(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            throw new BizException("模板文件名无效");
        }
        String name = fileName.trim();
        Long tenantId = TenantSupport.requireTenantId();
        SysWikiNode tplRoot = findTopLevelDir(tenantId, "模板");
        if (tplRoot == null) {
            Long rootId = createDirectory(null, "模板");
            tplRoot = requireNode(rootId);
        }
        SysWikiNode existing = findChildFile(tplRoot.getId(), name);
        if (existing != null) {
            return existing.getId();
        }
        for (SysWikiNode dir : listChildDirs(tplRoot.getId())) {
            SysWikiNode nested = findChildFile(dir.getId(), name);
            if (nested != null) {
                return nested.getId();
            }
        }
        String body = loadClasspathWikiTemplate(name);
        if (!StringUtils.hasText(body)) {
            throw new BizException("未找到输出参考模板：" + name + "（Wiki 与 classpath 种子均无）");
        }
        return createMarkdownFile(tplRoot.getId(), name, body);
    }

    private String loadClasspathWikiTemplate(String fileName) {
        String resource = WIKI_TEMPLATE_CLASSPATH + fileName;
        try (var in = WikiFsService.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取 classpath 模板失败 {}: {}", resource, e.getMessage());
            return null;
        }
    }

    /**
     * 自 gen_prd 起发布/输入根：`{产品}/{计划版本号}/{需求编号}`。
     */
    @Transactional
    public Long ensureVersionRequirementPublishDir(
            SysProduct product, String planVersion, ReqRequirement requirement) {
        if (product == null || product.getWikiNodeId() == null || requirement == null
                || !StringUtils.hasText(requirement.getReqNo())) {
            throw new BizException("产品 Wiki 或需求编号无效，无法解析版本需求目录");
        }
        if (!StringUtils.hasText(planVersion)) {
            throw new BizException("缺少计划版本号，无法解析发布目录");
        }
        Long verRoot = ensureChildDir(product.getWikiNodeId(), planVersion.trim());
        Long reqDir = ensureChildDir(verRoot, requirement.getReqNo().trim());
        // 首次进入版本目录时，把原始需求.md 从初审目录迁入（若尚无）
        if (findChildFile(reqDir, DEFAULT_REQ_MARKDOWN_NAME) == null) {
            Long rawDir = ensureRawRequirementPublishDir(product, requirement);
            SysWikiNode rawFile = findChildFile(rawDir, DEFAULT_REQ_MARKDOWN_NAME);
            if (rawFile != null) {
                try {
                    String body = Files.readString(resolveNodePath(rawFile), StandardCharsets.UTF_8);
                    upsertMarkdownFile(reqDir, DEFAULT_REQ_MARKDOWN_NAME, body);
                } catch (Exception e) {
                    log.warn("复制原始需求到版本目录失败: {}", e.getMessage());
                }
            }
        }
        return reqDir;
    }

    private void ensureRawRequirementFile(SysProduct product, ReqRequirement requirement, Long rawReqDir) {
        if (findChildFile(rawReqDir, DEFAULT_REQ_MARKDOWN_NAME) != null) {
            return;
        }
        // 兼容旧布局：{产品}/{需求编号}/原始需求.md
        SysWikiNode legacyDir = findChildDirectory(product.getWikiNodeId(), requirement.getReqNo().trim());
        if (legacyDir != null) {
            SysWikiNode legacyFile = findChildFile(legacyDir.getId(), DEFAULT_REQ_MARKDOWN_NAME);
            if (legacyFile != null) {
                try {
                    String body = Files.readString(resolveNodePath(legacyFile), StandardCharsets.UTF_8);
                    upsertMarkdownFile(rawReqDir, DEFAULT_REQ_MARKDOWN_NAME, body);
                    return;
                } catch (Exception e) {
                    log.warn("从旧路径回填原始需求失败: {}", e.getMessage());
                }
            }
        }
        createMarkdownFile(rawReqDir, DEFAULT_REQ_MARKDOWN_NAME, buildRequirementMarkdown(requirement));
    }

    /** 按相对路径段确保目录：如 归档/需求 → 相对 product 根 */
    @Transactional
    public Long ensureDirByRelativePath(Long rootDirId, String relativePath) {
        if (rootDirId == null || !StringUtils.hasText(relativePath)) {
            return rootDirId;
        }
        Long cur = rootDirId;
        for (String part : relativePath.replace('\\', '/').split("/")) {
            if (!StringUtils.hasText(part) || ".".equals(part)) {
                continue;
            }
            cur = ensureChildDir(cur, part.trim());
        }
        return cur;
    }

    /** 查找上下文目录（不自动创建）；relative 相对产品 Wiki 根 */
    public SysWikiNode findDirByRelativePath(Long rootDirId, String relativePath) {
        if (rootDirId == null || !StringUtils.hasText(relativePath)) {
            return rootDirId == null ? null : wikiNodeMapper.selectById(rootDirId);
        }
        Long cur = rootDirId;
        SysWikiNode node = null;
        for (String part : relativePath.replace('\\', '/').split("/")) {
            if (!StringUtils.hasText(part) || ".".equals(part)) {
                continue;
            }
            node = findChildDirectory(cur, part.trim());
            if (node == null) {
                return null;
            }
            cur = node.getId();
        }
        return node;
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

    /** 允许 .md / .html / .htm / .json；其它扩展名拒绝；无扩展名默认 .md */
    private String normalizeTextFileName(String displayName) {
        if (!StringUtils.hasText(displayName)) {
            throw new BizException("请输入名称");
        }
        String name = displayName.trim();
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new BizException("名称非法");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md")
                || lower.endsWith(".html")
                || lower.endsWith(".htm")
                || lower.endsWith(".json")) {
            return name;
        }
        if (name.contains(".")) {
            throw new BizException("仅支持 .md / .html / .json 文件：" + name);
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

    private String buildIssueMarkdown(IssIssue issue) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 问题单\n\n");
        sb.append("- 问题单号：").append(nullToEmpty(issue.getIssueNo())).append('\n');
        sb.append("- 状态：").append(nullToEmpty(issue.getStatus())).append('\n');
        if (issue.getRequirementId() != null) {
            sb.append("- 关联需求 ID：").append(issue.getRequirementId()).append('\n');
        }
        sb.append('\n');
        sb.append("> 截图按全文出现顺序标记为【附图N】（可带｜说明）；人看下方原图，模型看同号多模态附图。\n\n");
        sb.append("---\n\n");
        int[] imgSeq = {1};
        sb.append("## 问题描述\n\n");
        appendSectionBody(sb, issueMarkdownImages.annotateForWiki(issue.getDescription(), imgSeq));
        sb.append("\n## 实现步骤\n\n");
        appendSectionBody(sb, issueMarkdownImages.annotateForWiki(issue.getSteps(), imgSeq));
        sb.append("\n## 预期结果\n\n");
        appendSectionBody(sb, issueMarkdownImages.annotateForWiki(issue.getExpectedResult(), imgSeq));
        sb.append("\n## 已有解决方案摘要\n\n");
        appendSectionBody(sb, issueMarkdownImages.annotateForWiki(issue.getSolution(), imgSeq));
        return sb.toString();
    }

    private void appendSectionBody(StringBuilder sb, String body) {
        if (StringUtils.hasText(body)) {
            sb.append(body.trim());
            if (!body.trim().endsWith("\n")) {
                sb.append('\n');
            }
        } else {
            sb.append("（无）\n");
        }
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

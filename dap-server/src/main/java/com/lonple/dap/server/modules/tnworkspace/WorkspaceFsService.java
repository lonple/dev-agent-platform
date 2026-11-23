package com.lonple.dap.server.modules.tnworkspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.config.DataRootProperties;
import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import com.lonple.dap.server.modules.sysuser.mapper.SysUserMapper;
import com.lonple.dap.server.modules.tenant.entity.SysTenant;
import com.lonple.dap.server.modules.tenant.mapper.TenantMapper;
import com.lonple.dap.server.modules.tnworkspace.dto.WorkspaceFileContentVO;
import com.lonple.dap.server.modules.tnworkspace.dto.WorkspaceNodeVO;
import com.lonple.dap.server.modules.tnworkspace.entity.SysWorkspace;
import com.lonple.dap.server.modules.tnworkspace.entity.SysWorkspaceNode;
import com.lonple.dap.server.modules.tnworkspace.mapper.SysWorkspaceMapper;
import com.lonple.dap.server.modules.tnworkspace.mapper.SysWorkspaceNodeMapper;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
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
public class WorkspaceFsService {

    /** Agent 可读元数据目录（不入库、不出现在工作台文件树） */
    public static final String DAP_META_DIR = ".dap";
    public static final String MANIFEST_FILE = "manifest.json";

    private final DataRootProperties dataRootProperties;
    private final SysWorkspaceMapper workspaceMapper;
    private final SysWorkspaceNodeMapper nodeMapper;
    private final TenantMapper tenantMapper;
    private final SysUserMapper userMapper;
    private final ObjectMapper objectMapper;

    /** 按工作空间串行化 manifest 写入，避免并发写坏文件 */
    private final ConcurrentHashMap<Long, Object> manifestLocks = new ConcurrentHashMap<>();

    public Path resolveDataRoot() {
        Path root = Paths.get(dataRootProperties.getRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new BizException("无法创建数据根目录: " + root);
        }
        return root;
    }

    /**
     * 共享：data/{租户}/share/{workDir}
     * 个人：data/{租户}/{账号}/{workDir}
     */
    public Path resolveWorkspacePath(SysWorkspace workspace) {
        SysTenant tenant = tenantMapper.selectById(workspace.getTenantId());
        if (tenant == null || !StringUtils.hasText(tenant.getCode())) {
            throw new BizException("租户不存在");
        }
        String workDir = workspace.getWorkDir() == null ? "" : workspace.getWorkDir().replace('\\', '/').trim();
        if (!StringUtils.hasText(workDir) || workDir.contains("/") || workDir.contains("..")) {
            throw new BizException("工作空间路径非法");
        }
        Path path = resolveDataRoot().resolve(tenant.getCode());
        WorkspaceSpaceType type = WorkspaceSpaceType.fromCode(workspace.getSpaceType());
        if (type == WorkspaceSpaceType.SHARED) {
            path = path.resolve("share").resolve(workDir);
        } else {
            if (workspace.getOwnerUserId() == null) {
                throw new BizException("个人工作空间缺少所有者");
            }
            SysUser owner = userMapper.selectById(workspace.getOwnerUserId());
            if (owner == null || !StringUtils.hasText(owner.getUsername())) {
                throw new BizException("个人工作空间所有者无效");
            }
            path = path.resolve(owner.getUsername()).resolve(workDir);
        }
        path = path.normalize();
        if (!path.startsWith(resolveDataRoot())) {
            throw new BizException("工作空间路径非法");
        }
        return path;
    }

    public void ensureWorkspaceDir(SysWorkspace workspace) {
        Path path = resolveWorkspacePath(workspace);
        try {
            Files.createDirectories(path);
            Files.createDirectories(path.resolve(DAP_META_DIR));
        } catch (IOException e) {
            throw new BizException("创建工作空间目录失败: " + path);
        }
    }

    /**
     * 重写工作空间根目录下 {@code .dap/manifest.json}，供 Agent 按显示名检索并映射到磁盘路径。
     * <ul>
     *   <li>事务内调用时延后到 {@code afterCommit}，避免回滚后磁盘与库不一致</li>
     *   <li>同一事务内同一工作空间只调度一次</li>
     *   <li>按工作空间加锁 + 临时文件原子替换，避免并发写半截 JSON</li>
     * </ul>
     */
    public void rewriteManifest(SysWorkspace workspace) {
        if (workspace == null || workspace.getId() == null) {
            return;
        }
        Long workspaceId = workspace.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            String resourceKey = "dap.manifest.rewrite." + workspaceId;
            if (TransactionSynchronizationManager.hasResource(resourceKey)) {
                return;
            }
            TransactionSynchronizationManager.bindResource(resourceKey, Boolean.TRUE);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeManifestAtomic(workspaceId);
                }
            });
            return;
        }
        writeManifestAtomic(workspaceId);
    }

    private void writeManifestAtomic(Long workspaceId) {
        Object lock = manifestLocks.computeIfAbsent(workspaceId, id -> new Object());
        synchronized (lock) {
            try {
                SysWorkspace workspace = workspaceMapper.selectById(workspaceId);
                if (workspace == null) {
                    return;
                }
                Path root = resolveWorkspacePath(workspace);
                Path metaDir = root.resolve(DAP_META_DIR);
                Files.createDirectories(metaDir);

                List<SysWorkspaceNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<SysWorkspaceNode>()
                        .eq(SysWorkspaceNode::getWorkspaceId, workspaceId)
                        .orderByAsc(SysWorkspaceNode::getId));
                Map<Long, SysWorkspaceNode> byId = new HashMap<>();
                for (SysWorkspaceNode n : nodes) {
                    byId.put(n.getId(), n);
                }

                List<Map<String, Object>> entries = new ArrayList<>(nodes.size());
                for (SysWorkspaceNode n : nodes) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", n.getId());
                    row.put("parentId", n.getParentId());
                    row.put("type", n.getNodeType());
                    row.put("name", n.getDisplayName());
                    row.put("path", buildLogicalPath(n, byId, true));
                    row.put("storage", n.getStorageName());
                    row.put("storagePath", buildLogicalPath(n, byId, false));
                    if (n.getRequirementId() != null) {
                        row.put("requirementId", n.getRequirementId());
                    }
                    entries.add(row);
                }

                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("version", 1);
                doc.put("workspaceId", workspaceId);
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
                log.warn("写入工作空间清单失败: workspaceId={}", workspaceId, e);
            }
        }
    }

    /** display=true 用 displayName 拼逻辑路径；false 用 storageName 拼相对磁盘路径 */
    private String buildLogicalPath(SysWorkspaceNode node, Map<Long, SysWorkspaceNode> byId, boolean display) {
        List<String> parts = new ArrayList<>();
        SysWorkspaceNode cur = node;
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

    public void deleteWorkspaceDir(SysWorkspace workspace) {
        Path path = resolveWorkspacePath(workspace);
        try {
            if (Files.exists(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort
                        }
                    });
                }
            }
        } catch (IOException e) {
            log.warn("删除工作空间目录失败: {}", path, e);
        }
    }

    /** 清理工作空间下全部文件节点记录与磁盘目录（会话由 workspace FK CASCADE） */
    @Transactional
    public void purgeWorkspace(SysWorkspace workspace) {
        if (workspace == null || workspace.getId() == null) {
            return;
        }
        List<SysWorkspaceNode> roots = nodeMapper.selectList(new LambdaQueryWrapper<SysWorkspaceNode>()
                .eq(SysWorkspaceNode::getWorkspaceId, workspace.getId())
                .isNull(SysWorkspaceNode::getParentId));
        for (SysWorkspaceNode root : roots) {
            deleteNodeRecursive(workspace, root);
        }
        // 兜底删残留节点
        nodeMapper.delete(new LambdaQueryWrapper<SysWorkspaceNode>()
                .eq(SysWorkspaceNode::getWorkspaceId, workspace.getId()));
        deleteWorkspaceDir(workspace);
    }

    public void deleteTenantDataDir(String tenantCode) {
        if (!StringUtils.hasText(tenantCode)) {
            return;
        }
        Path path = resolveDataRoot().resolve(tenantCode).normalize();
        if (!path.startsWith(resolveDataRoot())) {
            return;
        }
        try {
            if (Files.exists(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        } catch (IOException e) {
            log.warn("删除租户数据目录失败: {}", path, e);
        }
    }

    public List<WorkspaceNodeVO> listTree(Long workspaceId) {
        SysWorkspace ws = requireOwnedPersonal(workspaceId);
        ensureWorkspaceDir(ws);
        List<SysWorkspaceNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<SysWorkspaceNode>()
                .eq(SysWorkspaceNode::getWorkspaceId, ws.getId())
                .and(w -> w.isNull(SysWorkspaceNode::getHidden).or().eq(SysWorkspaceNode::getHidden, false))
                .orderByAsc(SysWorkspaceNode::getNodeType)
                .orderByAsc(SysWorkspaceNode::getDisplayName));
        return buildTree(nodes);
    }

    public WorkspaceFileContentVO readFile(Long workspaceId, Long nodeId) {
        SysWorkspace ws = requireOwnedPersonal(workspaceId);
        return readFileInternal(ws, nodeId);
    }

    /**
     * 流水线等跨模块读个人空间文件：校验租户即可（不要求当前用户是空间所有者）。
     * 仍拒绝跨租户访问。
     */
    public WorkspaceFileContentVO readFileForTenant(Long workspaceId, Long nodeId) {
        Long tenantId = TenantSupport.requireTenantId();
        SysWorkspace ws = workspaceMapper.selectById(workspaceId);
        if (ws == null || !Objects.equals(ws.getTenantId(), tenantId)) {
            throw new BizException(404, "工作空间不存在");
        }
        return readFileInternal(ws, nodeId);
    }

    private WorkspaceFileContentVO readFileInternal(SysWorkspace ws, Long nodeId) {
        SysWorkspaceNode node = requireNode(ws.getId(), nodeId);
        if (!WorkspaceNodeType.FILE.name().equals(node.getNodeType())) {
            throw new BizException("只能打开文件");
        }
        Path filePath = resolveNodePath(ws, node);
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            WorkspaceFileContentVO vo = new WorkspaceFileContentVO();
            vo.setId(node.getId());
            vo.setDisplayName(node.getDisplayName());
            vo.setContent(content);
            vo.setRequirementId(node.getRequirementId());
            return vo;
        } catch (IOException e) {
            throw new BizException("读取文件失败");
        }
    }

    /** 查找子目录（不存在返回 null） */
    public SysWorkspaceNode findChildDirectory(Long workspaceId, Long parentId, String displayName) {
        if (!StringUtils.hasText(displayName)) {
            return null;
        }
        LambdaQueryWrapper<SysWorkspaceNode> qw = new LambdaQueryWrapper<SysWorkspaceNode>()
                .eq(SysWorkspaceNode::getWorkspaceId, workspaceId)
                .eq(SysWorkspaceNode::getNodeType, WorkspaceNodeType.DIR.name())
                .eq(SysWorkspaceNode::getDisplayName, displayName.trim())
                .last("LIMIT 1");
        if (parentId == null) {
            qw.isNull(SysWorkspaceNode::getParentId);
        } else {
            qw.eq(SysWorkspaceNode::getParentId, parentId);
        }
        return nodeMapper.selectOne(qw);
    }

    /** 确保子目录存在 */
    @Transactional
    public Long ensureDirectory(Long workspaceId, Long parentId, String displayName) {
        SysWorkspaceNode existing = findChildDirectory(workspaceId, parentId, displayName);
        if (existing != null) {
            return existing.getId();
        }
        return createDirectory(workspaceId, parentId, displayName);
    }

    @Transactional
    public Long createDirectory(Long workspaceId, Long parentId, String displayName) {
        SysWorkspace ws = requireOwnedPersonal(workspaceId);
        ensureWorkspaceDir(ws);
        String name = normalizeDisplayName(displayName, false);
        Long parent = resolveParentDirId(ws.getId(), parentId);
        SysWorkspaceNode existed = findChildDirectory(workspaceId, parent, name);
        if (existed != null) {
            return existed.getId();
        }
        assertDisplayUnique(ws.getId(), parent, name);

        LocalDateTime now = LocalDateTime.now();
        SysWorkspaceNode dir = new SysWorkspaceNode();
        dir.setTenantId(ws.getTenantId());
        dir.setWorkspaceId(ws.getId());
        dir.setParentId(parent);
        dir.setNodeType(WorkspaceNodeType.DIR.name());
        dir.setDisplayName(name);
        dir.setStorageName(randomStorageName(null));
        dir.setCreatedAt(now);
        dir.setUpdatedAt(now);
        nodeMapper.insert(dir);

        Path path = parent == null
                ? resolveWorkspacePath(ws).resolve(dir.getStorageName())
                : resolveNodePath(ws, requireNode(ws.getId(), parent)).resolve(dir.getStorageName());
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new BizException("创建目录失败");
        }
        rewriteManifest(ws);
        return dir.getId();
    }

    /**
     * 创建文本交付文件：允许 .md / .html（.htm）/ .json；无扩展名时默认补 .md。
     * 工作台「新建」UI 仍只建 md；html/json 来自上传或 Agent write_output。
     */
    @Transactional
    public Long createMarkdownFile(Long workspaceId, Long parentId, String displayName, String content) {
        SysWorkspace ws = requireOwnedPersonal(workspaceId);
        ensureWorkspaceDir(ws);
        String name = normalizeTextFileName(displayName);
        Long parent = resolveParentDirId(ws.getId(), parentId);
        assertDisplayUnique(ws.getId(), parent, name);

        LocalDateTime now = LocalDateTime.now();
        String storageFile = randomStorageName(extOf(name));
        SysWorkspaceNode file = new SysWorkspaceNode();
        file.setTenantId(ws.getTenantId());
        file.setWorkspaceId(ws.getId());
        file.setParentId(parent);
        file.setNodeType(WorkspaceNodeType.FILE.name());
        file.setDisplayName(name);
        file.setStorageName(storageFile);
        file.setHidden(false);
        file.setCreatedAt(now);
        file.setUpdatedAt(now);
        nodeMapper.insert(file);

        Path parentPath = parent == null
                ? resolveWorkspacePath(ws)
                : resolveNodePath(ws, requireNode(ws.getId(), parent));
        String body = content == null ? "" : content;
        try {
            Files.createDirectories(parentPath);
            Files.writeString(parentPath.resolve(storageFile), body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException("创建文件失败");
        }
        rewriteManifest(ws);
        return file.getId();
    }

    /** 查找同目录同名文件（含 hidden） */
    public SysWorkspaceNode findChildFile(Long workspaceId, Long parentId, String displayName) {
        if (!StringUtils.hasText(displayName)) {
            return null;
        }
        String name = normalizeTextFileName(displayName);
        LambdaQueryWrapper<SysWorkspaceNode> qw = new LambdaQueryWrapper<SysWorkspaceNode>()
                .eq(SysWorkspaceNode::getWorkspaceId, workspaceId)
                .eq(SysWorkspaceNode::getNodeType, WorkspaceNodeType.FILE.name())
                .eq(SysWorkspaceNode::getDisplayName, name)
                .last("LIMIT 1");
        if (parentId == null) {
            qw.isNull(SysWorkspaceNode::getParentId);
        } else {
            qw.eq(SysWorkspaceNode::getParentId, parentId);
        }
        return nodeMapper.selectOne(qw);
    }

    /**
     * 创建或覆盖同名文本文件；{@code hidden=true} 时不出现在个人空间目录树。
     * @return 文件节点 ID
     */
    @Transactional
    public Long upsertTextFile(Long workspaceId, Long parentId, String displayName, String content, boolean hidden) {
        SysWorkspace ws = requireOwnedPersonal(workspaceId);
        ensureWorkspaceDir(ws);
        String name = normalizeTextFileName(displayName);
        Long parent = resolveParentDirId(ws.getId(), parentId);
        SysWorkspaceNode existing = findChildFile(ws.getId(), parent, name);
        if (existing != null) {
            updateFileContent(ws.getId(), existing.getId(), content);
            if (!Objects.equals(Boolean.TRUE.equals(existing.getHidden()), hidden)) {
                existing.setHidden(hidden);
                existing.setUpdatedAt(LocalDateTime.now());
                nodeMapper.updateById(existing);
            }
            return existing.getId();
        }
        LocalDateTime now = LocalDateTime.now();
        String storageFile = randomStorageName(extOf(name));
        SysWorkspaceNode file = new SysWorkspaceNode();
        file.setTenantId(ws.getTenantId());
        file.setWorkspaceId(ws.getId());
        file.setParentId(parent);
        file.setNodeType(WorkspaceNodeType.FILE.name());
        file.setDisplayName(name);
        file.setStorageName(storageFile);
        file.setHidden(hidden);
        file.setCreatedAt(now);
        file.setUpdatedAt(now);
        nodeMapper.insert(file);

        Path parentPath = parent == null
                ? resolveWorkspacePath(ws)
                : resolveNodePath(ws, requireNode(ws.getId(), parent));
        String body = content == null ? "" : content;
        try {
            Files.createDirectories(parentPath);
            Files.writeString(parentPath.resolve(storageFile), body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException("创建文件失败");
        }
        rewriteManifest(ws);
        return file.getId();
    }

    /** 设置节点 hidden；目录树仅展示 hidden=false */
    @Transactional
    public void setHidden(Long workspaceId, Long nodeId, boolean hidden) {
        SysWorkspaceNode node = requireNode(workspaceId, nodeId);
        if (Objects.equals(Boolean.TRUE.equals(node.getHidden()), hidden)) {
            return;
        }
        node.setHidden(hidden);
        node.setUpdatedAt(LocalDateTime.now());
        nodeMapper.updateById(node);
    }

    /** 将指定父目录下若干文件名取消隐藏（不存在则跳过） */
    @Transactional
    public void unhideFiles(Long workspaceId, Long parentId, List<String> displayNames) {
        if (displayNames == null || displayNames.isEmpty()) {
            return;
        }
        for (String name : displayNames) {
            if (!StringUtils.hasText(name)) {
                continue;
            }
            SysWorkspaceNode f = findChildFile(workspaceId, parentId, name.trim());
            if (f != null && Boolean.TRUE.equals(f.getHidden())) {
                setHidden(workspaceId, f.getId(), false);
            }
        }
    }

    @Transactional
    public void updateFileContent(Long workspaceId, Long nodeId, String content) {
        SysWorkspace ws = requireOwnedPersonal(workspaceId);
        SysWorkspaceNode node = requireNode(ws.getId(), nodeId);
        if (!WorkspaceNodeType.FILE.name().equals(node.getNodeType())) {
            throw new BizException("只能编辑文件");
        }
        try {
            Files.writeString(resolveNodePath(ws, node), content == null ? "" : content, StandardCharsets.UTF_8);
            node.setUpdatedAt(LocalDateTime.now());
            nodeMapper.updateById(node);
        } catch (IOException e) {
            throw new BizException("保存文件失败");
        }
    }

    @Transactional
    public void deleteNode(Long workspaceId, Long nodeId) {
        SysWorkspace ws = requireOwnedPersonal(workspaceId);
        SysWorkspaceNode node = requireNode(ws.getId(), nodeId);
        deleteNodeRecursive(ws, node);
        rewriteManifest(ws);
    }

    private void deleteNodeRecursive(SysWorkspace ws, SysWorkspaceNode node) {
        if (WorkspaceNodeType.DIR.name().equals(node.getNodeType())) {
            List<SysWorkspaceNode> children = nodeMapper.selectList(new LambdaQueryWrapper<SysWorkspaceNode>()
                    .eq(SysWorkspaceNode::getWorkspaceId, ws.getId())
                    .eq(SysWorkspaceNode::getParentId, node.getId()));
            for (SysWorkspaceNode child : children) {
                deleteNodeRecursive(ws, child);
            }
        }
        Path path = resolveNodePath(ws, node);
        try {
            if (Files.exists(path)) {
                if (Files.isDirectory(path)) {
                    try (var walk = Files.walk(path)) {
                        walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                                // best-effort
                            }
                        });
                    }
                } else {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException e) {
            log.warn("删除磁盘文件失败: {} -> {}", node.getDisplayName(), path, e);
            // 磁盘失败不阻断 DB 清理，避免残留引用导致无法再删
        }
        nodeMapper.deleteById(node.getId());
    }

    private Long resolveParentDirId(Long workspaceId, Long parentId) {
        if (parentId == null) {
            return null;
        }
        SysWorkspaceNode parent = requireNode(workspaceId, parentId);
        if (!WorkspaceNodeType.DIR.name().equals(parent.getNodeType())) {
            throw new BizException("父节点必须是目录");
        }
        return parent.getId();
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

    private String extOf(String displayName) {
        int i = displayName.lastIndexOf('.');
        if (i > 0 && i < displayName.length() - 1) {
            return displayName.substring(i + 1).toLowerCase(Locale.ROOT);
        }
        return "md";
    }

    /** 供 Wiki 发布等跨模块场景解析个人空间节点绝对路径 */
    public Path resolveNodeAbsolutePath(SysWorkspace workspace, SysWorkspaceNode node) {
        return resolveNodePath(workspace, node);
    }

    private Path resolveNodePath(SysWorkspace workspace, SysWorkspaceNode node) {
        List<SysWorkspaceNode> chain = new ArrayList<>();
        SysWorkspaceNode cur = node;
        int guard = 0;
        while (cur != null && guard++ < 64) {
            chain.add(0, cur);
            if (cur.getParentId() == null) {
                break;
            }
            cur = nodeMapper.selectById(cur.getParentId());
        }
        Path path = resolveWorkspacePath(workspace);
        for (SysWorkspaceNode n : chain) {
            path = path.resolve(n.getStorageName());
        }
        Path normalized = path.normalize();
        if (!normalized.startsWith(resolveWorkspacePath(workspace))) {
            throw new BizException("文件路径非法");
        }
        return normalized;
    }

    private List<WorkspaceNodeVO> buildTree(List<SysWorkspaceNode> nodes) {
        Map<Long, WorkspaceNodeVO> map = new HashMap<>();
        for (SysWorkspaceNode n : nodes) {
            WorkspaceNodeVO vo = new WorkspaceNodeVO();
            vo.setId(n.getId());
            vo.setParentId(n.getParentId());
            vo.setNodeType(n.getNodeType());
            vo.setDisplayName(n.getDisplayName());
            vo.setRequirementId(n.getRequirementId());
            map.put(n.getId(), vo);
        }
        List<WorkspaceNodeVO> roots = new ArrayList<>();
        for (SysWorkspaceNode n : nodes) {
            WorkspaceNodeVO vo = map.get(n.getId());
            if (n.getParentId() == null) {
                roots.add(vo);
            } else {
                WorkspaceNodeVO parent = map.get(n.getParentId());
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

    private void sortTree(List<WorkspaceNodeVO> list) {
        list.sort(Comparator
                .comparing((WorkspaceNodeVO v) -> !"DIR".equals(v.getNodeType()))
                .thenComparing(WorkspaceNodeVO::getDisplayName, String.CASE_INSENSITIVE_ORDER));
        for (WorkspaceNodeVO v : list) {
            if (v.getChildren() != null && !v.getChildren().isEmpty()) {
                sortTree(v.getChildren());
            }
        }
    }

    private SysWorkspace requireOwnedPersonal(Long workspaceId) {
        Long tenantId = TenantSupport.requireTenantId();
        Long userId = TenantSupport.requireUserId();
        SysWorkspace ws = workspaceMapper.selectById(workspaceId);
        if (ws == null || !Objects.equals(ws.getTenantId(), tenantId)) {
            throw new BizException(404, "工作空间不存在");
        }
        if (WorkspaceSpaceType.fromCode(ws.getSpaceType()) != WorkspaceSpaceType.PERSONAL) {
            throw new BizException("仅支持个人工作空间");
        }
        if (!Objects.equals(ws.getOwnerUserId(), userId)) {
            throw new BizException(403, "只能操作自己的个人工作空间");
        }
        return ws;
    }

    private SysWorkspaceNode requireNode(Long workspaceId, Long nodeId) {
        SysWorkspaceNode node = nodeMapper.selectById(nodeId);
        if (node == null || !Objects.equals(node.getWorkspaceId(), workspaceId)) {
            throw new BizException(404, "节点不存在");
        }
        return node;
    }

    private void assertDisplayUnique(Long workspaceId, Long parentId, String displayName) {
        LambdaQueryWrapper<SysWorkspaceNode> qw = new LambdaQueryWrapper<SysWorkspaceNode>()
                .eq(SysWorkspaceNode::getWorkspaceId, workspaceId)
                .eq(SysWorkspaceNode::getDisplayName, displayName);
        if (parentId == null) {
            qw.isNull(SysWorkspaceNode::getParentId);
        } else {
            qw.eq(SysWorkspaceNode::getParentId, parentId);
        }
        Long count = nodeMapper.selectCount(qw);
        if (count != null && count > 0) {
            throw new BizException("目录或文件显示名已存在: " + displayName);
        }
    }

    private String randomStorageName(String ext) {
        String id = UUID.randomUUID().toString().replace("-", "");
        return StringUtils.hasText(ext) ? id + "." + ext : id;
    }
}

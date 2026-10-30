package com.lonple.dap.server.modules.wiki;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.tnworkspace.WorkspaceNodeType;
import com.lonple.dap.server.modules.wiki.entity.SysWikiNode;
import com.lonple.dap.server.modules.wiki.mapper.SysWikiNodeMapper;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 确保租户 Wiki 下存在「流水线模板」目录及标准模板文件，供流水线步骤绑定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineWikiTemplateService {

    public static final String TEMPLATE_ROOT = "流水线模板";

    private static final Map<String, String> RESOURCE_BY_FILE = new LinkedHashMap<>();

    static {
        RESOURCE_BY_FILE.put("模板-评审-原始需求初审.md", "pipeline-templates/模板-评审-原始需求初审.md");
        RESOURCE_BY_FILE.put("模板-评审-PRD架构师初审.md", "pipeline-templates/模板-评审-PRD架构师初审.md");
        RESOURCE_BY_FILE.put("模板-评审-需求分析.md", "pipeline-templates/模板-评审-需求分析.md");
        RESOURCE_BY_FILE.put("模板-评审-架构设计.md", "pipeline-templates/模板-评审-架构设计.md");
        RESOURCE_BY_FILE.put("模板-评审-UI设计.md", "pipeline-templates/模板-评审-UI设计.md");
        RESOURCE_BY_FILE.put("模板-评审-详细设计.md", "pipeline-templates/模板-评审-详细设计.md");
        RESOURCE_BY_FILE.put("模板-评审-测试用例.md", "pipeline-templates/模板-评审-测试用例.md");
        RESOURCE_BY_FILE.put("模板-评审-集成测试.md", "pipeline-templates/模板-评审-集成测试.md");
        RESOURCE_BY_FILE.put("模板-评审书.md", "pipeline-templates/模板-评审书.md");
        RESOURCE_BY_FILE.put("模板-需求分析PRD.md", "pipeline-templates/模板-需求分析PRD.md");
        RESOURCE_BY_FILE.put("模板-架构设计.md", "pipeline-templates/模板-架构设计.md");
        RESOURCE_BY_FILE.put("模板-UI设计规范.md", "pipeline-templates/模板-UI设计规范.md");
        RESOURCE_BY_FILE.put("模板-详细设计.md", "pipeline-templates/模板-详细设计.md");
        RESOURCE_BY_FILE.put("模板-测试用例.md", "pipeline-templates/模板-测试用例.md");
        RESOURCE_BY_FILE.put("模板-开发交付摘要.md", "pipeline-templates/模板-开发交付摘要.md");
        RESOURCE_BY_FILE.put("模板-集成测试执行摘要.md", "pipeline-templates/模板-集成测试执行摘要.md");
        RESOURCE_BY_FILE.put("模板-原始需求.md", "pipeline-templates/模板-原始需求.md");
    }

    private final WikiFsService wikiFsService;
    private final SysWikiNodeMapper wikiNodeMapper;

    /** 解析「流水线模板/xxx.md」；目录或文件缺失时按 classpath 种子创建 */
    @Transactional
    public Long resolveTemplateFileId(String templateWikiPath) {
        if (!StringUtils.hasText(templateWikiPath)) {
            return null;
        }
        String path = templateWikiPath.trim().replace('\\', '/');
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] parts = path.split("/");
        if (parts.length < 2) {
            throw new BizException("模板路径无效：" + templateWikiPath);
        }
        Long tenantId = TenantSupport.requireTenantId();
        wikiFsService.ensureWikiRoot(tenantId);

        Long parentId = null;
        for (int i = 0; i < parts.length - 1; i++) {
            parentId = ensureDir(tenantId, parentId, parts[i]);
        }
        String fileName = parts[parts.length - 1];
        if (!fileName.toLowerCase().endsWith(".md")) {
            fileName = fileName + ".md";
        }
        SysWikiNode existing = wikiFsService.findChildFile(parentId, fileName);
        if (existing != null) {
            return existing.getId();
        }
        String content = loadSeedContent(fileName);
        return wikiFsService.createMarkdownFile(parentId, fileName, content);
    }

    private Long ensureDir(Long tenantId, Long parentId, String displayName) {
        LambdaQueryWrapper<SysWikiNode> q = new LambdaQueryWrapper<SysWikiNode>()
                .eq(SysWikiNode::getTenantId, tenantId)
                .eq(SysWikiNode::getNodeType, WorkspaceNodeType.DIR.name())
                .eq(SysWikiNode::getDisplayName, displayName.trim())
                .last("LIMIT 1");
        if (parentId == null) {
            q.isNull(SysWikiNode::getParentId);
        } else {
            q.eq(SysWikiNode::getParentId, parentId);
        }
        SysWikiNode existing = wikiNodeMapper.selectOne(q);
        if (existing != null) {
            return existing.getId();
        }
        return wikiFsService.createDirectory(parentId, displayName.trim());
    }

    private String loadSeedContent(String fileName) {
        String resource = RESOURCE_BY_FILE.get(fileName);
        if (resource != null) {
            try {
                ClassPathResource cpr = new ClassPathResource(resource);
                if (cpr.exists()) {
                    return cpr.getContentAsString(StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                log.warn("load pipeline template seed {} failed: {}", resource, e.getMessage());
            }
        }
        return "# " + fileName.replace(".md", "") + "\n\n【请按此结构填写】\n";
    }
}

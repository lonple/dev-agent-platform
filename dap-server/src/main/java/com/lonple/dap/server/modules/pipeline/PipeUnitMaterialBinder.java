package com.lonple.dap.server.modules.pipeline;

import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.chat.dto.WorkspaceSessionCreateRequest;
import com.lonple.dap.server.modules.issue.entity.IssIssue;
import com.lonple.dap.server.modules.pipeline.entity.PipeInstance;
import com.lonple.dap.server.modules.pipeline.entity.PipeUnitInstance;
import com.lonple.dap.server.modules.product.ProductService;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.product.entity.SysProductVersion;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirement;
import com.lonple.dap.server.modules.tnworkspace.WorkspaceFsService;
import com.lonple.dap.server.modules.wiki.WikiFsService;
import com.lonple.dap.server.modules.wiki.entity.SysWikiNode;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 按《流水线节点与Agent配置》把单元材料解析并写入 TASK 会话创建请求。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipeUnitMaterialBinder {

    private final WikiFsService wikiFsService;
    private final WorkspaceFsService workspaceFsService;
    private final ProductService productService;

    public record BindResult(
            PipeUnitMaterialSpec spec,
            Long publishDirId,
            String planVersion,
            List<String> boundInputNames,
            List<String> skippedOptionalInputs
    ) {}

    public BindResult bindToCreateRequest(
            WorkspaceSessionCreateRequest req,
            Long workspaceId,
            PipeInstance inst,
            PipeUnitInstance unit,
            ReqRequirement requirement) {
        SysProduct product = productService.requireOwned(requirement.getProductId());
        return bindToCreateRequest(req, workspaceId, inst, unit, new PipeSubject(product, requirement, null));
    }

    public BindResult bindToCreateRequest(
            WorkspaceSessionCreateRequest req,
            Long workspaceId,
            PipeInstance inst,
            PipeUnitInstance unit,
            PipeSubject subject) {
        PipeUnitMaterialSpec spec = PipeUnitMaterialCatalog.require(unit.getTaskTypeCode());
        SysProduct product = subject.product();
        if (product.getWikiNodeId() == null) {
            throw new BizException("产品未绑定 Wiki 根目录");
        }

        String planVersion = null;
        if (spec.publishRoot() == PipeUnitMaterialSpec.PublishRoot.VER) {
            if (!subject.isRequirement()) {
                throw new BizException("当前任务需要计划版本发布目录，仅支持需求流水");
            }
            planVersion = resolvePlanVersion(inst, subject.requirement(), product);
            if (!StringUtils.hasText(planVersion)) {
                throw new BizException("缺少计划版本号，无法创建任务会话（taskType=" + unit.getTaskTypeCode() + "）");
            }
        }

        Long publishDirId = ensurePublishDir(product, subject, spec, planVersion);
        if (spec.publishRoot() == PipeUnitMaterialSpec.PublishRoot.ISSUE && subject.isIssue()) {
            publishDirId = wikiFsService.ensureIssueTicketMarkdown(product, subject.issue());
        }
        Long outputDirId = ensureOutputDir(workspaceId, product, subject);
        req.setOutputDirNodeId(outputDirId);

        if (StringUtils.hasText(spec.templateFileName())) {
            req.setOutputTemplateWikiNodeId(wikiFsService.ensureBuiltinTemplateFile(spec.templateFileName()));
        }

        Long standardsId = resolveStandardsNode(spec.standardsRefs());
        if (standardsId != null) {
            req.setStandardsWikiNodeId(standardsId);
        }

        List<WorkspaceSessionCreateRequest.SessionInputRef> inputs = new ArrayList<>();
        List<String> bound = new ArrayList<>();
        List<String> skippedOptional = new ArrayList<>();
        for (String name : spec.requiredInputFiles()) {
            SysWikiNode file = wikiFsService.findChildFile(publishDirId, name);
            if (file == null) {
                throw new BizException("缺少必填输入文件：" + name + "（发布目录未找到）");
            }
            WorkspaceSessionCreateRequest.SessionInputRef ref = new WorkspaceSessionCreateRequest.SessionInputRef();
            ref.setWikiNodeId(file.getId());
            inputs.add(ref);
            bound.add(name);
        }
        for (String name : spec.optionalInputFiles()) {
            SysWikiNode file = wikiFsService.findChildFile(publishDirId, name);
            if (file == null) {
                skippedOptional.add(name);
                continue;
            }
            WorkspaceSessionCreateRequest.SessionInputRef ref = new WorkspaceSessionCreateRequest.SessionInputRef();
            ref.setWikiNodeId(file.getId());
            inputs.add(ref);
            bound.add(name);
        }
        req.setInputs(inputs);

        List<WorkspaceSessionCreateRequest.SessionContextRef> contexts = new ArrayList<>();
        Set<Long> seenCtx = new LinkedHashSet<>();
        List<String> contextTokens = new ArrayList<>();
        if (spec.publishRoot() == PipeUnitMaterialSpec.PublishRoot.VER) {
            contextTokens.add("$VER_REQ");
        }
        if (spec.publishRoot() == PipeUnitMaterialSpec.PublishRoot.ISSUE) {
            contextTokens.add("$ISSUE_DIR");
        }
        if (spec.contextRelDirs() != null) {
            for (String rel : spec.contextRelDirs()) {
                if (StringUtils.hasText(rel) && !"$VER_REQ".equalsIgnoreCase(rel.trim())
                        && !"$PUBLISH_DIR".equalsIgnoreCase(rel.trim())
                        && !"$ISSUE_DIR".equalsIgnoreCase(rel.trim())) {
                    contextTokens.add(rel.trim());
                }
            }
        }
        for (String token : contextTokens) {
            Long dirId;
            if ("$VER_REQ".equalsIgnoreCase(token) || "$PUBLISH_DIR".equalsIgnoreCase(token)
                    || "$ISSUE_DIR".equalsIgnoreCase(token)) {
                dirId = publishDirId;
                if (dirId == null) {
                    log.warn("pipe context {} skipped: publishDir unset product={} task={}",
                            token, product.getCode(), unit.getTaskTypeCode());
                    continue;
                }
            } else {
                dirId = wikiFsService.ensureDirByRelativePath(product.getWikiNodeId(), token);
            }
            SysWikiNode dir = dirId == null ? null : wikiFsService.requireNode(dirId);
            if (dir == null) {
                log.warn("pipe context ensure failed, skip: product={} rel={}", product.getCode(), token);
                continue;
            }
            if (!seenCtx.add(dir.getId())) {
                continue;
            }
            WorkspaceSessionCreateRequest.SessionContextRef ref = new WorkspaceSessionCreateRequest.SessionContextRef();
            ref.setWikiNodeId(dir.getId());
            contexts.add(ref);
        }

        // 问题单若关联需求：追加 产品/{版本}/{需求编号} 作可选上下文
        if (subject.isIssue() && subject.issue().getRequirementId() != null && subject.requirement() != null) {
            try {
                String ver = StringUtils.hasText(subject.requirement().getReleaseVersion())
                        ? subject.requirement().getReleaseVersion().trim()
                        : (StringUtils.hasText(subject.requirement().getProductVersion())
                                ? subject.requirement().getProductVersion().trim() : null);
                if (StringUtils.hasText(ver) && StringUtils.hasText(subject.requirement().getReqNo())) {
                    Long linked = wikiFsService.ensureVersionRequirementPublishDir(
                            product, ver, subject.requirement());
                    if (linked != null && seenCtx.add(linked)) {
                        WorkspaceSessionCreateRequest.SessionContextRef ref =
                                new WorkspaceSessionCreateRequest.SessionContextRef();
                        ref.setWikiNodeId(linked);
                        contexts.add(ref);
                    }
                }
            } catch (Exception e) {
                log.warn("issue linked requirement context skipped: {}", e.getMessage());
            }
        }
        req.setContexts(contexts);

        return new BindResult(spec, publishDirId, planVersion, bound, skippedOptional);
    }

    public Long ensurePublishDir(
            SysProduct product,
            ReqRequirement requirement,
            PipeUnitMaterialSpec spec,
            String planVersion) {
        return ensurePublishDir(product, new PipeSubject(product, requirement, null), spec, planVersion);
    }

    public Long ensurePublishDir(
            SysProduct product,
            PipeSubject subject,
            PipeUnitMaterialSpec spec,
            String planVersion) {
        if (spec.publishRoot() == PipeUnitMaterialSpec.PublishRoot.ISSUE) {
            IssIssue issue = subject.issue();
            if (issue == null || !StringUtils.hasText(issue.getIssueNo())) {
                throw new BizException("问题单编号无效，无法解析发布目录");
            }
            return wikiFsService.ensureIssuePublishDir(product, issue.getIssueNo());
        }
        if (subject.requirement() == null) {
            throw new BizException("缺少需求主体，无法解析发布目录");
        }
        if (spec.publishRoot() == PipeUnitMaterialSpec.PublishRoot.RAW) {
            return wikiFsService.ensureRawRequirementPublishDir(product, subject.requirement());
        }
        return wikiFsService.ensureVersionRequirementPublishDir(product, planVersion, subject.requirement());
    }

    public String resolvePlanVersion(PipeInstance inst, ReqRequirement requirement, SysProduct product) {
        if (inst.getReleaseVersionId() != null) {
            SysProductVersion v = productService.requireVersionOfProduct(product.getId(), inst.getReleaseVersionId());
            return v.getVersion();
        }
        if (StringUtils.hasText(requirement.getReleaseVersion())) {
            return requirement.getReleaseVersion().trim();
        }
        if (StringUtils.hasText(requirement.getProductVersion())) {
            return requirement.getProductVersion().trim();
        }
        return null;
    }

    private Long ensureOutputDir(Long workspaceId, SysProduct product, PipeSubject subject) {
        String productCode = StringUtils.hasText(product.getCode()) ? product.getCode().trim() : "product";
        Long productDir = workspaceFsService.ensureDirectory(workspaceId, null, productCode);
        if (subject.isIssue()) {
            Long issuesDir = workspaceFsService.ensureDirectory(workspaceId, productDir, "issues");
            String issueNo = StringUtils.hasText(subject.issue().getIssueNo())
                    ? subject.issue().getIssueNo().trim()
                    : String.valueOf(subject.issue().getId());
            return workspaceFsService.ensureDirectory(workspaceId, issuesDir, issueNo);
        }
        String reqNo = StringUtils.hasText(subject.requirement().getReqNo())
                ? subject.requirement().getReqNo().trim()
                : String.valueOf(subject.requirement().getId());
        return workspaceFsService.ensureDirectory(workspaceId, productDir, reqNo);
    }

    private Long resolveStandardsNode(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        Long tenantId = TenantSupport.requireTenantId();
        List<Long> fileIds = new ArrayList<>();
        boolean anyNorm = false;
        for (String ref : refs) {
            if (!StringUtils.hasText(ref)) {
                continue;
            }
            String r = ref.trim();
            if (r.startsWith("模板:")) {
                String name = r.substring("模板:".length()).trim();
                SysWikiNode tplRoot = wikiFsService.findTopLevelDir(tenantId, "模板");
                if (tplRoot == null) {
                    throw new BizException("租户 Wiki 缺少「模板」目录，无法绑定规范：" + name);
                }
                SysWikiNode file = wikiFsService.findChildFile(tplRoot.getId(), name);
                if (file == null) {
                    throw new BizException("未找到规范/模板文件：" + name);
                }
                fileIds.add(file.getId());
            } else if ("@规范".equals(r) || "规范".equals(r) || ".".equals(r)) {
                // 编码类任务：只绑「规范」目录，由 Agent 按改动文件类型自行选读
                SysWikiNode stdRoot = wikiFsService.findTopLevelDir(tenantId, "规范");
                if (stdRoot == null) {
                    log.warn("租户 Wiki 缺少「规范」目录，跳过规范目录绑定");
                    continue;
                }
                return stdRoot.getId();
            } else {
                anyNorm = true;
                SysWikiNode stdRoot = wikiFsService.findTopLevelDir(tenantId, "规范");
                if (stdRoot == null) {
                    log.warn("租户 Wiki 缺少「规范」目录，跳过规范绑定: {}", r);
                    continue;
                }
                SysWikiNode file = wikiFsService.findChildFile(stdRoot.getId(), r);
                if (file == null) {
                    log.warn("规范文件不存在，跳过: {}", r);
                    continue;
                }
                fileIds.add(file.getId());
            }
        }
        if (fileIds.isEmpty()) {
            return null;
        }
        if (fileIds.size() == 1) {
            return fileIds.get(0);
        }
        if (anyNorm) {
            SysWikiNode stdRoot = wikiFsService.findTopLevelDir(tenantId, "规范");
            return stdRoot == null ? fileIds.get(0) : stdRoot.getId();
        }
        return fileIds.get(0);
    }
}

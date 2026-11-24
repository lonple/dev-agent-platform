package com.lonple.dap.server.modules.product;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.product.dto.ProductCodeRepoVO;
import com.lonple.dap.server.modules.product.dto.ProductManagerVO;
import com.lonple.dap.server.modules.product.dto.ProductSaveRequest;
import com.lonple.dap.server.modules.product.dto.ProductVO;
import com.lonple.dap.server.modules.product.dto.ProductVersionSaveRequest;
import com.lonple.dap.server.modules.product.dto.ProductVersionVO;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.product.entity.SysProductCodeRepo;
import com.lonple.dap.server.modules.product.entity.SysProductVersion;
import com.lonple.dap.server.modules.product.mapper.ProductCodeRepoMapper;
import com.lonple.dap.server.modules.product.mapper.ProductMapper;
import com.lonple.dap.server.modules.product.mapper.ProductVersionMapper;
import com.lonple.dap.server.modules.issue.entity.IssIssue;
import com.lonple.dap.server.modules.issue.mapper.IssueMapper;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirement;
import com.lonple.dap.server.modules.requirement.mapper.RequirementMapper;
import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import com.lonple.dap.server.modules.sysuser.mapper.SysUserMapper;
import com.lonple.dap.server.modules.tenant.mapper.TenantUserMapper;
import com.lonple.dap.server.modules.tnworkspace.WorkspaceNodeType;
import com.lonple.dap.server.modules.wiki.WikiFsService;
import com.lonple.dap.server.modules.wiki.entity.SysWikiNode;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;
    private final ProductVersionMapper productVersionMapper;
    private final ProductCodeRepoMapper codeRepoMapper;
    private final RequirementMapper requirementMapper;
    private final IssueMapper issueMapper;
    private final SysUserMapper userMapper;
    private final TenantUserMapper tenantUserMapper;
    private final WikiFsService wikiFsService;

    public List<ProductVO> list() {
        Long tenantId = TenantSupport.requireTenantId();
        return productMapper.selectList(new LambdaQueryWrapper<SysProduct>()
                        .eq(SysProduct::getTenantId, tenantId)
                        .orderByDesc(SysProduct::getId))
                .stream()
                .map(p -> toVO(p, true))
                .toList();
    }

    public ProductVO get(Long id) {
        return toVO(requireOwned(id), true);
    }

    public List<ProductManagerVO> listManagers() {
        Long tenantId = TenantSupport.requireTenantId();
        return productMapper.selectProductManagers(tenantId).stream().map(u -> {
            ProductManagerVO vo = new ProductManagerVO();
            vo.setId(u.getId());
            vo.setUsername(u.getUsername());
            vo.setDescription(u.getDescription());
            return vo;
        }).toList();
    }

    public List<ProductVersionVO> listVersions(Long productId) {
        SysProduct product = requireOwned(productId);
        return listVersionEntities(product.getId()).stream().map(this::toVersionVO).toList();
    }

    @Transactional
    public Long create(ProductSaveRequest req) {
        Long tenantId = TenantSupport.requireTenantId();
        String code = normalizeCode(req.getCode());
        assertCodeUnique(tenantId, code, null);
        assertNameUnique(tenantId, req.getName(), null);
        Long managerId = normalizeManager(tenantId, req.getManagerUserId());

        SysProduct product = new SysProduct();
        product.setTenantId(tenantId);
        product.setCode(code);
        product.setName(req.getName().trim());
        product.setDescription(req.getDescription());
        product.setManagerUserId(managerId);
        product.setWikiNodeId(resolveWikiNodeId(req));
        product.setCreatedAt(LocalDateTime.now());
        product.setUpdatedAt(LocalDateTime.now());
        productMapper.insert(product);
        replaceCodeRepos(product.getId(), req.getCodeRepos());
        return product.getId();
    }

    @Transactional
    public void update(Long id, ProductSaveRequest req) {
        SysProduct product = requireOwned(id);
        String code = normalizeCode(req.getCode());
        assertCodeUnique(product.getTenantId(), code, id);
        assertNameUnique(product.getTenantId(), req.getName(), id);
        Long managerId = normalizeManager(product.getTenantId(), req.getManagerUserId());
        product.setCode(code);
        product.setName(req.getName().trim());
        product.setDescription(req.getDescription());
        product.setManagerUserId(managerId);
        product.setWikiNodeId(resolveWikiNodeId(req));
        product.setUpdatedAt(LocalDateTime.now());
        productMapper.updateById(product);
        replaceCodeRepos(product.getId(), req.getCodeRepos());
    }

    @Transactional
    public void delete(Long id) {
        requireOwned(id);
        Long used = requirementMapper.selectCount(new LambdaQueryWrapper<ReqRequirement>()
                .eq(ReqRequirement::getProductId, id));
        if (used != null && used > 0) {
            throw new BizException("产品已关联需求，无法删除");
        }
        Long issueUsed = issueMapper.selectCount(new LambdaQueryWrapper<IssIssue>()
                .eq(IssIssue::getProductId, id));
        if (issueUsed != null && issueUsed > 0) {
            throw new BizException("产品已关联问题单，无法删除");
        }
        codeRepoMapper.delete(new LambdaQueryWrapper<SysProductCodeRepo>()
                .eq(SysProductCodeRepo::getProductId, id));
        productVersionMapper.delete(new LambdaQueryWrapper<SysProductVersion>()
                .eq(SysProductVersion::getProductId, id));
        productMapper.deleteById(id);
    }

    public List<SysProductCodeRepo> listCodeRepos(Long productId) {
        return codeRepoMapper.selectList(new LambdaQueryWrapper<SysProductCodeRepo>()
                .eq(SysProductCodeRepo::getProductId, productId)
                .orderByAsc(SysProductCodeRepo::getSortNo)
                .orderByAsc(SysProductCodeRepo::getId));
    }

    @Transactional
    public Long createVersion(Long productId, ProductVersionSaveRequest req) {
        SysProduct product = requireOwned(productId);
        String version = req.getVersion().trim();
        assertVersionUnique(product.getId(), version, null);
        ProductVersionStatus status = ProductVersionStatus.fromCode(req.getStatus());
        LocalDateTime planReleaseAt = normalizePlanReleaseAt(status, req.getPlanReleaseAt());

        SysProductVersion entity = new SysProductVersion();
        entity.setTenantId(product.getTenantId());
        entity.setProductId(product.getId());
        entity.setVersion(version);
        entity.setStatus(status.name());
        entity.setPlanReleaseAt(planReleaseAt);
        entity.setDescription(StringUtils.hasText(req.getDescription()) ? req.getDescription().trim() : null);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        productVersionMapper.insert(entity);
        touchProduct(product);
        return entity.getId();
    }

    @Transactional
    public void updateVersion(Long productId, Long versionId, ProductVersionSaveRequest req) {
        SysProduct product = requireOwned(productId);
        SysProductVersion entity = requireOwnedVersion(product.getId(), versionId);
        String version = req.getVersion().trim();
        assertVersionUnique(product.getId(), version, versionId);
        ProductVersionStatus status = ProductVersionStatus.fromCode(req.getStatus());
        LocalDateTime planReleaseAt = normalizePlanReleaseAt(status, req.getPlanReleaseAt());
        String oldVersion = entity.getVersion();
        entity.setVersion(version);
        entity.setStatus(status.name());
        entity.setPlanReleaseAt(planReleaseAt);
        entity.setDescription(StringUtils.hasText(req.getDescription()) ? req.getDescription().trim() : null);
        entity.setUpdatedAt(LocalDateTime.now());
        productVersionMapper.updateById(entity);
        if (!Objects.equals(oldVersion, version)) {
            requirementMapper.update(null, new LambdaUpdateWrapper<ReqRequirement>()
                    .eq(ReqRequirement::getProductVersionId, versionId)
                    .set(ReqRequirement::getProductVersion, version));
        }
        touchProduct(product);
    }

    @Transactional
    public void deleteVersion(Long productId, Long versionId) {
        SysProduct product = requireOwned(productId);
        requireOwnedVersion(product.getId(), versionId);
        Long used = requirementMapper.selectCount(new LambdaQueryWrapper<ReqRequirement>()
                .eq(ReqRequirement::getProductVersionId, versionId));
        if (used != null && used > 0) {
            throw new BizException("该版本已关联需求，无法删除");
        }
        productVersionMapper.deleteById(versionId);
        touchProduct(product);
    }

    public SysProduct requireOwned(Long id) {
        Long tenantId = TenantSupport.requireTenantId();
        SysProduct product = productMapper.selectById(id);
        if (product == null || !Objects.equals(product.getTenantId(), tenantId)) {
            throw new BizException(404, "产品不存在");
        }
        return product;
    }

    public SysProductVersion requireOwnedVersion(Long productId, Long versionId) {
        SysProductVersion entity = productVersionMapper.selectById(versionId);
        if (entity == null || !Objects.equals(entity.getProductId(), productId)) {
            throw new BizException(404, "产品版本不存在");
        }
        Long tenantId = TenantSupport.requireTenantId();
        if (!Objects.equals(entity.getTenantId(), tenantId)) {
            throw new BizException(404, "产品版本不存在");
        }
        return entity;
    }

    /** 校验版本属于产品，返回实体 */
    public SysProductVersion requireVersionOfProduct(Long productId, Long versionId) {
        return requireOwnedVersion(productId, versionId);
    }

    /** 仅在 wikiNodeId 为空时绑定产品 Wiki 根目录 */
    @Transactional
    public void updateWikiNodeId(Long productId, Long wikiNodeId) {
        SysProduct product = requireOwned(productId);
        if (product.getWikiNodeId() != null || wikiNodeId == null) {
            return;
        }
        product.setWikiNodeId(wikiNodeId);
        product.setUpdatedAt(LocalDateTime.now());
        productMapper.updateById(product);
    }

    private void touchProduct(SysProduct product) {
        product.setUpdatedAt(LocalDateTime.now());
        productMapper.updateById(product);
    }

    private Long normalizeManager(Long tenantId, Long managerUserId) {
        if (managerUserId == null) {
            return null;
        }
        if (!tenantUserMapper.existsMembership(tenantId, managerUserId)) {
            throw new BizException("所选用户不属于当前租户");
        }
        return managerUserId;
    }

    private String normalizeCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BizException("请输入产品编码");
        }
        return code.trim();
    }

    private void assertCodeUnique(Long tenantId, String code, Long excludeId) {
        Long count = productMapper.selectCount(new LambdaQueryWrapper<SysProduct>()
                .eq(SysProduct::getTenantId, tenantId)
                .eq(SysProduct::getCode, code)
                .ne(excludeId != null, SysProduct::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException("产品编码已存在");
        }
    }

    private void assertNameUnique(Long tenantId, String name, Long excludeId) {
        Long count = productMapper.selectCount(new LambdaQueryWrapper<SysProduct>()
                .eq(SysProduct::getTenantId, tenantId)
                .eq(SysProduct::getName, name.trim())
                .ne(excludeId != null, SysProduct::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException("产品名称已存在");
        }
    }

    private void assertVersionUnique(Long productId, String version, Long excludeId) {
        Long count = productVersionMapper.selectCount(new LambdaQueryWrapper<SysProductVersion>()
                .eq(SysProductVersion::getProductId, productId)
                .eq(SysProductVersion::getVersion, version)
                .ne(excludeId != null, SysProductVersion::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException("该产品下版本号已存在");
        }
    }

    private List<SysProductVersion> listVersionEntities(Long productId) {
        return productVersionMapper.selectList(new LambdaQueryWrapper<SysProductVersion>()
                .eq(SysProductVersion::getProductId, productId)
                .orderByDesc(SysProductVersion::getId));
    }

    private Long resolveWikiNodeId(ProductSaveRequest req) {
        if (req.getWikiCreate() != null && StringUtils.hasText(req.getWikiCreate().getDisplayName())) {
            return wikiFsService.createDirectory(
                    req.getWikiCreate().getParentId(),
                    req.getWikiCreate().getDisplayName().trim());
        }
        if (req.getWikiNodeId() != null) {
            SysWikiNode node = wikiFsService.requireNode(req.getWikiNodeId());
            if (!WorkspaceNodeType.DIR.name().equals(node.getNodeType())) {
                throw new BizException("产品上下文路径请选择 Wiki 目录");
            }
            return node.getId();
        }
        return null;
    }

    private void replaceCodeRepos(Long productId, List<ProductSaveRequest.CodeRepoSpec> specs) {
        if (specs != null) {
            for (var spec : specs) {
                if (spec != null && StringUtils.hasText(spec.getLocalPath())) normalizeRepoKind(spec.getRepoKind());
            }
        }
        codeRepoMapper.delete(new LambdaQueryWrapper<SysProductCodeRepo>()
                .eq(SysProductCodeRepo::getProductId, productId));
        if (CollectionUtils.isEmpty(specs)) {
            return;
        }
        int i = 0;
        LocalDateTime now = LocalDateTime.now();
        for (ProductSaveRequest.CodeRepoSpec spec : specs) {
            if (spec == null || !StringUtils.hasText(spec.getLocalPath())) {
                continue;
            }
            SysProductCodeRepo row = new SysProductCodeRepo();
            row.setProductId(productId);
            row.setLocalPath(spec.getLocalPath().trim());
            row.setLabel(StringUtils.hasText(spec.getLabel()) ? spec.getLabel().trim() : null);
            row.setRepoKind(normalizeRepoKind(spec.getRepoKind()));
            row.setSortNo(i++);
            row.setCreatedAt(now);
            codeRepoMapper.insert(row);
        }
    }

    private String normalizeRepoKind(String value) {
        String kind = StringUtils.hasText(value) ? value.trim().toUpperCase(java.util.Locale.ROOT) : "BIZ";
        if (!List.of("BIZ", "TEST", "OTHER").contains(kind)) {
            throw new BizException("仓库用途无效，请选择业务代码、自动化测试或其他");
        }
        return kind;
    }

    private ProductVO toVO(SysProduct product, boolean withVersions) {
        ProductVO vo = new ProductVO();
        vo.setId(product.getId());
        vo.setCode(product.getCode());
        vo.setName(product.getName());
        vo.setDescription(product.getDescription());
        vo.setManagerUserId(product.getManagerUserId());
        vo.setWikiNodeId(product.getWikiNodeId());
        vo.setCreatedAt(product.getCreatedAt());
        vo.setUpdatedAt(product.getUpdatedAt());
        if (product.getManagerUserId() != null) {
            SysUser user = userMapper.selectById(product.getManagerUserId());
            if (user != null) {
                vo.setManagerUsername(user.getUsername());
            }
        }
        if (product.getWikiNodeId() != null) {
            try {
                SysWikiNode node = wikiFsService.requireNode(product.getWikiNodeId());
                vo.setWikiPath(wikiFsService.buildDisplayPath(node));
            } catch (Exception ignored) {
                vo.setWikiPath(null);
            }
        }
        List<ProductCodeRepoVO> repos = new ArrayList<>();
        for (SysProductCodeRepo r : listCodeRepos(product.getId())) {
            ProductCodeRepoVO rv = new ProductCodeRepoVO();
            rv.setId(r.getId());
            rv.setLocalPath(r.getLocalPath());
            rv.setLabel(r.getLabel());
            rv.setRepoKind(r.getRepoKind() != null ? r.getRepoKind() : "BIZ");
            rv.setSortNo(r.getSortNo());
            repos.add(rv);
        }
        vo.setCodeRepos(repos);
        if (withVersions) {
            List<ProductVersionVO> versions = listVersionEntities(product.getId()).stream()
                    .map(this::toVersionVO)
                    .toList();
            vo.setVersions(versions);
            vo.setVersionCount(versions.size());
        }
        return vo;
    }

    private LocalDateTime normalizePlanReleaseAt(ProductVersionStatus status, LocalDateTime planReleaseAt) {
        if (status == ProductVersionStatus.PLANNING) {
            if (planReleaseAt == null) {
                throw new BizException("计划中版本请填写计划发布时间");
            }
            return planReleaseAt;
        }
        return planReleaseAt;
    }

    private ProductVersionVO toVersionVO(SysProductVersion entity) {
        ProductVersionStatus status = ProductVersionStatus.fromCode(entity.getStatus());
        ProductVersionVO vo = new ProductVersionVO();
        vo.setId(entity.getId());
        vo.setProductId(entity.getProductId());
        vo.setVersion(entity.getVersion());
        vo.setStatus(status.name());
        vo.setStatusLabel(status.getLabel());
        vo.setPlanReleaseAt(entity.getPlanReleaseAt());
        vo.setDescription(entity.getDescription());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}

package com.lonple.dap.server.modules.product;

import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.product.dto.ProductSaveRequest;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.product.entity.SysProductCodeRepo;
import com.lonple.dap.server.modules.product.mapper.*;
import com.lonple.dap.server.modules.requirement.mapper.RequirementMapper;
import com.lonple.dap.server.modules.issue.mapper.IssueMapper;
import com.lonple.dap.server.modules.sysuser.mapper.SysUserMapper;
import com.lonple.dap.server.modules.tenant.mapper.TenantUserMapper;
import com.lonple.dap.server.modules.wiki.WikiFsService;
import com.lonple.dap.server.security.LoginUser;
import com.lonple.dap.server.security.UserContext;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProductRepoKindTest {
    private final ProductMapper products = mock(ProductMapper.class);
    private final ProductCodeRepoMapper repos = mock(ProductCodeRepoMapper.class);
    private final ProductService service = new ProductService(products, mock(ProductVersionMapper.class), repos,
            mock(RequirementMapper.class), mock(IssueMapper.class), mock(SysUserMapper.class),
            mock(TenantUserMapper.class), mock(WikiFsService.class));

    @BeforeEach
    void setup() {
        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setCurrentTenantId(3L);
        UserContext.set(user);
        SysProduct product = new SysProduct();
        product.setId(4L);
        product.setTenantId(3L);
        product.setCode("DAP");
        product.setName("DAP");
        when(products.selectById(4L)).thenReturn(product);
        when(products.selectCount(any())).thenReturn(0L);
    }

    @AfterEach
    void cleanup() { UserContext.clear(); }

    private ProductSaveRequest request(String... kinds) {
        ProductSaveRequest req = new ProductSaveRequest();
        req.setCode("DAP");
        req.setName("DAP");
        int index = 0;
        for (String kind : kinds) {
            var spec = new ProductSaveRequest.CodeRepoSpec();
            spec.setLocalPath("/repos/repo-" + index++);
            spec.setRepoKind(kind);
            req.getCodeRepos().add(spec);
        }
        return req;
    }

    @Test
    void savesAndReturnsAllRepositoryKinds() {
        service.update(4L, request("BIZ", " test ", "OTHER"));
        ArgumentCaptor<SysProductCodeRepo> saved = ArgumentCaptor.forClass(SysProductCodeRepo.class);
        verify(repos, times(3)).insert(saved.capture());
        assertEquals(List.of("BIZ", "TEST", "OTHER"), saved.getAllValues().stream().map(SysProductCodeRepo::getRepoKind).toList());
        assertTrue(saved.getAllValues().stream().allMatch(r -> Long.valueOf(4L).equals(r.getProductId())));
        when(repos.selectList(any())).thenReturn(saved.getAllValues());
        assertEquals(List.of("BIZ", "TEST", "OTHER"), service.get(4L).getCodeRepos().stream().map(r -> r.getRepoKind()).toList());
    }

    @Test
    void omittedAndBlankKindsRemainBusinessRepositories() {
        service.update(4L, request(null, " "));
        ArgumentCaptor<SysProductCodeRepo> saved = ArgumentCaptor.forClass(SysProductCodeRepo.class);
        verify(repos, times(2)).insert(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(r -> "BIZ".equals(r.getRepoKind())));
    }

    @Test
    void invalidKindIsRejectedBeforeReplacingRepositories() {
        assertThrows(BizException.class, () -> service.update(4L, request("BIZ", "INVALID")));
        verifyNoInteractions(repos);
    }
}

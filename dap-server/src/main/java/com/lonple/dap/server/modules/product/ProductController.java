package com.lonple.dap.server.modules.product;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.product.dto.ProductManagerVO;
import com.lonple.dap.server.modules.product.dto.ProductSaveRequest;
import com.lonple.dap.server.modules.product.dto.ProductVO;
import com.lonple.dap.server.modules.product.dto.ProductVersionSaveRequest;
import com.lonple.dap.server.modules.product.dto.ProductVersionVO;
import com.lonple.dap.server.security.LoginUser;
import com.lonple.dap.server.security.RequirePermission;
import com.lonple.dap.server.security.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ApiResponse<List<ProductVO>> list() {
        assertCanViewProducts();
        return ApiResponse.ok(productService.list());
    }

    @GetMapping("/managers")
    @RequirePermission("product:view")
    public ApiResponse<List<ProductManagerVO>> managers() {
        return ApiResponse.ok(productService.listManagers());
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductVO> get(@PathVariable Long id) {
        assertCanViewProducts();
        return ApiResponse.ok(productService.get(id));
    }

    @PostMapping
    @RequirePermission("product:operate")
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody ProductSaveRequest request) {
        return ApiResponse.ok(Map.of("id", productService.create(request)));
    }

    @PutMapping("/{id}")
    @RequirePermission("product:operate")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody ProductSaveRequest request) {
        productService.update(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("product:operate")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<ProductVersionVO>> versions(@PathVariable Long id) {
        assertCanViewProducts();
        return ApiResponse.ok(productService.listVersions(id));
    }

    @PostMapping("/{id}/versions")
    @RequirePermission("product:operate")
    public ApiResponse<Map<String, Long>> createVersion(
            @PathVariable Long id, @Valid @RequestBody ProductVersionSaveRequest request) {
        return ApiResponse.ok(Map.of("id", productService.createVersion(id, request)));
    }

    @PutMapping("/{id}/versions/{versionId}")
    @RequirePermission("product:operate")
    public ApiResponse<Void> updateVersion(
            @PathVariable Long id,
            @PathVariable Long versionId,
            @Valid @RequestBody ProductVersionSaveRequest request) {
        productService.updateVersion(id, versionId, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/versions/{versionId}")
    @RequirePermission("product:operate")
    public ApiResponse<Void> deleteVersion(@PathVariable Long id, @PathVariable Long versionId) {
        productService.deleteVersion(id, versionId);
        return ApiResponse.ok();
    }

    private void assertCanViewProducts() {
        LoginUser user = UserContext.require();
        if (!user.getPermissions().contains("product:view")
                && !user.getPermissions().contains("requirement:view")
                && !user.getPermissions().contains("issue:view")) {
            throw new BizException(403, "无权限查看产品");
        }
    }
}

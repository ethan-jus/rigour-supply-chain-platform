package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpProductManagementApi;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.api.v1.model.ProductManagementCommand;
import com.rigour.erp.api.v1.model.ProductManagementDetailView;
import com.rigour.erp.api.v1.model.ProductManagementSummaryView;
import com.rigour.erp.application.service.product.ErpProductManagementService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** ERP 商品管理 HTTP 边界；只承载自研商品新增、编辑、删除、列表和详情。 */
@RestController
public final class ErpProductManagementController implements ErpProductManagementApi {
    private final ErpProductManagementService productService;

    public ErpProductManagementController(ErpProductManagementService productService) {
        this.productService = productService;
    }

    @Override
    public ApiResponse<MasterDataPageView<ProductManagementSummaryView>> products(
            int begin, int step, String productCode, String productName, Long categoryId, Long brandId,
            String unitCode, String saleTypeCode, String shelfStatusCode, String submitStatusCode,
            Long defaultWarehouseId) {
        return ApiResponse.success(productService.products(begin, step, productCode, productName,
                categoryId, brandId, unitCode, saleTypeCode, shelfStatusCode, submitStatusCode,
                defaultWarehouseId));
    }

    @Override
    public ApiResponse<ProductManagementDetailView> product(Long id) {
        return ApiResponse.success(productService.product(id));
    }

    @Override
    public ApiResponse<ProductManagementDetailView> createProduct(ProductManagementCommand command) {
        return ApiResponse.success(productService.create(command));
    }

    @Override
    public ApiResponse<ProductManagementDetailView> updateProduct(Long id, ProductManagementCommand command) {
        return ApiResponse.success(productService.update(id, command));
    }

    @Override
    public ApiResponse<Void> deleteProduct(Long id, int revision) {
        productService.delete(id, revision);
        return ApiResponse.success(null);
    }
}

package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpProductApi;
import com.rigour.erp.api.v1.model.BrandView;
import com.rigour.erp.api.v1.model.CategoryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.api.v1.model.ProductPageView;
import com.rigour.erp.api.v1.model.SkuPageView;
import com.rigour.erp.api.v1.model.SpecificationView;
import com.rigour.erp.api.v1.model.TagView;
import com.rigour.erp.application.service.product.ProductMasterDataQueryService;
import com.rigour.shared.context.TenantContext;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ERP 商品主数据浏览器边界；页面查询只读 ERP 本地表，同步由应用服务编排 Integration。 */
@RestController
@RequestMapping(ErpProductApi.BASE_PATH)
public final class ErpProductController implements ErpProductApi {
    private final ProductMasterDataQueryService queryService;

    public ErpProductController(ProductMasterDataQueryService queryService) {
        this.queryService = queryService;
    }

    /** {@inheritDoc} */
    @Override
    @GetMapping("/products")
    public ApiResponse<ProductPageView> list(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String internalStatus,
            @RequestParam(required = false) String sourcePutaway) {
        return ApiResponse.success(queryService.products(tenantId(), begin, step, query,
                internalStatus, sourcePutaway));
    }

    /** {@inheritDoc} */
    @Override
    @GetMapping("/skus")
    public ApiResponse<SkuPageView> skus(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String internalStatus,
            @RequestParam(required = false) String sourcePutaway) {
        return ApiResponse.success(queryService.skus(tenantId(), begin, step, query,
                internalStatus, sourcePutaway));
    }

    /** {@inheritDoc} */
    @Override
    @GetMapping("/categories")
    public ApiResponse<MasterDataPageView<CategoryView>> categories(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(queryService.categories(tenantId(), begin, step, query, status));
    }

    /** {@inheritDoc} */
    @Override
    @GetMapping("/brands")
    public ApiResponse<MasterDataPageView<BrandView>> brands(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(queryService.brands(tenantId(), begin, step, query, status));
    }

    /** {@inheritDoc} */
    @Override
    @GetMapping("/specifications")
    public ApiResponse<MasterDataPageView<SpecificationView>> specifications(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(queryService.specifications(tenantId(), begin, step, query, status));
    }

    /** {@inheritDoc} */
    @Override
    @GetMapping("/tags")
    public ApiResponse<MasterDataPageView<TagView>> tags(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(queryService.tags(tenantId(), begin, step, query, status));
    }

    private static String tenantId() {
        String value = TenantContext.getTenantId();
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少可信租户上下文");
        return value;
    }
}

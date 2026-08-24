package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpProductBrandApi;
import com.rigour.erp.api.v1.model.InternalProductBrandCommand;
import com.rigour.erp.api.v1.model.InternalProductBrandView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.service.product.ErpProductBrandService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** ERP 商品品牌 HTTP 边界；只承载品牌维护。 */
@RestController
public final class ErpProductBrandController implements ErpProductBrandApi {
    private final ErpProductBrandService brandService;

    public ErpProductBrandController(ErpProductBrandService brandService) {
        this.brandService = brandService;
    }

    @Override
    public ApiResponse<MasterDataPageView<InternalProductBrandView>> brands(
            int begin, int step, String brandCode, String brandName) {
        return ApiResponse.success(brandService.brands(begin, step, brandCode, brandName));
    }

    @Override
    public ApiResponse<InternalProductBrandView> brand(Long id) {
        return ApiResponse.success(brandService.brand(id));
    }

    @Override
    public ApiResponse<InternalProductBrandView> createBrand(InternalProductBrandCommand command) {
        return ApiResponse.success(brandService.create(command));
    }

    @Override
    public ApiResponse<InternalProductBrandView> updateBrand(Long id, InternalProductBrandCommand command) {
        return ApiResponse.success(brandService.update(id, command));
    }

    @Override
    public ApiResponse<Void> deleteBrand(Long id, int revision) {
        brandService.delete(id, revision);
        return ApiResponse.success(null);
    }
}

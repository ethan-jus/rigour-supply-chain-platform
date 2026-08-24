package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpProductSpecificationApi;
import com.rigour.erp.api.v1.model.InternalProductSpecificationCommand;
import com.rigour.erp.api.v1.model.InternalProductSpecificationView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.service.product.ErpProductSpecificationService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** ERP 商品规格 HTTP 边界；只承载规格及规格值维护。 */
@RestController
public final class ErpProductSpecificationController implements ErpProductSpecificationApi {
    private final ErpProductSpecificationService specificationService;

    public ErpProductSpecificationController(ErpProductSpecificationService specificationService) {
        this.specificationService = specificationService;
    }

    @Override
    public ApiResponse<MasterDataPageView<InternalProductSpecificationView>> specifications(
            int begin, int step, String specificationCode, String specificationName, String statusCode) {
        return ApiResponse.success(specificationService.specifications(
                begin, step, specificationCode, specificationName, statusCode));
    }

    @Override
    public ApiResponse<InternalProductSpecificationView> specification(Long id) {
        return ApiResponse.success(specificationService.specification(id));
    }

    @Override
    public ApiResponse<InternalProductSpecificationView> createSpecification(
            InternalProductSpecificationCommand command) {
        return ApiResponse.success(specificationService.create(command));
    }

    @Override
    public ApiResponse<InternalProductSpecificationView> updateSpecification(
            Long id, InternalProductSpecificationCommand command) {
        return ApiResponse.success(specificationService.update(id, command));
    }

    @Override
    public ApiResponse<Void> deleteSpecification(Long id, int revision) {
        specificationService.delete(id, revision);
        return ApiResponse.success(null);
    }
}

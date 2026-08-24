package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpSupplierProfileApi;
import com.rigour.erp.api.v1.model.InternalSupplierProfileCommand;
import com.rigour.erp.api.v1.model.InternalSupplierProfileView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.service.supply.ErpSupplierProfileService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** ERP 供应商档案 HTTP 边界；只承载自研供应商新增、编辑、删除、列表和详情。 */
@RestController
public final class ErpSupplierProfileController implements ErpSupplierProfileApi {
    private final ErpSupplierProfileService supplierService;

    public ErpSupplierProfileController(ErpSupplierProfileService supplierService) {
        this.supplierService = supplierService;
    }

    @Override
    public ApiResponse<MasterDataPageView<InternalSupplierProfileView>> suppliers(
            int begin, int step, String supplierCode, String supplierName, String contactPhone, String statusCode) {
        return ApiResponse.success(supplierService.suppliers(begin, step, supplierCode, supplierName,
                contactPhone, statusCode));
    }

    @Override
    public ApiResponse<InternalSupplierProfileView> supplier(Long id) {
        return ApiResponse.success(supplierService.supplier(id));
    }

    @Override
    public ApiResponse<InternalSupplierProfileView> createSupplier(InternalSupplierProfileCommand command) {
        return ApiResponse.success(supplierService.create(command));
    }

    @Override
    public ApiResponse<InternalSupplierProfileView> updateSupplier(Long id, InternalSupplierProfileCommand command) {
        return ApiResponse.success(supplierService.update(id, command));
    }

    @Override
    public ApiResponse<Void> deleteSupplier(Long id, int revision) {
        supplierService.delete(id, revision);
        return ApiResponse.success(null);
    }
}

package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpInventoryWarehouseApi;
import com.rigour.erp.api.v1.model.InternalInventoryWarehouseCommand;
import com.rigour.erp.api.v1.model.InternalInventoryWarehouseView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.service.inventory.ErpInventoryWarehouseService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** ERP 仓库 HTTP 边界；只承载仓库维护。 */
@RestController
public final class ErpInventoryWarehouseController implements ErpInventoryWarehouseApi {
    private final ErpInventoryWarehouseService warehouseService;

    public ErpInventoryWarehouseController(ErpInventoryWarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    @Override
    public ApiResponse<MasterDataPageView<InternalInventoryWarehouseView>> warehouses(
            int begin, int step, String warehouseCode, String warehouseName, String regionCode,
            Boolean defaultFlag, String statusCode) {
        return ApiResponse.success(warehouseService.warehouses(begin, step, warehouseCode,
                warehouseName, regionCode, defaultFlag, statusCode));
    }

    @Override
    public ApiResponse<InternalInventoryWarehouseView> warehouse(Long id) {
        return ApiResponse.success(warehouseService.warehouse(id));
    }

    @Override
    public ApiResponse<InternalInventoryWarehouseView> createWarehouse(InternalInventoryWarehouseCommand command) {
        return ApiResponse.success(warehouseService.create(command));
    }

    @Override
    public ApiResponse<InternalInventoryWarehouseView> updateWarehouse(
            Long id, InternalInventoryWarehouseCommand command) {
        return ApiResponse.success(warehouseService.update(id, command));
    }

    @Override
    public ApiResponse<Void> deleteWarehouse(Long id, int revision) {
        warehouseService.delete(id, revision);
        return ApiResponse.success(null);
    }
}

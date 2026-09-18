package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpOrderExecutionApi;
import com.rigour.erp.api.v1.model.SalesExecutionReceipt;
import com.rigour.erp.application.service.inventory.ErpOrderExecutionService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

/** ERP 执行与只读回执适配。 */
@RestController
public final class ErpOrderExecutionController implements ErpOrderExecutionApi {
    private final ErpOrderExecutionService service;

    public ErpOrderExecutionController(ErpOrderExecutionService service) {
        this.service = service;
    }

    public ApiResponse<SalesExecutionReceipt> execute(String id) {
        return ApiResponse.success(service.execute(id));
    }

    public ApiResponse<SalesExecutionReceipt> receipt(String id) {
        return ApiResponse.success(service.receipt(id));
    }

    public ApiResponse<Boolean> warehouseUsable(long id) {
        return ApiResponse.success(service.warehouseUsable(id));
    }
}

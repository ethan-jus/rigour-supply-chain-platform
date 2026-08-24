package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpStockBalanceApi;
import com.rigour.erp.api.v1.model.InternalStockBalanceView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.service.inventory.ErpStockBalanceService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** ERP 库存余额 HTTP 边界；只提供库存只读查询。 */
@RestController
public final class ErpStockBalanceController implements ErpStockBalanceApi {
    private final ErpStockBalanceService stockBalanceService;

    public ErpStockBalanceController(ErpStockBalanceService stockBalanceService) {
        this.stockBalanceService = stockBalanceService;
    }

    @Override
    public ApiResponse<MasterDataPageView<InternalStockBalanceView>> stockBalances(
            int begin, int step, String productCode, String productName,
            Long warehouseId, String warehouseName) {
        return ApiResponse.success(stockBalanceService.stockBalances(
                begin, step, productCode, productName, warehouseId, warehouseName));
    }
}

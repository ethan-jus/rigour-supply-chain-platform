package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpStockOutOrderApi;
import com.rigour.erp.api.v1.model.InternalSalesStockOutCommand;
import com.rigour.erp.api.v1.model.InternalStockOutOrderDetailView;
import com.rigour.erp.api.v1.model.InternalStockOutOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.service.inventory.ErpStockOutOrderService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** ERP 出库单 HTTP 边界；只承载出库列表、详情和销售出库确认。 */
@RestController
public final class ErpStockOutOrderController implements ErpStockOutOrderApi {
    private final ErpStockOutOrderService stockOutOrderService;

    public ErpStockOutOrderController(ErpStockOutOrderService stockOutOrderService) {
        this.stockOutOrderService = stockOutOrderService;
    }

    @Override
    public ApiResponse<MasterDataPageView<InternalStockOutOrderSummaryView>> stockOutOrders(
            int begin, int step, String stockOutNo, String stockOutTypeCode, Long warehouseId,
            String salesOrderNo, String transferOrderNo, String customerName, String statusCode,
            Instant stockOutTimeFrom, Instant stockOutTimeTo) {
        return ApiResponse.success(stockOutOrderService.stockOutOrders(begin, step, stockOutNo,
                stockOutTypeCode, warehouseId, salesOrderNo, transferOrderNo, customerName, statusCode,
                stockOutTimeFrom, stockOutTimeTo));
    }

    @Override
    public ApiResponse<InternalStockOutOrderDetailView> stockOutOrder(Long id) {
        return ApiResponse.success(stockOutOrderService.stockOutOrder(id));
    }

    @Override
    public ApiResponse<InternalStockOutOrderDetailView> confirmSalesStockOut(
            InternalSalesStockOutCommand command) {
        return ApiResponse.success(stockOutOrderService.confirmSalesStockOut(command));
    }
}

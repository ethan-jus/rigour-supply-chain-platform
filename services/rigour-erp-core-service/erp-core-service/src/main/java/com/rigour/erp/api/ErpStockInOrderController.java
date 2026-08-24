package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpStockInOrderApi;
import com.rigour.erp.api.v1.model.InternalProcurementStockInCommand;
import com.rigour.erp.api.v1.model.InternalStockInOrderDetailView;
import com.rigour.erp.api.v1.model.InternalStockInOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.service.inventory.ErpStockInOrderService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** ERP 入库单 HTTP 边界；只承载入库列表、详情和采购入库确认。 */
@RestController
public final class ErpStockInOrderController implements ErpStockInOrderApi {
    private final ErpStockInOrderService stockInOrderService;

    public ErpStockInOrderController(ErpStockInOrderService stockInOrderService) {
        this.stockInOrderService = stockInOrderService;
    }

    @Override
    public ApiResponse<MasterDataPageView<InternalStockInOrderSummaryView>> stockInOrders(
            int begin, int step, String stockInNo, String stockInTypeCode, Long procurementOrderId,
            Long warehouseId, Long supplierId, String statusCode, Instant stockInTimeFrom, Instant stockInTimeTo) {
        return ApiResponse.success(stockInOrderService.stockInOrders(begin, step, stockInNo, stockInTypeCode,
                procurementOrderId, warehouseId, supplierId, statusCode, stockInTimeFrom, stockInTimeTo));
    }

    @Override
    public ApiResponse<InternalStockInOrderDetailView> stockInOrder(Long id) {
        return ApiResponse.success(stockInOrderService.stockInOrder(id));
    }

    @Override
    public ApiResponse<InternalStockInOrderDetailView> confirmProcurementStockIn(
            InternalProcurementStockInCommand command) {
        return ApiResponse.success(stockInOrderService.confirmProcurementStockIn(command));
    }
}

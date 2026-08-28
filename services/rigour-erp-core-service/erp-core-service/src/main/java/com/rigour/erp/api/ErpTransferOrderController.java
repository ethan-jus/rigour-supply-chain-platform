package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpTransferOrderApi;
import com.rigour.erp.api.v1.model.ExternalTransferStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalTransferOrderProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalTransferStockInProjectionCommand;
import com.rigour.erp.api.v1.model.InternalTransferOrderCommand;
import com.rigour.erp.api.v1.model.InternalTransferOrderDetailView;
import com.rigour.erp.api.v1.model.InternalTransferOrderSummaryView;
import com.rigour.erp.api.v1.model.InternalTransferStockInCommand;
import com.rigour.erp.api.v1.model.InternalTransferStockOutCommand;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.service.inventory.ErpTransferOrderService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** ERP 调拨单 HTTP 边界；调拨出入库确认分别生成统一出库单和统一入库单。 */
@RestController
public final class ErpTransferOrderController implements ErpTransferOrderApi {
    private final ErpTransferOrderService transferOrderService;

    public ErpTransferOrderController(ErpTransferOrderService transferOrderService) {
        this.transferOrderService = transferOrderService;
    }

    @Override
    public ApiResponse<MasterDataPageView<InternalTransferOrderSummaryView>> transferOrders(
            int begin, int step, String transferNo, Long sourceWarehouseId, Long targetWarehouseId,
            String statusCode, Instant stockOutTimeFrom, Instant stockOutTimeTo) {
        return ApiResponse.success(transferOrderService.transferOrders(begin, step, transferNo,
                sourceWarehouseId, targetWarehouseId, statusCode, stockOutTimeFrom, stockOutTimeTo));
    }

    @Override
    public ApiResponse<InternalTransferOrderDetailView> transferOrder(Long id) {
        return ApiResponse.success(transferOrderService.transferOrder(id));
    }

    @Override
    public ApiResponse<InternalTransferOrderDetailView> createTransferOrder(InternalTransferOrderCommand command) {
        return ApiResponse.success(transferOrderService.create(command));
    }

    @Override
    public ApiResponse<InternalTransferOrderDetailView> updateTransferOrder(
            Long id, InternalTransferOrderCommand command) {
        return ApiResponse.success(transferOrderService.update(id, command));
    }

    @Override
    public ApiResponse<Void> deleteTransferOrder(Long id, int revision) {
        transferOrderService.delete(id, revision);
        return ApiResponse.success(null);
    }

    @Override
    public ApiResponse<InternalTransferOrderDetailView> confirmTransferStockOut(
            Long id, InternalTransferStockOutCommand command) {
        return ApiResponse.success(transferOrderService.confirmStockOut(id, command));
    }

    @Override
    public ApiResponse<InternalTransferOrderDetailView> confirmExternalTransferStockOut(
            ExternalTransferStockOutProjectionCommand command) {
        return ApiResponse.success(transferOrderService.confirmExternalStockOut(command));
    }

    @Override
    public ApiResponse<InternalTransferOrderDetailView> confirmExternalTransferStockIn(
            ExternalTransferStockInProjectionCommand command) {
        return ApiResponse.success(transferOrderService.confirmExternalStockIn(command));
    }

    @Override
    public ApiResponse<InternalTransferOrderDetailView> upsertExternalTransferOrder(
            ExternalTransferOrderProjectionCommand command) {
        return ApiResponse.success(transferOrderService.upsertExternalTransferOrder(command));
    }

    @Override
    public ApiResponse<InternalTransferOrderDetailView> confirmTransferStockIn(
            Long id, InternalTransferStockInCommand command) {
        return ApiResponse.success(transferOrderService.confirmStockIn(id, command));
    }
}

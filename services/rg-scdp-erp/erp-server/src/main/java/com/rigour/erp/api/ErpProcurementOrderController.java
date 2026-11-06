package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpProcurementOrderApi;
import com.rigour.erp.api.v1.model.InternalProcurementConfirmationCommand;
import com.rigour.erp.api.v1.model.InternalProcurementOrderCommand;
import com.rigour.erp.api.v1.model.InternalProcurementOrderDetailView;
import com.rigour.erp.api.v1.model.InternalProcurementOrderPageView;
import com.rigour.erp.api.v1.model.InternalProcurementProductView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.service.supply.ErpProcurementOrderService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** ERP 采购订单 HTTP 边界；只承载自研采购订单新增、编辑、删除、列表和详情。 */
@RestController
public final class ErpProcurementOrderController implements ErpProcurementOrderApi {
    private final ErpProcurementOrderService procurementOrderService;

    public ErpProcurementOrderController(ErpProcurementOrderService procurementOrderService) {
        this.procurementOrderService = procurementOrderService;
    }

    @Override
    public ApiResponse<InternalProcurementOrderPageView> procurementOrders(
            int begin,
            int step,
            String procurementNo,
            Long supplierId,
            Long targetWarehouseId,
            String statusCode,
            Instant expectedArrivalFrom,
            Instant expectedArrivalTo,
            String supplierCode,
            String targetWarehouseCode,
            String confirmationStatusCode,
            java.time.LocalDate purchaseDateFrom,
            java.time.LocalDate purchaseDateTo, String sourceDocumentNo) {
        return ApiResponse.success(
                procurementOrderService.procurementOrders(
                        begin,
                        step,
                        procurementNo,
                        supplierId,
                        targetWarehouseId,
                        statusCode,
                        expectedArrivalFrom,
                        expectedArrivalTo,
                        supplierCode,
                        targetWarehouseCode,
                        confirmationStatusCode,
                        purchaseDateFrom,
                        purchaseDateTo, sourceDocumentNo));
    }

    @Override
    public ApiResponse<MasterDataPageView<InternalProcurementProductView>> procurementProducts(
            int begin, int step, String productName, String productCode, String variantCode) {
        return ApiResponse.success(
                procurementOrderService.procurementProducts(
                        begin, step, productName, productCode, variantCode));
    }

    @Override
    public ApiResponse<InternalProcurementOrderDetailView> procurementOrder(Long id) {
        return ApiResponse.success(procurementOrderService.procurementOrder(id));
    }

    @Override
    public ApiResponse<InternalProcurementOrderDetailView> createProcurementOrder(
            InternalProcurementOrderCommand command) {
        return ApiResponse.success(procurementOrderService.create(command));
    }

    @Override
    public ApiResponse<InternalProcurementOrderDetailView> updateProcurementOrder(
            Long id, InternalProcurementOrderCommand command) {
        return ApiResponse.success(procurementOrderService.update(id, command));
    }

    @Override
    public ApiResponse<InternalProcurementOrderDetailView> confirmProcurementOrder(
            Long id, InternalProcurementConfirmationCommand command) {
        if (command == null || command.revision() == null)
            throw new com.rigour.shared.core.exception.BusinessException(
                    com.rigour.shared.core.api.ErrorCode.BAD_REQUEST,
                    "revision不能为空",
                    java.util.List.of());
        return ApiResponse.success(procurementOrderService.confirm(id, command.revision()));
    }

    @Override
    public ApiResponse<Void> deleteProcurementOrder(Long id, int revision) {
        procurementOrderService.delete(id, revision);
        return ApiResponse.success(null);
    }
}

package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.InternalTransferOrderCommand;
import com.rigour.erp.api.v1.model.InternalTransferOrderDetailView;
import com.rigour.erp.api.v1.model.InternalTransferOrderSummaryView;
import com.rigour.erp.api.v1.model.InternalTransferStockInCommand;
import com.rigour.erp.api.v1.model.InternalTransferStockOutCommand;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.api.v1.model.ExternalTransferOrderProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalTransferStockInProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalTransferStockOutProjectionCommand;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ERP 调拨单接口。
 *
 * <p>调拨单是仓库之间移动库存的业务入口。确认调拨出库时才写库存余额和库存流水，
 * 订货宝数据后续只允许映射为我方调拨业务的参考数据。</p>
 */
public interface ErpTransferOrderApi {
    String BASE_PATH = "/api/v1/erp/transfer-orders";

    @GetMapping(BASE_PATH)
    ApiResponse<MasterDataPageView<InternalTransferOrderSummaryView>> transferOrders(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String transferNo,
            @RequestParam(required = false) Long sourceWarehouseId,
            @RequestParam(required = false) Long targetWarehouseId,
            @RequestParam(required = false) String statusCode,
            @RequestParam(required = false) Instant stockOutTimeFrom,
            @RequestParam(required = false) Instant stockOutTimeTo);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalTransferOrderDetailView> transferOrder(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<InternalTransferOrderDetailView> createTransferOrder(
            @RequestBody InternalTransferOrderCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalTransferOrderDetailView> updateTransferOrder(
            @PathVariable("id") Long id,
            @RequestBody InternalTransferOrderCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> deleteTransferOrder(@PathVariable("id") Long id, @RequestParam int revision);

    @PostMapping(BASE_PATH + "/{id}/stock-out-confirmations")
    ApiResponse<InternalTransferOrderDetailView> confirmTransferStockOut(
            @PathVariable("id") Long id,
            @RequestBody InternalTransferStockOutCommand command);

    @PostMapping(BASE_PATH + "/external-stock-out-confirmations")
    ApiResponse<InternalTransferOrderDetailView> confirmExternalTransferStockOut(
            @RequestBody ExternalTransferStockOutProjectionCommand command);

    @PostMapping(BASE_PATH + "/external-stock-in-confirmations")
    ApiResponse<InternalTransferOrderDetailView> confirmExternalTransferStockIn(
            @RequestBody ExternalTransferStockInProjectionCommand command);

    @PostMapping(BASE_PATH + "/external-projections")
    ApiResponse<InternalTransferOrderDetailView> upsertExternalTransferOrder(
            @RequestBody ExternalTransferOrderProjectionCommand command);

    @PostMapping(BASE_PATH + "/{id}/stock-in-confirmations")
    ApiResponse<InternalTransferOrderDetailView> confirmTransferStockIn(
            @PathVariable("id") Long id,
            @RequestBody InternalTransferStockInCommand command);
}

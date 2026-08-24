package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.ExternalGenericStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.InternalSalesStockOutCommand;
import com.rigour.erp.api.v1.model.InternalStockOutOrderDetailView;
import com.rigour.erp.api.v1.model.InternalStockOutOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ERP 出库单接口。
 *
 * <p>出库单是库存扣减的唯一业务入口。销售出库和调拨出库共用出库单头与明细，
 * 通过 stockOutTypeCode 区分业务来源；订货宝数据后续只能映射成我方业务单据。</p>
 */
public interface ErpStockOutOrderApi {
    String BASE_PATH = "/api/v1/erp/stock-out-orders";

    @GetMapping(BASE_PATH)
    ApiResponse<MasterDataPageView<InternalStockOutOrderSummaryView>> stockOutOrders(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String stockOutNo,
            @RequestParam(required = false) String stockOutTypeCode,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String salesOrderNo,
            @RequestParam(required = false) String transferOrderNo,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String statusCode,
            @RequestParam(required = false) Instant stockOutTimeFrom,
            @RequestParam(required = false) Instant stockOutTimeTo);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalStockOutOrderDetailView> stockOutOrder(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH + "/sales-confirmations")
    ApiResponse<InternalStockOutOrderDetailView> confirmSalesStockOut(
            @RequestBody InternalSalesStockOutCommand command);

    @PostMapping(BASE_PATH + "/external-confirmations")
    ApiResponse<InternalStockOutOrderDetailView> confirmExternalStockOut(
            @RequestBody ExternalStockOutProjectionCommand command);

    @PostMapping(BASE_PATH + "/external-generic-confirmations")
    ApiResponse<InternalStockOutOrderDetailView> confirmExternalGenericStockOut(
            @RequestBody ExternalGenericStockOutProjectionCommand command);
}

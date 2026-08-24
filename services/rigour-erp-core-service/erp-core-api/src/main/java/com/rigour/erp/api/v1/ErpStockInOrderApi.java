package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.InternalProcurementStockInCommand;
import com.rigour.erp.api.v1.model.InternalStockInOrderDetailView;
import com.rigour.erp.api.v1.model.InternalStockInOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ERP 入库单接口。
 *
 * <p>本接口只处理我方 ERP 主流程的入库单。订货宝采购或入库数据后续只允许映射到我方单据，
 * 不能绕过这里的库存余额和库存流水写入规则。</p>
 */
public interface ErpStockInOrderApi {
    String BASE_PATH = "/api/v1/erp/stock-in-orders";

    @GetMapping(BASE_PATH)
    ApiResponse<MasterDataPageView<InternalStockInOrderSummaryView>> stockInOrders(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String stockInNo,
            @RequestParam(required = false) String stockInTypeCode,
            @RequestParam(required = false) Long procurementOrderId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String statusCode,
            @RequestParam(required = false) Instant stockInTimeFrom,
            @RequestParam(required = false) Instant stockInTimeTo);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalStockInOrderDetailView> stockInOrder(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH + "/procurement-confirmations")
    ApiResponse<InternalStockInOrderDetailView> confirmProcurementStockIn(
            @RequestBody InternalProcurementStockInCommand command);
}

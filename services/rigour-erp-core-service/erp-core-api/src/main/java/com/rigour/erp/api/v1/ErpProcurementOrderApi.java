package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.InternalProcurementOrderCommand;
import com.rigour.erp.api.v1.model.InternalProcurementOrderDetailView;
import com.rigour.erp.api.v1.model.InternalProcurementOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
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
 * ERP 采购订单接口。
 *
 * <p>采购订单是我方库存入库前置单据。订货宝采购数据后续只能映射为参考或导入结果，
 * 不直接覆盖本接口的编辑、提交、删除流程。</p>
 */
public interface ErpProcurementOrderApi {
    String BASE_PATH = "/api/v1/erp/procurement-orders";

    @GetMapping(BASE_PATH)
    ApiResponse<MasterDataPageView<InternalProcurementOrderSummaryView>> procurementOrders(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String procurementNo,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Long targetWarehouseId,
            @RequestParam(required = false) String statusCode,
            @RequestParam(required = false) Instant expectedArrivalFrom,
            @RequestParam(required = false) Instant expectedArrivalTo);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalProcurementOrderDetailView> procurementOrder(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<InternalProcurementOrderDetailView> createProcurementOrder(
            @RequestBody InternalProcurementOrderCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalProcurementOrderDetailView> updateProcurementOrder(
            @PathVariable("id") Long id,
            @RequestBody InternalProcurementOrderCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> deleteProcurementOrder(@PathVariable("id") Long id, @RequestParam int revision);
}

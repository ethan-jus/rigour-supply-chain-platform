package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

/** 选仓与出库分别授权；内部 claim 仍携带原操作人并在线核验。 */
public interface OrderFulfillmentApi {
    @GetMapping("/api/v1/orders/fulfillments")
    ApiResponse<OrderFulfillmentQueueView> queue(@RequestParam(defaultValue="0") int begin,@RequestParam(defaultValue="20") int step,@RequestParam(required=false) String keyword,@RequestParam(required=false) String outboundStatus);
    @GetMapping("/api/v1/orders/fulfillments/{id}")
    ApiResponse<OrderFulfillmentQueueView.Detail> detail(@PathVariable long id);

    record WarehouseOption(long id, String warehouseName) {}

    @GetMapping(OrderSalesOrderApi.BASE_PATH + "/{id}/warehouse-options")
    ApiResponse<java.util.List<WarehouseOption>> warehouses(@PathVariable long id);

    record WarehouseSelection(long warehouseId, int revision) {}

    @GetMapping(OrderSalesOrderApi.BASE_PATH + "/{id}/fulfillment")
    ApiResponse<OrderFulfillmentStatusView> status(@PathVariable long id);

    @PutMapping(OrderSalesOrderApi.BASE_PATH + "/{id}/warehouse-selection")
    ApiResponse<OrderFulfillmentStatusView> selectWarehouse(
            @PathVariable long id, @RequestBody WarehouseSelection command);

    @PostMapping(OrderSalesOrderApi.BASE_PATH + "/{id}/fulfillment/execute")
    ApiResponse<OrderFulfillmentStatusView> execute(
            @PathVariable long id, @RequestBody SalesOrderStockOutCommand command);

    @PostMapping("/internal/v1/order/fulfillments/{executionId}/claim")
    ApiResponse<FulfillmentExecutionView> claim(@PathVariable String executionId);

    @PostMapping("/internal/v1/order/sales-orders/{id}/fulfillment/reconcile")
    ApiResponse<OrderFulfillmentStatusView> reconcile(@PathVariable long id);
}

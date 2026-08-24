package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesOrderStockOutCommand;
import com.rigour.order.api.v1.model.SalesOrderStockOutResult;
import com.rigour.order.api.v1.model.SalesOrderSummaryView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** 自研销售订单接口；订货宝订单后续只能映射到该业务模型，不直接驱动本接口流程。 */
public interface OrderSalesOrderApi {
    String BASE_PATH = "/api/v1/orders/sales";

    @GetMapping(BASE_PATH)
    ApiResponse<OrderPageView<SalesOrderSummaryView>> salesOrders(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String sourceOrderNo,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String contactPhone,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerSalesUserId,
            @RequestParam(required = false) String ownerStaffCode,
            @RequestParam(required = false) String orderStatusCode,
            @RequestParam(required = false) String paymentStatusCode,
            @RequestParam(required = false) String outboundStatusCode,
            @RequestParam(required = false) Instant orderDateFrom,
            @RequestParam(required = false) Instant orderDateTo);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<SalesOrderDetailView> salesOrder(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<SalesOrderDetailView> createSalesOrder(@RequestBody SalesOrderCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<SalesOrderDetailView> updateSalesOrder(
            @PathVariable("id") Long id, @RequestBody SalesOrderCommand command);

    @PostMapping(BASE_PATH + "/{id}/submissions")
    ApiResponse<SalesOrderDetailView> submitSalesOrder(
            @PathVariable("id") Long id, @RequestParam int revision);

    @PostMapping(BASE_PATH + "/{id}/cancellations")
    ApiResponse<SalesOrderDetailView> cancelSalesOrder(
            @PathVariable("id") Long id, @RequestParam int revision);

    @PostMapping(BASE_PATH + "/{id}/outbound-confirmations")
    ApiResponse<SalesOrderDetailView> confirmSalesOrderOutbound(
            @PathVariable("id") Long id, @RequestParam int revision);

    @PostMapping(BASE_PATH + "/{id}/stock-out-confirmations")
    ApiResponse<SalesOrderStockOutResult> confirmSalesOrderStockOut(
            @PathVariable("id") Long id, @RequestBody SalesOrderStockOutCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> deleteSalesOrder(@PathVariable("id") Long id, @RequestParam int revision);
}

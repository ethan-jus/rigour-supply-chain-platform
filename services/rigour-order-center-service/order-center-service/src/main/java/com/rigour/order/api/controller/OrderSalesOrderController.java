package com.rigour.order.api.controller;

import com.rigour.order.api.v1.OrderSalesOrderApi;
import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesOrderStockOutCommand;
import com.rigour.order.api.v1.model.SalesOrderStockOutResult;
import com.rigour.order.api.v1.model.SalesOrderSummaryView;
import com.rigour.order.application.service.sales.OrderSalesOrderService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** Order 自研销售订单 HTTP 边界；订货宝同步查询不从这里进入。 */
@RestController
public final class OrderSalesOrderController implements OrderSalesOrderApi {
    private final OrderSalesOrderService service;

    public OrderSalesOrderController(OrderSalesOrderService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<OrderPageView<SalesOrderSummaryView>> salesOrders(
            int begin, int step, String orderNo, String sourceOrderNo, String customerName, String contactPhone,
            String regionCode, String ownerSalesUserId, String ownerStaffCode, String orderStatusCode,
            String paymentStatusCode, String outboundStatusCode, Instant orderDateFrom, Instant orderDateTo) {
        return ApiResponse.success(service.salesOrders(begin, step, orderNo, sourceOrderNo, customerName, contactPhone,
                regionCode, ownerSalesUserId, ownerStaffCode, orderStatusCode, paymentStatusCode, outboundStatusCode,
                orderDateFrom, orderDateTo));
    }

    @Override
    public ApiResponse<SalesOrderDetailView> salesOrder(Long id) {
        return ApiResponse.success(service.salesOrder(id));
    }

    @Override
    public ApiResponse<SalesOrderDetailView> createSalesOrder(SalesOrderCommand command) {
        return ApiResponse.success(service.create(command));
    }

    @Override
    public ApiResponse<SalesOrderDetailView> updateSalesOrder(Long id, SalesOrderCommand command) {
        return ApiResponse.success(service.update(id, command));
    }

    @Override
    public ApiResponse<SalesOrderDetailView> submitSalesOrder(Long id, int revision) {
        return ApiResponse.success(service.submit(id, revision));
    }

    @Override
    public ApiResponse<SalesOrderDetailView> cancelSalesOrder(Long id, int revision) {
        return ApiResponse.success(service.cancel(id, revision));
    }

    @Override
    public ApiResponse<SalesOrderDetailView> confirmSalesOrderOutbound(Long id, int revision) {
        return ApiResponse.success(service.confirmOutbound(id, revision));
    }

    @Override
    public ApiResponse<SalesOrderStockOutResult> confirmSalesOrderStockOut(Long id, SalesOrderStockOutCommand command) {
        return ApiResponse.success(service.confirmStockOut(id, command));
    }

    @Override
    public ApiResponse<Void> deleteSalesOrder(Long id, int revision) {
        service.delete(id, revision);
        return ApiResponse.success(null);
    }
}

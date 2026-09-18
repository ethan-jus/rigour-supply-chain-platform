package com.rigour.order.api.controller;

import com.rigour.order.api.v1.OrderFulfillmentApi;
import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.service.sales.OrderFulfillmentService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

/** 履约命令 HTTP 入口，授权及恢复规则由用例执行。 */
@RestController
public final class OrderFulfillmentController implements OrderFulfillmentApi {
    private final OrderFulfillmentService service;

    public OrderFulfillmentController(OrderFulfillmentService service) {
        this.service = service;
    }

    public ApiResponse<OrderFulfillmentQueueView> queue(int begin,int step,String keyword,String outboundStatus) { return ApiResponse.success(service.queue(begin,step,keyword,outboundStatus)); }
    public ApiResponse<OrderFulfillmentQueueView.Detail> detail(long id) { return ApiResponse.success(service.detail(id)); }

    public ApiResponse<java.util.List<WarehouseOption>> warehouses(long id) {
        return ApiResponse.success(service.warehouses(id));
    }

    public ApiResponse<OrderFulfillmentStatusView> status(long id) {
        return ApiResponse.success(service.status(id));
    }

    public ApiResponse<OrderFulfillmentStatusView> selectWarehouse(long id, WarehouseSelection c) {
        return ApiResponse.success(service.select(id, c.warehouseId(), c.revision()));
    }

    public ApiResponse<OrderFulfillmentStatusView> execute(long id, SalesOrderStockOutCommand c) {
        return ApiResponse.success(service.execute(id, c));
    }

    public ApiResponse<FulfillmentExecutionView> claim(String id) {
        return ApiResponse.success(service.claim(id));
    }

    public ApiResponse<OrderFulfillmentStatusView> reconcile(long id) {
        return ApiResponse.success(service.reconcile(id));
    }
}

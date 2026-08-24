package com.rigour.order.api.controller;

import com.rigour.order.api.v1.OrderSalesShipmentApi;
import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesShipmentCommand;
import com.rigour.order.api.v1.model.SalesShipmentDetailView;
import com.rigour.order.api.v1.model.SalesShipmentSummaryView;
import com.rigour.order.application.service.sales.OrderSalesShipmentService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** Order 销售发货单 HTTP 边界。 */
@RestController
public final class OrderSalesShipmentController implements OrderSalesShipmentApi {
    private final OrderSalesShipmentService service;

    public OrderSalesShipmentController(OrderSalesShipmentService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<OrderPageView<SalesShipmentSummaryView>> shipments(
            int begin, int step, String shipmentNo, String salesOrderNo, String customerName,
            String trackingNo, String shipmentStatusCode, Instant shipTimeFrom, Instant shipTimeTo) {
        return ApiResponse.success(service.shipments(begin, step, shipmentNo, salesOrderNo,
                customerName, trackingNo, shipmentStatusCode, shipTimeFrom, shipTimeTo));
    }

    @Override
    public ApiResponse<SalesShipmentDetailView> shipment(Long id) {
        return ApiResponse.success(service.shipment(id));
    }

    @Override
    public ApiResponse<SalesShipmentDetailView> createShipment(SalesShipmentCommand command) {
        return ApiResponse.success(service.create(command));
    }

    @Override
    public ApiResponse<SalesShipmentDetailView> updateShipment(Long id, SalesShipmentCommand command) {
        return ApiResponse.success(service.update(id, command));
    }

    @Override
    public ApiResponse<Void> deleteShipment(Long id, int revision) {
        service.delete(id, revision);
        return ApiResponse.success(null);
    }
}

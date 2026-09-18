package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesShipmentCommand;
import com.rigour.order.api.v1.model.SalesShipmentDetailView;
import com.rigour.order.api.v1.model.SalesShipmentSummaryView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** 自研销售发货单接口；订货宝发货单只能同步映射到本业务模型。 */
public interface OrderSalesShipmentApi {
    String BASE_PATH = "/api/v1/orders/sales-shipments";

    @GetMapping(BASE_PATH)
    ApiResponse<OrderPageView<SalesShipmentSummaryView>> shipments(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String shipmentNo,
            @RequestParam(required = false) String salesOrderNo,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String trackingNo,
            @RequestParam(required = false) String shipmentStatusCode,
            @RequestParam(required = false) Instant shipTimeFrom,
            @RequestParam(required = false) Instant shipTimeTo);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<SalesShipmentDetailView> shipment(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<SalesShipmentDetailView> createShipment(@RequestBody SalesShipmentCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<SalesShipmentDetailView> updateShipment(
            @PathVariable("id") Long id, @RequestBody SalesShipmentCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> deleteShipment(@PathVariable("id") Long id, @RequestParam int revision);
}

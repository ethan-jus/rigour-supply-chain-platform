package com.rigour.order.api.controller;

import com.rigour.order.api.v1.OrderSalesPaymentRecordApi;
import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesPaymentRecordCommand;
import com.rigour.order.api.v1.model.SalesPaymentRecordDetailView;
import com.rigour.order.api.v1.model.SalesPaymentRecordSummaryView;
import com.rigour.order.application.service.sales.OrderSalesPaymentRecordService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** Order 销售回款记录 HTTP 边界。 */
@RestController
public final class OrderSalesPaymentRecordController implements OrderSalesPaymentRecordApi {
    private final OrderSalesPaymentRecordService service;

    public OrderSalesPaymentRecordController(OrderSalesPaymentRecordService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<OrderPageView<SalesPaymentRecordSummaryView>> payments(
            int begin, int step, String paymentNo, String salesOrderNo, String customerName,
            String collectorStaffCode, String paymentMethodCode,
            Instant paymentTimeFrom, Instant paymentTimeTo) {
        return ApiResponse.success(service.payments(begin, step, paymentNo, salesOrderNo, customerName,
                collectorStaffCode, paymentMethodCode, paymentTimeFrom, paymentTimeTo));
    }

    @Override
    public ApiResponse<SalesPaymentRecordDetailView> payment(Long id) {
        return ApiResponse.success(service.payment(id));
    }

    @Override
    public ApiResponse<SalesPaymentRecordDetailView> createPayment(SalesPaymentRecordCommand command) {
        return ApiResponse.success(service.create(command));
    }

    @Override
    public ApiResponse<SalesPaymentRecordDetailView> updatePayment(Long id, SalesPaymentRecordCommand command) {
        return ApiResponse.success(service.update(id, command));
    }

    @Override
    public ApiResponse<Void> deletePayment(Long id, int revision) {
        service.delete(id, revision);
        return ApiResponse.success(null);
    }
}

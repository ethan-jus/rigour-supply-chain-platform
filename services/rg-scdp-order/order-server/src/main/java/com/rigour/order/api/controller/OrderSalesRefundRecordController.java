package com.rigour.order.api.controller;

import com.rigour.order.api.v1.OrderSalesRefundRecordApi;
import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesRefundRecordCommand;
import com.rigour.order.api.v1.model.SalesRefundRecordDetailView;
import com.rigour.order.api.v1.model.SalesRefundRecordSummaryView;
import com.rigour.order.application.service.sales.OrderSalesRefundRecordService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** Order 销售退款记录 HTTP 边界。 */
@RestController
public final class OrderSalesRefundRecordController implements OrderSalesRefundRecordApi {
    private final OrderSalesRefundRecordService service;

    public OrderSalesRefundRecordController(OrderSalesRefundRecordService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<OrderPageView<SalesRefundRecordSummaryView>> refunds(
            int begin, int step, String refundNo, String salesOrderNo, String customerName,
            String refundStaffCode, String refundMethodCode, String refundStatusCode,
            Instant refundTimeFrom, Instant refundTimeTo) {
        return ApiResponse.success(service.refunds(begin, step, refundNo, salesOrderNo, customerName,
                refundStaffCode, refundMethodCode, refundStatusCode, refundTimeFrom, refundTimeTo));
    }

    @Override
    public ApiResponse<SalesRefundRecordDetailView> refund(Long id) {
        return ApiResponse.success(service.refund(id));
    }

    @Override
    public ApiResponse<SalesRefundRecordDetailView> createRefund(SalesRefundRecordCommand command) {
        return ApiResponse.success(service.create(command));
    }

    @Override
    public ApiResponse<SalesRefundRecordDetailView> updateRefund(Long id, SalesRefundRecordCommand command) {
        return ApiResponse.success(service.update(id, command));
    }

    @Override
    public ApiResponse<Void> deleteRefund(Long id, int revision) {
        service.delete(id, revision);
        return ApiResponse.success(null);
    }
}

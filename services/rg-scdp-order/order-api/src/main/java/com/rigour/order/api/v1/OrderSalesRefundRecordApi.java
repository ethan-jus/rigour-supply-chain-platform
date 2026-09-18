package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesRefundRecordCommand;
import com.rigour.order.api.v1.model.SalesRefundRecordDetailView;
import com.rigour.order.api.v1.model.SalesRefundRecordSummaryView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** 自研销售退款记录接口；第三方付款单只同步映射到本业务模型。 */
public interface OrderSalesRefundRecordApi {
    String BASE_PATH = "/api/v1/orders/sales-refunds";

    @GetMapping(BASE_PATH)
    ApiResponse<OrderPageView<SalesRefundRecordSummaryView>> refunds(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String refundNo,
            @RequestParam(required = false) String salesOrderNo,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String refundStaffCode,
            @RequestParam(required = false) String refundMethodCode,
            @RequestParam(required = false) String refundStatusCode,
            @RequestParam(required = false) Instant refundTimeFrom,
            @RequestParam(required = false) Instant refundTimeTo);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<SalesRefundRecordDetailView> refund(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<SalesRefundRecordDetailView> createRefund(@RequestBody SalesRefundRecordCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<SalesRefundRecordDetailView> updateRefund(
            @PathVariable("id") Long id, @RequestBody SalesRefundRecordCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> deleteRefund(@PathVariable("id") Long id, @RequestParam int revision);
}

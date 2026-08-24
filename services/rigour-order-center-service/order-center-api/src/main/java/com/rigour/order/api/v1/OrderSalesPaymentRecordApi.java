package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesPaymentRecordCommand;
import com.rigour.order.api.v1.model.SalesPaymentRecordDetailView;
import com.rigour.order.api.v1.model.SalesPaymentRecordSummaryView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** 自研销售回款记录接口；第三方收款单只同步映射到本业务模型。 */
public interface OrderSalesPaymentRecordApi {
    String BASE_PATH = "/api/v1/orders/sales-payments";

    @GetMapping(BASE_PATH)
    ApiResponse<OrderPageView<SalesPaymentRecordSummaryView>> payments(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String paymentNo,
            @RequestParam(required = false) String salesOrderNo,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String collectorStaffCode,
            @RequestParam(required = false) String paymentMethodCode,
            @RequestParam(required = false) Instant paymentTimeFrom,
            @RequestParam(required = false) Instant paymentTimeTo);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<SalesPaymentRecordDetailView> payment(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<SalesPaymentRecordDetailView> createPayment(@RequestBody SalesPaymentRecordCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<SalesPaymentRecordDetailView> updatePayment(
            @PathVariable("id") Long id, @RequestBody SalesPaymentRecordCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> deletePayment(@PathVariable("id") Long id, @RequestParam int revision);
}

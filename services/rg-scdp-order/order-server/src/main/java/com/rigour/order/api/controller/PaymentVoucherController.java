package com.rigour.order.api.controller;

import com.rigour.order.api.v1.PaymentVoucherApi;
import com.rigour.order.api.v1.model.PaymentVoucherModels.TransactionMatch;
import com.rigour.order.api.v1.model.PaymentVoucherModels.VoucherTransaction;
import com.rigour.order.application.service.sales.PaymentVoucherService;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentVoucherController implements PaymentVoucherApi {
    private final PaymentVoucherService service;
    public PaymentVoucherController(PaymentVoucherService service) { this.service = service; }
    @Override public ApiResponse<List<VoucherTransaction>> vouchers(long id) {
        return ApiResponse.success(service.vouchers(id));
    }
    @Override public ApiResponse<List<TransactionMatch>> checkTransaction(String transactionNo) {
        return ApiResponse.success(service.checkTransaction(transactionNo));
    }
}

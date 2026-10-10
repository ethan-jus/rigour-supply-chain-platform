package com.rigour.order.api.controller;

import com.rigour.order.api.v1.PaymentVoucherAuditApi;
import com.rigour.order.api.v1.model.PaymentVoucherAuditModels.*;
import com.rigour.order.application.service.sales.PaymentVoucherAuditService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentVoucherAuditController implements PaymentVoucherAuditApi {
    private final PaymentVoucherAuditService service;
    public PaymentVoucherAuditController(PaymentVoucherAuditService service) {this.service=service;}
    @Override public ApiResponse<Scan> scan() {return ApiResponse.success(service.scan());}
    @Override public ApiResponse<Review> review(ReviewRequest request) {return ApiResponse.success(service.review(request));}
}

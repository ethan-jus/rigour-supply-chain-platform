package com.rigour.order.api.controller;

import com.rigour.order.api.v1.PaymentFilterApi;
import com.rigour.order.application.service.sales.PaymentFilterService;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.order.application.service.sales.RegisterPage;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentFilterController implements PaymentFilterApi {
    private final PaymentFilterService service;
    public PaymentFilterController(PaymentFilterService service) { this.service = service; }
    @Override public ApiResponse<Options> options() { return ApiResponse.success(service.options(RegisterPage.PAYMENTS)); }
    @Override public ApiResponse<Options> orderOptions() { return ApiResponse.success(service.options(RegisterPage.ORDERS)); }
    @Override public ApiResponse<Options> lineOptions() { return ApiResponse.success(service.options(RegisterPage.LINES)); }
}

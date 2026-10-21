package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.AnalyticsPaymentRiskApi;
import com.rigour.analytics.api.v1.model.PaymentRiskView;
import com.rigour.analytics.application.service.PaymentRiskService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class AnalyticsPaymentRiskController implements AnalyticsPaymentRiskApi {
    private final PaymentRiskService service;

    public AnalyticsPaymentRiskController(PaymentRiskService service) { this.service = service; }

    @Override
    public ApiResponse<PaymentRiskView> paymentRisk(Instant from, Instant to, String regionCode,
            String ownerStaffCode, List<Long> productCategoryIds, String riskLevel) {
        return ApiResponse.success(service.query(from, to, regionCode, ownerStaffCode, productCategoryIds, riskLevel));
    }
}

package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.PaymentRiskView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

public interface AnalyticsPaymentRiskApi {
    @GetMapping("/api/v1/analytics/supply/dashboard/payment-risk")
    ApiResponse<PaymentRiskView> paymentRisk(
            @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode,
            @RequestParam(required = false) List<Long> productCategoryIds,
            @RequestParam(required = false) String riskLevel);
}

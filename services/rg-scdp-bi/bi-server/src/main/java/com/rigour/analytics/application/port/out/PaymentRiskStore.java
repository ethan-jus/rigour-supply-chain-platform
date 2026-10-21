package com.rigour.analytics.application.port.out;

import com.rigour.analytics.api.v1.model.PaymentRiskView.Category;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface PaymentRiskStore {
    List<Category> categories(String tenant);
    List<Fact> load(String tenant, Instant from, Instant to, String region, String owner, int limit);

    record Fact(
            String orderId, String orderNo, String customerName,
            String regionCode, String regionName, String ownerStaffCode, String ownerStaffName,
            Instant orderDate, String categoryId, String categoryName,
            String productId, String productName, BigDecimal orderAmount, BigDecimal paidAmount,
            Instant syncedAt, BigDecimal lineAmount) {}
}

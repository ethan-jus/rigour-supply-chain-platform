package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SupplyDashboardReceiptDetailsView(long total, BigDecimal amount, List<Row> items) {
    public record Row(String id, String paymentNo, String orderNo, String customerName,
            String regionName, String ownerStaffName, Instant occurredAt,
            BigDecimal orderAmount, BigDecimal paidAmount, BigDecimal unpaidAmount) {}
}

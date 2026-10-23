package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 未结清订单按整单应收统计；每单唯一归属最高风险产品，并列取商品合计金额最大者。 */
public record PaymentRiskView(
        Instant generatedAt, Instant dataUpdatedAt, List<Category> categories, List<Order> orders,
        List<Region> regions) {
    public PaymentRiskView(Instant generatedAt, Instant dataUpdatedAt, List<Category> categories, List<Order> orders) {
        this(generatedAt, dataUpdatedAt, categories, orders, List.of());
    }
    public record Region(String code, String name, String parentCode) {}
    public record Category(String id, String parentId, String name) {}
    public record Product(String categoryId, String categoryName, String productId,
                          String productName, String riskLevel, BigDecimal productAmount) {}
    public record Order(
            String orderId, String orderNo, String customerName,
            String regionCode, String regionName, String ownerStaffCode, String ownerStaffName,
            LocalDate orderDate, long ageDays, BigDecimal orderAmount, BigDecimal paidAmount,
            List<Product> products) {}
}

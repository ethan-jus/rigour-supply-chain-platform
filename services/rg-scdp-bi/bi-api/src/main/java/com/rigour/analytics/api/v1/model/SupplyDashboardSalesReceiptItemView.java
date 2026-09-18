package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/**
 * 按 BI 归属销售快照聚合 payment_time 期间回款事实，不是订单累计已收。
 * 既有 ETL 的归属编码依次取订单销售、客户销售、回款人，不保证是原始订单销售。
 * 人员过滤与 collectionTrend 一致，匹配归属销售或回款人，但归属维度不改为回款人。
 */
public record SupplyDashboardSalesReceiptItemView(
        String ownerStaffCode,
        String ownerStaffName,
        BigDecimal paidAmount,
        Long paymentCount,
        Long customerCount) {
}

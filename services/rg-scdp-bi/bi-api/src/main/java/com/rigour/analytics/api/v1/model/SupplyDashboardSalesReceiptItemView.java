package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/**
 * 按 BI 归属销售快照聚合 payment_time 期间回款事实，不是订单累计已收。
 * 归属优先采用回款记录经办人；经办人缺失时采用客户当前归属业务员，两者均缺失时待核对。
 * 人员过滤与 collectionTrend 一致，仅匹配解析后的归属编码，不重复计入其他人员。
 */
public record SupplyDashboardSalesReceiptItemView(
        String ownerStaffCode,
        String ownerStaffName,
        BigDecimal paidAmount,
        Long paymentCount,
        Long customerCount) {
}

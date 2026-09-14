package com.rigour.analytics.api.v1.model;

import java.time.Instant;
import java.util.List;

/**
 * 经营分析补充读模型，不改变 overview 合同。
 *
 * <p>时间均为 UTC 闭区间，边界归一到 BI 事实的微秒精度；前期为紧邻当前期间的等长窗口，
 * 不是自然月同比。无效人员不进入前期排名，多城市或无效城市归属返回 null。</p>
 */
public record SupplyDashboardOperatingAnalysisView(
        Instant from,
        Instant to,
        Instant generatedAt,
        Instant previousFrom,
        Instant previousTo,
        List<SupplyDashboardRankingItemView> previousSalesRanking,
        List<SupplyDashboardCityProductItemView> cityProducts,
        List<SupplyDashboardCityCustomerItemView> cityCustomers,
        List<SupplyDashboardSalesReceiptItemView> salesReceipts) {
    public SupplyDashboardOperatingAnalysisView {
        previousSalesRanking = List.copyOf(previousSalesRanking == null ? List.of() : previousSalesRanking);
        cityProducts = List.copyOf(cityProducts == null ? List.of() : cityProducts);
        cityCustomers = List.copyOf(cityCustomers == null ? List.of() : cityCustomers);
        salesReceipts = List.copyOf(salesReceipts == null ? List.of() : salesReceipts);
    }
}

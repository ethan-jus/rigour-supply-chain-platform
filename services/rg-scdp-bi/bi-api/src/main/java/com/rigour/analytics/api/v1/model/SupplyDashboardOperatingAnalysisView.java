package com.rigour.analytics.api.v1.model;

import java.time.Instant;
import java.math.BigDecimal;
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
        List<SupplyDashboardSalesReceiptItemView> salesReceipts,
        List<CityReceipt> cityReceipts,
        CustomerRetention customerRetention, List<CityMonthlyGoal> cityMonthlyGoals, List<SalesPerson> salesPeople) {
    public record SalesPerson(String ownerStaffCode, String ownerStaffName, String employmentStatus) {}
    public record CityReceipt(String regionCode, String regionName, BigDecimal receiptAmount,
            Long paymentCount, Long customerCount) {}
    public record CustomerRetention(Long orderingCustomerCount, Long returningCustomerCount,
            Long newCustomerCount, Long annualReturningCustomerCount) {}
    public record CityMonthlyGoal(String regionCode, String regionName, Integer month,
            BigDecimal salesTarget, BigDecimal receiptTarget, BigDecimal newCustomerTarget,
            BigDecimal repeatCustomerTarget, Integer configuredCount) {}
    public SupplyDashboardOperatingAnalysisView {
        previousSalesRanking = List.copyOf(previousSalesRanking == null ? List.of() : previousSalesRanking);
        cityProducts = List.copyOf(cityProducts == null ? List.of() : cityProducts);
        cityCustomers = List.copyOf(cityCustomers == null ? List.of() : cityCustomers);
        salesReceipts = List.copyOf(salesReceipts == null ? List.of() : salesReceipts);
        cityReceipts = List.copyOf(cityReceipts == null ? List.of() : cityReceipts);
        salesPeople = List.copyOf(salesPeople == null ? List.of() : salesPeople);
        cityMonthlyGoals = List.copyOf(cityMonthlyGoals == null ? List.of() : cityMonthlyGoals);
    }
}

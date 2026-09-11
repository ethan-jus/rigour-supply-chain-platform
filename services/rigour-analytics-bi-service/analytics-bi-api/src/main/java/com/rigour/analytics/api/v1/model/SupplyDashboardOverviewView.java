package com.rigour.analytics.api.v1.model;

import java.time.Instant;
import java.util.List;

/** 供应链经营总览看板。 */
public record SupplyDashboardOverviewView(
        Instant from,
        Instant to,
        Instant generatedAt,
        List<SupplyDashboardMetricCardView> metrics,
        List<SupplyDashboardTrendPointView> salesTrend,
        List<SupplyDashboardTrendPointView> collectionTrend,
        List<SupplyDashboardTrendPointView> cityCostTrend,
        List<SupplyDashboardRankingItemView> citySalesRanking,
        List<SupplyDashboardRankingItemView> salesRanking,
        List<SupplyDashboardSalesMonthlyPerformanceView> salesMonthlyPerformance,
        List<SupplyDashboardRankingItemView> cityCollectionRateRanking,
        List<SupplyDashboardRankingItemView> sourceSystemBreakdown,
        List<SupplyDashboardProductSalesItemView> productSalesRanking,
        List<SupplyDashboardProductSalesItemView> skuSalesRanking,
        List<SupplyDashboardProductSalesItemView> categorySalesRanking,
        List<SupplyDashboardProductSalesItemView> brandSalesRanking,
        List<SupplyDashboardRankingItemView> paymentRiskCityRanking,
        List<SupplyDashboardRankingItemView> paymentRiskSalesRanking,
        List<SupplyDashboardPaymentAgingBucketView> paymentAgingBuckets,
        List<SupplyDashboardTargetCompletionItemView> cityTargetCompletions,
        List<SupplyDashboardTargetCompletionItemView> salesTargetCompletions,
        List<SupplyDashboardCustomerSegmentItemView> customerSegments,
        List<SupplyDashboardCustomerActivityItemView> customerActivityRanking,
        List<SupplyDashboardCustomerActivityItemView> customerChurnRiskRanking,
        List<SupplyDashboardInventoryItemSummaryView> inventoryItemSummary,
        List<SupplyDashboardInventoryReplenishmentItemView> inventoryReplenishment,
        List<SupplyDashboardCityCostItemView> cityCostRanking,
        List<SupplyDashboardRiskItemView> risks,
        List<SupplyDashboardDataFreshnessView> freshness,
        List<SupplyDashboardRolePerspectiveView> rolePerspectives,
        List<SupplyDashboardMetricDefinitionView> definitions) {
    public SupplyDashboardOverviewView {
        metrics = List.copyOf(metrics == null ? List.of() : metrics);
        salesTrend = List.copyOf(salesTrend == null ? List.of() : salesTrend);
        collectionTrend = List.copyOf(collectionTrend == null ? List.of() : collectionTrend);
        cityCostTrend = List.copyOf(cityCostTrend == null ? List.of() : cityCostTrend);
        citySalesRanking = List.copyOf(citySalesRanking == null ? List.of() : citySalesRanking);
        salesRanking = List.copyOf(salesRanking == null ? List.of() : salesRanking);
        salesMonthlyPerformance = List.copyOf(salesMonthlyPerformance == null ? List.of() : salesMonthlyPerformance);
        cityCollectionRateRanking = List.copyOf(cityCollectionRateRanking == null ? List.of() : cityCollectionRateRanking);
        sourceSystemBreakdown = List.copyOf(sourceSystemBreakdown == null ? List.of() : sourceSystemBreakdown);
        productSalesRanking = List.copyOf(productSalesRanking == null ? List.of() : productSalesRanking);
        skuSalesRanking = List.copyOf(skuSalesRanking == null ? List.of() : skuSalesRanking);
        categorySalesRanking = List.copyOf(categorySalesRanking == null ? List.of() : categorySalesRanking);
        brandSalesRanking = List.copyOf(brandSalesRanking == null ? List.of() : brandSalesRanking);
        paymentRiskCityRanking = List.copyOf(paymentRiskCityRanking == null ? List.of() : paymentRiskCityRanking);
        paymentRiskSalesRanking = List.copyOf(paymentRiskSalesRanking == null ? List.of() : paymentRiskSalesRanking);
        paymentAgingBuckets = List.copyOf(paymentAgingBuckets == null ? List.of() : paymentAgingBuckets);
        cityTargetCompletions = List.copyOf(cityTargetCompletions == null ? List.of() : cityTargetCompletions);
        salesTargetCompletions = List.copyOf(salesTargetCompletions == null ? List.of() : salesTargetCompletions);
        customerSegments = List.copyOf(customerSegments == null ? List.of() : customerSegments);
        customerActivityRanking = List.copyOf(customerActivityRanking == null ? List.of() : customerActivityRanking);
        customerChurnRiskRanking = List.copyOf(customerChurnRiskRanking == null ? List.of() : customerChurnRiskRanking);
        inventoryItemSummary = List.copyOf(inventoryItemSummary == null ? List.of() : inventoryItemSummary);
        inventoryReplenishment = List.copyOf(inventoryReplenishment == null ? List.of() : inventoryReplenishment);
        cityCostRanking = List.copyOf(cityCostRanking == null ? List.of() : cityCostRanking);
        risks = List.copyOf(risks == null ? List.of() : risks);
        freshness = List.copyOf(freshness == null ? List.of() : freshness);
        rolePerspectives = List.copyOf(rolePerspectives == null ? List.of() : rolePerspectives);
        definitions = List.copyOf(definitions == null ? List.of() : definitions);
    }
}

package com.rigour.analytics.application.port.out;

import com.rigour.analytics.application.model.SupplyDashboardFilter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 供应链看板读模型仓储。 */
public interface SupplyDashboardStore {
    SupplyDashboardData overview(String tenantId, SupplyDashboardFilter filter);

    default Optional<Instant> latestSalesOrderDate(String tenantId) {
        return Optional.empty();
    }

    TrustData trust(String tenantId);

    ReconciliationData reconciliation(String tenantId, SupplyDashboardFilter filter);

    FilterOptions filterOptions(String tenantId);

    List<String> refreshTenantIds();

    boolean acquireRefreshLock(String tenantId, String lockCode, Instant now, Instant lockedUntil);

    void releaseRefreshLock(String tenantId, String lockCode, String statusCode, Instant now, String failureReason);

    RefreshRun createRefreshRun(String tenantId, String jobCode, Instant startedAt);

    RefreshRun completeRefreshRun(
            Long runId, Instant completedAt, Instant watermarkTime,
            long pulledCount, long upsertedCount, long skippedCount);

    RefreshRun failRefreshRun(
            Long runId, Instant completedAt, long pulledCount, long upsertedCount,
            long skippedCount, String failureReason);

    Optional<Instant> checkpointWatermark(String tenantId, String sourceCode);

    default boolean refreshTargetNeedsBackfill(String tenantId, String sourceCode) {
        return false;
    }

    void updateCheckpoint(
            String tenantId, String sourceCode, String sourceName, Instant watermarkTime,
            Instant successTime, Long runId);

    SourceRefreshResult refreshCustomerDim(String tenantId, Instant from, Instant to, Instant syncedAt);

    SourceRefreshResult refreshSalesOrderFact(String tenantId, Instant from, Instant to, Instant syncedAt);

    SourceRefreshResult refreshSalesOrderLineFact(String tenantId, Instant from, Instant to, Instant syncedAt);

    SourceRefreshResult refreshProductDim(String tenantId, Instant from, Instant to, Instant syncedAt);

    SourceRefreshResult refreshSalesPaymentFact(String tenantId, Instant from, Instant to, Instant syncedAt);

    long backfillCustomerRegionAttribution(String tenantId, Instant syncedAt);

    SourceRefreshResult refreshInventoryBalanceCurrent(String tenantId, Instant syncedAt);

    SourceRefreshResult refreshInventoryOperationFact(String tenantId, Instant syncedAt);

    SourceRefreshResult refreshReconciliationCurrent(String tenantId, Instant from, Instant to, Instant observedAt);

    CityCostImportResult importCityCostRecords(String tenantId, List<CityCostImportRow> rows, Instant importedAt);

    FeishuArchiveRow registerFeishuArchive(String tenantId, FeishuArchiveWrite command, Instant registeredAt);

    List<FeishuArchiveRow> feishuArchives(String tenantId);

    record SupplyDashboardData(
            SalesSummary sales,
            CustomerSummary customers,
            CollectionSummary collections,
            ProfitSummary profit,
            PaymentRiskSummary paymentRisk,
            CityCostSummary cityCost,
            List<TrendPoint> salesTrend,
            List<TrendPoint> collectionTrend,
            List<TrendPoint> cityCostTrend,
            List<RankingItem> citySalesRanking,
            List<RankingItem> salesRanking,
            List<SalesMonthlyPerformanceItem> salesMonthlyPerformance,
            List<RankingItem> cityCollectionRateRanking,
            List<RankingItem> sourceSystemBreakdown,
            List<ProductSalesItem> productSalesRanking,
            List<ProductSalesItem> skuSalesRanking,
            List<ProductSalesItem> categorySalesRanking,
            List<ProductSalesItem> brandSalesRanking,
            List<RankingItem> paymentRiskCityRanking,
            List<RankingItem> paymentRiskSalesRanking,
            List<PaymentAgingBucket> paymentAgingBuckets,
            List<TargetCompletionItem> cityTargetCompletions,
            List<TargetCompletionItem> salesTargetCompletions,
            List<CustomerSegmentItem> customerSegments,
            List<CustomerActivityItem> customerActivityRanking,
            List<CustomerActivityItem> customerChurnRiskRanking,
            List<InventoryItemSummary> inventoryItemSummary,
            List<InventoryReplenishmentItem> inventoryReplenishment,
            List<CityCostItem> cityCostRanking,
            List<RiskItem> risks,
            List<DataFreshness> freshness) {
        public SupplyDashboardData {
            sales = sales == null ? SalesSummary.empty() : sales;
            customers = customers == null ? CustomerSummary.empty() : customers;
            collections = collections == null ? CollectionSummary.empty() : collections;
            profit = profit == null ? ProfitSummary.empty() : profit;
            paymentRisk = paymentRisk == null ? PaymentRiskSummary.empty() : paymentRisk;
            cityCost = cityCost == null ? CityCostSummary.empty() : cityCost;
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
        }
    }

    record SalesSummary(
            Long orderCount,
            Long orderingCustomerCount,
            Long repeatCustomerCount,
            BigDecimal totalQuantity,
            BigDecimal salesAmount,
            BigDecimal paidAmount,
            BigDecimal unpaidAmount,
            Long unpaidOrderCount,
            Instant latestUpdatedTime) {
        static SalesSummary empty() {
            return new SalesSummary(0L, 0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, 0L, null);
        }
    }

    record CustomerSummary(Long activeCustomerCount, Long contactedCustomerCount, Instant latestUpdatedTime) {
        static CustomerSummary empty() {
            return new CustomerSummary(0L, 0L, null);
        }
    }

    record CollectionSummary(Long paymentCount, BigDecimal receiptAmount, Instant latestUpdatedTime) {
        static CollectionSummary empty() {
            return new CollectionSummary(0L, BigDecimal.ZERO, null);
        }
    }

    record ProfitSummary(
            BigDecimal salesAmount,
            BigDecimal discountAmount,
            BigDecimal refundAmount,
            BigDecimal salesNetAmount,
            BigDecimal estimatedCostAmount,
            BigDecimal estimatedGrossProfit,
            BigDecimal estimatedGrossProfitRate,
            BigDecimal costCoverageRate,
            Instant latestUpdatedTime) {
        static ProfitSummary empty() {
            return new ProfitSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
        }
    }

    record PaymentRiskSummary(
            BigDecimal riskAmount,
            Long riskCustomerCount,
            Long highRiskCustomerCount,
            BigDecimal averageOverdueDays,
            BigDecimal riskAmountRate,
            Instant latestUpdatedTime) {
        static PaymentRiskSummary empty() {
            return new PaymentRiskSummary(BigDecimal.ZERO, 0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, null);
        }
    }

    record CityCostSummary(
            Long recordCount,
            BigDecimal costAmount,
            BigDecimal budgetAmount,
            Instant latestUpdatedTime) {
        static CityCostSummary empty() {
            return new CityCostSummary(0L, BigDecimal.ZERO, BigDecimal.ZERO, null);
        }
    }

    record TrendPoint(String metricCode, String period, BigDecimal value, BigDecimal secondaryValue) {
    }

    record RankingItem(
            String rankType,
            String dimensionCode,
            String dimensionName,
            String regionCode,
            String regionName,
            BigDecimal salesAmount,
            BigDecimal paidAmount,
            BigDecimal unpaidAmount,
            Long orderCount,
            Long customerCount,
            BigDecimal rate) {
    }

    record SalesMonthlyPerformanceItem(
            String period,
            String ownerStaffCode,
            String ownerStaffName,
            String regionCode,
            String regionName,
            BigDecimal salesAmount,
            BigDecimal paidAmount,
            BigDecimal unpaidAmount,
            Long orderCount,
            Long customerCount,
            BigDecimal rate) {
    }

    record ProductSalesItem(
            String rankType,
            String dimensionCode,
            String dimensionName,
            String categoryCode,
            String categoryName,
            BigDecimal salesQuantity,
            BigDecimal salesAmount,
            BigDecimal discountAmount,
            BigDecimal refundAmount,
            BigDecimal salesNetAmount,
            BigDecimal estimatedCostAmount,
            BigDecimal estimatedGrossProfit,
            BigDecimal estimatedGrossProfitRate,
            BigDecimal costCoverageRate,
            Long orderCount,
            Long customerCount) {
    }

    record PaymentAgingBucket(
            String bucketCode,
            String bucketName,
            Long orderCount,
            Long customerCount,
            BigDecimal unpaidAmount) {
    }

    record CityCostItem(
            String regionCode,
            String regionName,
            BigDecimal costAmount,
            BigDecimal budgetAmount,
            BigDecimal varianceAmount,
            BigDecimal salesAmount,
            BigDecimal costRate,
            Long recordCount,
            Instant latestCostTime) {
    }

    record TargetCompletionItem(
            String dimensionType,
            String dimensionCode,
            String dimensionName,
            String metricCode,
            String metricName,
            BigDecimal targetValue,
            BigDecimal actualValue,
            BigDecimal achievementRate) {
    }

    record CustomerSegmentItem(
            String segmentCode,
            String segmentName,
            Long customerCount,
            BigDecimal salesAmount,
            BigDecimal paidAmount,
            BigDecimal unpaidAmount,
            BigDecimal averageActivityScore,
            Long churnRiskCustomerCount) {
    }

    record CustomerActivityItem(
            String customerCode,
            String customerName,
            String regionCode,
            String regionName,
            String ownerStaffCode,
            String ownerStaffName,
            String customerTypeCode,
            String customerTypeName,
            String segmentCode,
            String segmentName,
            BigDecimal salesAmount,
            BigDecimal paidAmount,
            BigDecimal unpaidAmount,
            Long orderCount,
            Long paymentCount,
            Instant lastOrderTime,
            Instant lastPaymentTime,
            Long inactiveDays,
            BigDecimal activityScore,
            String churnRiskLevel) {
    }

    record InventoryItemSummary(
            String categoryCode,
            String categoryName,
            String unitCode,
            BigDecimal procurementQuantity,
            BigDecimal shippedQuantity,
            BigDecimal remainingQuantity,
            BigDecimal inactiveRemainingQuantity) {
    }

    record InventoryReplenishmentItem(
            String categoryCode,
            String categoryName,
            String productCode,
            String productName,
            String unitCode,
            BigDecimal salesQuantity,
            BigDecimal dailySalesQuantity,
            BigDecimal availableQuantity,
            BigDecimal inTransitQuantity,
            BigDecimal coverageDays,
            BigDecimal suggestedProcurementQuantity,
            String riskLevel,
            String inventoryStatus) {
    }

    record RiskItem(
            String riskType,
            String riskLevel,
            String dimensionCode,
            String dimensionName,
            String description,
            BigDecimal primaryValue,
            BigDecimal secondaryValue,
            Instant observedAt) {
    }

    record DataFreshness(
            String sourceCode,
            String sourceName,
            Instant latestUpdatedTime,
            String status,
            String description) {
    }

    record RefreshRun(
            Long id,
            String jobCode,
            String tenantId,
            String statusCode,
            Instant startedTime,
            Instant completedTime,
            Instant watermarkTime,
            Long pulledCount,
            Long upsertedCount,
            Long skippedCount,
            String failureReason) {
    }

    record SourceRefreshResult(
            String sourceCode,
            String sourceName,
            Long pulledCount,
            Long upsertedCount,
            Long skippedCount,
            Instant watermarkTime) {
        public SourceRefreshResult {
            pulledCount = pulledCount == null ? 0L : pulledCount;
            upsertedCount = upsertedCount == null ? 0L : upsertedCount;
            skippedCount = skippedCount == null ? 0L : skippedCount;
        }
    }

    record CityCostImportRow(
            String sourceSystemCode,
            String sourceRecordId,
            String regionCode,
            String regionName,
            String costTypeCode,
            String costTypeName,
            Instant costDate,
            BigDecimal costAmount,
            BigDecimal budgetAmount,
            String remark) {
    }

    record CityCostImportResult(Integer receivedCount, Integer upsertedCount, Instant importedAt) {
    }

    record TrustData(RefreshRun latestRun, List<TrustSource> sources) {
        public TrustData {
            sources = List.copyOf(sources == null ? List.of() : sources);
        }
    }

    record TrustSource(
            String sourceCode,
            String sourceName,
            Instant checkpointWatermarkTime,
            Instant lastSuccessTime,
            String checkpointStatus,
            Long lastRunId,
            String lastRunStatus,
            Instant lastRunStartedTime,
            Instant lastRunCompletedTime,
            Long pulledCount,
            Long upsertedCount,
            Long skippedCount,
            String failureReason) {
    }

    record ReconciliationData(Instant from, Instant to, Instant observedAt, List<ReconciliationItem> items) {
        public ReconciliationData {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    record ReconciliationItem(
            String subjectCode,
            String subjectName,
            Long sourceRowCount,
            Long businessRowCount,
            Long biRowCount,
            BigDecimal sourceAmount,
            BigDecimal businessAmount,
            BigDecimal biAmount) {
    }

    record FilterOptions(
            List<FilterOption> regions,
            List<FilterOption> salesOwners,
            List<FilterOption> customerTypes,
            List<FilterOption> productCategories,
            List<FilterOption> sourceSystems) {
        public FilterOptions {
            regions = List.copyOf(regions == null ? List.of() : regions);
            salesOwners = List.copyOf(salesOwners == null ? List.of() : salesOwners);
            customerTypes = List.copyOf(customerTypes == null ? List.of() : customerTypes);
            productCategories = List.copyOf(productCategories == null ? List.of() : productCategories);
            sourceSystems = List.copyOf(sourceSystems == null ? List.of() : sourceSystems);
        }
    }

    record FilterOption(
            String optionType,
            String optionValue,
            String optionLabel,
            Long usageCount,
            String parentOptionValue,
            Long categoryLevel,
            Long ordinal) {
        public FilterOption(String optionType, String optionValue, String optionLabel, Long usageCount) {
            this(optionType, optionValue, optionLabel, usageCount, null, null, null);
        }
    }

    record FeishuArchiveWrite(
            String archiveCode,
            String tableId,
            String viewId,
            String tableName,
            String fileName,
            String fileFormat,
            String exportedBy,
            Instant exportedTime,
            Instant frozenTime,
            Long recordCount,
            String checksumSha256,
            String storageUri,
            String fieldMappingUri,
            String reconciliationReportUri,
            String remark) {
    }

    record FeishuArchiveRow(
            Long id,
            String archiveCode,
            String tableId,
            String viewId,
            String tableName,
            String fileName,
            String fileFormat,
            String exportedBy,
            Instant exportedTime,
            Instant frozenTime,
            Long recordCount,
            String checksumSha256,
            String storageUri,
            String fieldMappingUri,
            String reconciliationReportUri,
            String archiveStatusCode,
            String remark,
            Instant createdTime,
            Instant updatedTime) {
    }
}

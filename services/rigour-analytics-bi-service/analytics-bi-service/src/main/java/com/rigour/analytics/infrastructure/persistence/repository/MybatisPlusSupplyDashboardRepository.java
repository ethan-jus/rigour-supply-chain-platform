package com.rigour.analytics.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.application.port.out.SupplyDashboardStore;
import com.rigour.analytics.infrastructure.persistence.entity.SupplyDashboardSourceMarkerEntity;
import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis-Plus 供应链 BI 看板仓储。 */
@Repository
public class MybatisPlusSupplyDashboardRepository
        extends ServiceImpl<SupplyDashboardQueryMapper, SupplyDashboardSourceMarkerEntity>
        implements SupplyDashboardStore {
    public MybatisPlusSupplyDashboardRepository(SupplyDashboardQueryMapper mapper) {
        this.baseMapper = mapper;
    }

    @Override
    public SupplyDashboardData overview(String tenantId, SupplyDashboardFilter filter) {
        LocalDateTime from = local(filter.from());
        LocalDateTime to = local(filter.to());
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        SalesSummary sales = sales(mapper.salesSummary(tenantId, from, to,
                filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode(), filter.sourceSystemCode()));
        CustomerSummary customers = customers(mapper.customerSummary(tenantId,
                filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode()));
        CollectionSummary collections = collections(mapper.collectionSummary(tenantId, from, to,
                filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode(), filter.sourceSystemCode()));
        ProfitSummary profit = profit(mapper.profitSummary(tenantId, from, to,
                filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode(),
                filter.productCategoryId(), filter.sourceSystemCode()));
        PaymentRiskSummary paymentRisk = paymentRisk(mapper.paymentRiskSummary(tenantId, from, to,
                filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode(), filter.sourceSystemCode()));
        CityCostSummary cityCost = cityCostSummary(mapper.cityCostSummary(tenantId, from, to, filter.regionCode()));
        List<RankingItem> citySalesRanking = mapper.citySalesRanking(tenantId, from, to,
                        filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode(), filter.sourceSystemCode())
                .stream().map(MybatisPlusSupplyDashboardRepository::ranking).toList();
        return new SupplyDashboardData(
                sales,
                customers,
                collections,
                profit,
                paymentRisk,
                cityCost,
                mapper.salesTrend(tenantId, from, to,
                        filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode(), filter.sourceSystemCode())
                        .stream().map(MybatisPlusSupplyDashboardRepository::trend).toList(),
                mapper.collectionTrend(tenantId, from, to,
                        filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode(), filter.sourceSystemCode())
                        .stream().map(MybatisPlusSupplyDashboardRepository::trend).toList(),
                mapper.cityCostTrend(tenantId, from, to, filter.regionCode())
                        .stream().map(MybatisPlusSupplyDashboardRepository::trend).toList(),
                citySalesRanking,
                mapper.salesRanking(tenantId, from, to,
                        filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode(), filter.sourceSystemCode())
                        .stream().map(MybatisPlusSupplyDashboardRepository::ranking).toList(),
                mapper.sourceSystemBreakdown(tenantId, from, to,
                        filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode(), filter.sourceSystemCode())
                        .stream().map(MybatisPlusSupplyDashboardRepository::ranking).toList(),
                mapper.productSalesRanking(tenantId, from, to,
                        filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode(),
                        filter.productCategoryId(), filter.sourceSystemCode())
                        .stream().map(MybatisPlusSupplyDashboardRepository::productSales).toList(),
                mapper.categorySalesRanking(tenantId, from, to,
                        filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode(),
                        filter.productCategoryId(), filter.sourceSystemCode())
                        .stream().map(MybatisPlusSupplyDashboardRepository::productSales).toList(),
                mapper.brandSalesRanking(tenantId, from, to,
                        filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode(),
                        filter.productCategoryId(), filter.sourceSystemCode())
                        .stream().map(MybatisPlusSupplyDashboardRepository::productSales).toList(),
                mapper.paymentRiskCityRanking(tenantId, from, to,
                        filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode(), filter.sourceSystemCode())
                        .stream().map(MybatisPlusSupplyDashboardRepository::ranking).toList(),
                mapper.paymentRiskSalesRanking(tenantId, from, to,
                        filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode(), filter.sourceSystemCode())
                        .stream().map(MybatisPlusSupplyDashboardRepository::ranking).toList(),
                mapper.cityCostRanking(tenantId, from, to,
                        filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode(), filter.sourceSystemCode())
                        .stream().map(MybatisPlusSupplyDashboardRepository::cityCost).toList(),
                mapper.inventoryRisks(tenantId, filter.regionCode(), filter.productCategoryId())
                        .stream().map(MybatisPlusSupplyDashboardRepository::risk).toList(),
                mapper.dataFreshness(tenantId).stream().map(MybatisPlusSupplyDashboardRepository::freshness).toList());
    }

    @Override
    public TrustData trust(String tenantId) {
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        return new TrustData(
                runOrNull(mapper.latestRefreshRun(tenantId)),
                mapper.trustSources(tenantId).stream().map(MybatisPlusSupplyDashboardRepository::trustSource).toList());
    }

    @Override
    public ReconciliationData reconciliation(String tenantId, SupplyDashboardFilter filter) {
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        List<Map<String, Object>> rows = mapper.currentReconciliation(tenantId);
        return new ReconciliationData(
                minInstant(rows, "fromTime"),
                maxInstant(rows, "toTime"),
                maxInstant(rows, "observedAt"),
                rows.stream().map(MybatisPlusSupplyDashboardRepository::reconciliation).toList());
    }

    @Override
    public FilterOptions filterOptions(String tenantId) {
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        return new FilterOptions(
                mapper.regionOptions(tenantId).stream().map(MybatisPlusSupplyDashboardRepository::option).toList(),
                mapper.salesOwnerOptions(tenantId).stream().map(MybatisPlusSupplyDashboardRepository::option).toList(),
                mapper.customerTypeOptions(tenantId).stream().map(MybatisPlusSupplyDashboardRepository::option).toList(),
                mapper.productCategoryOptions(tenantId).stream().map(MybatisPlusSupplyDashboardRepository::option).toList(),
                mapper.sourceSystemOptions(tenantId).stream().map(MybatisPlusSupplyDashboardRepository::option).toList());
    }

    @Override
    public List<String> refreshTenantIds() {
        return getBaseMapper().refreshTenantIds();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean acquireRefreshLock(String tenantId, String lockCode, Instant now, Instant lockedUntil) {
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        LocalDateTime localNow = local(now);
        mapper.ensureRefreshLock(tenantId, lockCode, "供应链 BI 刷新锁", localNow);
        return mapper.acquireRefreshLock(tenantId, lockCode, localNow, local(lockedUntil)) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseRefreshLock(
            String tenantId, String lockCode, String statusCode, Instant now, String failureReason) {
        getBaseMapper().releaseRefreshLock(tenantId, lockCode, statusCode, local(now), failureReason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RefreshRun createRefreshRun(String tenantId, String jobCode, Instant startedAt) {
        LocalDateTime localStartedAt = local(startedAt);
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        mapper.insertRefreshRun(tenantId, jobCode, localStartedAt);
        return run(mapper.refreshRunByStart(tenantId, jobCode, localStartedAt));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RefreshRun completeRefreshRun(
            Long runId, Instant completedAt, Instant watermarkTime,
            long pulledCount, long upsertedCount, long skippedCount) {
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        mapper.completeRefreshRun(
                runId, local(completedAt), localOrNull(watermarkTime), pulledCount, upsertedCount, skippedCount);
        return run(mapper.refreshRun(runId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RefreshRun failRefreshRun(
            Long runId, Instant completedAt, long pulledCount, long upsertedCount,
            long skippedCount, String failureReason) {
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        mapper.failRefreshRun(runId, local(completedAt), pulledCount, upsertedCount, skippedCount, failureReason);
        return run(mapper.refreshRun(runId));
    }

    @Override
    public Optional<Instant> checkpointWatermark(String tenantId, String sourceCode) {
        return Optional.ofNullable(instant(getBaseMapper().checkpointWatermark(tenantId, sourceCode), "watermarkTime"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCheckpoint(
            String tenantId, String sourceCode, String sourceName, Instant watermarkTime,
            Instant successTime, Long runId) {
        getBaseMapper().updateCheckpoint(
                tenantId, sourceCode, sourceName, local(watermarkTime), local(successTime), runId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SourceRefreshResult refreshCustomerDim(String tenantId, Instant from, Instant to, Instant syncedAt) {
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        LocalDateTime localFrom = local(from);
        LocalDateTime localTo = local(to);
        Map<String, Object> summary = mapper.customerSourceSummary(tenantId, localFrom, localTo);
        int affected = mapper.upsertCustomerDimFromSource(tenantId, localFrom, localTo, local(syncedAt));
        return sourceResult("CRM_CUSTOMER", "客户/门店", summary, affected);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SourceRefreshResult refreshSalesOrderFact(String tenantId, Instant from, Instant to, Instant syncedAt) {
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        LocalDateTime localFrom = local(from);
        LocalDateTime localTo = local(to);
        Map<String, Object> summary = mapper.orderSourceSummary(tenantId, localFrom, localTo);
        int affected = mapper.upsertSalesOrderFactFromSource(tenantId, localFrom, localTo, local(syncedAt));
        return sourceResult("ORDER_SALES_ORDER", "销售订单", summary, affected);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SourceRefreshResult refreshSalesOrderLineFact(String tenantId, Instant from, Instant to, Instant syncedAt) {
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        LocalDateTime localFrom = local(from);
        LocalDateTime localTo = local(to);
        Map<String, Object> summary = mapper.orderLineSourceSummary(tenantId, localFrom, localTo);
        int affected = mapper.upsertSalesOrderLineFactFromSource(tenantId, localFrom, localTo, local(syncedAt));
        return sourceResult("ORDER_SALES_ORDER_LINE", "销售订单行", summary, affected);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SourceRefreshResult refreshProductDim(String tenantId, Instant from, Instant to, Instant syncedAt) {
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        LocalDateTime localFrom = local(from);
        LocalDateTime localTo = local(to);
        Map<String, Object> summary = mapper.productSourceSummary(tenantId, localFrom, localTo);
        int affected = mapper.upsertProductDimFromSource(tenantId, localFrom, localTo, local(syncedAt));
        return sourceResult("ERP_PRODUCT", "ERP商品", summary, affected);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SourceRefreshResult refreshSalesPaymentFact(String tenantId, Instant from, Instant to, Instant syncedAt) {
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        LocalDateTime localFrom = local(from);
        LocalDateTime localTo = local(to);
        Map<String, Object> summary = mapper.paymentSourceSummary(tenantId, localFrom, localTo);
        int affected = mapper.upsertSalesPaymentFactFromSource(tenantId, localFrom, localTo, local(syncedAt));
        return sourceResult("ORDER_PAYMENT_RECORD", "销售回款记录", summary, affected);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long backfillCustomerRegionAttribution(String tenantId, Instant syncedAt) {
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        LocalDateTime localSyncedAt = local(syncedAt);
        long affected = 0;
        affected += mapper.backfillOrderFactCustomerRegion(tenantId, localSyncedAt);
        affected += mapper.backfillOrderLineFactCustomerRegion(tenantId, localSyncedAt);
        affected += mapper.backfillPaymentFactCustomerRegion(tenantId, localSyncedAt);
        return affected;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SourceRefreshResult refreshInventoryBalanceCurrent(String tenantId, Instant syncedAt) {
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        Map<String, Object> summary = mapper.inventorySourceSummary(tenantId);
        LocalDateTime localSyncedAt = local(syncedAt);
        mapper.markInventorySnapshotDeleted(tenantId, localSyncedAt);
        int affected = mapper.upsertInventoryBalanceCurrentFromSource(tenantId, localSyncedAt);
        return sourceResult("ERP_STOCK_BALANCE", "库存余额", summary, affected);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SourceRefreshResult refreshReconciliationCurrent(String tenantId, Instant from, Instant to, Instant observedAt) {
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        LocalDateTime localFrom = local(from);
        LocalDateTime localTo = local(to);
        LocalDateTime localObservedAt = local(observedAt);
        List<ReconciliationItem> items = List.of(
                reconciliation(mapper.salesOrderReconciliation(tenantId, localFrom, localTo,
                        null, null, null, null)),
                reconciliation(mapper.paymentReconciliation(tenantId, localFrom, localTo,
                        null, null, null, null)),
                reconciliation(mapper.customerReconciliation(tenantId, null, null, null)),
                reconciliation(mapper.productReconciliation(tenantId, null)),
                reconciliation(mapper.inventoryReconciliation(tenantId, null, null)),
                reconciliation(mapper.cityCostReconciliation(tenantId, localFrom, localTo, null)));
        int affected = 0;
        for (ReconciliationItem item : items) {
            affected += mapper.upsertReconciliationCurrent(
                    tenantId,
                    item.subjectCode(),
                    item.subjectName(),
                    "TENANT_CURRENT_MONTH",
                    "本月全量",
                    localFrom,
                    localTo,
                    item.sourceRowCount(),
                    item.businessRowCount(),
                    item.biRowCount(),
                    item.sourceAmount(),
                    item.businessAmount(),
                    item.biAmount(),
                    localObservedAt);
        }
        return new SourceRefreshResult(
                "BI_RECONCILIATION_CURRENT", "对账快照", (long) items.size(), (long) affected, 0L, observedAt);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CityCostImportResult importCityCostRecords(String tenantId, List<CityCostImportRow> rows, Instant importedAt) {
        int upserted = 0;
        LocalDateTime localImportedAt = local(importedAt);
        for (CityCostImportRow row : rows) {
            int affected = getBaseMapper().upsertCityCostRecord(
                    tenantId,
                    row.sourceSystemCode(),
                    row.sourceRecordId(),
                    row.regionCode(),
                    row.regionName(),
                    row.costTypeCode(),
                    row.costTypeName(),
                    local(row.costDate()),
                    row.costAmount(),
                    row.budgetAmount(),
                    row.remark(),
                    localImportedAt);
            if (affected > 0) upserted++;
        }
        return new CityCostImportResult(rows.size(), upserted, importedAt);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeishuArchiveRow registerFeishuArchive(String tenantId, FeishuArchiveWrite command, Instant registeredAt) {
        SupplyDashboardQueryMapper mapper = getBaseMapper();
        mapper.upsertFeishuArchive(
                tenantId,
                command.archiveCode(),
                command.tableId(),
                command.viewId(),
                command.tableName(),
                command.fileName(),
                command.fileFormat(),
                command.exportedBy(),
                local(command.exportedTime()),
                local(command.frozenTime()),
                command.recordCount(),
                command.checksumSha256(),
                command.storageUri(),
                command.fieldMappingUri(),
                command.reconciliationReportUri(),
                command.remark(),
                local(registeredAt));
        return feishuArchive(mapper.feishuArchiveByCode(tenantId, command.archiveCode()));
    }

    @Override
    public List<FeishuArchiveRow> feishuArchives(String tenantId) {
        return getBaseMapper().feishuArchives(tenantId).stream()
                .map(MybatisPlusSupplyDashboardRepository::feishuArchive)
                .toList();
    }

    private static SalesSummary sales(Map<String, Object> row) {
        return new SalesSummary(
                number(row, "orderCount"),
                number(row, "orderingCustomerCount"),
                decimal(row, "totalQuantity"),
                decimal(row, "salesAmount"),
                decimal(row, "paidAmount"),
                decimal(row, "unpaidAmount"),
                number(row, "unpaidOrderCount"),
                instant(row, "latestUpdatedTime"));
    }

    private static CustomerSummary customers(Map<String, Object> row) {
        return new CustomerSummary(number(row, "activeCustomerCount"), instant(row, "latestUpdatedTime"));
    }

    private static CollectionSummary collections(Map<String, Object> row) {
        return new CollectionSummary(
                number(row, "paymentCount"),
                decimal(row, "receiptAmount"),
                instant(row, "latestUpdatedTime"));
    }

    private static ProfitSummary profit(Map<String, Object> row) {
        return new ProfitSummary(
                decimal(row, "salesAmount"),
                decimal(row, "discountAmount"),
                decimal(row, "refundAmount"),
                decimal(row, "salesNetAmount"),
                decimal(row, "estimatedCostAmount"),
                decimal(row, "estimatedGrossProfit"),
                decimal(row, "estimatedGrossProfitRate"),
                decimal(row, "costCoverageRate"),
                instant(row, "latestUpdatedTime"));
    }

    private static PaymentRiskSummary paymentRisk(Map<String, Object> row) {
        return new PaymentRiskSummary(
                decimal(row, "riskAmount"),
                number(row, "riskCustomerCount"),
                number(row, "highRiskCustomerCount"),
                decimal(row, "averageOverdueDays"),
                decimal(row, "riskAmountRate"),
                instant(row, "latestUpdatedTime"));
    }

    private static CityCostSummary cityCostSummary(Map<String, Object> row) {
        return new CityCostSummary(
                number(row, "recordCount"),
                decimal(row, "costAmount"),
                decimal(row, "budgetAmount"),
                instant(row, "latestUpdatedTime"));
    }

    private static TrendPoint trend(Map<String, Object> row) {
        return new TrendPoint(
                text(row, "metricCode"),
                text(row, "period"),
                decimal(row, "value"),
                decimal(row, "secondaryValue"));
    }

    private static RankingItem ranking(Map<String, Object> row) {
        return new RankingItem(
                text(row, "rankType"),
                text(row, "dimensionCode"),
                text(row, "dimensionName"),
                decimal(row, "salesAmount"),
                decimal(row, "paidAmount"),
                decimal(row, "unpaidAmount"),
                number(row, "orderCount"),
                number(row, "customerCount"),
                decimal(row, "rate"));
    }

    private static ProductSalesItem productSales(Map<String, Object> row) {
        return new ProductSalesItem(
                text(row, "rankType"),
                text(row, "dimensionCode"),
                text(row, "dimensionName"),
                text(row, "categoryCode"),
                text(row, "categoryName"),
                decimal(row, "salesQuantity"),
                decimal(row, "salesAmount"),
                decimal(row, "discountAmount"),
                decimal(row, "refundAmount"),
                decimal(row, "salesNetAmount"),
                decimal(row, "estimatedCostAmount"),
                decimal(row, "estimatedGrossProfit"),
                decimal(row, "estimatedGrossProfitRate"),
                decimal(row, "costCoverageRate"),
                number(row, "orderCount"),
                number(row, "customerCount"));
    }

    private static CityCostItem cityCost(Map<String, Object> row) {
        String regionCode = text(row, "regionCode");
        BigDecimal costAmount = decimal(row, "costAmount");
        return new CityCostItem(
                regionCode,
                text(row, "regionName"),
                costAmount,
                decimal(row, "budgetAmount"),
                decimal(row, "varianceAmount"),
                decimal(row, "salesAmount"),
                decimal(row, "costRate"),
                number(row, "recordCount"),
                instant(row, "latestCostTime"));
    }

    private static RiskItem risk(Map<String, Object> row) {
        return new RiskItem(
                text(row, "riskType"),
                text(row, "riskLevel"),
                text(row, "dimensionCode"),
                text(row, "dimensionName"),
                text(row, "description"),
                decimal(row, "primaryValue"),
                decimal(row, "secondaryValue"),
                instant(row, "observedAt"));
    }

    private static DataFreshness freshness(Map<String, Object> row) {
        Instant latest = instant(row, "latestUpdatedTime");
        String status = latest == null ? "EMPTY" : "READY";
        String sourceName = text(row, "sourceName");
        String description = latest == null ? sourceName + "暂无数据" : sourceName + "已接入";
        return new DataFreshness(text(row, "sourceCode"), sourceName, latest, status, description);
    }

    private static RefreshRun run(Map<String, Object> row) {
        return new RefreshRun(
                number(row, "id"),
                text(row, "jobCode"),
                text(row, "tenantId"),
                text(row, "statusCode"),
                instant(row, "startedTime"),
                instant(row, "completedTime"),
                instant(row, "watermarkTime"),
                number(row, "pulledCount"),
                number(row, "upsertedCount"),
                number(row, "skippedCount"),
                text(row, "failureReason"));
    }

    private static RefreshRun runOrNull(Map<String, Object> row) {
        if (row == null || row.isEmpty()) return null;
        return run(row);
    }

    private static TrustSource trustSource(Map<String, Object> row) {
        return new TrustSource(
                text(row, "sourceCode"),
                text(row, "sourceName"),
                instant(row, "checkpointWatermarkTime"),
                instant(row, "lastSuccessTime"),
                text(row, "checkpointStatus"),
                number(row, "lastRunId"),
                text(row, "lastRunStatus"),
                instant(row, "lastRunStartedTime"),
                instant(row, "lastRunCompletedTime"),
                number(row, "pulledCount"),
                number(row, "upsertedCount"),
                number(row, "skippedCount"),
                text(row, "failureReason"));
    }

    private static ReconciliationItem reconciliation(Map<String, Object> row) {
        return new ReconciliationItem(
                text(row, "subjectCode"),
                text(row, "subjectName"),
                number(row, "sourceRowCount"),
                number(row, "businessRowCount"),
                number(row, "biRowCount"),
                decimal(row, "sourceAmount"),
                decimal(row, "businessAmount"),
                decimal(row, "biAmount"));
    }

    private static FilterOption option(Map<String, Object> row) {
        return new FilterOption(
                text(row, "optionType"),
                text(row, "optionValue"),
                text(row, "optionLabel"),
                number(row, "usageCount"),
                text(row, "parentOptionValue"),
                nullableNumber(row, "categoryLevel"),
                nullableNumber(row, "ordinal"));
    }

    private static FeishuArchiveRow feishuArchive(Map<String, Object> row) {
        return new FeishuArchiveRow(
                number(row, "id"),
                text(row, "archiveCode"),
                text(row, "tableId"),
                text(row, "viewId"),
                text(row, "tableName"),
                text(row, "fileName"),
                text(row, "fileFormat"),
                text(row, "exportedBy"),
                instant(row, "exportedTime"),
                instant(row, "frozenTime"),
                number(row, "recordCount"),
                text(row, "checksumSha256"),
                text(row, "storageUri"),
                text(row, "fieldMappingUri"),
                text(row, "reconciliationReportUri"),
                text(row, "archiveStatusCode"),
                text(row, "remark"),
                instant(row, "createdTime"),
                instant(row, "updatedTime"));
    }

    private static SourceRefreshResult sourceResult(
            String sourceCode, String sourceName, Map<String, Object> summary, long affected) {
        return new SourceRefreshResult(
                sourceCode,
                sourceName,
                number(summary, "rowCount"),
                affected,
                0L,
                instant(summary, "watermarkTime"));
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static LocalDateTime localOrNull(Instant instant) {
        return instant == null ? null : local(instant);
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? null : value.toString();
    }

    private static Long number(Map<String, Object> row, String key) {
        Object value = value(row, key);
        if (value instanceof Number number) return number.longValue();
        if (value == null || value.toString().isBlank()) return 0L;
        return Long.parseLong(value.toString());
    }

    private static Long nullableNumber(Map<String, Object> row, String key) {
        Object value = value(row, key);
        if (value instanceof Number number) return number.longValue();
        if (value == null || value.toString().isBlank()) return null;
        return Long.parseLong(value.toString());
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = value(row, key);
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        if (value == null || value.toString().isBlank()) return BigDecimal.ZERO;
        return new BigDecimal(value.toString());
    }

    private static Instant instant(Map<String, Object> row, String key) {
        Object value = value(row, key);
        if (value instanceof Instant instant) return instant;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC);
        if (value == null || value.toString().isBlank()) return null;
        return Instant.parse(value.toString());
    }

    private static Instant minInstant(List<Map<String, Object>> rows, String key) {
        return rows.stream()
                .map(row -> instant(row, key))
                .filter(value -> value != null)
                .min(Instant::compareTo)
                .orElse(null);
    }

    private static Instant maxInstant(List<Map<String, Object>> rows, String key) {
        return rows.stream()
                .map(row -> instant(row, key))
                .filter(value -> value != null)
                .max(Instant::compareTo)
                .orElse(null);
    }

    private static Object value(Map<String, Object> row, String key) {
        if (row == null || row.isEmpty()) return null;
        if (row.containsKey(key)) return row.get(key);
        String lower = key.toLowerCase();
        if (row.containsKey(lower)) return row.get(lower);
        String snake = snake(key);
        if (row.containsKey(snake)) return row.get(snake);
        return null;
    }

    private static String snake(String key) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char current = key.charAt(i);
            if (Character.isUpperCase(current)) {
                result.append('_').append(Character.toLowerCase(current));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }
}

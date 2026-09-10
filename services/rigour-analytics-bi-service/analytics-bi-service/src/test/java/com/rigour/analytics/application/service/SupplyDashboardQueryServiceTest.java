package com.rigour.analytics.application.service;

import com.rigour.analytics.AnalyticsBiServiceApplication;
import com.rigour.analytics.api.v1.model.SupplyDashboardOverviewView;
import com.rigour.analytics.api.v1.model.SupplyDashboardCityCostImportCommand;
import com.rigour.analytics.api.v1.model.SupplyDashboardCityCostImportRecord;
import com.rigour.analytics.api.v1.model.SupplyDashboardCityCostImportResultView;
import com.rigour.analytics.api.v1.model.SupplyDashboardDataTrustView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFeishuArchiveCommand;
import com.rigour.analytics.api.v1.model.SupplyDashboardFeishuArchiveView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionsView;
import com.rigour.analytics.api.v1.model.SupplyDashboardRefreshRunView;
import com.rigour.analytics.api.v1.model.SupplyDashboardRefreshCommand;
import com.rigour.analytics.api.v1.model.SupplyDashboardReconciliationView;
import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.application.port.out.SupplyDashboardStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CityCostImportResult;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CityCostImportRow;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CityCostItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CityCostSummary;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CollectionSummary;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CustomerActivityItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CustomerSegmentItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CustomerSummary;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.DataFreshness;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.FeishuArchiveRow;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.FeishuArchiveWrite;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.FilterOption;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.FilterOptions;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.InventoryItemSummary;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.InventoryReplenishmentItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.PaymentRiskSummary;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.ProductSalesItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.ProfitSummary;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.RankingItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.ReconciliationData;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.ReconciliationItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.RefreshRun;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.RiskItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.SalesMonthlyPerformanceItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.SalesSummary;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.SourceRefreshResult;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.SupplyDashboardData;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.TargetCompletionItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.TrustData;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.TrustSource;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.TrendPoint;
import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplyDashboardQueryServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb700-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb700-0000-7000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-25T08:00:00Z");

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void scheduledRefreshIsEnabledAndDefaultsToThirtyMinutes() throws NoSuchMethodException {
        assertThat(AnalyticsBiServiceApplication.class.getAnnotation(EnableScheduling.class)).isNotNull();
        Method refreshMethod = SupplyDashboardRefreshService.class.getMethod("refreshScheduledTenants");
        Scheduled scheduled = refreshMethod.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${rigour.analytics.supply-dashboard.refresh.fixed-delay-ms:1800000}");
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${rigour.analytics.supply-dashboard.refresh.initial-delay-ms:60000}");
    }

    @Test
    void sourceRefreshSqlUsesEmployeeColumnsFromSourceSchemas() throws NoSuchMethodException {
        String sourceSql = String.join("\n",
                mapperSql("upsertCustomerDimFromSource",
                        String.class, LocalDateTime.class, LocalDateTime.class, LocalDateTime.class),
                mapperSql("backfillCustomerContactSnapshots", String.class, LocalDateTime.class),
                mapperSql("upsertSalesOrderFactFromSource",
                        String.class, LocalDateTime.class, LocalDateTime.class, LocalDateTime.class),
                mapperSql("upsertSalesOrderLineFactFromSource",
                        String.class, LocalDateTime.class, LocalDateTime.class, LocalDateTime.class),
                mapperSql("upsertSalesPaymentFactFromSource",
                        String.class, LocalDateTime.class, LocalDateTime.class, LocalDateTime.class));

        assertThat(sourceSql)
                .contains("c.owner_employee_code")
                .contains("c.owner_employee_name_snapshot")
                .contains("o.owner_employee_code")
                .contains("o.owner_employee_name_snapshot")
                .doesNotContain("c.owner_staff_code")
                .doesNotContain("c.owner_staff_name_snapshot")
                .doesNotContain("o.owner_staff_code")
                .doesNotContain("o.owner_staff_name_snapshot");
    }

    @Test
    void reconciliationSqlIncludesFeishuRawRowsAsDiagnosticSource() throws NoSuchMethodException {
        String salesOrderSql = mapperSql("salesOrderReconciliation",
                String.class, LocalDateTime.class, LocalDateTime.class,
                String.class, String.class, String.class, String.class);
        String paymentSql = mapperSql("paymentReconciliation",
                String.class, LocalDateTime.class, LocalDateTime.class,
                String.class, String.class, String.class, String.class);

        assertThat(salesOrderSql)
                .contains("integration_feishu_import_raw_row")
                .contains("FEISHU_SALES_ORDER")
                .contains("feishu_sales_order_source")
                .contains("JSON_EXTRACT(r.row_json, '$.\"销售日期\"')")
                .contains("#{sourceSystemCode} = 'FEISHU'");
        assertThat(paymentSql)
                .contains("integration_feishu_import_raw_row")
                .contains("FEISHU_SALES_ORDER")
                .contains("FEISHU_PAYMENT_RECORD")
                .contains("feishu_payment_source")
                .contains("JSON_EXTRACT(r.row_json, '$.\"回款日期\"')")
                .contains("JSON_EXTRACT(r.row_json, '$.\"收款合计\"')")
                .contains("#{sourceSystemCode} = 'FEISHU'");
    }

    @Test
    void backfillHealthSqlComparesRowsAmountsCitiesAndSalesOwners() throws NoSuchMethodException {
        String healthSql = String.join("\n",
                mapperSql("orderSourceBackfillHealth", String.class),
                mapperSql("biSalesOrderFactBackfillHealth", String.class),
                mapperSql("orderLineSourceBackfillHealth", String.class),
                mapperSql("biSalesOrderLineFactBackfillHealth", String.class),
                mapperSql("paymentSourceBackfillHealth", String.class),
                mapperSql("biSalesPaymentFactBackfillHealth", String.class));

        assertThat(healthSql)
                .contains("rowCount")
                .contains("amount")
                .contains("regionCount")
                .contains("ownerCount")
                .contains("rowSignature")
                .contains("CRC32(CONCAT_WS('|',")
                .contains("rigour_order.order_sales_order")
                .contains("rigour_order.order_sales_order_line")
                .contains("rigour_order.order_payment_record")
                .contains("bi_sales_order_fact")
                .contains("bi_sales_order_line_fact")
                .contains("bi_sales_payment_fact");
    }

    @Test
    void customerRegionBackfillDoesNotOverrideOrderSnapshotRegion() throws NoSuchMethodException {
        String backfillSql = String.join("\n",
                mapperSql("backfillOrderFactCustomerRegion", String.class, LocalDateTime.class),
                mapperSql("backfillOrderLineFactCustomerRegion", String.class, LocalDateTime.class),
                mapperSql("backfillPaymentFactCustomerRegion", String.class, LocalDateTime.class));

        assertThat(backfillSql)
                .contains("o.region_code IS NULL")
                .contains("l.region_code IS NULL")
                .contains("p.region_code IS NULL")
                .doesNotContain("o.region_code <> c.region_code")
                .doesNotContain("l.region_code <> c.region_code")
                .doesNotContain("p.region_code <> c.region_code");
    }

    @Test
    void overviewIncludesCityCostSection() {
        FakeStore store = new FakeStore();
        SupplyDashboardQueryService service = new SupplyDashboardQueryService(
                store, Clock.fixed(NOW, ZoneOffset.UTC));
        TestAuthorizationContext.set(caller("analytics:dashboard:read"));

        SupplyDashboardOverviewView result = service.overview(null, null,
                " bj ", " RY202608220001 ", " store ", 10L, " dinghuobao ");

        assertThat(result.from()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(result.to()).isEqualTo(NOW);
        assertThat(store.filter.regionCode()).isEqualTo("BJ");
        assertThat(store.filter.ownerStaffCode()).isEqualTo("RY202608220001");
        assertThat(store.filter.customerTypeCode()).isEqualTo("STORE");
        assertThat(store.filter.productCategoryId()).isEqualTo(10L);
        assertThat(store.filter.sourceSystemCode()).isEqualTo("DINGHUOBAO");
        assertThat(result.metrics())
                .extracting("metricCode")
                .contains("refund_amount", "city_cost_amount", "city_cost_rate", "estimated_gross_profit",
                        "payment_risk_amount", "payment_avg_overdue_days", "target_achievement_rate",
                        "contacted_customer_count", "cooperated_customer_count", "repeat_customer_count");
        assertThat(result.metrics())
                .filteredOn(metric -> metric.metricCode().equals("paid_amount"))
                .singleElement()
                .satisfies(metric -> {
                    assertThat(metric.metricName()).isEqualTo("总回款额");
                    assertThat(metric.description()).contains("累计已收");
                });
        assertThat(result.metrics())
                .filteredOn(metric -> metric.metricCode().equals("payment_risk_amount"))
                .singleElement()
                .satisfies(metric -> assertThat(metric.description()).contains("超过客户账期"));
        assertThat(result.cityCostRanking()).hasSize(1);
        assertThat(result.cityCostRanking().getFirst().costAmount()).isEqualByComparingTo("300.00");
        assertThat(result.cityCostRanking().getFirst().costRate()).isEqualByComparingTo("10.00");
        assertThat(result.productSalesRanking()).hasSize(1);
        assertThat(result.productSalesRanking().getFirst().salesQuantity()).isEqualByComparingTo("12.00");
        assertThat(result.productSalesRanking().getFirst().estimatedGrossProfit()).isEqualByComparingTo("380.00");
        assertThat(result.categorySalesRanking()).hasSize(1);
        assertThat(result.categorySalesRanking().getFirst().dimensionName()).isEqualTo("台球用品");
        assertThat(result.brandSalesRanking()).hasSize(1);
        assertThat(result.brandSalesRanking().getFirst().dimensionName()).isEqualTo("瑞盖自营");
        assertThat(result.paymentRiskCityRanking()).hasSize(1);
        assertThat(result.paymentRiskCityRanking().getFirst().unpaidAmount()).isEqualByComparingTo("900.00");
        assertThat(result.paymentRiskSalesRanking()).hasSize(1);
        assertThat(result.paymentAgingBuckets()).hasSize(2);
        assertThat(result.paymentAgingBuckets().getFirst().bucketCode()).isEqualTo("CURRENT");
        assertThat(result.paymentAgingBuckets().get(1).unpaidAmount()).isEqualByComparingTo("900.00");
        assertThat(result.salesRanking().getFirst().regionName()).isEqualTo("北京");
        assertThat(result.salesMonthlyPerformance()).hasSize(1);
        assertThat(result.salesMonthlyPerformance().getFirst().period()).isEqualTo("2026-08");
        assertThat(result.salesMonthlyPerformance().getFirst().ownerStaffName()).isEqualTo("销售A");
        assertThat(result.cityCollectionRateRanking()).hasSize(1);
        assertThat(result.cityCollectionRateRanking().getFirst().rankType()).isEqualTo("CITY_COLLECTION_RATE");
        assertThat(result.paymentRiskSalesRanking().getFirst().regionCode()).isEqualTo("BJ");
        assertThat(result.cityTargetCompletions()).hasSize(1);
        assertThat(result.cityTargetCompletions().getFirst().metricCode()).isEqualTo("SALES_AMOUNT");
        assertThat(result.inventoryItemSummary()).hasSize(1);
        assertThat(result.inventoryItemSummary().getFirst().categoryName()).isEqualTo("台球用品");
        assertThat(result.inventoryItemSummary().getFirst().unitCode()).isEqualTo("件");
        assertThat(result.inventoryItemSummary().getFirst().inactiveRemainingQuantity()).isEqualByComparingTo("5.00");
        assertThat(result.inventoryReplenishment()).hasSize(1);
        assertThat(result.inventoryReplenishment().getFirst().productName()).isEqualTo("专业款皮头");
        assertThat(result.inventoryReplenishment().getFirst().coverageDays()).isEqualByComparingTo("12.50");
        assertThat(result.inventoryReplenishment().getFirst().inventoryStatus()).isEqualTo("HISTORICAL_STOCK");
        assertThat(result.sourceSystemBreakdown()).hasSize(1);
        assertThat(result.sourceSystemBreakdown().getFirst().rankType()).isEqualTo("SOURCE_SYSTEM");
        assertThat(result.sourceSystemBreakdown().getFirst().dimensionCode()).isEqualTo("DINGHUOBAO");
        assertThat(result.sourceSystemBreakdown().getFirst().dimensionName()).isEqualTo("订货宝");
        assertThat(result.skuSalesRanking()).hasSize(1);
        assertThat(result.skuSalesRanking().getFirst().rankType()).isEqualTo("SKU");
        assertThat(result.customerSegments()).hasSize(2);
        assertThat(result.customerSegments().getFirst().segmentCode()).isEqualTo("A");
        assertThat(result.customerActivityRanking()).hasSize(1);
        assertThat(result.customerActivityRanking().getFirst().activityScore()).isEqualByComparingTo("88.00");
        assertThat(result.customerChurnRiskRanking()).hasSize(1);
        assertThat(result.customerChurnRiskRanking().getFirst().churnRiskLevel()).isEqualTo("HIGH");
        assertThat(result.definitions())
                .extracting("metricCode")
                .contains("paid_amount", "unpaid_amount", "city_cost_amount", "city_cost_rate", "product_sales_amount",
                        "refund_amount", "estimated_gross_profit", "payment_risk_amount", "sku_sales_amount",
                        "payment_aging_bucket", "customer_abc_segment", "customer_activity_score", "customer_churn_risk");
        assertThat(result.definitions().getFirst().version()).isEqualTo("v1");
        assertThat(result.definitions().getFirst().exclusionRule()).contains("deleted=1");
        assertThat(result.rolePerspectives())
                .extracting("roleCode")
                .contains("CEO", "OPERATION", "SALES");
    }

    @Test
    void overviewDefaultsToLatestSalesOrderMonthWhenDateIsOmitted() {
        FakeStore store = new FakeStore();
        store.latestSalesOrderDate = Optional.of(Instant.parse("2026-08-27T09:27:06Z"));
        SupplyDashboardQueryService service = new SupplyDashboardQueryService(
                store, Clock.fixed(Instant.parse("2026-09-01T02:00:00Z"), ZoneOffset.UTC));
        TestAuthorizationContext.set(caller("analytics:dashboard:read"));

        SupplyDashboardOverviewView result = service.overview(null, null, null, null, null, null, null);

        assertThat(result.from()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(result.to()).isEqualTo(Instant.parse("2026-08-27T09:27:06Z"));
        assertThat(store.filter.from()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(store.filter.to()).isEqualTo(Instant.parse("2026-08-27T09:27:06Z"));
    }

    @Test
    void rejectsInvalidDateRange() {
        SupplyDashboardQueryService service = new SupplyDashboardQueryService(
                new FakeStore(), Clock.fixed(NOW, ZoneOffset.UTC));
        TestAuthorizationContext.set(caller("analytics:dashboard:read"));

        assertThatThrownBy(() -> service.overview(NOW, NOW.minusSeconds(1),
                null, null, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void overviewReturnsEmptySourceSystemBreakdownWhenStoreOmitsIt() {
        FakeStore store = new FakeStore();
        store.sourceSystemBreakdown = null;
        SupplyDashboardQueryService service = new SupplyDashboardQueryService(
                store, Clock.fixed(NOW, ZoneOffset.UTC));
        TestAuthorizationContext.set(caller("analytics:dashboard:read"));

        SupplyDashboardOverviewView result = service.overview(null, null,
                null, null, null, null, null);

        assertThat(result.sourceSystemBreakdown()).isEmpty();
    }

    @Test
    void overviewRequiresAnalyticsReadPermission() {
        SupplyDashboardQueryService service = new SupplyDashboardQueryService(
                new FakeStore(), Clock.fixed(NOW, ZoneOffset.UTC));
        TestAuthorizationContext.set(caller("order:read"));

        assertThatThrownBy(() -> service.overview(null, null, null, null, null, null, null))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void manualRefreshUsesCheckpointLookbackAndCompletesRun() {
        FakeStore store = new FakeStore();
        store.watermarks.put("CRM_CUSTOMER", Instant.parse("2026-08-25T07:00:00Z"));
        SupplyDashboardRefreshService service = new SupplyDashboardRefreshService(
                store, Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofHours(2), Duration.ofMinutes(55));
        TestAuthorizationContext.set(caller("analytics:refresh:write"));

        SupplyDashboardRefreshRunView result = service.refreshCurrentTenant();

        assertThat(result.statusCode()).isEqualTo("SUCCESS");
        assertThat(store.customerFrom).isEqualTo(Instant.parse("2026-08-25T05:00:00Z"));
        assertThat(store.orderFrom).isEqualTo(Instant.EPOCH);
        assertThat(result.pulledCount()).isEqualTo(24L);
        assertThat(result.upsertedCount()).isEqualTo(24L);
    }

    @Test
    void manualRefreshCanLimitSourceScope() {
        FakeStore store = new FakeStore();
        SupplyDashboardRefreshService service = new SupplyDashboardRefreshService(
                store, Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofHours(2), Duration.ofMinutes(55));
        TestAuthorizationContext.set(caller("analytics:refresh:write"));

        SupplyDashboardRefreshRunView result = service.refreshCurrentTenant(
                new SupplyDashboardRefreshCommand(List.of(" order_sales_order ", "ORDER_PAYMENT_RECORD")));

        assertThat(result.statusCode()).isEqualTo("SUCCESS");
        assertThat(store.refreshedSources).containsExactly("ORDER_SALES_ORDER", "ORDER_PAYMENT_RECORD");
        assertThat(store.customerFrom).isNull();
        assertThat(store.orderFrom).isEqualTo(Instant.EPOCH);
        assertThat(store.watermarks.keySet()).containsExactlyInAnyOrder(
                "ORDER_SALES_ORDER", "ORDER_PAYMENT_RECORD");
        assertThat(result.pulledCount()).isEqualTo(5L);
        assertThat(result.upsertedCount()).isEqualTo(5L);
    }

    @Test
    void manualFullRefreshIgnoresCheckpointWatermark() {
        FakeStore store = new FakeStore();
        store.watermarks.put("ORDER_SALES_ORDER", Instant.parse("2026-08-25T07:00:00Z"));
        SupplyDashboardRefreshService service = new SupplyDashboardRefreshService(
                store, Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofHours(2), Duration.ofMinutes(55));
        TestAuthorizationContext.set(caller("analytics:refresh:write"));

        SupplyDashboardRefreshRunView result = service.refreshCurrentTenant(
                new SupplyDashboardRefreshCommand(List.of("ORDER_SALES_ORDER"), true));

        assertThat(result.statusCode()).isEqualTo("SUCCESS");
        assertThat(store.orderFrom).isEqualTo(Instant.EPOCH);
        assertThat(result.pulledCount()).isEqualTo(3L);
        assertThat(result.upsertedCount()).isEqualTo(3L);
    }

    @Test
    void scheduledRefreshBackfillsWhenTargetRowsAreMissing() {
        FakeStore store = new FakeStore();
        store.watermarks.put("ORDER_SALES_ORDER", Instant.parse("2026-08-25T07:00:00Z"));
        store.backfillSources.add("ORDER_SALES_ORDER");
        SupplyDashboardRefreshService service = new SupplyDashboardRefreshService(
                store, Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofHours(2), Duration.ofMinutes(55));

        RefreshRun result = service.refreshTenant(TENANT_ID.toString(), "SUPPLY_DASHBOARD_HOURLY");

        assertThat(result.statusCode()).isEqualTo("SUCCESS");
        assertThat(store.orderFrom).isEqualTo(Instant.EPOCH);
        assertThat(result.pulledCount()).isEqualTo(24L);
        assertThat(result.upsertedCount()).isEqualTo(24L);
    }

    @Test
    void refreshContinuesOtherSourcesWhenOneSourceFails() {
        FakeStore store = new FakeStore();
        store.failingSources.add("CRM_CUSTOMER");
        SupplyDashboardRefreshService service = new SupplyDashboardRefreshService(
                store, Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofHours(2), Duration.ofMinutes(55));
        TestAuthorizationContext.set(caller("analytics:refresh:write"));

        SupplyDashboardRefreshRunView result = service.refreshCurrentTenant();

        assertThat(result.statusCode()).isEqualTo("FAILED");
        assertThat(result.failureReason()).contains("客户/门店刷新失败");
        assertThat(store.refreshedSources).containsExactly(
                "ORDER_SALES_ORDER",
                "ORDER_SALES_ORDER_LINE",
                "ERP_PRODUCT",
                "ORDER_PAYMENT_RECORD",
                "ERP_STOCK_BALANCE",
                "ERP_INVENTORY_OPERATION",
                "BI_RECONCILIATION_CURRENT");
        assertThat(store.orderFrom).isEqualTo(Instant.EPOCH);
        assertThat(result.pulledCount()).isEqualTo(22L);
        assertThat(result.upsertedCount()).isEqualTo(22L);
    }

    @Test
    void manualRefreshRejectsUnknownSourceScope() {
        SupplyDashboardRefreshService service = new SupplyDashboardRefreshService(
                new FakeStore(), Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofHours(2), Duration.ofMinutes(55));
        TestAuthorizationContext.set(caller("analytics:refresh:write"));

        assertThatThrownBy(() -> service.refreshCurrentTenant(
                new SupplyDashboardRefreshCommand(List.of("UNKNOWN_SOURCE"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void cityCostImportNormalizesRowsAndRequiresSourceRecordId() {
        FakeStore store = new FakeStore();
        SupplyDashboardCityCostImportService service = new SupplyDashboardCityCostImportService(
                store, Clock.fixed(NOW, ZoneOffset.UTC));
        TestAuthorizationContext.set(caller("analytics:city-cost:write"));

        SupplyDashboardCityCostImportResultView result = service.importRecords(
                new SupplyDashboardCityCostImportCommand(null, List.of(
                        new SupplyDashboardCityCostImportRecord(
                                " bj ", "北京", " marketing ", "市场", NOW,
                                new BigDecimal("120.50"), null, "fs-1", "投放"))));

        assertThat(result.receivedCount()).isEqualTo(1);
        assertThat(result.upsertedCount()).isEqualTo(1);
        assertThat(store.importedRows).hasSize(1);
        assertThat(store.importedRows.getFirst().sourceSystemCode()).isEqualTo("MANUAL_IMPORT");
        assertThat(store.importedRows.getFirst().regionCode()).isEqualTo("BJ");
        assertThat(store.importedRows.getFirst().costTypeCode()).isEqualTo("MARKETING");
        assertThat(store.importedRows.getFirst().budgetAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void governanceTrustShowsLatestRunAndDelayStatus() {
        FakeStore store = new FakeStore();
        SupplyDashboardGovernanceService service = new SupplyDashboardGovernanceService(
                store, Clock.fixed(NOW, ZoneOffset.UTC));
        TestAuthorizationContext.set(caller("analytics:dashboard:read"));

        SupplyDashboardDataTrustView result = service.trust();

        assertThat(result.latestRefreshRun().statusCode()).isEqualTo("SUCCESS");
        assertThat(result.sources()).hasSize(8);
        assertThat(result.sources())
                .extracting("sourceCode")
                .contains("ORDER_SALES_ORDER", "ORDER_SALES_ORDER_LINE", "CRM_CUSTOMER", "ERP_PRODUCT", "ORDER_PAYMENT_RECORD",
                        "ERP_STOCK_BALANCE", "ERP_INVENTORY_OPERATION", "BI_RECONCILIATION_CURRENT");
        assertThat(result.overallStatus()).isIn("OK", "WARN", "CRITICAL");
    }

    @Test
    void governanceReconciliationNormalizesDhbAliasAndReturnsDiff() {
        FakeStore store = new FakeStore();
        SupplyDashboardGovernanceService service = new SupplyDashboardGovernanceService(
                store, Clock.fixed(NOW, ZoneOffset.UTC));
        TestAuthorizationContext.set(caller("analytics:dashboard:read"));

        SupplyDashboardReconciliationView result = service.reconciliation(
                null, null, null, null, null, null, "dhb");

        assertThat(store.filter.sourceSystemCode()).isEqualTo("DINGHUOBAO");
        assertThat(result.status()).isEqualTo("DIFF");
        assertThat(result.items()).extracting("subjectCode").contains("SALES_ORDER", "ERP_PRODUCT");
    }

    @Test
    void governanceFilterOptionsFallsBackToSourceSystems() {
        SupplyDashboardGovernanceService service = new SupplyDashboardGovernanceService(
                new FakeStore(), Clock.fixed(NOW, ZoneOffset.UTC));
        TestAuthorizationContext.set(caller("analytics:dashboard:read"));

        SupplyDashboardFilterOptionsView result = service.filterOptions();

        assertThat(result.sourceSystems()).extracting("optionValue").contains("DINGHUOBAO", "FEISHU", "MANUAL");
    }

    @Test
    void feishuArchiveRequiresDedicatedWritePermissionAndValidChecksum() {
        FakeStore store = new FakeStore();
        SupplyDashboardGovernanceService service = new SupplyDashboardGovernanceService(
                store, Clock.fixed(NOW, ZoneOffset.UTC));
        TestAuthorizationContext.set(caller(
                "analytics:dashboard:read", "analytics:legacy-archive:write"));

        SupplyDashboardFeishuArchiveView result = service.registerFeishuArchive(
                new SupplyDashboardFeishuArchiveCommand(
                        " feishu_202608 ", "tbl-1", "view-1", "飞书旧看板",
                        "legacy.xlsx", "xlsx", "运营A",
                        NOW, NOW.minusSeconds(3600), 10L,
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        "cos://archive/legacy.xlsx", "cos://archive/field.md",
                        "cos://archive/report.md", "冻结归档"));

        assertThat(result.archiveCode()).isEqualTo("FEISHU_202608");
        assertThat(store.archiveWrite.fileFormat()).isEqualTo("XLSX");
        assertThat(store.archiveWrite.checksumSha256()).isEqualTo(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    private static CallerIdentity caller(String... permissions) {
        return new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("order"), Set.of(permissions));
    }

    private static String mapperSql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = SupplyDashboardQueryMapper.class.getMethod(methodName, parameterTypes);
        Insert insert = method.getAnnotation(Insert.class);
        if (insert != null) {
            return String.join("\n", insert.value());
        }
        Update update = method.getAnnotation(Update.class);
        if (update != null) {
            return String.join("\n", update.value());
        }
        Select select = method.getAnnotation(Select.class);
        if (select != null) {
            return String.join("\n", select.value());
        }
        throw new IllegalStateException("未找到 Mapper SQL 注解: " + methodName);
    }

    private static final class FakeStore implements SupplyDashboardStore {
        private SupplyDashboardFilter filter;
        private final Map<String, Instant> watermarks = new HashMap<>();
        private Instant customerFrom;
        private Instant orderFrom;
        private final List<String> refreshedSources = new ArrayList<>();
        private final Set<String> backfillSources = new HashSet<>();
        private final Set<String> failingSources = new HashSet<>();
        private Optional<Instant> latestSalesOrderDate = Optional.empty();
        private List<CityCostImportRow> importedRows = List.of();
        private List<RankingItem> sourceSystemBreakdown = List.of(new RankingItem(
                "SOURCE_SYSTEM", "DINGHUOBAO", "订货宝", null, null,
                new BigDecimal("3000.00"), new BigDecimal("2100.00"),
                new BigDecimal("900.00"), 3L, 2L, new BigDecimal("70.00")));
        private FeishuArchiveWrite archiveWrite;

        @Override
        public SupplyDashboardData overview(String tenantId, SupplyDashboardFilter filter) {
            this.filter = filter;
            return new SupplyDashboardData(
                    new SalesSummary(3L, 2L, 1L, new BigDecimal("30"),
                            new BigDecimal("3000.00"), new BigDecimal("2100.00"),
                            new BigDecimal("900.00"), 1L, NOW.minusSeconds(60)),
                    new CustomerSummary(20L, 16L, NOW.minusSeconds(50)),
                    new CollectionSummary(2L, new BigDecimal("2100.00"), NOW.minusSeconds(40)),
                    new ProfitSummary(
                            new BigDecimal("3000.00"), new BigDecimal("120.00"),
                            new BigDecimal("100.00"), new BigDecimal("2900.00"),
                            new BigDecimal("1800.00"), new BigDecimal("1100.00"),
                            new BigDecimal("37.93"), new BigDecimal("90.00"), NOW.minusSeconds(35)),
                    new PaymentRiskSummary(
                            new BigDecimal("900.00"), 2L, 1L,
                            new BigDecimal("45.00"), new BigDecimal("30.00"), NOW.minusSeconds(34)),
                    new CityCostSummary(1L, new BigDecimal("300.00"),
                            new BigDecimal("250.00"), NOW.minusSeconds(30)),
                    List.of(new TrendPoint("sales_amount", "2026-08-25",
                            new BigDecimal("3000.00"), new BigDecimal("2100.00"))),
                    List.of(new TrendPoint("receipt_amount", "2026-08-25",
                            new BigDecimal("2100.00"), new BigDecimal("2"))),
                    List.of(new TrendPoint("city_cost_amount", "2026-08-25",
                            new BigDecimal("300.00"), new BigDecimal("250.00"))),
                    List.of(new RankingItem("CITY_SALES", "BJ", "北京",
                            "BJ", "北京",
                            new BigDecimal("3000.00"), new BigDecimal("2100.00"),
                            new BigDecimal("900.00"), 3L, 2L, new BigDecimal("70.00"))),
                    List.of(new RankingItem("SALES_OWNER", "RY202608220001", "销售A",
                            "BJ", "北京",
                            new BigDecimal("3000.00"), new BigDecimal("2100.00"),
                            new BigDecimal("900.00"), 3L, 2L, new BigDecimal("70.00"))),
                    List.of(new SalesMonthlyPerformanceItem("2026-08", "RY202608220001", "销售A",
                            "BJ", "北京",
                            new BigDecimal("3000.00"), new BigDecimal("2100.00"),
                            new BigDecimal("900.00"), 3L, 2L, new BigDecimal("70.00"))),
                    List.of(new RankingItem("CITY_COLLECTION_RATE", "BJ", "北京",
                            "BJ", "北京",
                            new BigDecimal("3000.00"), new BigDecimal("2100.00"),
                            new BigDecimal("900.00"), 3L, 2L, new BigDecimal("70.00"))),
                    sourceSystemBreakdown,
                    List.of(new ProductSalesItem("PRODUCT", "1001", "专业款皮头",
                            "10", "台球用品", new BigDecimal("12.00"),
                            new BigDecimal("980.00"), new BigDecimal("20.00"), BigDecimal.ZERO,
                            new BigDecimal("980.00"), new BigDecimal("600.00"),
                            new BigDecimal("380.00"), new BigDecimal("38.78"),
                            new BigDecimal("100.00"), 2L, 2L)),
                    List.of(new ProductSalesItem("SKU", "2001", "专业款皮头 / 12mm",
                            "10", "台球用品", new BigDecimal("8.00"),
                            new BigDecimal("680.00"), new BigDecimal("12.00"), BigDecimal.ZERO,
                            new BigDecimal("680.00"), new BigDecimal("420.00"),
                            new BigDecimal("260.00"), new BigDecimal("38.24"),
                            new BigDecimal("100.00"), 1L, 1L)),
                    List.of(new ProductSalesItem("CATEGORY", "10", "台球用品",
                            "10", "台球用品", new BigDecimal("30.00"),
                            new BigDecimal("3000.00"), new BigDecimal("120.00"), new BigDecimal("100.00"),
                            new BigDecimal("2900.00"), new BigDecimal("1800.00"),
                            new BigDecimal("1100.00"), new BigDecimal("37.93"),
                            new BigDecimal("90.00"), 3L, 2L)),
                    List.of(new ProductSalesItem("BRAND", "20", "瑞盖自营",
                            "10", "台球用品", new BigDecimal("20.00"),
                            new BigDecimal("2000.00"), new BigDecimal("80.00"), new BigDecimal("60.00"),
                            new BigDecimal("1940.00"), new BigDecimal("1200.00"),
                            new BigDecimal("740.00"), new BigDecimal("38.14"),
                            new BigDecimal("100.00"), 2L, 2L)),
                    List.of(new RankingItem("PAYMENT_RISK_CITY", "BJ", "北京",
                            "BJ", "北京",
                            new BigDecimal("3000.00"), new BigDecimal("2100.00"),
                            new BigDecimal("900.00"), 1L, 2L, new BigDecimal("30.00"))),
                    List.of(new RankingItem("PAYMENT_RISK_OWNER", "RY202608220001", "销售A",
                            "BJ", "北京",
                            new BigDecimal("3000.00"), new BigDecimal("2100.00"),
                            new BigDecimal("900.00"), 1L, 2L, new BigDecimal("30.00"))),
                    List.of(
                            new PaymentAgingBucket("CURRENT", "未逾期", 1L, 1L, new BigDecimal("100.00")),
                            new PaymentAgingBucket("DAYS_31_60", "逾期31-60天", 1L, 1L, new BigDecimal("900.00"))),
                    List.of(new TargetCompletionItem("CITY", "BJ", "北京", "SALES_AMOUNT",
                            "销售额", new BigDecimal("5000.00"), new BigDecimal("3000.00"),
                            new BigDecimal("60.00"))),
                    List.of(new TargetCompletionItem("SALES_OWNER", "RY202608220001", "销售A", "PAID_AMOUNT",
                            "回款额", new BigDecimal("2500.00"), new BigDecimal("2100.00"),
                            new BigDecimal("84.00"))),
                    List.of(
                            new CustomerSegmentItem("A", "A级客户", 1L, new BigDecimal("2600.00"),
                                    new BigDecimal("2100.00"), new BigDecimal("500.00"),
                                    new BigDecimal("88.00"), 0L),
                            new CustomerSegmentItem("C", "C级客户", 1L, BigDecimal.ZERO,
                                    BigDecimal.ZERO, BigDecimal.ZERO,
                                    new BigDecimal("12.00"), 1L)),
                    List.of(new CustomerActivityItem("C001", "北京一店", "BJ", "北京",
                            "RY202608220001", "销售A", "STORE", "门店", "A", "A级客户",
                            new BigDecimal("2600.00"), new BigDecimal("2100.00"),
                            new BigDecimal("500.00"), 3L, 2L, NOW.minusSeconds(86400),
                            NOW.minusSeconds(43200), 1L, new BigDecimal("88.00"), "LOW")),
                    List.of(new CustomerActivityItem("C002", "北京二店", "BJ", "北京",
                            "RY202608220001", "销售A", "STORE", "门店", "C", "C级客户",
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L,
                            null, null, 9999L, new BigDecimal("12.00"), "HIGH")),
                    List.of(new InventoryItemSummary("10", "台球用品", "件",
                            new BigDecimal("100.00"), new BigDecimal("40.00"), new BigDecimal("60.00"),
                            new BigDecimal("5.00"))),
                    List.of(new InventoryReplenishmentItem("10", "台球用品", "P1001", "专业款皮头", "件",
                            new BigDecimal("120.00"), new BigDecimal("4.80"), new BigDecimal("60.00"),
                            new BigDecimal("10.00"), new BigDecimal("12.50"), new BigDecimal("74.00"),
                            "MEDIUM", "HISTORICAL_STOCK")),
                    List.of(new CityCostItem("BJ", "北京", new BigDecimal("300.00"),
                            new BigDecimal("250.00"), new BigDecimal("50.00"),
                            new BigDecimal("3000.00"), new BigDecimal("10.00"), 1L, NOW)),
                    List.of(new RiskItem("INVENTORY", "HIGH", "WH/P/SKU", "北京仓 - 商品",
                            "可用库存小于等于0", BigDecimal.ZERO, BigDecimal.ONE, NOW)),
                    List.of(new DataFreshness("BI_CITY_COST_RECORD", "城市端成本",
                            NOW.minusSeconds(30), "READY", "城市端成本已接入")));
        }

        @Override
        public Optional<Instant> latestSalesOrderDate(String tenantId) {
            return latestSalesOrderDate;
        }

        @Override
        public TrustData trust(String tenantId) {
            return new TrustData(
                    new RefreshRun(2L, "SUPPLY_DASHBOARD_HOURLY", tenantId, "SUCCESS",
                            NOW.minusSeconds(120), NOW.minusSeconds(60), NOW.minusSeconds(40),
                            20L, 19L, 1L, null),
                    List.of(
                            new TrustSource("ORDER_SALES_ORDER", "销售订单",
                                    NOW.minusSeconds(40), NOW.minusSeconds(60), "SUCCESS",
                                    2L, "SUCCESS", NOW.minusSeconds(120), NOW.minusSeconds(60),
                                    20L, 19L, 1L, null),
                            new TrustSource("CRM_CUSTOMER", "客户/门店",
                                    NOW.minusSeconds(30), NOW.minusSeconds(60), "SUCCESS",
                                    2L, "SUCCESS", NOW.minusSeconds(120), NOW.minusSeconds(60),
                                    20L, 19L, 1L, null)));
        }

        @Override
        public ReconciliationData reconciliation(String tenantId, SupplyDashboardFilter filter) {
            this.filter = filter;
            return new ReconciliationData(
                    Instant.parse("2026-08-01T00:00:00Z"),
                    NOW,
                    NOW.minusSeconds(20),
                    List.of(
                            new ReconciliationItem("SALES_ORDER", "销售订单",
                                    3L, 3L, 2L, BigDecimal.ZERO,
                                    new BigDecimal("3000.00"), new BigDecimal("2500.00")),
                            new ReconciliationItem("SALES_PAYMENT", "销售回款",
                                    0L, 2L, 2L, BigDecimal.ZERO,
                                    new BigDecimal("2100.00"), new BigDecimal("2100.00")),
                            new ReconciliationItem("ERP_PRODUCT", "ERP商品",
                                    0L, 4L, 4L, BigDecimal.ZERO,
                                    BigDecimal.ZERO, BigDecimal.ZERO)));
        }

        @Override
        public FilterOptions filterOptions(String tenantId) {
            return new FilterOptions(
                    List.of(new FilterOption("REGION", "BJ", "北京", 2L)),
                    List.of(new FilterOption("SALES_OWNER", "RY202608220001", "销售A", 3L)),
                    List.of(new FilterOption("CUSTOMER_TYPE", "STORE", "STORE", 5L)),
                    List.of(),
                    List.of());
        }

        @Override
        public List<String> refreshTenantIds() {
            return List.of(TENANT_ID.toString());
        }

        @Override
        public boolean acquireRefreshLock(String tenantId, String lockCode, Instant now, Instant lockedUntil) {
            return true;
        }

        @Override
        public void releaseRefreshLock(String tenantId, String lockCode, String statusCode, Instant now, String failureReason) {
        }

        @Override
        public RefreshRun createRefreshRun(String tenantId, String jobCode, Instant startedAt) {
            return new RefreshRun(1L, jobCode, tenantId, "RUNNING", startedAt, null, null, 0L, 0L, 0L, null);
        }

        @Override
        public RefreshRun completeRefreshRun(
                Long runId, Instant completedAt, Instant watermarkTime,
                long pulledCount, long upsertedCount, long skippedCount) {
            return new RefreshRun(runId, "SUPPLY_DASHBOARD_MANUAL", TENANT_ID.toString(), "SUCCESS",
                    NOW, completedAt, watermarkTime, pulledCount, upsertedCount, skippedCount, null);
        }

        @Override
        public RefreshRun failRefreshRun(
                Long runId, Instant completedAt, long pulledCount, long upsertedCount,
                long skippedCount, String failureReason) {
            return new RefreshRun(runId, "SUPPLY_DASHBOARD_MANUAL", TENANT_ID.toString(), "FAILED",
                    NOW, completedAt, null, pulledCount, upsertedCount, skippedCount, failureReason);
        }

        @Override
        public Optional<Instant> checkpointWatermark(String tenantId, String sourceCode) {
            return Optional.ofNullable(watermarks.get(sourceCode));
        }

        @Override
        public boolean refreshTargetNeedsBackfill(String tenantId, String sourceCode) {
            return backfillSources.contains(sourceCode);
        }

        @Override
        public void updateCheckpoint(
                String tenantId, String sourceCode, String sourceName, Instant watermarkTime,
                Instant successTime, Long runId) {
            watermarks.put(sourceCode, watermarkTime);
        }

        @Override
        public SourceRefreshResult refreshCustomerDim(String tenantId, Instant from, Instant to, Instant syncedAt) {
            failIfConfigured("CRM_CUSTOMER");
            refreshedSources.add("CRM_CUSTOMER");
            customerFrom = from;
            return new SourceRefreshResult("CRM_CUSTOMER", "客户/门店", 2L, 2L, 0L, to.minusSeconds(30));
        }

        @Override
        public SourceRefreshResult refreshSalesOrderFact(String tenantId, Instant from, Instant to, Instant syncedAt) {
            failIfConfigured("ORDER_SALES_ORDER");
            refreshedSources.add("ORDER_SALES_ORDER");
            orderFrom = from;
            return new SourceRefreshResult("ORDER_SALES_ORDER", "销售订单", 3L, 3L, 0L, to.minusSeconds(20));
        }

        @Override
        public SourceRefreshResult refreshSalesOrderLineFact(String tenantId, Instant from, Instant to, Instant syncedAt) {
            failIfConfigured("ORDER_SALES_ORDER_LINE");
            refreshedSources.add("ORDER_SALES_ORDER_LINE");
            return new SourceRefreshResult("ORDER_SALES_ORDER_LINE", "销售订单行", 3L, 3L, 0L, to.minusSeconds(15));
        }

        @Override
        public SourceRefreshResult refreshProductDim(String tenantId, Instant from, Instant to, Instant syncedAt) {
            failIfConfigured("ERP_PRODUCT");
            refreshedSources.add("ERP_PRODUCT");
            return new SourceRefreshResult("ERP_PRODUCT", "ERP商品", 4L, 4L, 0L, to.minusSeconds(12));
        }

        @Override
        public SourceRefreshResult refreshSalesPaymentFact(String tenantId, Instant from, Instant to, Instant syncedAt) {
            failIfConfigured("ORDER_PAYMENT_RECORD");
            refreshedSources.add("ORDER_PAYMENT_RECORD");
            return new SourceRefreshResult("ORDER_PAYMENT_RECORD", "销售回款记录", 2L, 2L, 0L, to.minusSeconds(10));
        }

        @Override
        public long backfillCustomerRegionAttribution(String tenantId, Instant syncedAt) {
            return 0L;
        }

        @Override
        public SourceRefreshResult refreshInventoryBalanceCurrent(String tenantId, Instant syncedAt) {
            failIfConfigured("ERP_STOCK_BALANCE");
            refreshedSources.add("ERP_STOCK_BALANCE");
            return new SourceRefreshResult("ERP_STOCK_BALANCE", "库存余额", 3L, 3L, 0L, syncedAt);
        }

        @Override
        public SourceRefreshResult refreshInventoryOperationFact(String tenantId, Instant syncedAt) {
            failIfConfigured("ERP_INVENTORY_OPERATION");
            refreshedSources.add("ERP_INVENTORY_OPERATION");
            return new SourceRefreshResult("ERP_INVENTORY_OPERATION", "采购/发货流转", 2L, 2L, 0L, syncedAt);
        }

        @Override
        public SourceRefreshResult refreshReconciliationCurrent(String tenantId, Instant from, Instant to, Instant observedAt) {
            failIfConfigured("BI_RECONCILIATION_CURRENT");
            refreshedSources.add("BI_RECONCILIATION_CURRENT");
            return new SourceRefreshResult("BI_RECONCILIATION_CURRENT", "对账快照", 5L, 5L, 0L, observedAt);
        }

        private void failIfConfigured(String sourceCode) {
            if (failingSources.contains(sourceCode)) {
                throw new IllegalStateException(sourceCode + " unavailable");
            }
        }

        @Override
        public CityCostImportResult importCityCostRecords(String tenantId, List<CityCostImportRow> rows, Instant importedAt) {
            importedRows = List.copyOf(rows);
            return new CityCostImportResult(rows.size(), rows.size(), importedAt);
        }

        @Override
        public FeishuArchiveRow registerFeishuArchive(
                String tenantId, FeishuArchiveWrite command, Instant registeredAt) {
            archiveWrite = command;
            return new FeishuArchiveRow(
                    1L,
                    command.archiveCode(),
                    command.tableId(),
                    command.viewId(),
                    command.tableName(),
                    command.fileName(),
                    command.fileFormat(),
                    command.exportedBy(),
                    command.exportedTime(),
                    command.frozenTime(),
                    command.recordCount(),
                    command.checksumSha256(),
                    command.storageUri(),
                    command.fieldMappingUri(),
                    command.reconciliationReportUri(),
                    "ARCHIVED",
                    command.remark(),
                    registeredAt,
                    registeredAt);
        }

        @Override
        public List<FeishuArchiveRow> feishuArchives(String tenantId) {
            return List.of();
        }
    }
}

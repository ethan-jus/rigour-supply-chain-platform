package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.SupplyDashboardOverviewView;
import com.rigour.analytics.api.v1.model.SupplyDashboardCityCostImportCommand;
import com.rigour.analytics.api.v1.model.SupplyDashboardCityCostImportRecord;
import com.rigour.analytics.api.v1.model.SupplyDashboardCityCostImportResultView;
import com.rigour.analytics.api.v1.model.SupplyDashboardDataTrustView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFeishuArchiveCommand;
import com.rigour.analytics.api.v1.model.SupplyDashboardFeishuArchiveView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionsView;
import com.rigour.analytics.api.v1.model.SupplyDashboardRefreshRunView;
import com.rigour.analytics.api.v1.model.SupplyDashboardReconciliationView;
import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.application.port.out.SupplyDashboardStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CityCostImportResult;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CityCostImportRow;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CityCostItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CityCostSummary;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CollectionSummary;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.CustomerSummary;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.DataFreshness;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.FeishuArchiveRow;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.FeishuArchiveWrite;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.FilterOption;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.FilterOptions;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.PaymentRiskSummary;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.ProductSalesItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.ProfitSummary;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.RankingItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.ReconciliationData;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.ReconciliationItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.RefreshRun;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.RiskItem;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.SalesSummary;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.SourceRefreshResult;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.SupplyDashboardData;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.TrustData;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.TrustSource;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.TrendPoint;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
                        "payment_risk_amount", "payment_avg_overdue_days", "target_achievement_rate");
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
        assertThat(result.sourceSystemBreakdown()).hasSize(1);
        assertThat(result.sourceSystemBreakdown().getFirst().rankType()).isEqualTo("SOURCE_SYSTEM");
        assertThat(result.sourceSystemBreakdown().getFirst().dimensionCode()).isEqualTo("DINGHUOBAO");
        assertThat(result.sourceSystemBreakdown().getFirst().dimensionName()).isEqualTo("订货宝");
        assertThat(result.definitions())
                .extracting("metricCode")
                .contains("city_cost_amount", "city_cost_rate", "product_sales_amount",
                        "refund_amount", "estimated_gross_profit", "payment_risk_amount");
        assertThat(result.definitions().getFirst().version()).isEqualTo("v1");
        assertThat(result.definitions().getFirst().exclusionRule()).contains("deleted=1");
        assertThat(result.rolePerspectives())
                .extracting("roleCode")
                .contains("CEO", "OPERATION", "SALES");
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
        assertThat(result.pulledCount()).isEqualTo(22L);
        assertThat(result.upsertedCount()).isEqualTo(22L);
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
        assertThat(result.sources()).hasSize(7);
        assertThat(result.sources())
                .extracting("sourceCode")
                .contains("ORDER_SALES_ORDER", "ORDER_SALES_ORDER_LINE", "CRM_CUSTOMER", "ERP_PRODUCT", "ORDER_PAYMENT_RECORD",
                        "ERP_STOCK_BALANCE", "BI_RECONCILIATION_CURRENT");
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

        assertThat(result.sourceSystems()).extracting("optionValue").contains("DINGHUOBAO", "MANUAL");
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

    private static final class FakeStore implements SupplyDashboardStore {
        private SupplyDashboardFilter filter;
        private final Map<String, Instant> watermarks = new HashMap<>();
        private Instant customerFrom;
        private Instant orderFrom;
        private List<CityCostImportRow> importedRows = List.of();
        private List<RankingItem> sourceSystemBreakdown = List.of(new RankingItem(
                "SOURCE_SYSTEM", "DINGHUOBAO", "订货宝",
                new BigDecimal("3000.00"), new BigDecimal("2100.00"),
                new BigDecimal("900.00"), 3L, 2L, new BigDecimal("70.00")));
        private FeishuArchiveWrite archiveWrite;

        @Override
        public SupplyDashboardData overview(String tenantId, SupplyDashboardFilter filter) {
            this.filter = filter;
            return new SupplyDashboardData(
                    new SalesSummary(3L, 2L, new BigDecimal("30"),
                            new BigDecimal("3000.00"), new BigDecimal("2100.00"),
                            new BigDecimal("900.00"), 1L, NOW.minusSeconds(60)),
                    new CustomerSummary(20L, NOW.minusSeconds(50)),
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
                            new BigDecimal("3000.00"), new BigDecimal("2100.00"),
                            new BigDecimal("900.00"), 3L, 2L, new BigDecimal("70.00"))),
                    List.of(new RankingItem("SALES_OWNER", "RY202608220001", "销售A",
                            new BigDecimal("3000.00"), new BigDecimal("2100.00"),
                            new BigDecimal("900.00"), 3L, 2L, new BigDecimal("70.00"))),
                    sourceSystemBreakdown,
                    List.of(new ProductSalesItem("PRODUCT", "1001", "专业款皮头",
                            "10", "台球用品", new BigDecimal("12.00"),
                            new BigDecimal("980.00"), new BigDecimal("20.00"), BigDecimal.ZERO,
                            new BigDecimal("980.00"), new BigDecimal("600.00"),
                            new BigDecimal("380.00"), new BigDecimal("38.78"),
                            new BigDecimal("100.00"), 2L, 2L)),
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
                            new BigDecimal("3000.00"), new BigDecimal("2100.00"),
                            new BigDecimal("900.00"), 1L, 2L, new BigDecimal("30.00"))),
                    List.of(new RankingItem("PAYMENT_RISK_OWNER", "RY202608220001", "销售A",
                            new BigDecimal("3000.00"), new BigDecimal("2100.00"),
                            new BigDecimal("900.00"), 1L, 2L, new BigDecimal("30.00"))),
                    List.of(new CityCostItem("BJ", "北京", new BigDecimal("300.00"),
                            new BigDecimal("250.00"), new BigDecimal("50.00"),
                            new BigDecimal("3000.00"), new BigDecimal("10.00"), 1L, NOW)),
                    List.of(new RiskItem("INVENTORY", "HIGH", "WH/P/SKU", "北京仓 - 商品",
                            "可用库存小于等于0", BigDecimal.ZERO, BigDecimal.ONE, NOW)),
                    List.of(new DataFreshness("BI_CITY_COST_RECORD", "城市端成本",
                            NOW.minusSeconds(30), "READY", "城市端成本已接入")));
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
        public void updateCheckpoint(
                String tenantId, String sourceCode, String sourceName, Instant watermarkTime,
                Instant successTime, Long runId) {
            watermarks.put(sourceCode, watermarkTime);
        }

        @Override
        public SourceRefreshResult refreshCustomerDim(String tenantId, Instant from, Instant to, Instant syncedAt) {
            customerFrom = from;
            return new SourceRefreshResult("CRM_CUSTOMER", "客户/门店", 2L, 2L, 0L, to.minusSeconds(30));
        }

        @Override
        public SourceRefreshResult refreshSalesOrderFact(String tenantId, Instant from, Instant to, Instant syncedAt) {
            orderFrom = from;
            return new SourceRefreshResult("ORDER_SALES_ORDER", "销售订单", 3L, 3L, 0L, to.minusSeconds(20));
        }

        @Override
        public SourceRefreshResult refreshSalesOrderLineFact(String tenantId, Instant from, Instant to, Instant syncedAt) {
            return new SourceRefreshResult("ORDER_SALES_ORDER_LINE", "销售订单行", 3L, 3L, 0L, to.minusSeconds(15));
        }

        @Override
        public SourceRefreshResult refreshProductDim(String tenantId, Instant from, Instant to, Instant syncedAt) {
            return new SourceRefreshResult("ERP_PRODUCT", "ERP商品", 4L, 4L, 0L, to.minusSeconds(12));
        }

        @Override
        public SourceRefreshResult refreshSalesPaymentFact(String tenantId, Instant from, Instant to, Instant syncedAt) {
            return new SourceRefreshResult("ORDER_PAYMENT_RECORD", "销售回款记录", 2L, 2L, 0L, to.minusSeconds(10));
        }

        @Override
        public long backfillCustomerRegionAttribution(String tenantId, Instant syncedAt) {
            return 0L;
        }

        @Override
        public SourceRefreshResult refreshInventoryBalanceCurrent(String tenantId, Instant syncedAt) {
            return new SourceRefreshResult("ERP_STOCK_BALANCE", "库存余额", 3L, 3L, 0L, syncedAt);
        }

        @Override
        public SourceRefreshResult refreshReconciliationCurrent(String tenantId, Instant from, Instant to, Instant observedAt) {
            return new SourceRefreshResult("BI_RECONCILIATION_CURRENT", "对账快照", 5L, 5L, 0L, observedAt);
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

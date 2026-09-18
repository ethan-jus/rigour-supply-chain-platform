package com.rigour.analytics.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.analytics.api.v1.model.SupplyDashboardOperatingAnalysisView;
import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.application.service.SupplyDashboardQueryService;
import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 在隔离 H2 内存库执行真实 Mapper 聚合；不启动服务，不连接共享 DEV。 */
class SupplyDashboardOperatingAnalysisRepositoryTest {
    private static final UUID TENANT = UUID.fromString("019fb700-0000-7000-8000-000000000001");
    private static final String OTHER_TENANT = "019fb700-0000-7000-8000-000000000099";
    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-02T23:59:59.999999Z");
    private static final Instant PREVIOUS_FROM = Instant.parse("2026-08-30T00:00:00Z");
    private static final Instant PREVIOUS_TO = Instant.parse("2026-08-31T23:59:59.999999Z");
    private SqlSession session;
    private JdbcTemplate jdbc;
    private SupplyDashboardQueryMapper mapper;
    private SupplyDashboardQueryService service;
    private ReadOnlyBiGuard guard;

    @BeforeEach
    void setUp() {
        var dataSource =
                new UnpooledDataSource(
                        "org.h2.Driver",
                        "jdbc:h2:mem:operating_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "");
        var configuration =
                new Configuration(
                        new Environment("test", new JdbcTransactionFactory(), dataSource));
        guard = new ReadOnlyBiGuard();
        configuration.addInterceptor(guard);
        configuration.addMapper(SupplyDashboardQueryMapper.class);
        session = new SqlSessionFactoryBuilder().build(configuration).openSession();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(session.getConnection(), true));
        // 仅建立查询使用的事实列，精度和唯一键与已存在迁移一致。
        jdbc.execute(
                """
                CREATE TABLE bi_sales_order_fact (
                    tenant_id VARCHAR(64), order_id BIGINT, customer_id BIGINT,
                    region_code VARCHAR(64), region_name VARCHAR(160),
                    owner_staff_code VARCHAR(50), owner_staff_name VARCHAR(100),
                    customer_type_code VARCHAR(64) DEFAULT 'STORE',
                    source_system_code VARCHAR(32) DEFAULT 'DINGHUOBAO',
                    order_date DATETIME(6), order_status_code VARCHAR(64) DEFAULT 'COMPLETED',
                    deleted INT DEFAULT 0, payable_amount DECIMAL(24,6),
                    paid_amount DECIMAL(24,6) DEFAULT 0, unpaid_amount DECIMAL(24,6) DEFAULT 0,
                    UNIQUE (tenant_id, order_id))
                """);
        jdbc.execute(
                """
CREATE TABLE bi_sales_order_line_fact (
    tenant_id VARCHAR(64), order_line_id BIGINT, order_id BIGINT, customer_id BIGINT,
    region_code VARCHAR(64), region_name VARCHAR(160), owner_staff_code VARCHAR(50),
    customer_type_code VARCHAR(64) DEFAULT 'STORE',
    source_system_code VARCHAR(32) DEFAULT 'DINGHUOBAO',
    order_date DATETIME(6), order_status_code VARCHAR(64) DEFAULT 'COMPLETED',
    deleted INT DEFAULT 0, product_id BIGINT, product_category_id BIGINT, product_category_name VARCHAR(120),
    line_amount DECIMAL(24,6), unit_code VARCHAR(64), quantity DECIMAL(24,6) DEFAULT 100,
    discount_amount DECIMAL(24,6) DEFAULT 0, refund_amount DECIMAL(24,6) DEFAULT 0,
    sales_net_amount DECIMAL(24,6) DEFAULT 1, estimated_cost_amount DECIMAL(24,6) DEFAULT 0,
    estimated_gross_profit_amount DECIMAL(24,6) DEFAULT 0, cost_covered INT DEFAULT 0,
    UNIQUE (tenant_id, order_line_id))
""");
        jdbc.execute(
                """
CREATE TABLE bi_sales_payment_fact (
    tenant_id VARCHAR(64), payment_id BIGINT, customer_id BIGINT,
    owner_staff_code VARCHAR(50), owner_staff_name VARCHAR(100), collector_staff_code VARCHAR(50), collector_staff_name VARCHAR(100),
    region_code VARCHAR(64) DEFAULT 'BJ', customer_type_code VARCHAR(64) DEFAULT 'STORE',
    source_system_code VARCHAR(32) DEFAULT 'DINGHUOBAO',
    payment_time DATETIME(6), paid_amount DECIMAL(24,6), deleted INT DEFAULT 0,
    source_updated_time DATETIME(6), UNIQUE (tenant_id, payment_id))
""");
        mapper = session.getMapper(SupplyDashboardQueryMapper.class);
        jdbc.execute(
                "CREATE TABLE bi_product_category_dim (tenant_id VARCHAR(64), category_id BIGINT,"
                        + " category_name VARCHAR(160), parent_id BIGINT, deleted INT)");
        jdbc.execute(
                "CREATE TABLE bi_product_category_closure (tenant_id VARCHAR(64), ancestor_id"
                        + " BIGINT, descendant_id BIGINT, depth INT)");
        jdbc.execute(
                "CREATE TABLE bi_product_dim (tenant_id VARCHAR(64), product_id BIGINT,"
                        + " product_category_id BIGINT, deleted INT)");
        service =
                new SupplyDashboardQueryService(
                        new MybatisPlusSupplyDashboardRepository(mapper),
                        Clock.fixed(TO.plusSeconds(1), ZoneOffset.UTC),
                        new com.rigour.analytics.application.service.BiDataScopeService(
                                org.mockito.Mockito.mock(
                                        com.rigour.analytics.application.port.out.BiDataScopeStore
                                                .class),
                                Clock.fixed(TO, ZoneOffset.UTC),
                                org.mockito.Mockito.mock(
                                        com.rigour.analytics.application.service.BiDataScopeRenewer
                                                .class)));
        UUID user = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        TENANT,
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of("TENANT_SUPER_ADMIN"),
                        Set.of("analytics:dashboard:read")));
    }

    @AfterEach
    void tearDown() {
        TestAuthorizationContext.clear();
        if (session != null) session.close();
    }

    @Test
    void
            cityProductsAggregateFullMatrixByCityAndCategoryWithoutMixingUnitsOrCountingLinesAsOrders() {
        line(1, 1, 10L, "BJ", 10L, "S1", FROM, "100", "BOX");
        line(2, 1, 10L, "BJ", 10L, "S1", FROM, "50", "BOTTLE");
        line(3, 2, 20L, "BJ", 10L, "S2", TO, "25", "BOTTLE");
        line(4, 1, 10L, "BJ", 20L, "S1", FROM, "20", "KG");
        line(5, 3, 10L, "SH", 10L, "S1", FROM, "75", "BOX");
        jdbc.update(
                "INSERT INTO bi_product_category_dim VALUES"
                        + " (?,10,'分类10',NULL,0),(?,20,'分类20',NULL,0)",
                TENANT.toString(),
                TENANT.toString());
        jdbc.update(
                "INSERT INTO bi_product_dim VALUES (?,10010,10,0),(?,10020,20,0)",
                TENANT.toString(),
                TENANT.toString());
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET product_id=10010 WHERE tenant_id=? AND"
                        + " product_category_id=10",
                TENANT.toString());
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET product_id=10020 WHERE tenant_id=? AND"
                        + " product_category_id=20",
                TENANT.toString());
        assertThat(
                        jdbc.queryForObject(
                                """
SELECT COUNT(*) FROM bi_sales_order_line_fact l
JOIN bi_product_dim p ON p.tenant_id=l.tenant_id AND p.product_id=l.product_id
    AND p.product_category_id=l.product_category_id
WHERE l.tenant_id=?
""",
                                Integer.class,
                                TENANT.toString()))
                .isEqualTo(5);

        var result = query(null, null, null, null);
        assertThat(result.cityProducts()).hasSize(3);
        var cityCategory =
                result.cityProducts().stream()
                        .filter(
                                row ->
                                        row.regionCode().equals("BJ")
                                                && row.categoryCode().equals("10"))
                        .findFirst()
                        .orElseThrow();
        assertThat(cityCategory.regionName()).isEqualTo("城市BJ");
        assertThat(cityCategory.categoryName()).isEqualTo("分类10");
        assertThat(cityCategory.salesAmount()).isEqualByComparingTo("175");
        assertThat(cityCategory.orderCount()).isEqualTo(2L);
        assertThat(cityCategory.customerCount()).isEqualTo(2L);
        var categoryRanking =
                mapper.categorySalesRanking(
                        TENANT.toString(), local(FROM), local(TO), null, null, null, null, null);
        assertThat(categoryRanking)
                .hasSize(2)
                .anySatisfy(
                        row -> {
                            assertThat(decimal(row, "dimensionCode")).isEqualByComparingTo("10");
                            assertThat(decimal(row, "salesAmount")).isEqualByComparingTo("250");
                        })
                .anySatisfy(
                        row -> {
                            assertThat(decimal(row, "dimensionCode")).isEqualByComparingTo("20");
                            assertThat(decimal(row, "salesAmount")).isEqualByComparingTo("20");
                        });
        BigDecimal baseline =
                categoryRanking.stream()
                        .map(row -> decimal(row, "salesAmount"))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(baseline).isEqualByComparingTo("270");
        assertThat(
                        result.cityProducts().stream()
                                .map(row -> row.salesAmount())
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(baseline);
    }

    @Test
    void cityCustomerRepeatRequiresTwoNonCancelledOrdersForSameCustomerInSameCity() {
        order(1, 10L, "BJ", "S1", FROM, "100");
        order(2, 10L, "BJ", "S2", TO, "50");
        order(3, 10L, "SH", "S1", FROM, "70");
        order(4, 20L, "BJ", "S1", FROM, "20");
        order(5, 20L, "BJ", "S1", FROM, "99");
        order(6, null, "BJ", "S1", FROM, "99");
        order(7, 20L, "BJ", "S1", FROM, "99");
        order(8, 20L, "BJ", "S1", FROM, "99");
        jdbc.update(
                "UPDATE bi_sales_order_fact SET order_status_code = 'CANCELLED' WHERE order_id ="
                        + " 5");
        jdbc.update("UPDATE bi_sales_order_fact SET deleted = 1 WHERE order_id = 7");
        jdbc.update(
                "UPDATE bi_sales_order_fact SET tenant_id = ? WHERE order_id = 8", OTHER_TENANT);

        var result = query(null, null, null, null);
        assertThat(result.cityCustomers()).hasSize(2);
        assertThat(result.cityCustomers().get(0).orderingCustomerCount()).isEqualTo(2L);
        assertThat(result.cityCustomers().get(0).repeatCustomerCount()).isEqualTo(1L);
        assertThat(result.cityCustomers().get(1).regionCode()).isEqualTo("SH");
        assertThat(result.cityCustomers().get(1).repeatCustomerCount()).isZero();
        assertThat(query("BJ", "S1", null, null).cityCustomers().getFirst().repeatCustomerCount())
                .isZero();
    }

    @Test
    void receiptsUsePaymentTimeAndFrozenCollectorInsteadOfOrderOwner() {
        order(1, 10L, "BJ", "S1", PREVIOUS_FROM, "9000");
        jdbc.update(
                "UPDATE bi_sales_order_fact SET paid_amount = 8888, order_status_code ="
                        + " 'CANCELLED'");
        payment(1, 10L, "S1", "S9", FROM, "100");
        payment(2, 10L, "S1", "S1", TO, "-20");
        payment(3, 20L, "S2", "S1", FROM, "40");
        payment(4, 30L, "S1", "S9", FROM, "0");
        payment(5, 10L, "S1", "S9", FROM.minusNanos(1000), "500");
        payment(6, 10L, "S1", "S9", TO.plusNanos(1000), "500");
        payment(7, 10L, "S1", "S9", FROM, "500");
        jdbc.update("UPDATE bi_sales_payment_fact SET deleted = 1 WHERE payment_id = 7");

        var result = query("BJ", "S1", "STORE", "DHB");
        assertThat(result.salesReceipts()).hasSize(1);
        assertThat(result.salesReceipts().getFirst().ownerStaffCode()).isEqualTo("S1");
        assertThat(result.salesReceipts().getFirst().paidAmount()).isEqualByComparingTo("20");
        assertThat(result.salesReceipts().getFirst().paymentCount()).isEqualTo(2L);
        assertThat(result.salesReceipts().getFirst().customerCount()).isEqualTo(2L);
        var otherCollector = query("BJ", "S9", "STORE", "DHB");
        assertThat(otherCollector.salesReceipts()).hasSize(1);
        assertThat(otherCollector.salesReceipts().getFirst().paidAmount())
                .isEqualByComparingTo("100");
        var baseline =
                mapper.collectionSummary(
                        TENANT.toString(),
                        local(FROM),
                        local(TO),
                        "BJ",
                        "S1",
                        "STORE",
                        "DINGHUOBAO");
        assertThat(
                        result.salesReceipts().stream()
                                .map(row -> row.paidAmount())
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(decimal(baseline, "receiptAmount"));

        Map<String, Object> params =
                Map.of(
                        "tenantId",
                        TENANT.toString(),
                        "from",
                        local(FROM),
                        "to",
                        local(TO),
                        "regionCode",
                        "BJ",
                        "ownerStaffCode",
                        "S1",
                        "customerTypeCode",
                        "STORE",
                        "sourceSystemCode",
                        "DINGHUOBAO");
        assertThat(whereClause("collectionSummary", params))
                .isEqualTo(whereClause("collectionTrend", params));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {10, 0, -1})
    void allQueriesApplyEachFilterTenantAndPeriodBoundariesIndependently(Long category) {
        for (int id = 1; id <= 9; id++) {
            Instant date = id == 8 ? FROM.minusNanos(1000) : id == 9 ? TO.plusNanos(1000) : FROM;
            order(id, (long) id, "BJ", "S1", date, "10");
            line(id, id, (long) id, "BJ", category, "S1", date, "10", "BOX");
            payment(id, (long) id, "S1", "S1", date, "10");
            order(
                    100 + id,
                    (long) id,
                    "BJ",
                    "S1",
                    id == 8 ? PREVIOUS_FROM.minusNanos(1000) : id == 9 ? FROM : PREVIOUS_FROM,
                    "10");
        }
        for (String table :
                List.of(
                        "bi_sales_order_fact",
                        "bi_sales_order_line_fact",
                        "bi_sales_payment_fact")) {
            String idColumn = table.equals("bi_sales_payment_fact") ? "payment_id" : "order_id";
            jdbc.update(
                    "UPDATE "
                            + table
                            + " SET region_code = 'SH' WHERE "
                            + idColumn
                            + " IN (2, 102)");
            jdbc.update(
                    "UPDATE "
                            + table
                            + " SET owner_staff_code = 'S2' WHERE "
                            + idColumn
                            + " IN (3, 103)");
            jdbc.update(
                    "UPDATE "
                            + table
                            + " SET customer_type_code = 'RETAIL' WHERE "
                            + idColumn
                            + " IN (4, 104)");
            jdbc.update(
                    "UPDATE "
                            + table
                            + " SET source_system_code = 'FEISHU' WHERE "
                            + idColumn
                            + " IN (5, 105)");
            jdbc.update(
                    "UPDATE " + table + " SET tenant_id = ? WHERE " + idColumn + " IN (6, 106)",
                    OTHER_TENANT);
            jdbc.update("UPDATE " + table + " SET deleted = 1 WHERE " + idColumn + " IN (7, 107)");
        }
        jdbc.update(
                "UPDATE bi_sales_payment_fact SET collector_staff_code = 'S2' WHERE payment_id ="
                        + " 3");
        // 109 位于当前期，避免它改变本例当前期预期；前期窗口仍必须排除它。
        jdbc.update(
                "UPDATE bi_sales_order_fact SET owner_staff_code = 'BOUNDARY' WHERE order_id ="
                        + " 109");

        var result = query("BJ", "S1", "STORE", "DHB");
        assertThat(result.cityProducts())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.salesAmount()).isEqualByComparingTo("10");
                            assertThat(row.orderCount()).isEqualTo(1L);
                        });
        assertThat(result.cityCustomers())
                .singleElement()
                .satisfies(row -> assertThat(row.orderingCustomerCount()).isEqualTo(1L));
        assertThat(result.salesReceipts())
                .singleElement()
                .satisfies(row -> assertThat(row.paidAmount()).isEqualByComparingTo("10"));
        // 订单 8 恰在前期末尾，订单 101 在前期起点，两者都应计入。
        assertThat(result.previousSalesRanking())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.salesAmount()).isEqualByComparingTo("20");
                            assertThat(row.orderCount()).isEqualTo(2L);
                        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "UNKNOWN", "MULTI", " unknown ", "multi"})
    void placeholderDimensionsNeverBecomeCitiesOrSalesOwners(String dimension) {
        order(1, 10L, dimension, "S1", FROM, "10");
        line(1, 1, 10L, dimension, 10L, "S1", FROM, "10", "BOX");
        payment(1, 10L, "S1", dimension, FROM, "10");
        order(2, 10L, "BJ", dimension, PREVIOUS_FROM, "10");
        var result = query(null, null, null, null);
        assertThat(result.cityProducts()).isEmpty();
        assertThat(result.cityCustomers()).isEmpty();
        assertThat(result.salesReceipts()).isEmpty();
        assertThat(result.previousSalesRanking()).isEmpty();
    }

    @Test
    void missingAndInvalidCategoriesShareOneUnknownGroupAndMatrixIsNotTopTwenty() {
        line(1, 1, 10L, "BJ", null, "S1", FROM, "10", "BOX");
        line(2, 1, 10L, "BJ", 0L, "S1", FROM, "10", "BOX");
        line(3, 1, 10L, "BJ", -1L, "S1", FROM, "10", "BOX");
        for (int id = 4; id <= 28; id++)
            line(id, id, 10L, "BJ", (long) id, "S1", FROM, "10", "BOX");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET product_category_name = ' ', region_name ="
                        + " ''");
        var result = query(null, null, null, null);
        assertThat(result.cityProducts())
                .hasSize(26)
                .allSatisfy(
                        row -> {
                            assertThat(row.regionName()).isEqualTo("BJ");
                            assertThat(row.categoryName())
                                    .isEqualTo(
                                            row.categoryCode().equals("UNKNOWN")
                                                    ? "分类关联待核对"
                                                    : row.categoryCode());
                        });
        assertThat(result.cityProducts())
                .filteredOn(row -> row.categoryCode().equals("UNKNOWN"))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.salesAmount()).isEqualByComparingTo("30");
                            assertThat(row.orderCount()).isEqualTo(1L);
                            assertThat(row.customerCount()).isEqualTo(1L);
                        });
        assertThat(
                        result.cityProducts().stream()
                                .map(row -> row.salesAmount())
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("280");
    }

    @Test
    void unknownCategoriesStaySeparatePerCityWithoutChangingSignedLineAmounts() {
        line(1, 1, 10L, "BJ", null, "S1", FROM, "100.123456", "BOX");
        line(2, 1, 10L, "BJ", 0L, "S1", TO, "-0.123456", "METER");
        line(3, 2, 20L, "SH", -1L, "S2", FROM, "25", "EACH");
        line(4, 3, 30L, "BJ", null, "S1", FROM, "999", "BOX");
        line(5, 4, 40L, "BJ", null, "S1", FROM, "999", "BOX");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET order_status_code='CANCELLED' WHERE"
                        + " order_line_id=4");
        jdbc.update("UPDATE bi_sales_order_line_fact SET deleted=1 WHERE order_line_id=5");

        assertThat(query(null, null, null, null).cityProducts())
                .hasSize(2)
                .allSatisfy(
                        row -> {
                            assertThat(row.categoryCode()).isEqualTo("UNKNOWN");
                            assertThat(row.categoryName()).isEqualTo("分类关联待核对");
                            assertThat(row.salesAmount())
                                    .isEqualByComparingTo(
                                            row.regionCode().equals("BJ") ? "100" : "25");
                            assertThat(row.orderCount()).isEqualTo(1L);
                            assertThat(row.customerCount()).isEqualTo(1L);
                        });
    }

    @Test
    void cancelledAndDeletedOrderLinesAreExcludedUsingSameStatusRuleAsCategoryRanking() {
        for (int id = 1; id <= 4; id++) line(id, id, 10L, "BJ", 10L, "S1", FROM, "10", "BOX");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET order_status_code = 'CANCELLED' WHERE order_id"
                        + " = 2");
        jdbc.update("UPDATE bi_sales_order_line_fact SET deleted = 1 WHERE order_id = 3");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET order_status_code = NULL WHERE order_id = 4");
        assertThat(query(null, null, null, null).cityProducts())
                .singleElement()
                .satisfies(row -> assertThat(row.salesAmount()).isEqualByComparingTo("10"));
    }

    @Test
    void previousRankingIncludesBothPreviousBoundsAndExcludesCurrentStartAndCancelledOrders() {
        order(1, 10L, "BJ", "S1", PREVIOUS_FROM, "10");
        order(2, 20L, "SH", "S1", PREVIOUS_TO, "20");
        order(3, 10L, "BJ", "S1", FROM, "100");
        order(4, 10L, "BJ", "S1", PREVIOUS_FROM.minusNanos(1000), "100");
        order(5, 10L, "BJ", "S1", PREVIOUS_FROM, "100");
        jdbc.update(
                "UPDATE bi_sales_order_fact SET order_status_code = 'CANCELLED' WHERE order_id ="
                        + " 5");
        assertThat(query(null, null, null, null).previousSalesRanking())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.salesAmount()).isEqualByComparingTo("30");
                            assertThat(row.orderCount()).isEqualTo(2L);
                            assertThat(row.customerCount()).isEqualTo(2L);
                            assertThat(row.regionCode()).isNull();
                            assertThat(row.regionName()).isNull();
                        });
    }

    @Test
    void actualDefaultRequestUsesOnlyReadOnlyLocalBiQueries() throws NoSuchMethodException {
        order(1, 10L, "BJ", "S1", TO, "10");
        var result = service.operatingAnalysis(null, null, null, null, null, null, null);
        assertThat(result.to()).isEqualTo(TO);
        assertThat(guard.methods)
                .containsExactly(
                        "latestSalesOrderDate",
                        "salesRanking",
                        "cityProducts",
                        "cityCustomers",
                        "salesReceipts");
        assertThat(guard.tables)
                .containsExactlyInAnyOrder(
                        "bi_sales_order_fact", "bi_sales_order_line_fact", "bi_sales_payment_fact");
        assertThat(
                        MybatisPlusSupplyDashboardRepository.class
                                .getMethod(
                                        "operatingAnalysis",
                                        String.class,
                                        SupplyDashboardFilter.class,
                                        SupplyDashboardFilter.class)
                                .getAnnotation(Transactional.class)
                                .readOnly())
                .isTrue();
    }

    @Test
    void localReadGuardRecognizesDeclaredCtesAndStillChecksTheirPhysicalSources() {
        assertThat(
                        ReadOnlyBiGuard.localTables(
                                """
WITH scoped_lines AS (SELECT * FROM bi_sales_order_line_fact),
     selected_products AS (SELECT * FROM bi_product_dim)
SELECT * FROM scoped_lines l JOIN selected_products p ON p.product_id=l.product_id
"""))
                .containsExactlyInAnyOrder("bi_sales_order_line_fact", "bi_product_dim");
        assertThatThrownBy(() -> ReadOnlyBiGuard.localTables("SELECT * FROM scoped_lines"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(
                        () ->
                                ReadOnlyBiGuard.localTables(
                                        """
WITH scoped_lines AS (SELECT * FROM bi_source_order_order_sales_order_line)
SELECT * FROM scoped_lines
"""))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(
                        () ->
                                ReadOnlyBiGuard.localTables(
                                        """
WITH scoped_lines AS (SELECT * FROM bi_sales_order_line_fact JOIN unexpected_table ON 1=1)
SELECT * FROM scoped_lines
"""))
                .isInstanceOf(AssertionError.class);
    }

    private SupplyDashboardOperatingAnalysisView query(
            String city, String owner, String type, String source) {
        return service.operatingAnalysis(FROM, TO, city, owner, type, null, source);
    }

    private void order(
            long id, Long customer, String city, String owner, Instant date, String amount) {
        jdbc.update(
                """
INSERT INTO bi_sales_order_fact (tenant_id, order_id, customer_id, region_code, region_name,
    owner_staff_code, owner_staff_name, order_date, payable_amount)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
""",
                TENANT.toString(),
                id,
                customer,
                city,
                "城市" + city,
                owner,
                "销售" + owner,
                local(date),
                new BigDecimal(amount));
    }

    private void line(
            long id,
            long order,
            Long customer,
            String city,
            Long category,
            String owner,
            Instant date,
            String amount,
            String unit) {
        jdbc.update(
                """
INSERT INTO bi_sales_order_line_fact (tenant_id, order_line_id, order_id, customer_id,
    region_code, region_name, product_category_id, product_category_name, owner_staff_code,
    order_date, line_amount, unit_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
""",
                TENANT.toString(),
                id,
                order,
                customer,
                city,
                "城市" + city,
                category,
                "分类" + category,
                owner,
                local(date),
                new BigDecimal(amount),
                unit);
    }

    private void payment(
            long id, Long customer, String owner, String collector, Instant date, String amount) {
        jdbc.update(
                """
INSERT INTO bi_sales_payment_fact (tenant_id, payment_id, customer_id, owner_staff_code,
    owner_staff_name, collector_staff_code, payment_time, paid_amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
""",
                TENANT.toString(),
                id,
                customer,
                owner,
                "销售" + owner,
                collector,
                local(date),
                new BigDecimal(amount));
    }

    private String whereClause(String method, Map<String, Object> params) {
        String sql =
                session.getConfiguration()
                        .getMappedStatement(
                                SupplyDashboardQueryMapper.class.getName() + "." + method)
                        .getBoundSql(params)
                        .getSql()
                        .replaceAll("\\s+", " ")
                        .strip();
        String where = sql.substring(sql.indexOf("WHERE "));
        return where.contains(" GROUP BY ")
                ? where.substring(0, where.indexOf(" GROUP BY "))
                : where;
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        return row.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(entry -> new BigDecimal(entry.getValue().toString()))
                .findFirst()
                .orElseThrow();
    }

    private static LocalDateTime local(Instant time) {
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    /** 拦截真实读路径，任何写命令、跨 schema 表或缺少租户参数都会立即使测试失败。 */
    @Intercepts({
        @Signature(
                type = Executor.class,
                method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(
                type = Executor.class,
                method = "update",
                args = {MappedStatement.class, Object.class})
    })
    public static class ReadOnlyBiGuard implements Interceptor {
        private static final Set<String> ALLOWED_TABLES =
                Set.of(
                        "bi_sales_order_fact",
                        "bi_sales_order_line_fact",
                        "bi_sales_payment_fact",
                        "bi_product_category_dim",
                        "bi_product_category_closure",
                        "bi_product_dim");
        private static final Pattern TABLE_REFERENCE =
                Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+([a-zA-Z_][a-zA-Z0-9_.]*)");
        private static final Pattern CTE_DECLARATION =
                Pattern.compile("(?i)(?:\\bWITH|,)\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s+AS\\s*\\(");
        final List<String> methods = new ArrayList<>();
        final Set<String> tables = new java.util.HashSet<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
            Object parameters = invocation.getArgs()[1];
            assertThat(((Map<?, ?>) parameters).get("tenantId")).isEqualTo(TENANT.toString());
            var bound = statement.getBoundSql(parameters);
            assertThat(bound.getParameterMappings())
                    .anySatisfy(mapping -> assertThat(mapping.getProperty()).isEqualTo("tenantId"));
            tables.addAll(localTables(bound.getSql()));
            methods.add(statement.getId().substring(statement.getId().lastIndexOf('.') + 1));
            return invocation.proceed();
        }

        static Set<String> localTables(String sql) {
            Set<String> ctes = new java.util.HashSet<>();
            var declarations = CTE_DECLARATION.matcher(sql);
            while (declarations.find()) ctes.add(declarations.group(1).toLowerCase(Locale.ROOT));
            Set<String> physicalTables = new java.util.HashSet<>();
            var matcher = TABLE_REFERENCE.matcher(sql);
            while (matcher.find()) {
                String table = matcher.group(1).toLowerCase(Locale.ROOT);
                if (ctes.contains(table)) continue;
                assertThat(table).isIn(ALLOWED_TABLES);
                physicalTables.add(table);
            }
            assertThat(physicalTables).isNotEmpty();
            return physicalTables;
        }
    }
}

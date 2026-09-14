package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.AnalyticsCityProductReportApi;
import com.rigour.analytics.api.v1.model.CityProductReportView;
import com.rigour.analytics.api.v1.model.CityProductReportView.Row;
import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.application.port.out.CityProductReportStore;
import com.rigour.analytics.application.service.CityProductReportService;
import com.rigour.analytics.infrastructure.persistence.mapper.CityProductReportMapper;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.RequestParam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在隔离H2运行真实报表SQL和用例，不调用共享DEV或启动HTTP服务。 */
class CityProductReportRepositoryTest {
    private static final UUID TENANT = UUID.fromString("019fb700-0000-7000-8000-000000000001");
    private static final String OTHER = "019fb700-0000-7000-8000-000000000099";
    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-30T23:59:59.999999Z");
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private SqlSession session;
    private JdbcTemplate jdbc;
    private CityProductReportMapper mapper;
    private CityProductReportService service;
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        var source = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:city_product_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        configuration.addMapper(CityProductReportMapper.class);
        session = new SqlSessionFactoryBuilder().build(configuration).openSession();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(session.getConnection(), true));
        jdbc.execute("""
                CREATE TABLE bi_sales_order_fact (
                    tenant_id VARCHAR(64), order_id BIGINT, order_no VARCHAR(50), source_order_no VARCHAR(80),
                    source_system_code VARCHAR(32) DEFAULT 'DINGHUOBAO', customer_id BIGINT,
                    region_code VARCHAR(64) DEFAULT 'BJ', region_name VARCHAR(160) DEFAULT '北京',
                    owner_staff_code VARCHAR(50) DEFAULT 'S1', customer_type_code VARCHAR(64) DEFAULT 'STORE',
                    customer_name VARCHAR(200), owner_staff_name VARCHAR(100),
                    order_date DATETIME(6), order_status_code VARCHAR(64) DEFAULT 'COMPLETED',
                    payable_amount DECIMAL(24,6), paid_amount DECIMAL(24,6), unpaid_amount DECIMAL(24,6),
                    synced_time DATETIME(6), deleted INT DEFAULT 0, UNIQUE (tenant_id, order_id))
                """);
        jdbc.execute("""
                CREATE TABLE bi_sales_order_line_fact (
                    tenant_id VARCHAR(64), order_line_id BIGINT, order_id BIGINT,
                    product_category_id BIGINT, product_category_code VARCHAR(50), product_category_name VARCHAR(120),
                    product_id BIGINT, product_code VARCHAR(50), product_name VARCHAR(200),
                    brand_id BIGINT, brand_name VARCHAR(120),
                    product_variant_id BIGINT, sku_code VARCHAR(50), unit_code VARCHAR(64),
                    specification_snapshot VARCHAR(500),
                    quantity DECIMAL(24,6), line_amount DECIMAL(24,6), refund_amount DECIMAL(24,6) DEFAULT 0,
                    sales_net_amount DECIMAL(24,6), synced_time DATETIME(6), deleted INT DEFAULT 0,
                    UNIQUE (tenant_id, order_line_id))
                """);
        jdbc.execute("""
                CREATE TABLE bi_customer_dim (
                    tenant_id VARCHAR(64), customer_id BIGINT, region_code VARCHAR(64), region_name VARCHAR(160),
                    owner_staff_code VARCHAR(50), customer_type_code VARCHAR(64), deleted INT DEFAULT 0)
                """);
        mapper = session.getMapper(CityProductReportMapper.class);
        jdbc.execute("CREATE TABLE bi_product_category_closure (tenant_id VARCHAR(64), ancestor_id BIGINT, descendant_id BIGINT, depth INT)");
        jdbc.update("INSERT INTO bi_product_category_closure VALUES (?,10,10,0),(?,20,20,0),(?,1,1,0),(?,1,10,1),(?,1,20,1)", TENANT.toString(), TENANT.toString(), TENANT.toString(), TENANT.toString(), TENANT.toString());
        jdbc.execute("CREATE TABLE bi_product_dim (tenant_id VARCHAR(64), product_id BIGINT, product_category_id BIGINT, product_category_code VARCHAR(64), product_category_name VARCHAR(160), brand_id BIGINT, brand_name VARCHAR(120))");
        service = new CityProductReportService(new MybatisCityProductReportRepository(mapper), Clock.fixed(NOW, ZoneOffset.UTC));
        authorize(Set.of("analytics:dashboard:read"));
    }

    @AfterEach
    void tearDown() {
        TestAuthorizationContext.clear();
        session.close();
    }

    @Test
    void exactSingleSkuUsesNetReceivableAfterOrderDiscountAndDeduplicatesOrders() {
        order(1, 10, "90", "45");
        line(1, 1, 10, 100, 1000, "BOX", "2", "60");
        line(2, 1, 10, 100, 1000, "BOX", "1", "40");
        order(2, 10, "20", "20");
        line(3, 2, 10, 100, 1000, "BOX", "1", "20");
        var report = query(null, null);
        assertThat(report.rows()).singleElement().satisfies(row -> {
            assertThat(row.quantity()).isEqualByComparingTo("4");
            assertThat(row.salesAmount()).isEqualByComparingTo("120");
            assertThat(row.salesNetAmount()).isEqualByComparingTo("120");
            assertThat(row.receivableAmount()).isEqualByComparingTo("110");
            assertThat(row.paidAmount()).isEqualByComparingTo("65");
            assertThat(row.allocatedPaidAmount()).isNull();
            assertThat(row.allocationStatus()).isEqualTo("EXACT");
            assertThat(row.orderCount()).isEqualTo(2);
            assertThat(row.customerCount()).isEqualTo(1);
        });
        assertThat(report.orderTrace()).hasSize(2);
        assertThat(report.orderTrace().getFirst().orderAdjustmentAmount()).isEqualByComparingTo("-10");
        assertThat(report.generatedAt()).isEqualTo(NOW);
        assertThat(report.dataUpdatedAt()).isEqualTo(FROM.plusSeconds(120));
        assertThat(report.summary().orderCount()).isEqualTo(2);
        assertThat(report.summary().customerCount()).isEqualTo(1);
        conservation(report);
    }

    @Test
    void refundsKeepOriginalOrderedQuantityAndLineNetBeforeOrderDiscount() {
        order(1, 10, "90", "45");
        line(1, 1, 10, 100, 1000, "BOX", "10", "100");
        jdbc.update("UPDATE bi_sales_order_line_fact SET refund_amount=20,sales_net_amount=80 WHERE order_id=1");
        var report = query(null, "EXACT_ONLY");
        assertThat(report.rows()).singleElement().satisfies(row -> {
            assertThat(row.quantity()).isEqualByComparingTo("10");
            assertThat(row.quantities().getFirst().quantity()).isEqualByComparingTo("10");
            assertThat(row.salesAmount()).isEqualByComparingTo("100");
            assertThat(row.refundAmount()).isEqualByComparingTo("20");
            assertThat(row.salesNetAmount()).isEqualByComparingTo("80");
            assertThat(row.receivableAmount()).isNull();
        });
        assertThat(report.orderTrace().getFirst().total()).isEqualByComparingTo("90");
        assertThat(report.orderTrace().getFirst().status()).isEqualTo("REFUND_REVIEW");
        assertThat(report.definitions()).anySatisfy(definition ->
                assertThat(definition).contains("quantity", "未扣退货数量", "不是净出库销量"));
        assertThat(report.definitions()).anySatisfy(definition ->
                assertThat(definition).contains("salesNetAmount", "未减整单优惠", "receivableAmount"));
        conservation(report);
    }

    @Test
    void mixedSkuIsPendingByDefaultWhileSingleCategoryHasExactAttribution() {
        order(1, 10, "90", "45");
        line(1, 1, 10, 100, 1000, "BOX", "2", "60");
        line(2, 1, 10, 200, 2000, "BOX", "1", "40");
        var report = query(null, "EXACT_ONLY");
        assertThat(report.rows()).hasSize(2).allSatisfy(row -> {
            assertThat(row.paidAmount()).isNull();
            assertThat(row.receivableAmount()).isNull();
            assertThat(row.unallocatedOrderCount()).isEqualTo(1);
        });
        assertThat(report.categoryRows()).singleElement().satisfies(row -> {
            assertThat(row.paidAmount()).isEqualByComparingTo("45");
            assertThat(row.receivableAmount()).isEqualByComparingTo("90");
            assertThat(row.allocationStatus()).isEqualTo("EXACT");
            assertThat(row.orderCount()).isEqualTo(1);
        });
        assertThat(report.orderTrace().getFirst().status()).isEqualTo("MIXED_ITEMS");
        assertThat(report.orderTrace().getFirst().categoryStatus()).isEqualTo("EXACT");
        assertThat(report.summary().paidAmount()).isNull();
        assertThat(report.summary().unallocatedOrderCount()).isEqualTo(1);
        conservation(report);
    }

    @Test
    void categoryFilterNeverChangesFullOrderDenominatorOrCopiesWholeOrderPayments() {
        order(1, 10, "90", "45");
        line(1, 1, 10, 100, 1000, "BOX", "2", "60");
        line(2, 1, 20, 200, 2000, "BOX", "1", "40");
        var full = query(null, "PROPORTIONAL");
        var selected = query(10L, "PROPORTIONAL");
        assertThat(selected.rows()).singleElement().satisfies(row -> {
            assertThat(row).isEqualTo(full.rows().getFirst());
            assertThat(row.salesAmount()).isEqualByComparingTo("60");
            assertThat(row.receivableAmount()).isEqualByComparingTo("54");
            assertThat(row.paidAmount()).isEqualByComparingTo("27");
            assertThat(row.allocatedPaidAmount()).isEqualByComparingTo("27");
        });
        assertThat(selected.orderTrace().getFirst().lineTotal()).isEqualByComparingTo("100");
        assertThat(selected.orderTrace().getFirst().selectedLineTotal()).isEqualByComparingTo("60");
        assertThat(selected.orderTrace().getFirst().lineCount()).isEqualTo(2);
        assertThat(selected.orderTrace().getFirst().selectedLineCount()).isEqualTo(1);
        assertThat(selected.summary().orderPaidAmount()).isEqualByComparingTo("45");
        assertThat(selected.summary().excludedPaidAmount()).isEqualByComparingTo("18");
        assertThat(query(10L, "EXACT_ONLY").rows().getFirst().paidAmount()).isNull();
        conservation(full);
        conservation(selected);
    }

    @Test
    void intersectingBrandCategoryProductAndSkuFiltersPreserveWholeOrderAndPartialCategoryAttribution() {
        order(1, 10, "90", "45");
        line(1, 1, 10, 100, 1000, "BOX", "2", "60");
        line(2, 1, 10, 200, 2000, "BUCKET", "12", "40");
        jdbc.update("UPDATE bi_sales_order_line_fact SET brand_id=7, brand_name='品牌甲' WHERE order_line_id=1");
        jdbc.update("UPDATE bi_sales_order_line_fact SET brand_id=8, brand_name='品牌乙' WHERE order_line_id=2");
        var full = query(null, "PROPORTIONAL");
        var selected = service.report(FROM, TO, null, null, null, 10L, null, "PROPORTIONAL", 7L, 100L, 1000L);
        assertThat(selected.rows()).containsExactly(full.rows().getFirst());
        assertThat(selected.categoryRows().getFirst().paidAmount()).isEqualByComparingTo("27");
        assertThat(selected.categoryRows().getFirst().allocatedPaidAmount()).isEqualByComparingTo("27");
        assertThat(selected.orderTrace().getFirst().lineTotal()).isEqualByComparingTo("100");
        assertThat(selected.orderTrace().getFirst().selectedLineTotal()).isEqualByComparingTo("60");
        assertThat(selected.summary().excludedPaidAmount()).isEqualByComparingTo("18");
        assertThat(selected.rows().getFirst().brandName()).isEqualTo("品牌甲");
        assertThat(selected.rows().getFirst().quantity()).isEqualByComparingTo("2");
        var exact = service.report(FROM, TO, null, null, null, 10L, null, "EXACT_ONLY", 7L, 100L, 1000L);
        assertThat(exact.rows().getFirst().paidAmount()).isNull();
        assertThat(exact.categoryRows().getFirst().paidAmount()).isNull();
        assertThat(exact.summary().unallocatedCategoryOrderCount()).isEqualTo(1);
        assertThat(service.report(FROM, TO, null, null, null, 10L, null, null, 8L, 100L, 1000L).rows()).isEmpty();
        assertThat(service.report(FROM, TO, null, null, null, 20L, null, null, 7L, 100L, 1000L).rows()).isEmpty();
        conservation(selected);
        conservation(exact);
    }

    @Test
    void currentErpProductCategoryWinsOverStaleOrderLineSnapshotAndParentIncludesDescendants() {
        order(1, 10, "90", "45");
        line(1, 1, 10, 100, 1000, "BOX", "1", "100");
        jdbc.update("INSERT INTO bi_product_dim VALUES (?,100,20,'NEW','ERP当前分类',7,'ERP当前品牌')", TENANT.toString());
        assertThat(query(10L, "EXACT_ONLY").rows()).isEmpty();
        var parent = query(1L, "EXACT_ONLY");
        assertThat(parent.rows()).singleElement().satisfies(row -> {
            assertThat(row.categoryId()).isEqualTo("20");
            assertThat(row.categoryName()).isEqualTo("ERP当前分类");
            assertThat(row.brandId()).isEqualTo("7");
            assertThat(row.paidAmount()).isEqualByComparingTo("45");
        });
        assertThatThrownBy(() -> query(999L, "EXACT_ONLY")).hasMessageContaining("分类目录");
        conservation(parent);
    }

    @Test
    void unitsStaySeparateAndCategoryAmountsAreAssignedOnceWithDistinctSummaryCounts() {
        order(1, 10, "100", "50");
        line(1, 1, 10, 100, 1000, "BOX", "2", "60");
        line(2, 1, 10, 100, 1001, "BOTTLE", "12", "40");
        var report = query(null, "PROPORTIONAL");
        assertThat(report.rows()).hasSize(2);
        assertThat(report.categoryRows()).singleElement().satisfies(row -> {
            assertThat(row.quantity()).isNull();
            assertThat(row.unitCode()).isNull();
            assertThat(row.quantities()).extracting(CityProductReportView.UnitQuantity::unitCode).containsExactly("BOX", "BOTTLE");
            assertThat(row.quantities().getFirst().quantity()).isEqualByComparingTo("2");
            assertThat(row.quantities().getLast().quantity()).isEqualByComparingTo("12");
        });
        assertThat(report.summary().quantities()).extracting(CityProductReportView.UnitQuantity::unitCode)
                .containsExactly("BOX", "BOTTLE");
        assertThat(sum(report.categoryRows(), Row::paidAmount)).isEqualByComparingTo("50");
        assertThat(sum(report.rows(), Row::paidAmount)).isEqualByComparingTo("50");
        assertThat(report.summary().orderCount()).isEqualTo(1);
        assertThat(report.summary().customerCount()).isEqualTo(1);
        var exact = query(null, "EXACT_ONLY");
        assertThat(exact.categoryRows()).singleElement().satisfies(row -> {
            assertThat(row.paidAmount()).isEqualByComparingTo("50");
            assertThat(row.allocationStatus()).isEqualTo("EXACT");
            assertThat(row.orderCount()).isEqualTo(1);
            assertThat(row.customerCount()).isEqualTo(1);
        });
        assertThat(exact.rows()).allSatisfy(row -> assertThat(row.paidAmount()).isNull());
        assertThat(exact.summary().categoryPaidAmount()).isEqualByComparingTo("50");
        assertThat(exact.summary().unallocatedCategoryOrderCount()).isZero();
        assertThat(exact.summary().paidAmount()).isNull();
        assertThat(exact.summary().unallocatedOrderCount()).isEqualTo(1);
        conservation(report);
    }

    @Test
    void multiplePaymentRecordsAndUnlinkedPaymentsCannotMultiplyOrChangeTheReport() {
        order(1, 10, "100", "50");
        line(1, 1, 10, 100, 1000, "BOX", "2", "100");
        var before = query(null, null);
        jdbc.execute("CREATE TABLE bi_sales_payment_fact (order_id BIGINT, paid_amount DECIMAL(24,6))");
        jdbc.update("INSERT INTO bi_sales_payment_fact VALUES (1,30),(1,20),(NULL,999),(1,999)");
        assertThat(query(null, null)).isEqualTo(before);
        conservation(before);
    }

    @Test
    void partialKnownPaymentsRemainPartialAndAllocatedIsASubsetOfPaid() {
        order(1, 10, "100", "50");
        line(1, 1, 10, 100, 1000, "BOX", "1", "100");
        order(2, 20, "100", "20");
        line(2, 2, 10, 100, 1000, "BOX", "1", "50");
        line(3, 2, 20, 200, 2000, "BOX", "1", "50");
        var exact = query(null, null);
        assertThat(exact.rows().getFirst().paidAmount()).isEqualByComparingTo("50");
        assertThat(exact.rows().getFirst().allocationStatus()).isEqualTo("PARTIAL");
        assertThat(exact.rows().getFirst().unallocatedOrderCount()).isEqualTo(1);
        var proportional = query(null, "PROPORTIONAL");
        assertThat(proportional.rows().getFirst().paidAmount()).isEqualByComparingTo("60");
        assertThat(proportional.rows().getFirst().allocatedPaidAmount()).isEqualByComparingTo("10");
        assertThat(proportional.summary().paidAmount()).isEqualByComparingTo("70");
        assertThat(proportional.summary().allocatedPaidAmount()).isEqualByComparingTo("20");
        conservation(exact);
        conservation(proportional);
    }

    @Test
    void ambiguousAmountsAndMissingLinesStayInOrderTraceWithoutInventedAttribution() {
        for (int i = 1; i <= 7; i++) {
            order(i, 10, "100", "50");
            if (i != 1) line(i, i, 10, 100, 1000, "BOX", "1", "100");
        }
        jdbc.update("UPDATE bi_sales_order_line_fact SET refund_amount=10,sales_net_amount=90 WHERE order_id=2");
        jdbc.update("UPDATE bi_sales_order_line_fact SET line_amount=-100,sales_net_amount=-100 WHERE order_id=3");
        jdbc.update("UPDATE bi_sales_order_line_fact SET line_amount=0,sales_net_amount=0 WHERE order_id=4");
        jdbc.update("UPDATE bi_sales_order_fact SET payable_amount=110,unpaid_amount=60 WHERE order_id=5");
        jdbc.update("UPDATE bi_sales_order_fact SET paid_amount=-1,unpaid_amount=101 WHERE order_id=6");
        jdbc.update("UPDATE bi_sales_order_line_fact SET unit_code=NULL WHERE order_id=7");
        var report = query(null, "PROPORTIONAL");
        assertThat(report.orderTrace()).extracting(CityProductReportView.OrderTrace::status).containsExactly(
                "MISSING_LINES", "REFUND_REVIEW", "NEGATIVE_LINE", "ZERO_DENOMINATOR",
                "EXTRA_CHARGE_OR_MISSING_LINES", "ORDER_BALANCE_REVIEW", "UNKNOWN_DIMENSION");
        assertThat(report.summary().unallocatedOrderCount()).isEqualTo(7);
        assertThat(report.rows()).allSatisfy(row -> assertThat(row.paidAmount()).isNull());
        conservation(report);
    }

    @Test
    void everyFilterAndTenantBoundaryUseOrderHeaderAndLineJoinIncludesTenant() {
        for (int i = 1; i <= 9; i++) {
            order(i, i, "100", "50");
            line(i, i, 10, 100, 1000, "BOX", "1", "100");
        }
        jdbc.update("UPDATE bi_sales_order_fact SET region_code='SH' WHERE order_id=2");
        jdbc.update("UPDATE bi_sales_order_fact SET owner_staff_code='S2' WHERE order_id=3");
        jdbc.update("UPDATE bi_sales_order_fact SET customer_type_code='OTHER' WHERE order_id=4");
        jdbc.update("UPDATE bi_sales_order_fact SET source_system_code='FEISHU' WHERE order_id=5");
        jdbc.update("UPDATE bi_sales_order_fact SET deleted=1 WHERE order_id=6");
        jdbc.update("UPDATE bi_sales_order_fact SET order_status_code='CANCELLED' WHERE order_id=7");
        jdbc.update("UPDATE bi_sales_order_fact SET order_date=? WHERE order_id=8", local(FROM.minusNanos(1000)));
        jdbc.update("UPDATE bi_sales_order_fact SET order_date=? WHERE order_id=9", local(TO.plusNanos(1000)));
        jdbc.update("INSERT INTO bi_sales_order_fact SELECT ?, order_id+100,order_no,source_order_no,source_system_code," +
                "customer_id,region_code,region_name,owner_staff_code,customer_type_code,customer_name,owner_staff_name,order_date,order_status_code," +
                "payable_amount,paid_amount,unpaid_amount,synced_time,deleted FROM bi_sales_order_fact WHERE order_id=1", OTHER);
        jdbc.update("INSERT INTO bi_sales_order_line_fact SELECT ?,order_line_id,order_id,product_category_id," +
                "product_category_code,product_category_name,product_id,product_code,product_name,brand_id,brand_name,product_variant_id," +
                "sku_code,unit_code,specification_snapshot,quantity,line_amount,refund_amount,sales_net_amount,synced_time,deleted " +
                "FROM bi_sales_order_line_fact WHERE order_id=1", OTHER);
        var report = service.report(FROM, TO, " bj ", " S1 ", "store", 10L, "DHB", "PROPORTIONAL");
        assertThat(report.orderTrace()).singleElement().satisfies(trace -> assertThat(trace.orderId()).isEqualTo("1"));
        assertThat(report.rows().getFirst().salesAmount()).isEqualByComparingTo("100");
        assertThat(report.rows().getFirst().paidAmount()).isEqualByComparingTo("50");
        conservation(report);
    }

    @Test
    void roundingConservesMoneyAndNeverExceedsReceivableAcrossHierarchiesAndFilters() {
        Random random = new Random(621);
        long lineId = 0;
        for (int orderId = 1; orderId <= 80; orderId++) {
            int payable = 1 + random.nextInt(1000);
            int paid = random.nextInt(payable + 1);
            order(orderId, orderId % 7, BigDecimal.valueOf(payable, 6).toPlainString(), BigDecimal.valueOf(paid, 6).toPlainString());
            for (int child = 0; child < 7; child++) {
                line(++lineId, orderId, child % 3 + 1, child + 100, child + 1000, "BOX", "1",
                        BigDecimal.valueOf(1000 + random.nextInt(2000), 6).toPlainString());
            }
        }
        var all = query(null, "PROPORTIONAL");
        conservation(all);
        assertThat(sum(all.rows(), Row::paidAmount)).isEqualByComparingTo(all.summary().orderPaidAmount());
        assertThat(sum(all.rows(), Row::receivableAmount)).isEqualByComparingTo(all.summary().orderPayableAmount());
        assertThat(sum(all.categoryRows(), Row::paidAmount)).isEqualByComparingTo(all.summary().orderPaidAmount());
        assertThat(all.rows()).allSatisfy(row -> {
            assertThat(row.paidAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO).isLessThanOrEqualTo(row.receivableAmount());
            assertThat(row.paidAmount()).isEqualByComparingTo(row.allocatedPaidAmount());
        });
        var category = query(1L, "PROPORTIONAL");
        assertThat(category.rows()).containsExactlyElementsOf(all.rows().stream().filter(row -> row.categoryId().equals("1")).toList());
        assertThat(query(null, "PROPORTIONAL")).isEqualTo(all);
        conservation(category);
    }

    @Test
    void zeroReceiptAndFullyDiscountedOrderAreKnownZeroNotMissing() {
        order(1, 10, "0", "0");
        line(1, 1, 10, 100, 1000, "BOX", "1", "100");
        var report = query(null, null);
        assertThat(report.rows().getFirst().paidAmount()).isEqualByComparingTo("0");
        assertThat(report.rows().getFirst().receivableAmount()).isEqualByComparingTo("0");
        assertThat(report.rows().getFirst().unallocatedOrderCount()).isZero();
        conservation(report);
    }

    @Test
    void defaultsMatchOverviewAndSubMicrosecondWindowDoesNotLeakAdjacentOrders() {
        order(1, 10, "100", "50");
        line(1, 1, 10, 100, 1000, "BOX", "1", "100");
        order(2, 10, "50", "25");
        line(2, 2, 10, 100, 1000, "BOX", "1", "50");
        // This is September 1 at 00:00 in Shanghai; a UTC-month default would omit it.
        Instant septemberStart = Instant.parse("2026-08-31T16:00:00Z");
        jdbc.update("UPDATE bi_sales_order_fact SET order_date=? WHERE order_id=2", local(septemberStart));
        var latest = service.report(null, null, null, null, null, null, null, null);
        assertThat(latest.from()).isEqualTo(septemberStart);
        assertThat(latest.to()).isEqualTo(FROM);
        assertThat(latest.summary().orderCount()).isEqualTo(2);
        assertThat(latest.summary().salesAmount()).isEqualByComparingTo("150");
        assertThat(latest.summary().paidAmount()).isEqualByComparingTo("75");
        assertThat(latest.allocationMode()).isEqualTo("EXACT_ONLY");
        assertThat(service.report(FROM.plusNanos(1), TO, null, null, null, null, null, null).rows()).isEmpty();
        assertThatThrownBy(() -> service.report(FROM.plusNanos(1), FROM.plusNanos(2), null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void actualSqlCapNeverProducesExportablePartialOrdersOrSummary() {
        order(1, 10, "100", "50");
        line(1, 1, 10, 100, 1000, "BOX", "1", "50");
        line(2, 1, 10, 200, 2000, "BOX", "1", "50");
        var repository = new MybatisCityProductReportRepository(mapper);
        CityProductReportStore limited = new CityProductReportStore() {
            public List<Long> categoryIds(String tenantId, Long parentId) { return repository.categoryIds(tenantId, parentId); }
            public List<CityProductReportView.CustomerArchive> customerArchives(String tenantId, SupplyDashboardFilter filter) {
                return repository.customerArchives(tenantId, filter);
            }
            public Optional<Instant> latestOrderDate(String tenantId) { return repository.latestOrderDate(tenantId); }
            public List<FactRow> load(String tenantId, SupplyDashboardFilter filter, int orders, int lines) {
                return repository.load(tenantId, filter, orders, 1);
            }
            public List<FactRow> load(String tenantId, SupplyDashboardFilter filter, ProductSelection selection, int orders, int lines) {
                return repository.load(tenantId, filter, selection, orders, 1);
            }
        };
        var capped = new CityProductReportService(limited, Clock.fixed(NOW, ZoneOffset.UTC))
                .report(FROM, TO, null, null, null, null, null, null);
        assertThat(capped.truncated()).isTrue();
        assertThat(capped.exportBlocked()).isTrue();
        assertThat(capped.rows()).isEmpty();
        assertThat(capped.orderTrace()).isEmpty();
        assertThat(capped.summary()).isNull();
    }

    @Test
    void orderLimitIsExplicitAndDoesNotReturnASilentFirstPage() {
        jdbc.update("""
                INSERT INTO bi_sales_order_fact
                    (tenant_id,order_id,customer_id,order_date,payable_amount,paid_amount,unpaid_amount)
                SELECT ?, n, n, ?, 1, 0, 1 FROM SYSTEM_RANGE(1, ?) AS series(n)
                """, TENANT.toString(), local(FROM), CityProductReportService.MAX_ORDERS + 1);
        var report = query(null, null);
        assertThat(report.truncated()).isTrue();
        assertThat(report.exportBlocked()).isTrue();
        assertThat(report.summary()).isNull();
    }

    @Test
    void cglibProxyExecutesRealSqlInsideReadOnlyTransaction() {
        order(1, 10, "100", "50");
        line(1, 1, 10, 100, 1000, "BOX", "1", "100");
        var repository = new MybatisCityProductReportRepository(mapper);
        var observedCalls = new java.util.concurrent.atomic.AtomicInteger();
        CityProductReportStore observed = new CityProductReportStore() {
            public List<Long> categoryIds(String tenantId, Long parentId) { return repository.categoryIds(tenantId, parentId); }
            public List<CityProductReportView.CustomerArchive> customerArchives(String tenantId, SupplyDashboardFilter filter) {
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                return repository.customerArchives(tenantId, filter);
            }
            public Optional<Instant> latestOrderDate(String tenantId) { return repository.latestOrderDate(tenantId); }
            public List<FactRow> load(String tenantId, SupplyDashboardFilter filter, int orders, int lines) {
                return load(tenantId, filter, new ProductSelection(null, null, null), orders, lines);
            }
            public List<FactRow> load(String tenantId, SupplyDashboardFilter filter, ProductSelection selection, int orders, int lines) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                observedCalls.incrementAndGet();
                return repository.load(tenantId, filter, selection, orders, lines);
            }
        };
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(jdbc.getDataSource()));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(new CityProductReportService(observed, Clock.fixed(NOW, ZoneOffset.UTC)));
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        var proxy = (CityProductReportService) factory.getProxy();
        assertThat(AopUtils.isCglibProxy(proxy)).isTrue();
        var report = proxy.report(FROM, TO, null, null, null, null, null, null);
        assertThat(observedCalls.get()).isEqualTo(1);
        assertThat(report.rows()).singleElement().satisfies(row -> assertThat(row.paidAmount()).isEqualByComparingTo("50"));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    }

    @Test
    void endpointAndSqlAreReadOnlyLocalAndAuthorizationRejectsBeforeReading() throws Exception {
        for (String method : List.of("latestOrderDate", "load")) {
            MappedStatement statement = configuration.getMappedStatement(CityProductReportMapper.class.getName() + "." + method);
            assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
            var params = new java.util.HashMap<String, Object>();
            params.put("tenantId", TENANT.toString());
            params.put("from", local(FROM));
            params.put("to", local(TO));
            params.put("productCategoryId", 10L);
            params.put("orderLimit", 100);
            params.put("lineLimit", 100);
            String sql = statement.getBoundSql(params).getSql().toLowerCase(java.util.Locale.ROOT);
            assertThat(sql).doesNotContain("bi_sales_payment_fact", "rigour_", "insert ", "update ", "delete ");
        }
        assertThat(CityProductReportService.class.getMethod("report", Instant.class, Instant.class, String.class,
                String.class, String.class, Long.class, String.class, String.class).getAnnotation(Transactional.class).readOnly()).isTrue();
        var endpoint = AnalyticsCityProductReportApi.class.getDeclaredMethods()[0];
        assertThat(endpoint.getParameters()[7].getAnnotation(RequestParam.class).defaultValue()).isEqualTo("EXACT_ONLY");
        authorize(Set.of());
        assertThatThrownBy(() -> query(null, null)).isInstanceOf(AuthorizationDeniedException.class);
        authorize(Set.of("analytics:dashboard:read"));
        assertThatThrownBy(() -> query(null, "INVALID")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query(-1L, null)).isInstanceOf(BusinessException.class);
        UUID principal = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("SERVICE", principal, null, null, null, UUID.randomUUID(),
                0, 0, 0, Set.of(), Set.of("analytics:dashboard:read")));
        assertThatThrownBy(() -> query(null, null)).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void businessMonthsUseShanghaiBoundariesAndDoNotReallocateReceipts() {
        order(1, 10, "100", "50");
        order(2, 10, "100", "60");
        line(1, 1, 10, 100, 1000, "BUCKET", "12", "60");
        line(2, 1, 20, 200, 2000, "BOX", "1", "40");
        line(3, 2, 10, 100, 1000, "BUCKET", "12", "100");
        jdbc.update("UPDATE bi_sales_order_fact SET order_date=? WHERE order_id=1", local(Instant.parse("2026-08-31T15:59:59Z")));
        jdbc.update("UPDATE bi_sales_order_fact SET order_date=? WHERE order_id=2", local(Instant.parse("2026-08-31T16:00:00Z")));
        var from = Instant.parse("2026-07-31T16:00:00Z");
        var to = Instant.parse("2026-09-30T15:59:59Z");
        var report = service.report(from, to, null, null, null, 10L, null, "PROPORTIONAL");
        assertThat(report.monthlyRows()).extracting(CityProductReportView.MonthlyRow::month)
                .containsExactly("2026-08", "2026-09");
        assertThat(report.monthlyRows().getFirst().metrics().paidAmount()).isEqualByComparingTo("30");
        assertThat(sum(report.monthlyRows(), row -> row.metrics().paidAmount())).isEqualByComparingTo(report.summary().paidAmount());
        var exact = service.report(from, to, null, null, null, 10L, null, null);
        assertThat(exact.monthlyRows().getFirst().metrics().paidAmount()).isNull();
        assertThat(exact.monthlyRows().getFirst().metrics().unallocatedOrderCount()).isEqualTo(1);
        var latest = service.report(null, null, null, null, null, 10L, null, null);
        assertThat(latest.from()).isEqualTo(Instant.parse("2026-08-31T16:00:00Z"));
        assertThat(latest.monthlyRows()).extracting(CityProductReportView.MonthlyRow::month)
                .containsExactly("2026-09");
        assertThat(latest.summary().paidAmount()).isEqualByComparingTo("60");
    }

    @Test
    void archivesAreCurrentTenantScopedCustomerCountsNotOrderCustomers() {
        jdbc.update("INSERT INTO bi_customer_dim VALUES (?, 10, 'BJ', '北京', 'S1', 'STORE', 0)", TENANT.toString());
        jdbc.update("INSERT INTO bi_customer_dim VALUES (?, 11, 'BJ', '北京', 'S1', 'STORE', 0)", TENANT.toString());
        jdbc.update("INSERT INTO bi_customer_dim VALUES (?, 12, 'BJ', '北京', 'S2', 'STORE', 0)", TENANT.toString());
        jdbc.update("INSERT INTO bi_customer_dim VALUES (?, 13, 'BJ', '北京', 'S1', 'STORE', 1)", TENANT.toString());
        jdbc.update("INSERT INTO bi_customer_dim VALUES ('other', 14, 'BJ', '北京', 'S1', 'STORE', 0)");
        order(1, 10, "10", "5");
        line(1, 1, 10, 100, 1000, "BOX", "1", "10");
        jdbc.update("UPDATE bi_sales_order_fact SET customer_name='客户甲', owner_staff_name='销售甲'");
        jdbc.update("UPDATE bi_sales_order_line_fact SET specification_snapshot='12桶/箱'");
        var report = service.report(FROM, TO, "BJ", "S1", "STORE", 10L, "DINGHUOBAO", null);
        assertThat(report.customerArchives()).singleElement().satisfies(row -> assertThat(row.customerCount()).isEqualTo(2));
        assertThat(report.summary().customerCount()).isEqualTo(1);
        assertThat(report.orderTrace().getFirst().customerName()).isEqualTo("客户甲");
        assertThat(report.orderTrace().getFirst().ownerStaffName()).isEqualTo("销售甲");
        assertThat(report.rows().getFirst().specification()).isEqualTo("12桶/箱");
    }

    private CityProductReportView query(Long category, String mode) {
        return service.report(FROM, TO, null, null, null, category, null, mode);
    }

    private void order(long id, long customer, String payable, String paid) {
        jdbc.update("""
                INSERT INTO bi_sales_order_fact
                    (tenant_id,order_id,order_no,source_order_no,customer_id,order_date,payable_amount,paid_amount,unpaid_amount,synced_time)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, TENANT.toString(), id, "O" + id, "SOURCE" + id, customer, local(FROM),
                new BigDecimal(payable), new BigDecimal(paid), new BigDecimal(payable).subtract(new BigDecimal(paid)), local(FROM.plusSeconds(60)));
    }

    private void line(long id, long order, long category, long product, long sku, String unit, String quantity, String amount) {
        jdbc.update("""
                INSERT INTO bi_sales_order_line_fact
                    (tenant_id,order_line_id,order_id,product_category_id,product_category_code,product_category_name,
                     product_id,product_code,product_name,product_variant_id,sku_code,unit_code,quantity,line_amount,sales_net_amount,synced_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, TENANT.toString(), id, order, category, "C" + category, "分类" + category,
                product, "P" + product, "商品" + product, sku, "SKU" + sku, unit, new BigDecimal(quantity),
                new BigDecimal(amount), new BigDecimal(amount), local(FROM.plusSeconds(120)));
    }

    private static LocalDateTime local(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }

    private static void authorize(Set<String> permissions) {
        UUID user = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT", user, TENANT, user, null, UUID.randomUUID(),
                0, 0, 0, Set.of(), permissions));
    }

    private static <T> BigDecimal sum(List<T> rows, Function<T, BigDecimal> getter) {
        return rows.stream().map(getter).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void conservation(CityProductReportView report) {
        assertThat(report.exportBlocked()).isFalse();
        report.orderTrace().forEach(trace -> assertThat(trace.paid()).isEqualByComparingTo(
                (trace.selectedPaidAmount() == null ? BigDecimal.ZERO : trace.selectedPaidAmount())
                        .add(trace.excludedPaidAmount()).add(trace.unallocatedPaidAmount())));
        var summary = report.summary();
        assertThat(summary.orderPaidAmount()).isEqualByComparingTo(
                (summary.paidAmount() == null ? BigDecimal.ZERO : summary.paidAmount())
                        .add(summary.excludedPaidAmount()).add(summary.unallocatedPaidAmount()));
    }
}

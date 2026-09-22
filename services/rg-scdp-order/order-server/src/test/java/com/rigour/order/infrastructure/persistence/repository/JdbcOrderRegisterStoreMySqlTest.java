package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.order.api.v1.model.OrderRegisterModels.HistoryCoverage;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterLineView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodRow;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodStatisticsView;
import com.rigour.order.api.v1.model.OrderRegisterModels.ReceivablesView;
import com.rigour.order.application.port.out.OrderRegisterStore.LineCriteria;
import com.rigour.order.application.port.out.OrderRegisterStore.OrderCriteria;
import com.rigour.order.application.port.out.OrderRegisterStore.PaymentCriteria;
import com.rigour.order.application.port.out.OrderRegisterStore.PeriodCriteria;
import com.rigour.order.application.port.out.OrderRegisterStore.ReceivablesCriteria;
import com.rigour.shared.context.TestAuthorizationContext;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 期间统计/未回款/创建人 SQL 在真实 MySQL 上验证：分组合计口径、期末未回款含往期订单、
 * 快照优先于订单字段、历史覆盖状态与四项应收合计。
 */
@Testcontainers
class JdbcOrderRegisterStoreMySqlTest {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("rigour_order")
                    .withUsername("rigour_order")
                    .withPassword("rigour_order");

    static final String TENANT = UUID.randomUUID().toString();
    static final String TENANT_COVERED = UUID.randomUUID().toString();
    static JdbcTemplate jdbc;
    static JdbcOrderRegisterStore store;

    @BeforeAll
    static void migrate() {
        // 与运行时一致：DATETIME 按 UTC 会话读写，否则测试写库与读库的时区约定会互相偏移。
        String url =
                MYSQL.getJdbcUrl()
                        + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                        + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        var ds = new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(ds);
        // 无授权上下文时 OrderDataScope 返回 1=1，本测试只验证 SQL 口径本身。
        TestAuthorizationContext.clear();
        store = new JdbcOrderRegisterStore(jdbc, new OrderDataScope(null, jdbc));
        fixture();
    }

    @Test
    void periodTotalsCoverOrderReceivedRefundNetAndEndingUnpaid() {
        PeriodStatisticsView view = store.periodStatistics(TENANT, period("region", null, null, null, null));

        assertThat(view.dateFrom()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(view.dateTo()).isEqualTo(LocalDate.parse("2026-09-17"));
        assertThat(view.groupBy()).isEqualTo("region");
        assertThat(view.totals().periodOrderAmount()).isEqualByComparingTo("340");
        // PM-6 挂在 SO-7（8 月订单）上，期间实收按收款时间口径计入，不受订单日期限制。
        assertThat(view.totals().periodReceivedAmount()).isEqualByComparingTo("205");
        assertThat(view.totals().periodRefundAmount()).isEqualByComparingTo("20");
        assertThat(view.totals().periodNetReceivedAmount()).isEqualByComparingTo("185");
        assertThat(view.totals().endingUnpaidAmount()).isEqualByComparingTo("245");
    }

    @Test
    void periodRowsGroupByRegionUseSnapshotAndKeepKeyLabelContract() {
        PeriodStatisticsView view = store.periodStatistics(TENANT, period("region", null, null, null, null));

        assertThat(view.rows()).extracting(PeriodRow::key).containsExactly("HZ", "BJ", null);
        assertThat(view.rows()).extracting(PeriodRow::label).containsExactly("HZ", "BJ", null);

        PeriodRow hangzhou = view.rows().get(0);
        assertThat(hangzhou.periodOrderAmount()).isEqualByComparingTo("260");
        assertThat(hangzhou.periodReceivedAmount()).isEqualByComparingTo("205");
        assertThat(hangzhou.periodRefundAmount()).isEqualByComparingTo("20");
        assertThat(hangzhou.periodNetReceivedAmount()).isEqualByComparingTo("185");
        assertThat(hangzhou.endingUnpaidAmount()).isEqualByComparingTo("165");

        PeriodRow beijing = view.rows().get(1);
        assertThat(beijing.periodOrderAmount()).isEqualByComparingTo("50");
        assertThat(beijing.periodReceivedAmount()).isEqualByComparingTo("0");
        assertThat(beijing.endingUnpaidAmount()).isEqualByComparingTo("50");

        PeriodRow unassigned = view.rows().get(2);
        assertThat(unassigned.periodOrderAmount()).isEqualByComparingTo("30");
        assertThat(unassigned.endingUnpaidAmount()).isEqualByComparingTo("30");
    }

    @Test
    void periodRowsGroupByCustomerAndEmployeeResolveReadableLabels() {
        PeriodStatisticsView customers =
                store.periodStatistics(TENANT, period("customer", null, null, null, null));
        assertThat(customers.rows()).extracting(PeriodRow::key).containsExactly("1", "3", "2", "5");
        assertThat(customers.rows())
                .extracting(PeriodRow::label)
                .containsExactly("杭州甲客户", "杭州乙客户", "北京丙客户", "超额客户");

        PeriodStatisticsView employees =
                store.periodStatistics(TENANT, period("employee", null, null, null, null));
        assertThat(employees.rows()).extracting(PeriodRow::key).containsExactly("EMP-1", "EMP-2");
        assertThat(employees.rows()).extracting(PeriodRow::label).containsExactly("张三", "赵六");
        assertThat(employees.rows().get(0).periodOrderAmount()).isEqualByComparingTo("260");
        assertThat(employees.rows().get(1).periodOrderAmount()).isEqualByComparingTo("80");
    }

    @Test
    void periodFiltersByCustomerAndRegionStillApplyToRowsAndTotals() {
        PeriodStatisticsView filtered =
                store.periodStatistics(TENANT, period("region", "HZ", null, "甲", null));
        assertThat(filtered.totals().periodOrderAmount()).isEqualByComparingTo("200");
        assertThat(filtered.rows()).extracting(PeriodRow::key).containsExactly("HZ");

        // 员工筛选按订单自身员工字段，分组展示优先冻结快照；SO-6 订单侧 EMP-2、快照侧 HZ/EMP-1。
        PeriodStatisticsView employeeFiltered =
                store.periodStatistics(TENANT, period("region", null, "EMP-2", null, null));
        assertThat(employeeFiltered.totals().periodOrderAmount()).isEqualByComparingTo("140");
        assertThat(employeeFiltered.rows()).extracting(PeriodRow::key).containsExactly("HZ", "BJ", null);
        assertThat(employeeFiltered.rows().get(0).periodOrderAmount()).isEqualByComparingTo("60");
    }

    @Test
    void periodRejectsInvertedRangeAndIgnoresDeletedOrders() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM order_sales_order WHERE tenant_id=? AND deleted=1",
                                Long.class,
                                TENANT))
                .isEqualTo(1L);
        assertThatThrownBy(
                        () ->
                                store.periodStatistics(
                                        TENANT,
                                        new PeriodCriteria(
                                                LocalDate.parse("2026-09-17"),
                                                LocalDate.parse("2026-09-01"),
                                                "region",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null)))
                .hasMessageContaining("dateFrom必须早于dateTo");
        PeriodStatisticsView view = store.periodStatistics(TENANT, period("region", null, null, null, null));
        assertThat(view.totals().periodOrderAmount()).isEqualByComparingTo("340");
    }

    @Test
    void historyCoverageRequiresFinancialEventsBeforeCutover() {
        HistoryCoverage missing = store.periodStatistics(TENANT, period(null, null, null, null, null)).coverage();
        assertThat(missing.historyComplete()).isFalse();
        assertThat(missing.missingCount()).isEqualTo(2L);
        assertThat(missing.coverageFrom()).isEqualTo(Instant.parse("2026-09-03T16:00:00Z"));
        assertThat(missing.message()).contains("历史资金事件");

        HistoryCoverage covered =
                store.periodStatistics(TENANT_COVERED, period(null, null, null, null, null)).coverage();
        assertThat(covered.historyComplete()).isTrue();
        assertThat(covered.missingCount()).isZero();
    }

    @Test
    void receivablesTotalsExposeFourAmounts() {
        var unpaidOnly =
                store.receivables(
                        TENANT, 0, 20, new ReceivablesCriteria(LocalDate.parse("2026-09-17"), true, null, null, null, null, null));
        assertThat(unpaidOnly.total()).isEqualTo(5L);
        assertThat(unpaidOnly.totals())
                .containsOnlyKeys(
                        "receivableAmount", "netReceivedAmount", "unpaidAmount", "overpaidAmount");
        assertThat(unpaidOnly.totals().get("receivableAmount")).isEqualByComparingTo("440");
        assertThat(unpaidOnly.totals().get("netReceivedAmount")).isEqualByComparingTo("195");
        assertThat(unpaidOnly.totals().get("unpaidAmount")).isEqualByComparingTo("245");
        assertThat(unpaidOnly.totals().get("overpaidAmount")).isEqualByComparingTo("0");

        var all =
                store.receivables(
                        TENANT, 0, 20, new ReceivablesCriteria(LocalDate.parse("2026-09-17"), false, null, null, null, null, null));
        assertThat(all.total()).isEqualTo(6L);
        assertThat(all.totals().get("receivableAmount")).isEqualByComparingTo("450");
        assertThat(all.totals().get("netReceivedAmount")).isEqualByComparingTo("210");
        assertThat(all.totals().get("unpaidAmount")).isEqualByComparingTo("245");
        assertThat(all.totals().get("overpaidAmount")).isEqualByComparingTo("5");

        ReceivablesView overpaid =
                all.items().stream()
                        .filter(item -> "SO-7".equals(item.orderNo()))
                        .findFirst()
                        .orElseThrow();
        assertThat(overpaid.receivableAmount()).isEqualByComparingTo("10");
        assertThat(overpaid.netReceivedAmount()).isEqualByComparingTo("15");
        assertThat(overpaid.unpaidAmount()).isEqualByComparingTo("0");
        assertThat(overpaid.overpaidAmount()).isEqualByComparingTo("5");
    }

    @Test
    void creatorsAreTrimmedDeduplicatedAndStable() {
        List<String> creators = store.creators(TENANT);
        assertThat(creators).containsExactlyInAnyOrder("张三", "赵六", "王五");
        assertThat(creators).hasSize(3);
        assertThat(creators).doesNotContainNull();
        assertThat(creators).allSatisfy(name -> assertThat(name).isEqualTo(name.strip()));
        // 排序受 MySQL 排序规则影响，只校验稳定与升序结果一致，不锁定汉字顺序。
        assertThat(creators).isSortedAccordingTo(java.util.Comparator.naturalOrder());
        assertThat(store.creators(TENANT)).isEqualTo(creators);
        assertThat(store.creators(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void invoiceStatusFilterSplitsNotAppliedPendingAndInvoiced() {
        String tenant = UUID.randomUUID().toString();
        long pending = order(tenant, "SO-INV-1", 1L, "C-1", "发票客户甲", "HZ", "EMP-1", "张三", "2026-09-11T05:00:00Z", 10, 0);
        long invoiced = order(tenant, "SO-INV-2", 1L, "C-1", "发票客户乙", "HZ", "EMP-1", "张三", "2026-09-11T06:00:00Z", 20, 0);
        long revoked = order(tenant, "SO-INV-3", 1L, "C-1", "发票客户丙", "HZ", "EMP-1", "张三", "2026-09-11T07:00:00Z", 30, 0);
        long untouched = order(tenant, "SO-INV-4", 1L, "C-1", "发票客户丁", "HZ", "EMP-1", "张三", "2026-09-11T08:00:00Z", 40, 0);
        invoice(tenant, pending, "SO-INV-1", "PENDING");
        invoice(tenant, invoiced, "SO-INV-2", "INVOICED");
        invoice(tenant, revoked, "SO-INV-3", "REVOKED");

        assertThat(orderNumbersWithInvoice(tenant, "PENDING")).containsExactly("SO-INV-1");
        assertThat(orderNumbersWithInvoice(tenant, "INVOICED")).containsExactly("SO-INV-2");
        // 已撤回与无发票行都按“未申请”口径展示。
        assertThat(orderNumbersWithInvoice(tenant, "NOT_APPLIED"))
                .containsExactlyInAnyOrder("SO-INV-3", "SO-INV-4");
        assertThat(untouched).isPositive();
    }

    @Test
    void orderDatesRoundTripStoredUtcInstants() {
        String tenant = UUID.randomUUID().toString();
        order(tenant, "SO-UTC-1", 1L, "C-1", "时间客户", "HZ", "EMP-1", "张三", "2026-08-31T16:00:00Z", 10, 0);

        var page =
                store.orders(
                        tenant,
                        0,
                        10,
                        new OrderCriteria(
                                null, null, null, null, null, null, null,
                                Instant.parse("2026-08-31T16:00:00Z"),
                                Instant.parse("2026-09-01T16:00:00Z"),
                                null, null, null, null, null, null, null, null, null, null));

        assertThat(page.items()).singleElement()
                .satisfies(view -> assertThat(view.orderDate()).isEqualTo(Instant.parse("2026-08-31T16:00:00Z")));
    }

    @Test
    void dhbLinkedFilterMatchesDisplayedDhbOrderNo() {
        String tenant = UUID.randomUUID().toString();
        order(tenant, "SO-DHB-LINK-1", 1L, "C-1", "关联客户", "HZ", "EMP-1", "张三",
                "2026-09-11T05:00:00Z", 10, 0);
        order(tenant, "SO-DHB-LINK-2", 1L, "C-1", "关联客户", "HZ", "EMP-1", "张三",
                "2026-09-11T06:00:00Z", 20, 0);
        jdbc.update(
                "INSERT INTO order_number_mapping(tenant_id,source_system_code,source_object_type,"
                        + " source_object_id,dhb_order_no,internal_order_no,state,evidence,deleted)"
                        + " VALUES(?,'DINGHUOBAO','ORDER','DH-1','DH.20260911.0001','SO-DHB-LINK-1',"
                        + " 'ACTIVE','历史单关联核对',0)",
                tenant);

        assertThat(orderNumbers(tenant, true)).containsExactly("SO-DHB-LINK-1");
        var matched = store.orders(tenant, 0, 20, new OrderCriteria(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, "0911.0001", null, null, null, null));
        assertThat(matched.items()).extracting(v -> v.orderNo()).containsExactly("SO-DHB-LINK-1");
        assertThat(matched.total()).isEqualTo(1);
        assertThat(matched.totals().get("payableAmount")).isEqualByComparingTo("10");

        assertThat(orderNumbers(tenant, false)).containsExactly("SO-DHB-LINK-2");
        assertThat(orderNumbers(tenant, null))
                .containsExactlyInAnyOrder("SO-DHB-LINK-1", "SO-DHB-LINK-2");
    }

    @Test
    void dhbLinkedFilterReadsHistoryGroupSourceNumbers() {
        String tenant = UUID.randomUUID().toString();
        long linked =
                order(tenant, "SO-DHB-HIST-1", 1L, "C-1", "关联客户", "HZ", "EMP-1", "张三",
                        "2026-09-12T05:00:00Z", 10, 0);
        order(tenant, "SO-DHB-HIST-2", 1L, "C-1", "关联客户", "HZ", "EMP-1", "张三",
                "2026-09-12T06:00:00Z", 20, 0);
        jdbc.update(
                "INSERT INTO order_history_group(tenant_id,id,customer_id,evidence,actor_id,created_at)"
                        + " VALUES(?,?,1,?,?,UTC_TIMESTAMP(6))",
                tenant, "G-DHB-HIST-1", "批量历史关联核对", "tester");
        jdbc.update(
                "INSERT INTO order_history_member(tenant_id,order_id,group_id,baseline_at,opening_paid)"
                        + " VALUES(?,?,?,?,?)",
                tenant, linked, "G-DHB-HIST-1",
                Timestamp.from(Instant.parse("2026-09-03T16:00:00Z")), BigDecimal.ZERO);
        for (String no : List.of("DH.20260912.0001", "DH.20260912.0002")) {
            jdbc.update(
                    "INSERT INTO order_sync_source(tenant_id,connector_id,source_no,customer_id,"
                            + " source_date,amount,payload,checksum,state,group_id,revision)"
                            + " VALUES(?,'CONN-DHB-HIST',?,1,?,10,'{}',?,'BOUND','G-DHB-HIST-1',0)",
                    tenant, no, Timestamp.from(Instant.parse("2026-09-12T05:00:00Z")), "hash-" + no);
        }

        assertThat(orderNumbers(tenant, true)).containsExactly("SO-DHB-HIST-1");
        assertThat(store.orders(tenant, 0, 20, new OrderCriteria(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, "0912.0002", null, null, null, null)).items())
                .extracting(v -> v.orderNo()).containsExactly("SO-DHB-HIST-1");

        assertThat(orderNumbers(tenant, false)).containsExactly("SO-DHB-HIST-2");
        assertThat(orderNumbersWithDhbNo(tenant))
                .containsEntry("SO-DHB-HIST-1", "DH.20260912.0001+DH.20260912.0002");
    }

    private static java.util.Map<String, String> orderNumbersWithDhbNo(String tenant) {
        return store.orders(
                        tenant,
                        0,
                        50,
                        new OrderCriteria(
                                null, null, null, null, null, null, null, null, null, null, null, null,
                                null, null, null, null, null, null, null))
                .items()
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        view -> view.orderNo(),
                        view -> view.dhbOrderNo() == null ? "" : view.dhbOrderNo()));
    }

    @Test
    void lineReceivedAmountIsAllocatedByLineShareForFullAndPartialPayment() {
        String tenant = UUID.randomUUID().toString();
        long orderId = order(tenant, "SO-LINE-PAY-1", 1L, "C-1", "分摊客户", "HZ", "EMP-1", "张三",
                "2026-09-11T05:00:00Z", 100, 0);
        insertLine(tenant, orderId, 1, "台呢", "60");
        insertLine(tenant, orderId, 2, "皮头", "40");
        insertPayment(tenant, "PAY-LINE-1", orderId, "2026-09-12T05:00:00Z", 50, "RECEIVED", null);

        jdbc.update("UPDATE order_sales_order SET paid_amount=50,unpaid_amount=50 WHERE tenant_id=? AND id=?", tenant, orderId);
        // 部分回款：按明细金额占订单应收的比例分摊（60% / 40%），合计等于订单实收
        var partial = lineStats(tenant, "台呢");
        assertThat(partial.total()).isEqualTo(1);
        assertThat(partial.items().getFirst().receivedAmount()).isEqualByComparingTo("30.00");
        var partialTotals = lineTotals(tenant, null);
        assertThat(partialTotals.get("receivedAmount")).isEqualByComparingTo("50.00");
        assertThat(partialTotals.get("lineAmount")).isEqualByComparingTo("100.00");

        // 全部回款：明细分摊回到各自明细金额
        insertPayment(tenant, "PAY-LINE-2", orderId, "2026-09-13T05:00:00Z", 50, "CHECKED", "TXN-LINE-2");
        jdbc.update("UPDATE order_sales_order SET paid_amount=100,unpaid_amount=0 WHERE tenant_id=? AND id=?", tenant, orderId);
        var full = lineStats(tenant, null);
        assertThat(full.items()).hasSize(2);
        assertThat(full.items())
                .extracting(view -> view.receivedAmount().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder("60", "40");
        assertThat(lineTotals(tenant, null).get("receivedAmount")).isEqualByComparingTo("100.00");
    }

    @Test
    void allThreeListsFilterFrozenDepartmentRegionAndEmployeeAndCreators() {
        String tenant = UUID.randomUUID().toString();
        long a = order(tenant, "FILTER-A", 1L, "C1", "客户甲", "OLD", "OLD", "旧归属", "2026-09-11T05:00:00Z", 80, 0);
        long b = order(tenant, "FILTER-B", 2L, "C2", "客户乙", "NEW", "E10", "另一人", "2026-09-11T05:00:00Z", 90, 0);
        insertLine(tenant, a, 1, "商品", "100");
        insertLine(tenant, b, 1, "商品", "100");
        insertPayment(tenant, "PAY-A", a, "2026-09-12T05:00:00Z", 10, "CHECKED", null);
        insertPayment(tenant, "PAY-B", b, "2026-09-12T05:00:00Z", 20, "CHECKED", null);
        jdbc.update("UPDATE order_sales_order SET source_creator_name='来源创建人' WHERE tenant_id=? AND id=?", tenant, a);
        jdbc.update("INSERT INTO order_attribution_snapshot(tenant_id,order_id,state,employee_code,employee_name,"
                + "department_id,department_name,department_path,region_code,region_path,source_version,"
                + "customer_revision,employee_revision,organization_version,resolved_at,frozen_at,revision)"
                + " VALUES(?,?,'FROZEN','E1','新归属',11,'武汉市','[]','NEW','[]','v1',1,1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1)", tenant, a);
        for (java.util.Set<Long> departments : java.util.List.of(java.util.Set.of(11L), java.util.Set.of(12L))) {
            long expected = departments.contains(11L) ? 1 : 0;
            var orders = store.orders(tenant, 0, 20, new OrderCriteria(null,null,null,null,"NEW","E1",departments,
                    null,null,null,null,null,null,null,null,null,null,null,"来源创建人"));
            var lines = store.lines(tenant, 0, 20, new LineCriteria(null,null,null,null,"NEW","E1",departments,
                    null,null,null,null,null,null,null,null,null,null));
            var payments = store.payments(tenant, 0, 20, new PaymentCriteria(null,null,null,null,"NEW","E1",departments,
                    null,null,null,null,null,null,null,null,null,null,"来源创建人"));
            assertThat(orders.total()).isEqualTo(expected);
            assertThat(lines.total()).isEqualTo(expected);
            assertThat(payments.total()).isEqualTo(expected);
            if (expected == 1) {
                assertThat(orders.items().getFirst().orderNo()).isEqualTo("FILTER-A");
                assertThat(lines.items().getFirst().orderNo()).isEqualTo("FILTER-A");
                assertThat(payments.items().getFirst().orderNo()).isEqualTo("FILTER-A");
            }
        }
        assertThat(store.orders(tenant,0,20,new OrderCriteria(null,null,null,null,null,null,null,null,null,null,
                null,null,null,null,null,null,null,null,"不存在的创建人")).total()).isZero();
    }

    @Test
    void orderDiscountFilterAndSortingApplyBeforePaginationAndToTotals() {
        String tenant = UUID.randomUUID().toString();
        long a = order(tenant, "SORT-A", 1L, "C1", "客户", "HZ", "E1", "张三", "2026-09-11T05:00:00Z", 80, 0);
        long b = order(tenant, "SORT-B", 1L, "C1", "客户", "HZ", "E1", "张三", "2026-09-11T05:00:00Z", 150, 0);
        long c = order(tenant, "SORT-C", 1L, "C1", "客户", "HZ", "E1", "张三", "2026-09-11T05:00:00Z", 100, 0);
        insertLine(tenant, a, 1, "商品", "100");
        insertLine(tenant, b, 1, "商品", "200");
        insertLine(tenant, c, 1, "商品", "100");
        for (String sort : List.of("payableAmount", "discountAmount", "discountRate")) {
            var criteria = orderDiscountCriteria(true, sort, "desc");
            var first = store.orders(tenant, 0, 1, criteria);
            assertThat(first.total()).isEqualTo(2);
            assertThat(first.items().getFirst().orderNo()).isEqualTo("SORT-B");
            assertThat(first.totals().get("payableAmount")).isEqualByComparingTo("230");
            assertThat(first.totals().get("discountAmount")).isEqualByComparingTo("70");
            assertThat(store.orders(tenant, 1, 1, criteria).items().getFirst().orderNo()).isEqualTo("SORT-A");
            assertThat(store.orders(tenant, 0, 1, orderDiscountCriteria(true, sort, "asc")).items().getFirst().orderNo()).isEqualTo("SORT-A");
        }
        var noDiscount = store.orders(tenant, 0, 20, orderDiscountCriteria(false, "discountAmount", "desc"));
        assertThat(noDiscount.total()).isEqualTo(1);
        assertThat(noDiscount.items().getFirst().orderNo()).isEqualTo("SORT-C");
        assertThat(noDiscount.totals().get("discountAmount")).isEqualByComparingTo("0");
        assertThat(store.orders(tenant, 0, 20, orderDiscountCriteria(null, null, null)).total()).isEqualTo(3);
    }

    private OrderCriteria orderDiscountCriteria(Boolean hasDiscount, String sort, String direction) {
        return new OrderCriteria(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, hasDiscount, sort, direction, null);
    }

    @Test
    void orderAmountsUseLinePriceTimesQuantityAndWeightedDiscountAcrossAllFilteredOrders() {
        String tenant = UUID.randomUUID().toString();
        long first = order(tenant, "METRIC-1", 1L, "C1", "客户", "HZ", "E1", "张三", "2026-09-11T05:00:00Z", 90, 0);
        long second = order(tenant, "METRIC-2", 1L, "C1", "客户", "HZ", "E1", "张三", "2026-09-11T05:00:00Z", 240, 0);
        insertLine(tenant, first, 1, "商品一", "60");
        insertLine(tenant, first, 2, "商品二", "40");
        insertLine(tenant, second, 1, "商品三", "300");
        jdbc.update("UPDATE order_sales_order_line SET unit_price=12.5,quantity=3.2 WHERE tenant_id=? AND order_id=? AND line_no=2", tenant, first);
        // 故意让来源头金额、明细金额与单价乘数量不一致，确保使用指定公式。
        jdbc.update("UPDATE order_sales_order SET original_amount=999,paid_amount=20,unpaid_amount=payable_amount-20 WHERE tenant_id=?", tenant);
        jdbc.update("UPDATE order_sales_order_line SET line_amount=1 WHERE tenant_id=?", tenant);
        insertPayment(tenant, "METRIC-P1", first, "2026-09-12T05:00:00Z", 12, "CHECKED", null);
        insertPayment(tenant, "METRIC-P2", first, "2026-09-12T05:00:00Z", 8, "CHECKED", null);
        insertPayment(tenant, "METRIC-P3", first, "2026-09-12T05:00:00Z", 99, "CANCELLED", null);
        var criteria = new OrderCriteria(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var page = store.orders(tenant, 0, 1, criteria);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.totals().get("originalAmount")).isEqualByComparingTo("400");
        assertThat(page.totals().get("payableAmount")).isEqualByComparingTo("330");
        assertThat(page.totals().get("discountAmount")).isEqualByComparingTo("70");
        assertThat(page.totals().get("discountRate")).isEqualByComparingTo("0.175");
        assertThat(page.totals().get("paidAmount")).isEqualByComparingTo("40");
        assertThat(page.totals().get("unpaidAmount")).isEqualByComparingTo("290");
        assertThat(page.totals().get("checkedAmount")).isEqualByComparingTo("20");
        var filtered = store.orders(tenant, 0, 20, new OrderCriteria("METRIC-1", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertThat(filtered.totals().get("originalAmount")).isEqualByComparingTo("100");
        assertThat(filtered.totals().get("discountRate")).isEqualByComparingTo("0.1");
        var item = filtered.items().getFirst();
        assertThat(item.discountAmount()).isEqualByComparingTo("10");
        var json = tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(item);
        assertThat(json.get("discountRate").asDouble()).isEqualTo(0.1);
        // 缺少明细后不能把不完整的合计当成准确订货金额。
        jdbc.update("UPDATE order_sales_order_line SET deleted=1 WHERE tenant_id=? AND order_id=?", tenant, first);
        var missing = store.orders(tenant, 0, 20, criteria);
        assertThat(missing.totals().get("missingLineOrderCount")).isEqualByComparingTo("1");
        assertThat(missing.totals()).doesNotContainKeys("originalAmount", "discountAmount", "discountRate");
        assertThat(missing.items().stream().filter(row -> row.id().equals(first)).findFirst().orElseThrow().originalAmount()).isNull();
        jdbc.update("UPDATE order_sales_order_line SET unit_price=0 WHERE tenant_id=? AND order_id=?", tenant, second);
        var zero = store.orders(tenant, 0, 20, new OrderCriteria("METRIC-2", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertThat(zero.totals().get("originalAmount")).isEqualByComparingTo("0");
        assertThat(zero.totals()).doesNotContainKey("discountRate");
        assertThat(zero.items().getFirst().discountRate()).isNull();
        assertThat(zero.items().getFirst().discountAmount()).isEqualByComparingTo("-240");
    }

    @Test
    void orderAndLineStatisticsAgreeAndDeduplicateEntitiesInsteadOfAmountsOrNames() {
        String tenant = UUID.randomUUID().toString();
        var criteria = new OrderCriteria(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        for (int i = 0; i < 3; i++) {
            long id = order(tenant, "SAME-" + i, i == 2 ? 2L : 1L, "C" + i, "同名客户", "HZ", "E1", "张三", "2026-09-11T05:00:00Z", 80, 0);
            insertLine(tenant, id, 1, "商品A", "60");
            insertLine(tenant, id, 2, "商品B", "40");
        }
        jdbc.update("UPDATE order_sales_order SET paid_amount=30,unpaid_amount=50 WHERE tenant_id=?", tenant);
        var orders = store.orders(tenant, 0, 1, criteria).totals();
        var lines = lineStats(tenant, null).totals();
        assertThat(orders.get("customerCount")).isEqualByComparingTo("2");
        assertThat(lines.get("productCount")).isEqualByComparingTo("1");
        assertThat(lines.get("lineAmount")).isEqualByComparingTo("300");
        assertThat(lines.get("orderAmount")).isEqualByComparingTo("240");
        assertThat(lines.get("discountAmount")).isEqualByComparingTo("60");
        assertThat(lines.get("discountRate")).isEqualByComparingTo("0.2");
        assertThat(lines.get("receivedAmount")).isEqualByComparingTo("90");
        assertThat(lines.get("unpaidAmount")).isEqualByComparingTo("150");
        for (String key : List.of("originalAmount", "payableAmount", "paidAmount", "unpaidAmount", "discountAmount", "discountRate", "customerCount")) {
            assertThat(lines.get(key)).as(key).isEqualByComparingTo(orders.get(key));
        }
        var product = lineStats(tenant, "商品A").totals();
        assertThat(product.get("lineAmount")).isEqualByComparingTo("180");
        assertThat(product.get("orderAmount")).isEqualByComparingTo("144");
        assertThat(product.get("discountAmount")).isEqualByComparingTo("36");
        assertThat(product.get("receivedAmount")).isEqualByComparingTo("54");
        order(tenant, "NO-LINES", 3L, "C3", "未补明细", "HZ", "E1", "张三", "2026-09-11T05:00:00Z", 10, 0);
        var incomplete = lineStats(tenant, null).totals();
        assertThat(incomplete.get("orderAmount")).isEqualByComparingTo(store.orders(tenant, 0, 1, criteria).totals().get("payableAmount"));
        assertThat(incomplete).doesNotContainKeys("lineAmount", "discountAmount", "discountRate");
        assertThat(lineStats(tenant, "不存在").totals().get("orderAmount")).isEqualByComparingTo("0");
    }

    @Test
    void productAllocationMatchesTwentyEightyExampleAndPreservesEveryCent() {
        String tenant = UUID.randomUUID().toString();
        long id = order(tenant, "DISCOUNT", 1L, "C1", "客户", "HZ", "E1", "张三", "2026-09-11T05:00:00Z", 80, 0);
        insertLine(tenant, id, 1, "方便面", "20");
        insertLine(tenant, id, 2, "台呢", "80");
        jdbc.update("UPDATE order_sales_order SET paid_amount=40,unpaid_amount=40 WHERE tenant_id=?", tenant);
        var noodles = lineStats(tenant, "方便面").totals();
        assertThat(noodles.get("lineAmount")).isEqualByComparingTo("20");
        assertThat(noodles.get("orderAmount")).isEqualByComparingTo("16");
        assertThat(noodles.get("discountAmount")).isEqualByComparingTo("4");
        assertThat(noodles.get("discountRate")).isEqualByComparingTo("0.2");
        assertThat(noodles.get("receivedAmount")).isEqualByComparingTo("8");
        assertThat(noodles.get("unpaidAmount")).isEqualByComparingTo("8");
        var cloth = lineStats(tenant, "台呢").totals();
        var all = lineStats(tenant, null).totals();
        for (String key : List.of("lineAmount", "orderAmount", "discountAmount", "receivedAmount", "unpaidAmount")) {
            assertThat(noodles.get(key).add(cloth.get(key))).as(key).isEqualByComparingTo(all.get(key));
        }
        String roundingTenant = UUID.randomUUID().toString();
        long rounded = order(roundingTenant, "ROUND", 1L, "C1", "客户", "HZ", "E1", "张三", "2026-09-11T05:00:00Z", 1, 0);
        for (int i = 1; i <= 3; i++) insertLine(roundingTenant, rounded, i, "商品" + i, "1");
        jdbc.update("UPDATE order_sales_order SET paid_amount=0.01,unpaid_amount=0.99 WHERE tenant_id=?", roundingTenant);
        BigDecimal payable = BigDecimal.ZERO, paid = BigDecimal.ZERO, unpaid = BigDecimal.ZERO;
        for (int i = 1; i <= 3; i++) {
            var part = lineStats(roundingTenant, "商品" + i).totals();
            payable = payable.add(part.get("orderAmount"));
            paid = paid.add(part.get("receivedAmount"));
            unpaid = unpaid.add(part.get("unpaidAmount"));
        }
        assertThat(payable).isEqualByComparingTo("1");
        assertThat(paid).isEqualByComparingTo("0.01");
        assertThat(unpaid).isEqualByComparingTo("0.99");
        jdbc.update("UPDATE order_sales_order_line SET unit_price=0 WHERE tenant_id=?", roundingTenant);
        var unknown = lineStats(roundingTenant, "商品1").totals();
        assertThat(unknown.get("unallocatableOrderCount")).isEqualByComparingTo("1");
        assertThat(unknown).doesNotContainKeys("orderAmount", "discountAmount", "discountRate", "receivedAmount", "unpaidAmount");
    }

    @Test
    void discountFiltersAndGlobalSortUseAllocatedLineAmountsBeforePagination() {
        String tenant = UUID.randomUUID().toString();
        long a = order(tenant, "DIS-A", 1L, "C1", "客户", "HZ", "EMP1", "张三", "2026-09-11T05:00:00Z", 80, 0);
        insertLine(tenant, a, 1, "方便面", "20");
        insertLine(tenant, a, 2, "台呢", "80");
        long b = order(tenant, "DIS-B", 1L, "C1", "客户", "HZ", "EMP1", "张三", "2026-09-12T05:00:00Z", 90, 0);
        insertLine(tenant, b, 1, "台呢", "100");
        long c = order(tenant, "DIS-C", 1L, "C1", "客户", "HZ", "EMP1", "张三", "2026-09-13T05:00:00Z", 100, 0);
        insertLine(tenant, c, 1, "台呢", "100");
        var discounted = discountCriteria(null, true, "discountAmount", "desc");
        var first = store.lines(tenant, 0, 1, discounted);
        var second = store.lines(tenant, 1, 1, discounted);
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.items().getFirst().orderNo()).isEqualTo("DIS-A");
        assertThat(first.items().getFirst().discountAmount()).isEqualByComparingTo("16");
        assertThat(first.items().getFirst().orderAmount()).isEqualByComparingTo("64");
        assertThat(first.items().getFirst().discountRate()).isEqualByComparingTo("0.2");
        assertThat(second.items().getFirst().discountAmount()).isEqualByComparingTo("10");
        assertThat(first.totals().get("discountAmount")).isEqualByComparingTo("30");
        assertThat(first.totals().get("orderAmount")).isEqualByComparingTo("170");
        assertThat(first.totals()).isEqualTo(second.totals());
        var cloth = store.lines(tenant, 0, 20, discountCriteria("台呢", true, "discountRate", "asc"));
        assertThat(cloth.items()).extracting(OrderRegisterLineView::orderNo).containsExactly("DIS-B", "DIS-A");
        assertThat(cloth.totals().get("discountAmount")).isEqualByComparingTo("26");
        assertThat(cloth.totals().get("orderAmount")).isEqualByComparingTo("154");
        assertThat(cloth.totals().get("quantitySum")).isEqualByComparingTo("2");
        var fullPrice = store.lines(tenant, 0, 20, discountCriteria(null, false, "discountRate", "desc"));
        assertThat(fullPrice.items()).extracting(OrderRegisterLineView::orderNo).containsExactly("DIS-C");
        assertThat(fullPrice.totals().get("discountAmount")).isEqualByComparingTo("0");
        assertThat(store.lines(tenant, 0, 20, discountCriteria(null, null, "discountAmount", "asc")).items())
                .extracting(OrderRegisterLineView::discountAmount)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("0"), new BigDecimal("4"), new BigDecimal("10"), new BigDecimal("16"));
    }

    @Test
    void zeroGrossHasNoInventedDiscountAndRoundingRemainsStableUnderDiscountFilter() {
        String tenant = UUID.randomUUID().toString();
        long a = order(tenant, "ROUND", 1L, "C1", "客户", "HZ", "EMP1", "张三", "2026-09-11T05:00:00Z", 2, 0);
        insertLine(tenant, a, 1, "一", "1"); insertLine(tenant, a, 2, "二", "1"); insertLine(tenant, a, 3, "三", "1");
        var result = store.lines(tenant, 0, 20, discountCriteria(null, true, "discountAmount", "desc"));
        assertThat(result.items().stream().map(OrderRegisterLineView::discountAmount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1");
        assertThat(result.totals().get("discountAmount")).isEqualByComparingTo("1");
        jdbc.update("UPDATE order_sales_order_line SET unit_price=0 WHERE tenant_id=?", tenant);
        var unknown = store.lines(tenant, 0, 20, discountCriteria(null, null, "discountRate", "desc"));
        assertThat(unknown.items()).allSatisfy(row -> {
            assertThat(row.orderAmount()).isNull(); assertThat(row.discountAmount()).isNull(); assertThat(row.discountRate()).isNull();
        });
        assertThat(store.lines(tenant, 0, 20, discountCriteria(null, true, null, null)).total()).isZero();
        assertThat(store.lines(tenant, 0, 20, discountCriteria(null, false, null, null)).total()).isZero();
    }

    private static LineCriteria discountCriteria(String product, Boolean discounted, String sort, String direction) {
        return new LineCriteria(null, null, null, null, null, null, null, null, null, null, product, null, null, null, discounted, sort, direction);
    }

    @Test
    void linePaymentStatusMatchesOrderAndFiltersRowsAndTotals() {
        String tenant = UUID.randomUUID().toString();
        for (String status : List.of("UNPAID", "PARTIAL_PAID", "PAID")) {
            long id = order(tenant, "SO-STATUS-" + status, 1L, "C-1", "状态客户", "HZ", "EMP-1", "张三",
                    "2026-09-11T05:00:00Z", 100, 0);
            jdbc.update("UPDATE order_sales_order SET payment_status_code=? WHERE tenant_id=? AND id=?", status, tenant, id);
            insertLine(tenant, id, 1, "商品一", "60");
            insertLine(tenant, id, 2, "商品二", "40");
        }
        for (String status : List.of("UNPAID", "PARTIAL_PAID", "PAID")) {
            var page = store.lines(tenant, 0, 50, new LineCriteria(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, status, null, null, null));
            assertThat(page.total()).isEqualTo(2);
            assertThat(page.items()).extracting(OrderRegisterLineView::paymentStatusCode).containsOnly(status);
            assertThat(page.items()).extracting(OrderRegisterLineView::orderNo).containsOnly("SO-STATUS-" + status);
            assertThat(page.totals().get("lineAmount")).isEqualByComparingTo("100");
            assertThat(page.totals().get("orderAmount")).isEqualByComparingTo("100");
        }
        assertThat(lineStats(tenant, null).total()).isEqualTo(6);
    }

    private static OrderRegisterPage<OrderRegisterLineView> lineStats(
            String tenant, String productKeyword) {
        return store.lines(
                tenant,
                0,
                50,
                new LineCriteria(
                        null, null, null, null, null, null, null, null, null, null,
                        productKeyword, null, null, null, null, null, null));
    }

    private static Map<String, BigDecimal> lineTotals(String tenant, String productKeyword) {
        return store.lines(
                        tenant,
                        0,
                        50,
                        new LineCriteria(
                                null, null, null, null, null, null, null, null, null, null,
                                productKeyword, null, null, null, null, null, null))
                .totals();
    }

    private static void insertLine(String tenant, long orderId, int lineNo, String productName, String amount) {
        jdbc.update(
                "INSERT INTO order_sales_order_line(tenant_id,order_id,line_no,product_id,product_variant_id,"
                        + " product_code_snapshot,sku_code_snapshot,product_name_snapshot,unit_code,quantity,"
                        + " unit_price,discount_amount,line_amount,revision,created_by,created_time,updated_by,"
                        + " updated_time,deleted) VALUES(?,?,?,1,1,?,?,?,'SET',1,?,0,?,1,"
                        + " 'SYSTEM',UTC_TIMESTAMP(6),'SYSTEM',UTC_TIMESTAMP(6),0)",
                tenant,
                orderId,
                lineNo,
                "P-" + lineNo,
                "SKU-" + lineNo,
                productName,
                amount,
                amount);
    }

    @Test
    void orderNoAndCustomerNameSupportContainsMatch() {
        String tenant = UUID.randomUUID().toString();
        order(tenant, "SO-FUZZY-830871", 1L, "C-1", "极客台球俱乐部", "HZ", "EMP-1", "张三",
                "2026-09-11T05:00:00Z", 10, 0);
        order(tenant, "SO-FUZZY-830872", 1L, "C-1", "其他门店", "HZ", "EMP-1", "张三",
                "2026-09-11T06:00:00Z", 20, 0);
        long orderId = orderId(tenant, "SO-FUZZY-830871");
        insertPayment(tenant, "PAY-FUZZY-1", orderId, "2026-09-12T05:00:00Z", 10, "RECEIVED", null);

        // 订单列表：订单号取中段、客户名称取中段都能命中
        var orderPage =
                store.orders(
                        tenant,
                        0,
                        50,
                        new OrderCriteria(
                                "83087", null, "台球", null, null, null, null, null, null, null, null,
                                null, null, null, null, null, null, null, null));
        assertThat(orderPage.items()).extracting(view -> view.orderNo())
                .containsExactly("SO-FUZZY-830871");

        // 明细列表
        jdbc.update(
                "INSERT INTO order_sales_order_line(tenant_id,order_id,line_no,product_id,product_variant_id,"
                        + " product_code_snapshot,sku_code_snapshot,product_name_snapshot,unit_code,quantity,"
                        + " unit_price,discount_amount,line_amount,revision,created_by,created_time,updated_by,"
                        + " updated_time,deleted) VALUES(?,?,1,1,1,'P-1','SKU-1','测试商品','BOX',1,10,0,10,1,"
                        + " 'SYSTEM',UTC_TIMESTAMP(6),'SYSTEM',UTC_TIMESTAMP(6),0)",
                tenant,
                orderId);
        jdbc.update("UPDATE order_sales_order_line SET source_line_id='DHB-LINE-123' WHERE tenant_id=? AND order_id=?", tenant, orderId);
        jdbc.update("UPDATE order_payment_record SET source_record_id=NULL,source_document_no='FR.20260912.001' WHERE tenant_id=? AND payment_no='PAY-FUZZY-1'", tenant);
        var linePage =
                store.lines(
                        tenant,
                        0,
                        50,
                        new LineCriteria(
                                "83087", null, "台球", null, null, null, null, null, null, null, null,
                                null, null, null, null, null, null));
        assertThat(linePage.items()).hasSize(1);
        assertThat(linePage.items().getFirst().sourceLineId()).isEqualTo("DHB-LINE-123");
        assertThat(linePage.items().getFirst().orderNo()).isEqualTo("SO-FUZZY-830871");

        // 回款列表
        var paymentPage =
                store.payments(
                        tenant,
                        0,
                        50,
                        new PaymentCriteria(
                                "83087", null, "台球", null, null, null, null, null, null, null, null,
                                null, null, null, null, null, null, null));
        assertThat(paymentPage.items()).hasSize(1);
        assertThat(paymentPage.items().getFirst().sourceRecordId()).isEqualTo("FR.20260912.001");
        assertThat(paymentPage.items().getFirst().orderNo()).isEqualTo("SO-FUZZY-830871");
    }

    @Test
    void paymentTotalsDeduplicateMatchingOrdersAndKeepFullLedgerBalance() {
        String tenant = UUID.randomUUID().toString();
        long first = order(tenant, "PAY-METRIC-1", 1L, "C1", "客户", "HZ", "E1", "张三", "2026-09-11T05:00:00Z", 100, 0);
        order(tenant, "PAY-METRIC-OTHER", 2L, "C2", "另一客户", "HZ", "E1", "张三", "2026-09-11T05:00:00Z", 999, 0);
        insertPayment(tenant, "MATCH-1", first, "2026-09-12T05:00:00Z", 20, "CHECKED", null);
        insertPayment(tenant, "MATCH-2", first, "2026-09-12T06:00:00Z", 30, "RECEIVED", null);
        insertPayment(tenant, "OLDER", first, "2026-09-11T05:00:00Z", 10, "RECEIVED", null);
        insertPayment(tenant, "MATCH-CANCEL", first, "2026-09-12T07:00:00Z", 88, "CANCELLED", null);
        jdbc.update("UPDATE order_sales_order SET paid_amount=60,unpaid_amount=40 WHERE tenant_id=? AND id=?", tenant, first);
        var page = store.payments(tenant, 0, 1, new PaymentCriteria(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, Instant.parse("2026-09-12T00:00:00Z"), Instant.parse("2026-09-13T00:00:00Z"), null, null, null));
        assertThat(page.items()).hasSize(1);
        assertThat(page.totals().get("relatedOrderAmount")).isEqualByComparingTo("100");
        assertThat(page.totals().get("receivedAmount")).isEqualByComparingTo("50");
        assertThat(page.totals().get("checkedAmount")).isEqualByComparingTo("20");
        assertThat(page.totals().get("unpaidAmount")).isEqualByComparingTo("40");
        assertThat(page.totals().get("customerCount")).isEqualByComparingTo("1");
    }

    private static long orderId(String tenant, String orderNo) {
        Long id =
                jdbc.queryForObject(
                        "SELECT id FROM order_sales_order WHERE tenant_id=? AND order_no=?",
                        Long.class,
                        tenant,
                        orderNo);
        return id == null ? 0L : id;
    }

    private static List<String> orderNumbers(String tenant, Boolean dhbLinked) {
        return store.orders(
                        tenant,
                        0,
                        50,
                        new OrderCriteria(
                                null, null, null, null, null, null, null, null, null, null, null, null,
                                null, dhbLinked, null, null, null, null, null))
                .items()
                .stream()
                .map(view -> view.orderNo())
                .toList();
    }

    private static List<String> orderNumbersWithInvoice(String tenant, String invoiceStatusCode) {
        return store.orders(
                        tenant,
                        0,
                        50,
                        new OrderCriteria(
                                null, null, null, null, null, null, null, null, null, null, null, null,
                                invoiceStatusCode, null, null, null, null, null, null))
                .items()
                .stream()
                .map(view -> view.orderNo())
                .toList();
    }

    private static void invoice(String tenant, long orderId, String orderNo, String status) {
        jdbc.update(
                "INSERT INTO order_invoice(tenant_id,sales_order_id,order_no,status,title_type,title,"
                        + " invoice_type,amount,deleted,created_at,updated_at)"
                        + " VALUES(?,?,?,?,'COMPANY','测试抬头','NORMAL',10,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                tenant,
                orderId,
                orderNo,
                status);
    }

    private static PeriodCriteria period(
            String groupBy, String region, String employee, String customerName, String customerCode) {
        return new PeriodCriteria(
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-17"),
                groupBy,
                region,
                employee,
                null,
                null,
                customerName,
                customerCode);
    }

    private static void fixture() {
        long order1 = order("SO-1", 1L, "C-1", "杭州甲客户", "HZ", "EMP-1", "张三", "2026-08-20T02:00:00Z", 100, 0);
        long order2 = order("SO-2", 1L, "C-1", "杭州甲客户", "HZ", "EMP-1", "张三", "2026-09-10T02:00:00Z", 200, 0);
        long order3 = order("SO-3", 2L, "C-2", "北京丙客户", "BJ", "EMP-2", "赵六", "2026-09-12T02:00:00Z", 50, 0);
        long order4 = order("SO-4", 3L, "C-3", "杭州乙客户", null, "EMP-2", null, "2026-09-05T02:00:00Z", 30, 0);
        order("SO-5", 4L, "C-4", "已删除客户", "HZ", "EMP-1", "张三", "2026-09-06T02:00:00Z", 1000, 1);
        long order6 = order("SO-6", 3L, "C-3", "杭州乙客户", "BJ", "EMP-2", "赵六", "2026-09-06T02:00:00Z", 60, 0);
        long order7 = order("SO-7", 5L, "C-5", "超额客户", "HZ", "EMP-1", "张三", "2026-08-01T02:00:00Z", 10, 0);
        jdbc.update(
                "UPDATE order_sales_order SET created_by='SYSTEM' WHERE tenant_id=? AND order_no='SO-2'",
                TENANT);
        jdbc.update(
                "UPDATE order_sales_order SET source_creator_name='   ',created_by=' 王五 ' WHERE tenant_id=? AND order_no='SO-4'",
                TENANT);
        // 快照优先：SO-6 自身写 BJ/EMP-2，冻结快照把它归到 HZ/EMP-1。
        jdbc.update(
                "INSERT INTO order_attribution_snapshot(tenant_id,order_id,state,employee_code,employee_name,"
                        + " department_id,department_name,department_path,region_code,region_path,source_version,"
                        + " customer_revision,employee_revision,organization_version,resolved_at,frozen_at,revision)"
                        + " VALUES(?,?,'FROZEN','EMP-1','张三',11,'杭州一部','[]','HZ','[]','v1',1,1,1,"
                        + " UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1)",
                TENANT,
                order6);
        payment("PM-1", order1, "2026-09-08T02:00:00Z", 40, "RECEIVED");
        payment("PM-2", order2, "2026-09-11T02:00:00Z", 150, "RECEIVED");
        payment("PM-3", order1, "2026-09-16T02:00:00Z", 10, "PENDING");
        payment("PM-4", order2, "2026-08-01T02:00:00Z", 25, "RECEIVED");
        payment("PM-5", order2, "2026-09-20T02:00:00Z", 5, "RECEIVED");
        payment("PM-6", order7, "2026-09-10T02:00:00Z", 15, "RECEIVED");
        refund("RF-1", order2, "2026-09-12T02:00:00Z", 20, "CONFIRMED");
        refund("RF-2", order1, "2026-09-13T02:00:00Z", 7, "PENDING");

        long covered = order(TENANT_COVERED, "SO-C1", 1L, "C-1", "历史客户", "HZ", "EMP-1", "张三", "2026-08-10T02:00:00Z", 20, 0);
        jdbc.update(
                "INSERT INTO order_financial_event(tenant_id,order_id,source_key,event_type,receivable_delta,"
                        + " received_delta,effective_at,recorded_at,revision) VALUES(?,?,'EV-1','ORDER',20,0,"
                        + " UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1)",
                TENANT_COVERED,
                covered);
    }

    private static long order(
            String no,
            long customerId,
            String customerCode,
            String customerName,
            String regionCode,
            String employeeCode,
            String creator,
            String orderDate,
            int payable,
            int deleted) {
        return order(TENANT, no, customerId, customerCode, customerName, regionCode, employeeCode, creator, orderDate, payable, deleted);
    }

    private static long order(
            String tenant,
            String no,
            long customerId,
            String customerCode,
            String customerName,
            String regionCode,
            String employeeCode,
            String creator,
            String orderDate,
            int payable,
            int deleted) {
        jdbc.update(
                "INSERT INTO order_sales_order(tenant_id,order_no,customer_id,customer_code_snapshot,"
                        + " customer_name_snapshot,region_code,owner_employee_code,owner_employee_name_snapshot,"
                        + " order_date,payable_amount,source_creator_name,created_by,deleted)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,'双人核对',?)",
                tenant,
                no,
                customerId,
                customerCode,
                customerName,
                regionCode,
                employeeCode,
                employeeCode == null ? null : (employeeCode.equals("EMP-1") ? "张三" : "赵六"),
                Timestamp.from(Instant.parse(orderDate)),
                new BigDecimal(payable),
                creator,
                deleted);
        return jdbc.queryForObject(
                "SELECT id FROM order_sales_order WHERE tenant_id=? AND order_no=?",
                Long.class,
                tenant,
                no);
    }

    @Test
    void checkPaymentWritesTransactionNoAndRejectsDuplicateOrCancelled() {
        // 独立租户：核对用例自带数据，避免影响其它断言共享租户的合计口径。
        String tenant = UUID.randomUUID().toString();
        long orderId =
                order(tenant, "SO-PAY-CHK-1", 1L, "C-1", "核对客户", "HZ", "EMP-1", "张三",
                        "2026-09-11T05:00:00Z", 100, 0);
        insertPayment(tenant, "PAY-CHK-1", orderId, "2026-09-12T05:00:00Z", 60, "RECEIVED", null);
        insertPayment(tenant, "PAY-CHK-2", orderId, "2026-09-12T06:00:00Z", 40, "RECEIVED", "TXN-USED");
        insertPayment(tenant, "PAY-CHK-3", orderId, "2026-09-12T07:00:00Z", 10, "CANCELLED", null);
        long checkedPayment = paymentId(tenant, "PAY-CHK-1");
        long pendingPayment = paymentId(tenant, "PAY-CHK-2");
        long cancelledPayment = paymentId(tenant, "PAY-CHK-3");

        var checked =
                store.checkPayment(
                        tenant, checkedPayment, "TXN-NEW-001",
                        paymentRevision(tenant, checkedPayment), "finance-1",
                        Instant.parse("2026-09-21T03:00:00Z"));
        assertThat(checked.paymentStatusCode()).isEqualTo("CHECKED");
        assertThat(checked.transactionNo()).isEqualTo("TXN-NEW-001");
        assertThat(checked.checkedBy()).isEqualTo("finance-1");
        assertThat(checked.checkedAt()).isEqualTo(Instant.parse("2026-09-21T03:00:00Z"));

        // 已核对：不可重复核对
        assertThatThrownBy(
                        () ->
                                store.checkPayment(
                                        tenant, checkedPayment, "TXN-NEW-002",
                                        paymentRevision(tenant, checkedPayment), "finance-1",
                                        Instant.parse("2026-09-21T04:00:00Z")))
                .hasMessageContaining("已核对");
        // 付款凭证验重：流水号已被其他回款单占用
        assertThatThrownBy(
                        () ->
                                store.checkPayment(
                                        tenant, pendingPayment, "TXN-NEW-001",
                                        paymentRevision(tenant, pendingPayment), "finance-1",
                                        Instant.parse("2026-09-21T04:00:00Z")))
                .hasMessageContaining("交易单号已被其他回款单使用");
        // 已取消：不可核对
        assertThatThrownBy(
                        () ->
                                store.checkPayment(
                                        tenant, cancelledPayment, "TXN-NEW-003",
                                        paymentRevision(tenant, cancelledPayment), "finance-1",
                                        Instant.parse("2026-09-21T04:00:00Z")))
                .hasMessageContaining("已取消");
    }

    @Test
    void checkPaymentRejectsStaleRevisionAndDuplicateTransactionConcurrently() {
        String tenant = UUID.randomUUID().toString();
        long orderId =
                order(tenant, "SO-PAY-RACE-1", 1L, "C-1", "并发客户", "HZ", "EMP-1", "张三",
                        "2026-09-11T05:00:00Z", 300, 0);
        insertPayment(tenant, "PAY-RACE-1", orderId, "2026-09-12T05:00:00Z", 100, "RECEIVED", null);
        insertPayment(tenant, "PAY-RACE-2", orderId, "2026-09-12T06:00:00Z", 100, "RECEIVED", null);
        insertPayment(tenant, "PAY-RACE-3", orderId, "2026-09-12T07:00:00Z", 100, "RECEIVED", null);
        long first = paymentId(tenant, "PAY-RACE-1");
        long second = paymentId(tenant, "PAY-RACE-2");
        long third = paymentId(tenant, "PAY-RACE-3");
        int firstRevision = paymentRevision(tenant, first);

        store.checkPayment(tenant, first, "TXN-RACE-001", firstRevision, "finance-1",
                Instant.parse("2026-09-21T03:00:00Z"));

        // 已核对：重复核对被前置校验拦下，不会覆盖第一次结果
        assertThatThrownBy(
                        () ->
                                store.checkPayment(
                                        tenant, first, "TXN-RACE-002", firstRevision, "finance-2",
                                        Instant.parse("2026-09-21T03:01:00Z")))
                .hasMessageContaining("已核对");
        // 并发场景：两个人同时打开页面，后到的人带的是过期版本，原子更新必须失败
        assertThatThrownBy(
                        () ->
                                store.checkPayment(
                                        tenant, third, "TXN-RACE-003",
                                        paymentRevision(tenant, third) + 5, "finance-2",
                                        Instant.parse("2026-09-21T03:02:00Z")))
                .hasMessageContaining("回款状态已变化");
        // 流水号唯一约束兜底：并发写入同一交易单号时数据库直接拒绝
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE order_payment_record SET transaction_no='TXN-RACE-001'"
                                                + " WHERE tenant_id=? AND id=?",
                                        tenant,
                                        second))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject(
                                "SELECT COUNT(*) FROM order_payment_record"
                                        + " WHERE tenant_id=? AND transaction_no='TXN-RACE-001' AND deleted=0",
                                Long.class,
                                tenant))
                .isEqualTo(1L);
    }

    @Test
    void orderAuditFieldsPreferSourceCreatorAndFallbackToSystem() {
        String tenant = UUID.randomUUID().toString();
        order(tenant, "SO-AUDIT-1", 1L, "C-1", "审计客户", "HZ", "EMP-1", "张三",
                "2026-09-11T05:00:00Z", 50, 0);
        jdbc.update(
                "UPDATE order_sales_order SET source_creator_name='刘鹏昆',"
                        + " source_created_at=?, source_modifier_name='张艺瀚', source_updated_at=?"
                        + " WHERE tenant_id=? AND order_no='SO-AUDIT-1'",
                Timestamp.from(Instant.parse("2026-09-10T02:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-11T02:00:00Z")),
                tenant);
        order(tenant, "SO-AUDIT-2", 1L, "C-1", "审计客户", "HZ", "EMP-1", "张三",
                "2026-09-11T06:00:00Z", 60, 0);
        // 显式清空来源字段：验证无来源时回退本系统记录人（created_by）。
        jdbc.update(
                "UPDATE order_sales_order SET source_creator_name=NULL, source_created_at=NULL"
                        + " WHERE tenant_id=? AND order_no='SO-AUDIT-2'",
                tenant);

        var page =
                store.orders(
                        tenant, 0, 10,
                        new OrderCriteria(null, null, "审计客户", null, null, null, null,
                                null, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(page.items()).hasSize(2);
        var withSource =
                page.items().stream()
                        .filter(view -> "SO-AUDIT-1".equals(view.orderNo()))
                        .findFirst()
                        .orElseThrow();
        assertThat(withSource.createdBy()).isEqualTo("刘鹏昆");
        assertThat(withSource.createdTime()).isEqualTo(Instant.parse("2026-09-10T02:00:00Z"));
        assertThat(withSource.updatedBy()).isEqualTo("张艺瀚");
        assertThat(withSource.updatedTime()).isEqualTo(Instant.parse("2026-09-11T02:00:00Z"));
        // 无来源的订单回退为本系统记录人（同步落库账号），不显示为空。
        var withoutSource =
                page.items().stream()
                        .filter(view -> "SO-AUDIT-2".equals(view.orderNo()))
                        .findFirst()
                        .orElseThrow();
        assertThat(withoutSource.createdBy()).isEqualTo("双人核对");
    }

    @Test
    void lineRowsExposeSourceAuditAndFallbackToSystemRecorder() {
        // 明细 SQL 必须同时取来源审计与系统记录列，否则行映射会因缺列直接失败。
        String tenant = UUID.randomUUID().toString();
        long withSource =
                order(tenant, "SO-LINE-1", 1L, "C-1", "明细客户", "HZ", "EMP-1", "张三",
                        "2026-09-11T05:00:00Z", 100, 0);
        long withoutSource =
                order(tenant, "SO-LINE-2", 1L, "C-1", "明细客户", "HZ", "EMP-1", "张三",
                        "2026-09-12T05:00:00Z", 50, 0);
        jdbc.update(
                "UPDATE order_sales_order SET source_creator_name=NULL,source_created_at=NULL,"
                        + "updated_by='李四' WHERE tenant_id=? AND order_no='SO-LINE-2'",
                tenant);
        insertLine(tenant, withSource, "P-LINE-1");
        insertLine(tenant, withoutSource, "P-LINE-2");

        var page =
                store.lines(
                        tenant, 0, 10,
                        new com.rigour.order.application.port.out.OrderRegisterStore.LineCriteria(
                                null, null, null, null, null, null, null, null, null, null, null,
                                null, null, null, null, null, null));

        assertThat(page.items()).hasSize(2);
        var source =
                page.items().stream()
                        .filter(view -> "SO-LINE-1".equals(view.orderNo()))
                        .findFirst()
                        .orElseThrow();
        assertThat(source.createdBy()).isEqualTo("张三");
        var fallback =
                page.items().stream()
                        .filter(view -> "SO-LINE-2".equals(view.orderNo()))
                        .findFirst()
                        .orElseThrow();
        assertThat(fallback.createdBy()).isEqualTo("双人核对");
        assertThat(fallback.updatedBy()).isEqualTo("李四");
        assertThat(page.totals().get("lineAmount")).isEqualByComparingTo("40");
    }

    private static void insertLine(String tenant, long orderId, String productCode) {
        jdbc.update(
                "INSERT INTO order_sales_order_line(tenant_id,order_id,line_no,product_id,"
                        + " product_code_snapshot,product_name_snapshot,unit_code,quantity,unit_price,"
                        + " line_amount,revision,created_by,created_time,updated_by,updated_time,deleted)"
                        + " VALUES(?,?,1,1,?,?,'BOX',2,10,20,1,'双人核对',UTC_TIMESTAMP(6),"
                        + " '双人核对',UTC_TIMESTAMP(6),0)",
                tenant,
                orderId,
                productCode,
                "明细商品");
    }

    private static int paymentRevision(String tenant, long paymentId) {
        Integer revision =
                jdbc.queryForObject(
                        "SELECT revision FROM order_payment_record WHERE tenant_id=? AND id=?",
                        Integer.class,
                        tenant,
                        paymentId);
        return revision == null ? 0 : revision;
    }

    private static long paymentId(String tenant, String paymentNo) {
        Long id =
                jdbc.queryForObject(
                        "SELECT id FROM order_payment_record WHERE tenant_id=? AND payment_no=?",
                        Long.class,
                        tenant,
                        paymentNo);
        return id == null ? 0L : id;
    }

    private static void insertPayment(
            String tenant,
            String paymentNo,
            long orderId,
            String time,
            int amount,
            String status,
            String transactionNo) {
        jdbc.update(
                "INSERT INTO order_payment_record(tenant_id,payment_no,order_id,payment_time,paid_amount,"
                        + " payment_status_code,transaction_no) VALUES(?,?,?,?,?,?,?)",
                tenant,
                paymentNo,
                orderId,
                Timestamp.from(Instant.parse(time)),
                new BigDecimal(amount),
                status,
                transactionNo);
    }

    private static void payment(
            String no, long orderId, String time, int amount, String status) {
        jdbc.update(
                "INSERT INTO order_payment_record(tenant_id,payment_no,order_id,payment_time,paid_amount,"
                        + " payment_status_code) VALUES(?,?,?,?,?,?)",
                TENANT,
                no,
                orderId,
                Timestamp.from(Instant.parse(time)),
                new BigDecimal(amount),
                status);
    }

    private static void refund(
            String no, long orderId, String time, int amount, String status) {
        jdbc.update(
                "INSERT INTO order_refund_record(tenant_id,refund_no,order_id,refund_time,refund_amount,"
                        + " refund_status_code) VALUES(?,?,?,?,?,?)",
                TENANT,
                no,
                orderId,
                Timestamp.from(Instant.parse(time)),
                new BigDecimal(amount),
                status);
    }
}

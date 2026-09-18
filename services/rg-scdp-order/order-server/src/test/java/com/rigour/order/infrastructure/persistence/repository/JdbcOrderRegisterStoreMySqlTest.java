package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.order.api.v1.model.OrderRegisterModels.HistoryCoverage;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodRow;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodStatisticsView;
import com.rigour.order.api.v1.model.OrderRegisterModels.ReceivablesView;
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
        var ds =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
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

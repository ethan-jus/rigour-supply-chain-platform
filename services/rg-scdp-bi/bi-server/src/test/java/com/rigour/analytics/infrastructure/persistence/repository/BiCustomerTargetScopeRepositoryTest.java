package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;

/** 隔离 H2 执行实际 MyBatis 动态 SQL，验证客户转归属和跨城目标不穿透。 */
class BiCustomerTargetScopeRepositoryTest {
    // The selected August-September business dates are bound to SQL as UTC DATETIME.
    private static final LocalDateTime FROM = LocalDateTime.parse("2026-07-31T16:00:00");
    private static final LocalDateTime TO = LocalDateTime.parse("2026-09-30T15:59:59.999999");
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private Configuration configuration;

    @BeforeEach void setup() {
        source = new SingleConnectionDataSource("jdbc:h2:mem:customer_scope_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        jdbc = new JdbcTemplate(source);
        configuration = new Configuration();
        configuration.addMapper(SupplyDashboardQueryMapper.class);
        jdbc.execute("""
                CREATE TABLE bi_customer_dim (
                    tenant_id VARCHAR(64), customer_id BIGINT, customer_code VARCHAR(50), customer_name VARCHAR(100),
                    region_code VARCHAR(64), region_name VARCHAR(100), owner_staff_code VARCHAR(50), owner_staff_name VARCHAR(100),
                    customer_type_code VARCHAR(64) DEFAULT 'STORE', customer_type_name VARCHAR(100) DEFAULT '门店',
                    status_code VARCHAR(64) DEFAULT 'ACTIVE', has_contact INT DEFAULT 1, deleted INT DEFAULT 0,
                    UNIQUE(tenant_id,customer_id))
                """);
        jdbc.execute("""
                CREATE TABLE bi_sales_order_fact (
                    tenant_id VARCHAR(64), order_id BIGINT, customer_id BIGINT, region_code VARCHAR(64), owner_staff_code VARCHAR(50),
                    customer_type_code VARCHAR(64) DEFAULT 'STORE', source_system_code VARCHAR(32) DEFAULT 'FEISHU',
                    order_date DATETIME(6), order_status_code VARCHAR(64) DEFAULT 'COMPLETED', deleted INT DEFAULT 0,
                    payable_amount DECIMAL(24,6), paid_amount DECIMAL(24,6), unpaid_amount DECIMAL(24,6), UNIQUE(tenant_id,order_id))
                """);
        jdbc.execute("""
                CREATE TABLE bi_sales_payment_fact (
                    tenant_id VARCHAR(64), payment_id BIGINT, customer_id BIGINT, region_code VARCHAR(64),
                    owner_staff_code VARCHAR(50), collector_staff_code VARCHAR(50), source_system_code VARCHAR(32) DEFAULT 'FEISHU',
                    payment_time DATETIME(6), deleted INT DEFAULT 0, UNIQUE(tenant_id,payment_id))
                """);
        jdbc.execute("""
                CREATE TABLE bi_business_target (
                    tenant_id VARCHAR(64), dimension_type VARCHAR(32), dimension_code VARCHAR(64), dimension_name VARCHAR(100),
                    target_month DATE, metric_code VARCHAR(64), target_value DECIMAL(24,6), deleted INT DEFAULT 0)
                """);
        customer("T", 1, "BJ", "E1");
        customer("T", 2, "BJ", "E1");
        customer("OTHER", 1, "BJ", "E1");
        // 客户现属北京 E1，订单/回款仍保留发生时的真实城市和销售。
        order("T", 1, 1, "BJ", "E1", "2026-07-01T00:00:00", "10", "6");
        order("T", 2, 1, "BJ", "E1", "2026-08-01T00:00:00", "20", "12");
        order("T", 3, 1, "SH", "E2", "2026-09-29T00:00:00", "900", "600");
        order("T", 4, 1, "BJ", "E2", "2026-09-28T00:00:00", "80", "40");
        order("T", 5, 1, "SH", "E1", "2026-09-27T00:00:00", "50", "25");
        order("T", 6, 2, "SH", "E2", "2026-09-29T00:00:00", "40", "20");
        order("OTHER", 2, 1, "BJ", "E1", "2026-09-30T00:00:00", "9999", "9999");
        payment("T", 1, 1, "BJ", "E1", "2026-08-02T00:00:00");
        payment("T", 2, 1, "SH", "E2", "2026-09-29T00:00:00");
        payment("T", 3, 1, "BJ", "E2", "2026-09-28T00:00:00");
        payment("T", 4, 1, "SH", "E1", "2026-09-27T00:00:00");
        payment("T", 5, 2, "SH", "E2", "2026-09-29T00:00:00");
        payment("OTHER", 1, 1, "BJ", "E1", "2026-09-30T00:00:00");
    }
    @AfterEach void close() { source.destroy(); }

    @ParameterizedTest
    @ValueSource(strings = {"customerSegmentSummary", "customerActivityRanking", "customerChurnRiskRanking"})
    void selfDoesNotInheritOtherCityOrFormerOwnerOrdersPaymentsAndLastActivity(String method) {
        var rows = query(method, "BJ", "E1");
        assertThat(sum(rows, "salesAmount")).isEqualByComparingTo("20");
        assertThat(sum(rows, "paidAmount")).isEqualByComparingTo("12");
        assertThat(sum(rows, "unpaidAmount")).isEqualByComparingTo("8");
        if (!method.equals("customerSegmentSummary")) {
            assertThat(rows).hasSize(2);
            var first = byCode(rows, "customerCode", "C1");
            assertThat(number(first, "orderCount")).isEqualByComparingTo("1");
            assertThat(number(first, "paymentCount")).isEqualByComparingTo("1");
            assertThat(value(first, "lastOrderTime").toString()).startsWith("2026-08-01");
            assertThat(value(first, "lastPaymentTime").toString()).startsWith("2026-08-02");
            assertThat(number(first, "inactiveDays")).isEqualByComparingTo("60");
            assertThat(value(first, "churnRiskLevel")).isEqualTo("HIGH");
            var empty = byCode(rows, "customerCode", "C2");
            assertThat(number(empty, "salesAmount")).isZero();
            assertThat(value(empty, "lastOrderTime")).isNull();
            assertThat(value(empty, "lastPaymentTime")).isNull();
        }
    }
    @Test void cityScopeIncludesOtherOwnersInSameCityButNotSameOwnerInAnotherCity() {
        var rows = query("customerActivityRanking", "BJ", null);
        assertThat(sum(rows, "salesAmount")).isEqualByComparingTo("100");
        var first = byCode(rows, "customerCode", "C1");
        assertThat(number(first, "paymentCount")).isEqualByComparingTo("2");
        assertThat(value(first, "lastOrderTime").toString()).startsWith("2026-09-28");
        assertThat(query("customerChurnRiskRanking", "BJ", null)).singleElement()
                .satisfies(row -> assertThat(value(row, "customerCode")).isEqualTo("C2"));
        assertThat(sum(query("customerSegmentSummary", "BJ", null), "salesAmount")).isEqualByComparingTo("100");
    }

    @ParameterizedTest
    @ValueSource(strings = {"customerActivityRanking", "customerChurnRiskRanking"})
    void inactivityChangesOnlyAtShanghaiMidnightWithoutLeakingOtherOwners(String method) {
        var before = byCode(query(method, "BJ", "E1", TO), "customerCode", "C1");
        var after = byCode(query(method, "BJ", "E1", TO.plusNanos(1000)), "customerCode", "C1");
        assertThat(number(before, "inactiveDays")).isEqualByComparingTo("60");
        assertThat(number(after, "inactiveDays")).isEqualByComparingTo("61");
        assertThat(value(after, "lastOrderTime")).isEqualTo(value(before, "lastOrderTime"));
        assertThat(number(after, "salesAmount")).isEqualByComparingTo(number(before, "salesAmount"));
        assertThat(number(after, "paidAmount")).isEqualByComparingTo(number(before, "paidAmount"));
    }
    @Test void tenantAdminWithoutFiltersKeepsAllRealHistoricalAttribution() {
        var rows = query("customerActivityRanking", null, null);
        assertThat(sum(rows, "salesAmount")).isEqualByComparingTo("1090");
        assertThat(sum(rows, "paidAmount")).isEqualByComparingTo("697");
        assertThat(number(byCode(rows, "customerCode", "C1"), "paymentCount")).isEqualByComparingTo("4");
        assertThat(sum(query("customerSegmentSummary", null, null), "salesAmount")).isEqualByComparingTo("1090");
        assertThat(query("customerChurnRiskRanking", null, null)).isEmpty();
    }
    @Test void ownerFilterWithoutCityStillUsesExactOwnerNotCollector() {
        var rows = query("customerActivityRanking", null, "E1");
        assertThat(sum(rows, "salesAmount")).isEqualByComparingTo("70");
        assertThat(number(byCode(rows, "customerCode", "C1"), "paymentCount")).isEqualByComparingTo("2");
    }
    @Test void targetListRequiresCompleteSingleCityAssociationFromCustomersOrOrders() {
        targetFixtures();
        var targets = query("salesTargetCompletions", "BJ", null);
        assertThat(targets.stream().map(row -> value(row, "dimensionCode"))).containsExactlyInAnyOrder("LOCAL", "ORDER_ONLY");
        assertThat(number(byCode(targets, "dimensionCode", "LOCAL"), "actualValue")).isZero();
        assertThat(query("salesTargetCompletions", "SH", null).stream().map(row -> value(row, "dimensionCode")))
                .containsExactly("SH_ONLY");
    }
    @Test void explicitOwnerCannotRevealCrossCityOrUnknownCityTargetAndNoPartialAllocation() {
        targetFixtures();
        for (String owner : List.of("E1", "MULTI_CUSTOMER", "UNKNOWN_CITY", "SH_ONLY", "UNBOUND")) {
            assertThat(query("salesTargetCompletions", "BJ", owner)).as(owner).isEmpty();
        }
        assertThat(query("salesTargetCompletions", "BJ", "LOCAL")).singleElement().satisfies(row -> {
            assertThat(value(row, "dimensionCode")).isEqualTo("LOCAL");
            assertThat(number(row, "targetValue")).isEqualByComparingTo("100");
        });
    }
    @Test void tenantWideTargetHistoryAndOwnerFilterRemainUnchanged() {
        targetFixtures();
        assertThat(query("salesTargetCompletions", null, null)).hasSize(7);
        assertThat(query("salesTargetCompletions", null, "E1")).singleElement()
                .satisfies(row -> assertThat(number(row, "targetValue")).isEqualByComparingTo("100"));
    }
    private void targetFixtures() {
        customer("T", 10, "BJ", "LOCAL");
        customer("OTHER", 10, "SH", "LOCAL");
        customer("T", 11, "SH", "SH_ONLY");
        customer("T", 12, "BJ", "MULTI_CUSTOMER");
        customer("T", 13, "SH", "MULTI_CUSTOMER");
        customer("T", 14, "BJ", "UNKNOWN_CITY");
        customer("T", 15, null, "UNKNOWN_CITY");
        order("T", 10, 99, "BJ", "ORDER_ONLY", "2026-09-01T00:00:00", "10", "5");
        for (String owner : List.of("LOCAL", "SH_ONLY", "MULTI_CUSTOMER", "UNKNOWN_CITY", "ORDER_ONLY", "E1", "UNBOUND")) {
            jdbc.update("INSERT INTO bi_business_target VALUES ('T','SALES_OWNER',?,?,'2026-09-01','SALES_AMOUNT',100,0)", owner, owner);
        }
        jdbc.update("INSERT INTO bi_business_target VALUES ('OTHER','SALES_OWNER','LOCAL','其他租户','2026-09-01','SALES_AMOUNT',9999,0)");
    }
    private List<Map<String, Object>> query(String method, String city, String owner) {
        return query(method, city, owner, TO);
    }
    private List<Map<String, Object>> query(String method, String city, String owner, LocalDateTime to) {
        var parameters = new HashMap<String, Object>();
        parameters.put("tenantId", "T"); parameters.put("from", FROM); parameters.put("to", to);
        parameters.put("regionCode", city); parameters.put("ownerStaffCode", owner);
        parameters.put("customerTypeCode", null); parameters.put("sourceSystemCode", null);
        var bound = configuration.getMappedStatement(SupplyDashboardQueryMapper.class.getName() + "." + method).getBoundSql(parameters);
        // 仅适配两个 MySQL 日期函数；FROM/JOIN/WHERE/聚合/窗口/排序及所有绑定值保持实际 Mapper SQL。
        String sql = bound.getSql().replace("DATEDIFF(TIMESTAMPADD(HOUR, 8, ?), TIMESTAMPADD(HOUR, 8, ho.lastOrderTime))", "DATEDIFF('DAY', TIMESTAMPADD(HOUR, 8, ho.lastOrderTime), TIMESTAMPADD(HOUR, 8, CAST(? AS TIMESTAMP)))")
                .replace("DATE_FORMAT(TIMESTAMPADD(HOUR, 8, ?), '%Y-%m-01')", "FORMATDATETIME(TIMESTAMPADD(HOUR, 8, CAST(? AS TIMESTAMP)), 'yyyy-MM-01')")
                .replace("DATE_FORMAT(TIMESTAMPADD(HOUR, 8, o.order_date), '%Y-%m-01')", "FORMATDATETIME(TIMESTAMPADD(HOUR, 8, o.order_date), 'yyyy-MM-01')");
        Object[] args = bound.getParameterMappings().stream().map(mapping -> parameters.get(mapping.getProperty())).toArray();
        return jdbc.queryForList(sql, args);
    }
    private void customer(String tenant, long id, String city, String owner) {
        jdbc.update("INSERT INTO bi_customer_dim(tenant_id,customer_id,customer_code,customer_name,region_code,region_name,owner_staff_code,owner_staff_name) VALUES(?,?,?,?,?,?,?,?)",
                tenant, id, "C" + id, "客户" + id, city, city, owner, owner);
    }
    private void order(String tenant, long id, long customer, String city, String owner, String time, String amount, String paid) {
        jdbc.update("INSERT INTO bi_sales_order_fact(tenant_id,order_id,customer_id,region_code,owner_staff_code,order_date,payable_amount,paid_amount,unpaid_amount) VALUES(?,?,?,?,?,?,?,?,?)",
                tenant, id, customer, city, owner, LocalDateTime.parse(time), new BigDecimal(amount), new BigDecimal(paid), new BigDecimal(amount).subtract(new BigDecimal(paid)));
    }
    private void payment(String tenant, long id, long customer, String city, String owner, String time) {
        jdbc.update("INSERT INTO bi_sales_payment_fact(tenant_id,payment_id,customer_id,region_code,owner_staff_code,collector_staff_code,payment_time) VALUES(?,?,?,?,?,'E1',?)",
                tenant, id, customer, city, owner, LocalDateTime.parse(time));
    }
    private static Object value(Map<String, Object> row, String field) {
        for (var entry : row.entrySet()) if (entry.getKey().equalsIgnoreCase(field)) return entry.getValue();
        return null;
    }
    private static BigDecimal number(Map<String, Object> row, String field) { return new BigDecimal(Objects.requireNonNull(value(row, field)).toString()); }
    private static BigDecimal sum(List<Map<String, Object>> rows, String field) { return rows.stream().map(row -> number(row, field)).reduce(BigDecimal.ZERO, BigDecimal::add); }
    private static Map<String, Object> byCode(List<Map<String, Object>> rows, String field, String code) {
        return rows.stream().filter(row -> code.equals(value(row, field))).findFirst().orElseThrow();
    }
}

package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** 执行真实趋势与账龄 SQL，核查 UTC 存储在北京时间午夜、单日与月界的归属。 */
class BiBusinessDayRepositoryTest {
    private static final String AGING_FROM = "2026-06-30T16:00:00";
    private static final String AGING_TO = "2026-09-01T15:59:59.999999";
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private Configuration configuration;

    @BeforeEach void setup() {
        source = new SingleConnectionDataSource("jdbc:h2:mem:business_day_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE", "sa", "", true);
        jdbc = new JdbcTemplate(source);
        configuration = new Configuration();
        configuration.addMapper(SupplyDashboardQueryMapper.class);
        jdbc.execute("CREATE TABLE bi_sales_order_fact (tenant_id VARCHAR(64), order_date DATETIME(6), payable_amount DECIMAL(24,6), paid_amount DECIMAL(24,6), deleted INT, order_status_code VARCHAR(32))");
        jdbc.execute("ALTER TABLE bi_sales_order_fact ADD (owner_staff_code VARCHAR(64) DEFAULT 'E1', owner_staff_name VARCHAR(64) DEFAULT '销售', region_code VARCHAR(64) DEFAULT 'BJ', region_name VARCHAR(64) DEFAULT '北京', unpaid_amount DECIMAL(24,6) DEFAULT 0, customer_id BIGINT DEFAULT 1)");
        jdbc.execute("ALTER TABLE bi_sales_order_fact ADD (order_id BIGINT, payment_due_date DATETIME(6), source_updated_time DATETIME(6))");
        jdbc.execute("CREATE TABLE bi_sales_payment_fact (tenant_id VARCHAR(64), payment_time DATETIME(6), paid_amount DECIMAL(24,6), deleted INT)");
        jdbc.execute("CREATE TABLE bi_city_cost_record (tenant_id VARCHAR(64), cost_date DATETIME(6), cost_amount DECIMAL(24,6), budget_amount DECIMAL(24,6), deleted INT)");
        add("T", "2026-08-31T15:59:59.999999", "1.11");
        add("T", "2026-08-31T16:00:00", "2.22");
        add("T", "2026-09-01T00:00:00", "3.33");
        add("T", "2026-09-01T15:59:59.999999", "4.44");
        add("T", "2026-09-01T16:00:00", "5.55");
        add("OTHER", "2026-08-31T16:00:00", "999");
    }

    @AfterEach void close() { source.destroy(); }

    @ParameterizedTest @ValueSource(strings = {"salesTrend", "collectionTrend", "cityCostTrend"})
    void sameSingleDayFilterIncludesBothMidnightEdgesWithoutPreviousOrNextDay(String method) {
        var rows = query(method, "2026-08-31T16:00:00", "2026-09-01T15:59:59.999999");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("period")).isEqualTo("2026-09-01");
        assertThat((BigDecimal) rows.getFirst().get("value")).isEqualByComparingTo("9.99");
        assertThat(jdbc.queryForObject("SELECT MIN(order_date) FROM bi_sales_order_fact", LocalDateTime.class))
                .isEqualTo(LocalDateTime.parse("2026-08-31T15:59:59.999999"));
    }

    @ParameterizedTest @ValueSource(strings = {"salesTrend", "collectionTrend", "cityCostTrend"})
    void groupedDaysCrossBusinessMonthWithoutChangingAmounts(String method) {
        var rows = query(method, "2026-08-31T15:59:59.999999", "2026-09-01T16:00:00");
        assertThat(rows).extracting(row -> row.get("period")).containsExactly("2026-08-31", "2026-09-01", "2026-09-02");
        assertThat(rows.stream().map(row -> (BigDecimal) row.get("value")).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("16.65");
    }

    @Test void salesMonthlyPerformanceUsesTheSameOrderDatesAsDailyTrend() {
        var rows = query("salesMonthlyPerformance", "2026-08-31T15:59:59.999999", "2026-09-01T16:00:00");
        assertThat(rows).extracting(row -> row.get("period")).containsExactly("2026-08", "2026-09");
        assertThat((BigDecimal) rows.getFirst().get("salesAmount")).isEqualByComparingTo("1.11");
        assertThat((BigDecimal) rows.getLast().get("salesAmount")).isEqualByComparingTo("15.54");
    }

    @Test void salesMonthlyPerformanceReturnsEveryOwnerMonthBeyondThreeHundredGroups() {
        jdbc.update("DELETE FROM bi_sales_order_fact");
        for (int month = 1; month <= 12; month++) {
            for (int owner = 1; owner <= 26; owner++) {
                jdbc.update("""
                        INSERT INTO bi_sales_order_fact (tenant_id, order_date, owner_staff_code,
                            payable_amount, paid_amount, unpaid_amount, deleted, order_status_code)
                        VALUES ('T', ?, ?, 10.25, 4.25, 6, 0, 'COMPLETED')
                        """, LocalDateTime.of(2026, month, 1, 0, 0), "E" + owner);
            }
        }

        var rows = query("salesMonthlyPerformance", "2025-12-31T16:00:00", "2026-12-31T15:59:59.999999");

        assertThat(rows).hasSize(312);
        assertThat(rows).extracting(row -> row.get("period") + ":" + row.get("ownerStaffCode"))
                .doesNotHaveDuplicates();
        assertThat(rows.stream().filter(row -> row.get("period").equals("2026-12"))).hasSize(26);
        for (int owner = 1; owner <= 26; owner++) {
            String code = "E" + owner;
            assertThat(rows.stream().filter(row -> row.get("ownerStaffCode").equals(code))).hasSize(12);
        }
        assertThat(rows.stream().map(row -> (BigDecimal) row.get("salesAmount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("3198");
        assertThat(rows.stream().map(row -> (BigDecimal) row.get("paidAmount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1326");
        assertThat(rows.stream().map(row -> (BigDecimal) row.get("unpaidAmount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1872");
    }

    @Test void salesMonthlyPerformanceKeepsEveryScopeFilterAndCancelledDeletedExclusions() {
        jdbc.update("DELETE FROM bi_sales_order_fact");
        jdbc.execute("ALTER TABLE bi_sales_order_fact ADD (customer_type_code VARCHAR(64) DEFAULT 'STORE', source_system_code VARCHAR(64) DEFAULT 'FEISHU')");
        String from = "2025-12-31T16:00:00";
        String to = "2026-12-31T15:59:59.999999";
        for (int id = 1; id <= 11; id++) {
            jdbc.update("""
                    INSERT INTO bi_sales_order_fact (tenant_id, order_id, order_date,
                        payable_amount, paid_amount, deleted, order_status_code)
                    VALUES ('T', ?, ?, 1.25, 0.25, 0, 'COMPLETED')
                    """, id, LocalDateTime.parse(id == 1 ? from : to));
        }
        jdbc.update("UPDATE bi_sales_order_fact SET tenant_id='OTHER' WHERE order_id=3");
        jdbc.update("UPDATE bi_sales_order_fact SET deleted=1 WHERE order_id=4");
        jdbc.update("UPDATE bi_sales_order_fact SET order_status_code='CANCELLED' WHERE order_id=5");
        jdbc.update("UPDATE bi_sales_order_fact SET region_code='SH' WHERE order_id=6");
        jdbc.update("UPDATE bi_sales_order_fact SET owner_staff_code='E2' WHERE order_id=7");
        jdbc.update("UPDATE bi_sales_order_fact SET customer_type_code='RETAIL' WHERE order_id=8");
        jdbc.update("UPDATE bi_sales_order_fact SET source_system_code='DINGHUOBAO' WHERE order_id=9");
        jdbc.update("UPDATE bi_sales_order_fact SET order_date=? WHERE order_id=10", LocalDateTime.parse(from).minusNanos(1000));
        jdbc.update("UPDATE bi_sales_order_fact SET order_date=? WHERE order_id=11", LocalDateTime.parse(to).plusNanos(1000));

        var rows = query("salesMonthlyPerformance", from, to, Map.of("regionCode", "BJ",
                "ownerStaffCode", "E1", "customerTypeCode", "STORE", "sourceSystemCode", "FEISHU"));

        assertThat(rows).extracting(row -> row.get("period")).containsExactly("2026-01", "2026-12");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("ownerStaffCode")).isEqualTo("E1");
            assertThat((BigDecimal) row.get("salesAmount")).isEqualByComparingTo("1.25");
            assertThat(((Number) row.get("orderCount")).longValue()).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @CsvSource(value = {
            "NULL, CURRENT, 未逾期",
            "2026-08-31T16:00:00, CURRENT, 未逾期",
            "2026-09-01T00:00:00, CURRENT, 未逾期",
            "2026-09-01T15:59:59.999998, CURRENT, 未逾期",
            "2026-09-01T15:59:59.999999, CURRENT, 未逾期",
            "2026-09-01T16:00:00, CURRENT, 未逾期",
            "2026-08-31T15:59:59.999999, DAYS_1_30, 逾期1-30天",
            "2026-08-02T15:59:59.999999, DAYS_1_30, 逾期1-30天",
            "2026-08-01T15:59:59.999999, DAYS_31_60, 逾期31-60天",
            "2026-07-03T15:59:59.999999, DAYS_31_60, 逾期31-60天",
            "2026-07-02T15:59:59.999999, DAYS_61_PLUS, 逾期60天以上"
    }, nullValues = "NULL")
    void agingUsesBeijingCalendarDayBoundaries(String due, String code, String name) {
        addAgingOrder(1, due, "123.45");

        var rows = query("paymentAgingBuckets", AGING_FROM, AGING_TO);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("bucketCode", code).containsEntry("bucketName", name);
        assertThat(((Number) rows.getFirst().get("orderCount")).longValue()).isEqualTo(1);
        assertThat(((Number) rows.getFirst().get("customerCount")).longValue()).isEqualTo(1);
        assertThat((BigDecimal) rows.getFirst().get("unpaidAmount")).isEqualByComparingTo("123.45");
    }

    @Test void agingCodeNameAndSortStayAlignedWithoutChangingAmountsOrScope() {
        addAgingOrder(1, "2026-07-02T15:59:59.999999", "61");
        addAgingOrder(2, "2026-07-03T15:59:59.999999", "60");
        addAgingOrder(3, "2026-08-01T15:59:59.999999", "31");
        addAgingOrder(4, "2026-08-02T15:59:59.999999", "30");
        addAgingOrder(5, "2026-08-31T15:59:59.999999", "1");
        addAgingOrder(6, "2026-08-31T16:00:00", "2");
        addAgingOrder(7, "2026-09-01T16:00:00", "3");
        addAgingOrder(8, null, "4");
        addAgingOrder(9, "2026-09-01T00:00:00", "5");
        addAgingOrder(10, null, "-100");
        addAgingOrder(11, null, "0");
        for (int id = 12; id <= 16; id++) addAgingOrder(id, null, "9999");
        jdbc.update("UPDATE bi_sales_order_fact SET deleted = 1 WHERE order_id = 12");
        jdbc.update("UPDATE bi_sales_order_fact SET order_status_code = 'CANCELLED' WHERE order_id = 13");
        jdbc.update("UPDATE bi_sales_order_fact SET tenant_id = 'OTHER' WHERE order_id = 14");
        jdbc.update("UPDATE bi_sales_order_fact SET order_date = ? WHERE order_id = 15", LocalDateTime.parse(AGING_FROM).minusNanos(1000));
        jdbc.update("UPDATE bi_sales_order_fact SET order_date = ? WHERE order_id = 16", LocalDateTime.parse(AGING_TO).plusNanos(1000));

        var rows = query("paymentAgingBuckets", AGING_FROM, AGING_TO);

        assertThat(rows).extracting(row -> row.get("bucketCode"))
                .containsExactly("CURRENT", "DAYS_1_30", "DAYS_31_60", "DAYS_61_PLUS");
        assertThat(rows).extracting(row -> row.get("bucketName"))
                .containsExactly("未逾期", "逾期1-30天", "逾期31-60天", "逾期60天以上");
        assertThat(rows).extracting(row -> ((Number) row.get("orderCount")).longValue())
                .containsExactly(4L, 2L, 2L, 1L);
        assertThat(rows).extracting(row -> ((Number) row.get("customerCount")).longValue())
                .containsExactly(4L, 2L, 2L, 1L);
        assertThat(rows).extracting(row -> ((BigDecimal) row.get("unpaidAmount")).stripTrailingZeros())
                .containsExactly(new BigDecimal("14"), new BigDecimal("31"), new BigDecimal("91"), new BigDecimal("61"));
    }

    @Test void sameDayAgingDoesNotChangeTimestampBasedPaymentRiskAmount() {
        addAgingOrder(1, "2026-09-01T00:00:00", "10");
        addAgingOrder(2, "2026-08-31T15:59:59.999999", "20");

        var buckets = query("paymentAgingBuckets", AGING_FROM, AGING_TO);
        var risk = query("paymentRiskSummary", AGING_FROM, AGING_TO).getFirst();

        assertThat(buckets).extracting(row -> row.get("bucketCode")).containsExactly("CURRENT", "DAYS_1_30");
        assertThat((BigDecimal) risk.get("riskAmount")).isEqualByComparingTo("30");
        assertThat(((Number) risk.get("riskCustomerCount")).longValue()).isEqualTo(2);
        assertThat(((Number) risk.get("highRiskCustomerCount")).longValue()).isEqualTo(1);
    }

    private void addAgingOrder(long id, String due, String amount) {
        jdbc.update("INSERT INTO bi_sales_order_fact (tenant_id,order_id,customer_id,order_date,payment_due_date,payable_amount,paid_amount,unpaid_amount,deleted,order_status_code) VALUES ('T',?,?,?, ?,?,0,?,0,'COMPLETED')",
                id, id, LocalDateTime.parse("2026-07-01T00:00:00"), due == null ? null : LocalDateTime.parse(due),
                new BigDecimal(amount), new BigDecimal(amount));
    }

    private void add(String tenant, String date, String amount) {
        var time = LocalDateTime.parse(date);
        var value = new BigDecimal(amount);
        jdbc.update("INSERT INTO bi_sales_order_fact (tenant_id,order_date,payable_amount,paid_amount,deleted,order_status_code) VALUES (?,?,?,?,0,'COMPLETED')", tenant, time, value, value);
        jdbc.update("INSERT INTO bi_sales_payment_fact VALUES (?,?,?,0)", tenant, time, value);
        jdbc.update("INSERT INTO bi_city_cost_record VALUES (?,?,?,?,0)", tenant, time, value, value);
    }

    private List<Map<String, Object>> query(String method, String from, String to) {
        return query(method, from, to, Map.of());
    }

    private List<Map<String, Object>> query(String method, String from, String to, Map<String, Object> filters) {
        var parameters = new HashMap<String, Object>();
        parameters.put("tenantId", "T");
        parameters.put("from", LocalDateTime.parse(from));
        parameters.put("to", LocalDateTime.parse(to));
        for (String key : List.of("regionCode", "ownerStaffCode", "customerTypeCode", "sourceSystemCode")) parameters.put(key, null);
        parameters.putAll(filters);
        var bound = configuration.getMappedStatement(SupplyDashboardQueryMapper.class.getName() + "." + method).getBoundSql(parameters);
        // Only adapt MySQL date functions; preserve the actual branches, filters, sums and sort.
        String sql = bound.getSql().replace("DATE_FORMAT(", "FORMATDATETIME(")
                .replace("'%Y-%m-%d'", "'yyyy-MM-dd'").replace("'%Y-%m'", "'yyyy-MM'")
                .replace("DATEDIFF(TIMESTAMPADD(HOUR, 8, ?), TIMESTAMPADD(HOUR, 8, o.payment_due_date))",
                        "DATEDIFF('DAY', TIMESTAMPADD(HOUR, 8, o.payment_due_date), TIMESTAMPADD(HOUR, 8, CAST(? AS TIMESTAMP)))");
        Object[] args = bound.getParameterMappings().stream().map(mapping -> parameters.get(mapping.getProperty())).toArray();
        return jdbc.queryForList(sql, args);
    }
}

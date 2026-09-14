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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** 执行真实趋势 SQL，核查 UTC 存储在北京时间午夜、单日与月界的归属。 */
class BiBusinessDayRepositoryTest {
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

    private void add(String tenant, String date, String amount) {
        var time = LocalDateTime.parse(date);
        var value = new BigDecimal(amount);
        jdbc.update("INSERT INTO bi_sales_order_fact (tenant_id,order_date,payable_amount,paid_amount,deleted,order_status_code) VALUES (?,?,?,?,0,'COMPLETED')", tenant, time, value, value);
        jdbc.update("INSERT INTO bi_sales_payment_fact VALUES (?,?,?,0)", tenant, time, value);
        jdbc.update("INSERT INTO bi_city_cost_record VALUES (?,?,?,?,0)", tenant, time, value, value);
    }

    private List<Map<String, Object>> query(String method, String from, String to) {
        var parameters = new HashMap<String, Object>();
        parameters.put("tenantId", "T");
        parameters.put("from", LocalDateTime.parse(from));
        parameters.put("to", LocalDateTime.parse(to));
        for (String key : List.of("regionCode", "ownerStaffCode", "customerTypeCode", "sourceSystemCode")) parameters.put(key, null);
        var bound = configuration.getMappedStatement(SupplyDashboardQueryMapper.class.getName() + "." + method).getBoundSql(parameters);
        // H2 lacks MySQL DATE_FORMAT; keep the timestamp conversion, filters and sums intact.
        String sql = bound.getSql().replace("DATE_FORMAT(", "FORMATDATETIME(")
                .replace("'%Y-%m-%d'", "'yyyy-MM-dd'").replace("'%Y-%m'", "'yyyy-MM'");
        Object[] args = bound.getParameterMappings().stream().map(mapping -> parameters.get(mapping.getProperty())).toArray();
        return jdbc.queryForList(sql, args);
    }
}

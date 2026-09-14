package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 执行真实动态 SQL，防止目标可配置却无实际值、遗漏人员或混用统计范围。 */
class SupplyDashboardTargetSemanticsRepositoryTest {
    private static final LocalDateTime FROM = LocalDateTime.parse("2026-08-31T16:00:00");
    private static final LocalDateTime TO = LocalDateTime.parse("2026-09-30T15:59:59.999999");
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private Configuration configuration;

    @BeforeEach void setup() {
        source = new SingleConnectionDataSource("jdbc:h2:mem:target_semantics_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        jdbc = new JdbcTemplate(source);
        configuration = new Configuration();
        configuration.addMapper(SupplyDashboardQueryMapper.class);
        jdbc.execute("""
                CREATE TABLE bi_business_target (
                    tenant_id VARCHAR(64), dimension_type VARCHAR(32), dimension_code VARCHAR(64),
                    dimension_name VARCHAR(100), target_month DATE, metric_code VARCHAR(64),
                    target_value DECIMAL(24,6), deleted INT DEFAULT 0,
                    UNIQUE (tenant_id, target_month, dimension_type, dimension_code, metric_code))
                """);
        jdbc.execute("""
                CREATE TABLE bi_customer_dim (
                    tenant_id VARCHAR(64), customer_id BIGINT, region_code VARCHAR(64),
                    owner_staff_code VARCHAR(50), customer_type_code VARCHAR(64) DEFAULT 'STORE',
                    status_code VARCHAR(64) DEFAULT 'ACTIVE', has_contact INT DEFAULT 1, deleted INT DEFAULT 0,
                    UNIQUE (tenant_id, customer_id))
                """);
        jdbc.execute("""
                CREATE TABLE bi_sales_order_fact (
                    tenant_id VARCHAR(64), order_id BIGINT, customer_id BIGINT, region_code VARCHAR(64),
                    owner_staff_code VARCHAR(50), customer_type_code VARCHAR(64) DEFAULT 'STORE',
                    source_system_code VARCHAR(32) DEFAULT 'FEISHU', order_date DATETIME(6),
                    order_status_code VARCHAR(64) DEFAULT 'COMPLETED', deleted INT DEFAULT 0,
                    payable_amount DECIMAL(24,6), paid_amount DECIMAL(24,6),
                    UNIQUE (tenant_id, order_id))
                """);
        jdbc.update("INSERT INTO bi_customer_dim (tenant_id,customer_id,region_code,owner_staff_code) VALUES ('T',1,'BJ','E1'),('T',2,'BJ','E1'),('OTHER',1,'BJ','E1')");
        order("T", 1, 1, "2026-08-31T16:00:00", "60", "20");
        order("T", 2, 2, "2026-09-10T00:00:00", "40", "10");
        order("T", 3, 1, "2026-09-30T15:59:59.999999", "10", "5");
        order("OTHER", 1, 1, "2026-09-10T00:00:00", "9999", "9999");
    }

    @AfterEach void close() { source.destroy(); }

    @ParameterizedTest
    @ValueSource(strings = {"CITY", "SALES_OWNER"})
    void allFourConfiguredMetricsHaveCorrectActualsAndCoverage(String dimension) {
        for (String metric : List.of("SALES_AMOUNT", "PAID_AMOUNT", "CONTACTED_CUSTOMER", "COOPERATED_CUSTOMER")) {
            target(dimension, "2026-09-01", metric, "100");
        }
        jdbc.update("INSERT INTO bi_customer_dim VALUES ('T',3,'BJ','E1','STORE','INACTIVE',1,0),('T',4,'BJ','E1','STORE','ACTIVE',0,0),('T',5,'BJ','E1','STORE','ACTIVE',1,1)");
        var rows = query(dimension, FROM, TO, "BJ", null, null, null);
        assertThat(rows).hasSize(4);
        assertThat(number(metric(rows, "SALES_AMOUNT"), "actualValue")).isEqualByComparingTo("110");
        assertThat(number(metric(rows, "SALES_AMOUNT"), "achievementRate")).isEqualByComparingTo("110");
        assertThat(number(metric(rows, "PAID_AMOUNT"), "actualValue")).isEqualByComparingTo("35");
        assertThat(number(metric(rows, "COOPERATED_CUSTOMER"), "actualValue")).isEqualByComparingTo("2");
        assertThat(number(metric(rows, "CONTACTED_CUSTOMER"), "actualValue")).isEqualByComparingTo("2");
        assertThat(value(metric(rows, "CONTACTED_CUSTOMER"), "metricName")).isEqualTo("建联客户数");
        rows.forEach(row -> {
            assertThat(number(row, "configuredMonthCount")).isEqualByComparingTo("1");
            assertThat(number(row, "periodMonthCount")).isEqualByComparingTo("1");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"CITY", "SALES_OWNER"})
    void singleBusinessDayUsesSeptemberTargetsEvenWhenUtcFromIsInAugust(String dimension) {
        target(dimension, "2026-08-01", "SALES_AMOUNT", "9999");
        target(dimension, "2026-09-01", "SALES_AMOUNT", "100");
        var row = query(dimension, FROM, FROM.plusDays(1).minusNanos(1000), "BJ", null, null, null).getFirst();
        assertThat(number(row, "actualValue")).isEqualByComparingTo("60");
        assertThat(number(row, "targetValue")).isEqualByComparingTo("100");
        assertThat(number(row, "configuredMonthCount")).isEqualByComparingTo("1");
        assertThat(number(row, "periodMonthCount")).isEqualByComparingTo("1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CITY", "SALES_OWNER"})
    void unconfiguredMonthsDoNotInflateActualsAndCoverageExposesTheGap(String dimension) {
        target(dimension, "2026-09-01", "SALES_AMOUNT", "100");
        target(dimension, "2026-09-01", "PAID_AMOUNT", "100");
        target(dimension, "2026-09-01", "COOPERATED_CUSTOMER", "2");
        order("T", 4, 3, "2026-08-15T00:00:00", "900", "800");
        var from = FROM.minusMonths(1);
        var rows = query(dimension, from, TO, "BJ", null, null, null);
        assertThat(number(metric(rows, "SALES_AMOUNT"), "actualValue")).isEqualByComparingTo("110");
        assertThat(number(metric(rows, "PAID_AMOUNT"), "actualValue")).isEqualByComparingTo("35");
        assertThat(number(metric(rows, "COOPERATED_CUSTOMER"), "actualValue")).isEqualByComparingTo("2");
        assertThat(number(rows.getFirst(), "configuredMonthCount")).isEqualByComparingTo("1");
        assertThat(number(rows.getFirst(), "periodMonthCount")).isEqualByComparingTo("2");
        target(dimension, "2026-08-01", "SALES_AMOUNT", "300");
        rows = query(dimension, from, TO, "BJ", null, null, null);
        assertThat(number(metric(rows, "SALES_AMOUNT"), "actualValue")).isEqualByComparingTo("1010");
        assertThat(number(metric(rows, "SALES_AMOUNT"), "targetValue")).isEqualByComparingTo("400");
        assertThat(number(metric(rows, "SALES_AMOUNT"), "configuredMonthCount")).isEqualByComparingTo("2");
        assertThat(number(metric(rows, "PAID_AMOUNT"), "actualValue")).isEqualByComparingTo("35");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CITY", "SALES_OWNER"})
    void boundsCancelledDeletedAndOtherTenantTargetsDoNotChangeActuals(String dimension) {
        target(dimension, "2026-09-01", "SALES_AMOUNT", "100");
        target(dimension, "2026-08-01", "SALES_AMOUNT", "9999");
        target(dimension, "2026-10-01", "SALES_AMOUNT", "9999");
        order("T", 4, 1, "2026-08-31T15:59:59.999999", "900", "800");
        order("T", 5, 1, "2026-09-30T16:00:00", "900", "800");
        order("T", 6, 1, "2026-09-11T00:00:00", "900", "800");
        order("T", 7, 1, "2026-09-12T00:00:00", "900", "800");
        jdbc.update("UPDATE bi_sales_order_fact SET deleted=1 WHERE tenant_id='T' AND order_id=6");
        jdbc.update("UPDATE bi_sales_order_fact SET order_status_code='CANCELLED' WHERE tenant_id='T' AND order_id=7");
        jdbc.update("INSERT INTO bi_business_target VALUES ('OTHER',?,?,'其他租户','2026-09-01','SALES_AMOUNT',9999,0)", dimension, code(dimension));
        var row = query(dimension, FROM, TO, "BJ", null, null, null).getFirst();
        assertThat(number(row, "actualValue")).isEqualByComparingTo("110");
        assertThat(number(row, "targetValue")).isEqualByComparingTo("100");
        assertThat(number(row, "configuredMonthCount")).isEqualByComparingTo("1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CITY", "SALES_OWNER"})
    void customerTypeOrOrderSourceCannotProduceAFalseCompleteTargetComparison(String dimension) {
        target(dimension, "2026-09-01", "SALES_AMOUNT", "100");
        assertThat(query(dimension, FROM, TO, "BJ", null, "STORE", null)).isEmpty();
        assertThat(query(dimension, FROM, TO, "BJ", null, null, "FEISHU")).isEmpty();
    }

    @Test void ownerSelectionKeepsOwnTargetButNotTheWholeCityTarget() {
        target("CITY", "2026-09-01", "SALES_AMOUNT", "100");
        target("SALES_OWNER", "2026-09-01", "SALES_AMOUNT", "100");
        assertThat(query("CITY", FROM, TO, "BJ", "E1", null, null)).isEmpty();
        assertThat(query("SALES_OWNER", FROM, TO, "BJ", "E1", null, null)).hasSize(1);
    }

    @Test void personalGlobalTargetsAreNotComparedWithOnlyOneCityOfActuals() {
        for (String metric : List.of("SALES_AMOUNT", "PAID_AMOUNT", "CONTACTED_CUSTOMER", "COOPERATED_CUSTOMER")) {
            target("SALES_OWNER", "2026-09-01", metric, "100");
        }
        order("T", 4, 2, "2026-09-15T00:00:00", "90", "80");
        jdbc.update("UPDATE bi_sales_order_fact SET region_code='SH' WHERE tenant_id='T' AND order_id=4");
        assertThat(query("SALES_OWNER", FROM, TO, "BJ", "E1", null, null)).isEmpty();
        assertThat(query("SALES_OWNER", FROM, TO, "SH", "E1", null, null)).isEmpty();
        var global = query("SALES_OWNER", FROM, TO, null, "E1", null, null);
        assertThat(global).hasSize(4);
        assertThat(number(metric(global, "SALES_AMOUNT"), "actualValue")).isEqualByComparingTo("200");
        assertThat(number(metric(global, "SALES_AMOUNT"), "targetValue")).isEqualByComparingTo("100");
        assertThat(number(metric(global, "PAID_AMOUNT"), "actualValue")).isEqualByComparingTo("115");
    }

    @Test void historicalOtherCityOrUnknownAttributionCannotBeHiddenByCurrentPeriod() {
        target("SALES_OWNER", "2026-09-01", "SALES_AMOUNT", "100");
        order("T", 4, 1, "2026-07-01T00:00:00", "90", "80");
        jdbc.update("UPDATE bi_sales_order_fact SET region_code='SH' WHERE tenant_id='T' AND order_id=4");
        assertThat(query("SALES_OWNER", FROM, TO, "BJ", "E1", null, null)).isEmpty();
        jdbc.update("UPDATE bi_sales_order_fact SET region_code=NULL WHERE tenant_id='T' AND order_id=4");
        assertThat(query("SALES_OWNER", FROM, TO, "BJ", "E1", null, null)).isEmpty();
        var global = query("SALES_OWNER", FROM, TO, null, "E1", null, null);
        assertThat(number(global.getFirst(), "actualValue")).isEqualByComparingTo("110");
    }

    @Test void otherTenantCityDoesNotInvalidateTheLocalSalesTarget() {
        target("SALES_OWNER", "2026-09-01", "SALES_AMOUNT", "100");
        jdbc.update("UPDATE bi_sales_order_fact SET region_code='SH' WHERE tenant_id='OTHER'");
        jdbc.update("UPDATE bi_customer_dim SET region_code='SH' WHERE tenant_id='OTHER'");
        var local = query("SALES_OWNER", FROM, TO, "BJ", "E1", null, null);
        assertThat(local).hasSize(1);
        assertThat(number(local.getFirst(), "actualValue")).isEqualByComparingTo("110");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CITY", "SALES_OWNER"})
    void currentContactStockIsNotComparedWithSumOfSeveralMonthlyTargets(String dimension) {
        target(dimension, "2026-08-01", "CONTACTED_CUSTOMER", "100");
        target(dimension, "2026-09-01", "CONTACTED_CUSTOMER", "100");
        assertThat(query(dimension, FROM.minusMonths(1), TO, null, null, null, null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CITY", "SALES_OWNER"})
    void nationalTargetComparisonDoesNotTruncateAtEightyRows(String dimension) {
        for (int i = 0; i < 95; i++) {
            jdbc.update("INSERT INTO bi_business_target VALUES ('T',?,?,'对象','2026-09-01','SALES_AMOUNT',100,0)", dimension, "OBJECT_" + i);
        }
        assertThat(query(dimension, FROM, TO, null, null, null, null)).hasSize(95);
    }

    @Test void granularCategoryFilterSkipsWholeBusinessTargetsBeforeQuerying() {
        var mapper = mock(SupplyDashboardQueryMapper.class);
        var repository = new MybatisPlusSupplyDashboardRepository(mapper);
        var result = repository.overview("T", new SupplyDashboardFilter(Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-30T23:59:59Z"), null, null, null, 1L, null));
        assertThat(result.cityTargetCompletions()).isEmpty();
        assertThat(result.salesTargetCompletions()).isEmpty();
        verify(mapper, never()).cityTargetCompletions(any(), any(), any(), any(), any(), any(), any());
        verify(mapper, never()).salesTargetCompletions(any(), any(), any(), any(), any(), any(), any());
    }

    private List<Map<String, Object>> query(String dimension, LocalDateTime from, LocalDateTime to,
            String city, String owner, String customerType, String orderSource) {
        var parameters = new HashMap<String, Object>();
        parameters.put("tenantId", "T"); parameters.put("from", from); parameters.put("to", to);
        parameters.put("regionCode", city); parameters.put("ownerStaffCode", owner);
        parameters.put("customerTypeCode", customerType); parameters.put("sourceSystemCode", orderSource);
        String method = "CITY".equals(dimension) ? "cityTargetCompletions" : "salesTargetCompletions";
        var bound = configuration.getMappedStatement(SupplyDashboardQueryMapper.class.getName() + "." + method).getBoundSql(parameters);
        String sql = bound.getSql().replace("DATE_FORMAT(TIMESTAMPADD(HOUR, 8, ?), '%Y-%m-01')", "FORMATDATETIME(TIMESTAMPADD(HOUR, 8, CAST(? AS TIMESTAMP)), 'yyyy-MM-01')")
                .replace("DATE_FORMAT(TIMESTAMPADD(HOUR, 8, o.order_date), '%Y-%m-01')", "FORMATDATETIME(TIMESTAMPADD(HOUR, 8, o.order_date), 'yyyy-MM-01')");
        Object[] args = bound.getParameterMappings().stream().map(mapping -> parameters.get(mapping.getProperty())).toArray();
        return jdbc.queryForList(sql, args);
    }
    private void target(String dimension, String month, String metric, String value) {
        jdbc.update("INSERT INTO bi_business_target VALUES ('T',?,?,?,CAST(? AS DATE),?,?,0)",
                dimension, code(dimension), code(dimension), month, metric, new BigDecimal(value));
    }
    private static String code(String dimension) { return "CITY".equals(dimension) ? "BJ" : "E1"; }
    private void order(String tenant, long id, long customer, String time, String amount, String paid) {
        jdbc.update("INSERT INTO bi_sales_order_fact (tenant_id,order_id,customer_id,region_code,owner_staff_code,order_date,payable_amount,paid_amount) VALUES (?,?,?,'BJ','E1',?,?,?)",
                tenant, id, customer, LocalDateTime.parse(time), new BigDecimal(amount), new BigDecimal(paid));
    }
    private static Object value(Map<String, Object> row, String field) {
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(field))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }
    private static BigDecimal number(Map<String, Object> row, String field) {
        return new BigDecimal(Objects.requireNonNull(value(row, field)).toString());
    }
    private static Map<String, Object> metric(List<Map<String, Object>> rows, String metric) {
        return rows.stream().filter(row -> metric.equals(value(row, "metricCode"))).findFirst().orElseThrow();
    }
}

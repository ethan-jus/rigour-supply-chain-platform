package com.rigour.analytics.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.application.port.out.HrTargetClient;
import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;
import com.rigour.hr.api.v1.model.TargetSettingsModels.Target;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

class HrDashboardTargetsTest {
    @Test
    void cityFilteredSalesTargetsUseEmployeeRegionAndRetainAttributionBoundaries() {
        var ds = new SingleConnectionDataSource(
                "jdbc:h2:mem:hr_targets_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "", true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE bi_sales_contact_city_dim(tenant_id VARCHAR,region_code VARCHAR,department_id BIGINT,department_path VARCHAR)");
            jdbc.execute("CREATE TABLE bi_employee_dim(tenant_id VARCHAR,employee_code VARCHAR,region_code VARCHAR)");
            jdbc.execute("CREATE TABLE bi_customer_authority(tenant_id VARCHAR,employee_code VARCHAR,region_code VARCHAR)");
            jdbc.execute("CREATE TABLE bi_order_authority(tenant_id VARCHAR,employee_code VARCHAR,region_code VARCHAR)");
            jdbc.update("INSERT INTO bi_employee_dim VALUES('T','A','HZ'),('T','B','BJ'),('OTHER','C','HZ'),('T','D','HZ'),('T','E','HZ'),('T','F','HZ')");
            jdbc.update("INSERT INTO bi_customer_authority VALUES('T','A','HZ'),('T','D','BJ')");
            jdbc.update("INSERT INTO bi_order_authority VALUES('T','E','BJ'),('T','F',NULL)");
            var mapper = mock(SupplyDashboardQueryMapper.class);
            var targets = new HrDashboardTargets(mock(HrTargetClient.class), ds, mapper);
            var goals = List.of("A", "B", "C", "D", "E", "F").stream()
                    .map(code -> new Target("2026-10", "SALES_OWNER", code, code,
                            "SALES_AMOUNT", BigDecimal.TEN, 1)).toList();
            var filter = new SupplyDashboardFilter(
                    Instant.parse("2026-09-30T16:00:00Z"), Instant.parse("2026-10-31T15:59:59Z"),
                    "HZ", null, null, null, null);

            assertThat(targets.completions("T", filter, "SALES_OWNER", goals))
                    .singleElement().satisfies(item -> {
                        assertThat(item.dimensionCode()).isEqualTo("A");
                        assertThat(item.targetValue()).isEqualByComparingTo("10");
                    });
        } finally {
            ds.destroy();
        }
    }

    @Test
    void apiValuesDriveMonthlyGoalsAndPeriodAttainmentWithoutAnyTargetTable() {
        var client = mock(HrTargetClient.class);
        var mapper = mock(SupplyDashboardQueryMapper.class);
        var ds = new SingleConnectionDataSource("jdbc:h2:mem:targets_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "", true);
        new JdbcTemplate(ds).execute("CREATE TABLE bi_sales_contact_city_dim(tenant_id VARCHAR,region_code VARCHAR,department_id BIGINT,department_path VARCHAR)");
        var targets = new HrDashboardTargets(client, ds, mapper);
        var values = new ArrayList<Target>();
        for (var month : List.of("2026-01", "2026-02"))
            for (var metric :
                    List.of(
                            "CONTACTED_STORE_COUNT",
                            "ORDERED_STORE_COUNT",
                            "YANGZHANGGUI_SALES_AMOUNT",
                            "SALES_AMOUNT",
                            "RECEIPT_AMOUNT"))
                values.add(
                        new Target(
                                month,
                                "CITY",
                                "BJ",
                                "北京",
                                metric,
                                month.endsWith("01") ? BigDecimal.ZERO : new BigDecimal("321.25"),
                                1));
        when(client.values("T", "2026-01", "2026-12")).thenReturn(values);
        var goals = targets.cityGoals("T", 2026, null);
        assertThat(goals).hasSize(2);
        assertThat(goals.getFirst().salesTarget()).isZero();
        assertThat(goals.getLast().salesTarget()).isEqualByComparingTo("321.25");
        when(mapper.targetCityAmounts(any(), any(), any(), any())).thenReturn(List.of(
                Map.of("dimensionCode", "BJ", "metricCode", "SALES_AMOUNT", "actualValue", new BigDecimal("160.625")),
                Map.of("dimensionCode", "BJ", "metricCode", "RECEIPT_AMOUNT", "actualValue", new BigDecimal("50"))));
        when(mapper.targetBusinessActuals(any(), any(), any(), any(), any(), eq(true)))
                .thenReturn(List.of(
                        Map.of("dimensionCode", "BJ", "metricCode", "ORDERED_STORE_COUNT", "actualValue", 10),
                        Map.of("dimensionCode", "BJ", "metricCode", "YANGZHANGGUI_SALES_AMOUNT", "actualValue", new BigDecimal("64.25"))));
        var filter =
                new SupplyDashboardFilter(
                        Instant.parse("2025-12-31T16:00:00Z"),
                        Instant.parse("2026-02-28T15:59:59Z"),
                        null,
                        null,
                        null,
                        null,
                        null);
        var actual = targets.completions("T", filter, "CITY", values);
        assertThat(actual).hasSize(5);
        assertThat(actual)
                .filteredOn(t -> t.metricCode().equals("SALES_AMOUNT"))
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.targetValue()).isEqualByComparingTo("321.25");
                            assertThat(t.actualValue()).isEqualByComparingTo("160.625");
                            assertThat(t.achievementRate()).isEqualByComparingTo("50");
                            assertThat(t.periodMonthCount()).isEqualTo(2);
                        });
        assertThat(actual)
                .filteredOn(t -> t.metricCode().equals("ORDERED_STORE_COUNT"))
                .singleElement()
                .satisfies(t -> assertThat(t.actualValue()).isEqualByComparingTo("10"));
        assertThat(actual)
                .filteredOn(t -> t.metricCode().equals("CONTACTED_STORE_COUNT"))
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.actualValue()).isNull();
                            assertThat(t.achievementRate()).isNull();
                        });
        assertThat(actual)
                .filteredOn(t -> t.metricCode().equals("YANGZHANGGUI_SALES_AMOUNT"))
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.actualValue()).isEqualByComparingTo("64.25");
                            assertThat(t.achievementRate()).isEqualByComparingTo("20");
                        });
        verify(client, times(1)).values(any(), any(), any());
        verify(mapper, times(1)).targetBusinessActuals(any(), any(), any(), any(), any(), eq(true));
        verify(mapper, never()).customerRetention(any(), any(), any(), any(), any(), any(), any());
        verify(mapper, never()).yangzhangguiTargetSales(any(), any(), any(), any(), any());
        verify(mapper, times(1)).targetCityAmounts(any(), any(), any(), any());
        verify(mapper, never()).salesSummary(any(), any(), any(), any(), any(), any(), any());
        verify(mapper, never()).collectionSummary(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void fiveHundredEmployeeTargetsUseOneBatchAndNoUnneededRetentionOrBrandReads() {
        var mapper = mock(SupplyDashboardQueryMapper.class);
        var targets = new HrDashboardTargets(mock(HrTargetClient.class), mock(javax.sql.DataSource.class), mapper);
        var goals = new ArrayList<Target>();
        var actuals = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 500; i++) {
            String code = "E" + i;
            goals.add(new Target("2026-10", "SALES_OWNER", code, code, "ORDERED_STORE_COUNT", new BigDecimal("20"), 1));
            goals.add(new Target("2026-10", "SALES_OWNER", code, code, "YANGZHANGGUI_SALES_AMOUNT", new BigDecimal("1000"), 1));
            actuals.add(Map.of("dimensionCode", code, "metricCode", "ORDERED_STORE_COUNT", "actualValue", i));
            actuals.add(Map.of("dimensionCode", code, "metricCode", "YANGZHANGGUI_SALES_AMOUNT", "actualValue", new BigDecimal(i + ".25")));
        }
        when(mapper.targetBusinessActuals(any(), any(), any(), any(), any(), eq(false))).thenReturn(actuals);
        var filter = new SupplyDashboardFilter(Instant.parse("2026-09-30T16:00:00Z"),
                Instant.parse("2026-10-23T15:59:59Z"), null, null, null, null, null);
        var result = targets.completions("T", filter, "SALES_OWNER", goals);
        assertThat(result).hasSize(1000);
        assertThat(result).filteredOn(r -> r.dimensionCode().equals("E499") && r.metricCode().equals("ORDERED_STORE_COUNT"))
                .singleElement().satisfies(r -> assertThat(r.actualValue()).isEqualByComparingTo("499"));
        assertThat(result).filteredOn(r -> r.dimensionCode().equals("E499") && r.metricCode().equals("YANGZHANGGUI_SALES_AMOUNT"))
                .singleElement().satisfies(r -> assertThat(r.actualValue()).isEqualByComparingTo("499.25"));
        verify(mapper, times(1)).targetBusinessActuals(any(), any(), any(), any(), any(), eq(false));
        verify(mapper, never()).customerRetention(any(), any(), any(), any(), any(), any(), any());
        verify(mapper, never()).yangzhangguiTargetSales(any(), any(), any(), any(), any());

        clearInvocations(mapper);
        var salaryOnly = List.of(new Target("2026-10", "SALES_OWNER", "E1", "E1", "SALES_AMOUNT", BigDecimal.TEN, 1));
        assertThat(targets.completions("T", filter, "SALES_OWNER", salaryOnly)).hasSize(1);
        assertThat(targets.completions("T", filter, "CITY", salaryOnly)).isEmpty();
        verify(mapper, never()).targetBusinessActuals(any(), any(), any(), any(), any(), anyBoolean());
        verify(mapper, never()).customerRetention(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unavailableHrNeverBecomesInventedGoals() {
        var client = mock(HrTargetClient.class);
        when(client.values(any(), any(), any()))
                .thenThrow(new IllegalStateException("HR unavailable"));
        var targets =
                new HrDashboardTargets(
                        client,
                        mock(javax.sql.DataSource.class),
                        mock(SupplyDashboardQueryMapper.class));
        assertThatThrownBy(() -> targets.cityGoals("T", 2026, null)).hasMessage("HR unavailable");
    }

    @Test
    void batchedSqlPreservesDistinctCustomersBrandRefundsTenantAndExactDateCutoff() {
        var ds = new SingleConnectionDataSource("jdbc:h2:mem:target_actuals_" + UUID.randomUUID()
                + ";MODE=MySQL", "sa", "", true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE bi_sales_contact_city_dim(tenant_id VARCHAR,region_code VARCHAR,department_id BIGINT,department_path VARCHAR)");
            jdbc.execute("CREATE TABLE bi_sales_order_fact(tenant_id VARCHAR,order_id BIGINT,customer_id BIGINT,"
                    + "region_code VARCHAR,owner_staff_code VARCHAR,order_date TIMESTAMP,order_status_code VARCHAR,deleted INT)");
            jdbc.execute("CREATE TABLE bi_sales_order_line_fact(tenant_id VARCHAR,order_id BIGINT,"
                    + "region_code VARCHAR,owner_staff_code VARCHAR,order_date TIMESTAMP,order_status_code VARCHAR,"
                    + "deleted INT,brand_name VARCHAR,line_amount DECIMAL(18,2))");
            jdbc.execute("CREATE TABLE bi_dashboard_customer_history(tenant_id VARCHAR,customer_id BIGINT,first_order_date TIMESTAMP)");
            jdbc.update("""
                    INSERT INTO bi_sales_order_fact VALUES
                    ('T',1,1,'BJ','E1','2026-10-10','COMPLETED',0),
                    ('T',2,1,'BJ','E1','2026-10-11','COMPLETED',0),
                    ('T',3,2,'BJ','E2','2026-10-12','COMPLETED',0),
                    ('T',4,3,'HZ','E1','2026-10-13','COMPLETED',0),
                    ('T',5,4,'BJ','E1','2026-09-30 15:59:59','COMPLETED',0),
                    ('T',6,5,'BJ','E1','2026-10-23 16:00:00','COMPLETED',0),
                    ('T',7,6,'BJ','E1','2026-10-10','CANCELLED',0),
                    ('T',8,7,'BJ','E1','2026-10-10','COMPLETED',1),
                    ('OTHER',1,8,'BJ','E1','2026-10-10','COMPLETED',0)
                    """);
            jdbc.update("""
                    INSERT INTO bi_sales_order_line_fact
                    SELECT tenant_id,order_id,region_code,owner_staff_code,order_date,order_status_code,
                           deleted,'杨掌柜 X CBSA世界斯诺克学院',
                           CASE order_id WHEN 1 THEN 100 WHEN 2 THEN -5 WHEN 3 THEN 90 WHEN 4 THEN 50 ELSE 9999 END
                      FROM bi_sales_order_fact
                    """);
            jdbc.update("INSERT INTO bi_sales_order_line_fact VALUES('T',1,'BJ','E1','2026-10-10',"
                    + "'COMPLETED',0,'其他品牌',7777)");
            var config = new org.apache.ibatis.session.Configuration();
            config.addMapper(SupplyDashboardQueryMapper.class);
            var args = new HashMap<String, Object>();
            args.put("tenantId", "T");
            args.put("from", java.time.LocalDateTime.parse("2026-09-30T16:00:00"));
            args.put("to", java.time.LocalDateTime.parse("2026-10-23T15:59:59"));
            for (boolean city : List.of(false, true)) {
                args.put("city", city);
                var rows = query(jdbc, config, "targetBusinessActuals", args);
                assertThat(rows.stream().filter(r -> r.get("metricCode").equals("ORDERED_STORE_COUNT"))
                        .map(r -> new BigDecimal(r.get("actualValue").toString())).reduce(BigDecimal.ZERO, BigDecimal::add))
                        .isEqualByComparingTo("3");
                assertThat(rows.stream().filter(r -> r.get("metricCode").equals("YANGZHANGGUI_SALES_AMOUNT"))
                        .map(r -> new BigDecimal(r.get("actualValue").toString())).reduce(BigDecimal.ZERO, BigDecimal::add))
                        .isEqualByComparingTo("235");
                for (var row : rows) {
                    var scalar = new HashMap<>(args);
                    scalar.put(city ? "regionCode" : "ownerStaffCode", row.get("dimensionCode"));
                    boolean count = row.get("metricCode").equals("ORDERED_STORE_COUNT");
                    var old = query(jdbc, config, count ? "customerRetention" : "yangzhangguiTargetSales", scalar).getFirst();
                    Object expected = count ? old.get("orderingCustomerCount") : old.values().iterator().next();
                    assertThat(new BigDecimal(row.get("actualValue").toString()))
                            .isEqualByComparingTo(expected.toString());
                }
                args.put("regionCode", "BJ"); args.put("ownerStaffCode", "E1");
                assertThat(query(jdbc, config, "targetBusinessActuals", args))
                        .anySatisfy(r -> {
                            assertThat(r.get("metricCode")).isEqualTo("YANGZHANGGUI_SALES_AMOUNT");
                            assertThat(new BigDecimal(r.get("actualValue").toString())).isEqualByComparingTo("95");
                        });
                args.put("regionCode", "UNKNOWN");
                assertThat(query(jdbc, config, "targetBusinessActuals", args)).isEmpty();
                args.remove("regionCode"); args.remove("ownerStaffCode");
            }
        } finally { ds.destroy(); }
    }

    private static List<Map<String, Object>> query(JdbcTemplate jdbc,
            org.apache.ibatis.session.Configuration config, String statement, Map<String, Object> args) {
        var bound = config.getMappedStatement(SupplyDashboardQueryMapper.class.getName() + "." + statement)
                .getBoundSql(args);
        return jdbc.queryForList(bound.getSql(), bound.getParameterMappings().stream()
                .map(p -> args.get(p.getProperty())).toArray());
    }
}

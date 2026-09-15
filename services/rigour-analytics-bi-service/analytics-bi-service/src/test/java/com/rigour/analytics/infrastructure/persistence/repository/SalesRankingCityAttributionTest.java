package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 调动后的人员当前城市不覆盖历史订单城市，元数据查询遵循原排行的数据范围。 */
class SalesRankingCityAttributionTest {
    private final LocalDateTime from = LocalDateTime.parse("2026-01-01T00:00:00");
    private final LocalDateTime to = LocalDateTime.parse("2026-09-15T23:59:59");
    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;
    private Configuration config;

    @BeforeEach
    void setUp() {
        ds = new SingleConnectionDataSource("jdbc:h2:mem:rank_cities_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE bi_sales_order_fact (
                    tenant_id VARCHAR, owner_staff_code VARCHAR, owner_staff_name VARCHAR,
                    region_code VARCHAR, region_name VARCHAR, order_date TIMESTAMP,
                    customer_type_code VARCHAR, source_system_code VARCHAR, deleted INT,
                    order_status_code VARCHAR, payable_amount DECIMAL(18,2), paid_amount DECIMAL(18,2),
                    unpaid_amount DECIMAL(18,2), customer_id BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE bi_employee_dim (tenant_id VARCHAR, employee_code VARCHAR, city_name VARCHAR,
                    PRIMARY KEY (tenant_id, employee_code))
                """);
        jdbc.update("INSERT INTO bi_employee_dim VALUES ('T','E1','金华'),('OTHER','E1','其他租户城市')");
        jdbc.update("""
                INSERT INTO bi_sales_order_fact VALUES
                    ('T','E1','苗乐','HZ','杭州','2026-04-22','RETAIL','ORDER',0,'COMPLETED',100,80,20,1),
                    ('T','E1','苗乐','HZ','杭州','2026-06-01','RETAIL','ORDER',0,'COMPLETED',100,80,20,1),
                    ('T','E1','苗乐','JH','金华','2026-08-25','RETAIL','ORDER',0,'COMPLETED',200,120,80,2),
                    ('OTHER','E1','同编码','OTHER','其他租户城市','2026-08-25','RETAIL','ORDER',0,'COMPLETED',999,0,999,3),
                    ('T','E1','苗乐','OLD','期间外','2025-12-31','RETAIL','ORDER',0,'COMPLETED',999,0,999,3),
                    ('T','E1','苗乐','FUTURE','期间后','2026-09-16','RETAIL','ORDER',0,'COMPLETED',999,0,999,3),
                    ('T','E1','苗乐','DEL','已删除','2026-08-25','RETAIL','ORDER',1,'COMPLETED',999,0,999,3),
                    ('T','E1','苗乐','CANCEL','已取消','2026-08-25','RETAIL','ORDER',0,'CANCELLED',999,0,999,3)
                """);
        config = new Configuration();
        config.addMapper(SupplyDashboardQueryMapper.class);
    }

    @AfterEach
    void tearDown() {
        ds.destroy();
    }

    @Test
    void enrichesOverviewWithoutMovingOrMultiplyingHistoricalAmounts() {
        var mapper = mock(SupplyDashboardQueryMapper.class);
        var args = parameters();
        when(mapper.salesRanking("T", from, to, null, null, null, null))
                .thenReturn(query("salesRanking", args));
        when(mapper.salesRankingCityAttributions("T", from, to, null, null, null, null))
                .thenReturn(query("salesRankingCityAttributions", args));
        var data = new MybatisPlusSupplyDashboardRepository(mapper).overview("T",
                new SupplyDashboardFilter(from.toInstant(ZoneOffset.UTC), to.toInstant(ZoneOffset.UTC),
                        null, null, null, null, null));
        assertThat(data.salesRanking()).singleElement().satisfies(rank -> {
            assertThat(rank.currentRegionName()).isEqualTo("金华");
            assertThat(rank.orderRegionNames()).containsExactly("杭州", "金华");
            assertThat(rank.regionCode()).isEqualTo("MULTI");
            assertThat(rank.orderCount()).isEqualTo(3);
            assertThat(rank.customerCount()).isEqualTo(2);
            assertThat(rank.salesAmount()).isEqualByComparingTo("400");
            assertThat(rank.paidAmount()).isEqualByComparingTo("280");
        });
        var scoped = parameters();
        scoped.put("regionCode", "HZ");
        assertThat(query("salesRankingCityAttributions", scoped)).singleElement().satisfies(row -> {
            assertThat(row.get("currentregionname")).isEqualTo("金华");
            assertThat(row.get("orderregionname")).isEqualTo("杭州");
        });
    }

    @Test
    void honorsEveryRankingFilterAndRetainsMissingAttribution() {
        jdbc.update("""
                INSERT INTO bi_sales_order_fact VALUES
                    ('T','E2','未建档','SH','上海','2026-08-25','WHOLESALE','IMPORT',0,'COMPLETED',10,0,10,4),
                    ('T','E3','未归属',NULL,NULL,'2026-08-25','RETAIL','ORDER',0,'COMPLETED',10,0,10,5)
                """);
        var args = parameters();
        args.put("ownerStaffCode", "E2");
        args.put("customerTypeCode", "WHOLESALE");
        args.put("sourceSystemCode", "IMPORT");
        assertThat(query("salesRankingCityAttributions", args)).singleElement().satisfies(row -> {
            assertThat(row.get("dimensioncode")).isEqualTo("E2");
            assertThat(row.get("currentregionname")).isNull();
            assertThat(row.get("orderregionname")).isEqualTo("上海");
        });
        args.put("customerTypeCode", "RETAIL");
        assertThat(query("salesRankingCityAttributions", args)).isEmpty();
        args.put("customerTypeCode", "WHOLESALE");
        args.put("sourceSystemCode", "ORDER");
        assertThat(query("salesRankingCityAttributions", args)).isEmpty();
        args = parameters();
        args.put("ownerStaffCode", "E3");
        assertThat(query("salesRankingCityAttributions", args)).singleElement().satisfies(row -> {
            assertThat(row.get("currentregionname")).isNull();
            assertThat(row.get("orderregionname")).isEqualTo("未归属城市");
        });
    }

    private Map<String, Object> parameters() {
        var args = new HashMap<String, Object>();
        args.put("tenantId", "T");
        args.put("from", from);
        args.put("to", to);
        return args;
    }

    private List<Map<String, Object>> query(String method, Map<String, Object> args) {
        var bound = config.getMappedStatement(SupplyDashboardQueryMapper.class.getName() + "." + method)
                .getBoundSql(args);
        var values = bound.getParameterMappings().stream().map(p -> args.get(p.getProperty())).toArray();
        return jdbc.queryForList(bound.getSql(), values);
    }
}

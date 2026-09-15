package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL dialect regression enabled by BI_TEST_MYSQL_URL/USER/PASSWORD.
 * CTE fixtures never read or write business tables; an unconfigured run is skipped, not dialect-verified.
 */
@EnabledIfEnvironmentVariable(named = "BI_TEST_MYSQL_URL", matches = "jdbc:mysql:.*")
class SkuSalesRankingMySqlTest {
    private static final String TENANT = "sku-strict-mode-test";
    private Connection connection;
    private JdbcTemplate jdbc;
    private Configuration configuration;

    // Shadow both table names so this test is safe on a read-only MySQL account.
    private static final String FIXTURE = """
            WITH fixture (order_id, product_variant_id, sku_code, product_code, product_name, specification_snapshot) AS (
                SELECT 1, 39, 'S39', 'P39', 'Tip', 'H/M'
                UNION ALL SELECT 2, 39, 'S39', 'P39', 'Tip', 'Competitive'
                UNION ALL SELECT 3, 40, 'S40', 'P40', 'Tip', 'H/M'
                UNION ALL SELECT 4, 40, 'S40', 'P40', 'Tip', 'H/M'
                UNION ALL SELECT 5, 41, 'S41', 'P41', 'Tip', 'H'
                UNION ALL SELECT 6, 41, 'S41', 'P41', 'Chalk', 'M'
                UNION ALL SELECT 7, NULL, NULL, NULL, 'Unlinked tip', 'H'
                UNION ALL SELECT 8, NULL, NULL, NULL, 'Unlinked cloth', 'A300'
                UNION ALL SELECT 9, NULL, 'FALLBACK-SKU', 'FALLBACK-PRODUCT', 'Fallback SKU', 'M'
                UNION ALL SELECT 10, NULL, NULL, 'FALLBACK-PRODUCT', 'Fallback product', 'H'
            ), bi_sales_order_line_fact AS (
                SELECT fixture.*, 'sku-strict-mode-test' AS tenant_id, 10 AS product_category_id,
                       'Accessories' AS product_category_name, 'BJ' AS region_code,
                       'S1' AS owner_staff_code, 'T1' AS customer_type_code, 'FEISHU' AS source_system_code,
                       0 AS deleted, 'COMPLETED' AS order_status_code,
                       CAST('2026-09-12 12:00:00' AS DATETIME) AS order_date,
                       1 AS quantity, 10.25 AS line_amount, 0.25 AS discount_amount,
                       0 AS refund_amount, 10 AS sales_net_amount, 4 AS estimated_cost_amount,
                       6 AS estimated_gross_profit_amount, 1 AS cost_covered, order_id AS customer_id
                  FROM fixture
            ), bi_product_category_closure AS (
                SELECT 'sku-strict-mode-test' AS tenant_id, 1 AS ancestor_id, 10 AS descendant_id
            )
            """;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(System.getenv("BI_TEST_MYSQL_URL"),
                System.getenv("BI_TEST_MYSQL_USER"), System.getenv("BI_TEST_MYSQL_PASSWORD"));
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.setQueryTimeout(30);
        String mode = jdbc.queryForObject("SELECT @@SESSION.sql_mode", String.class);
        if (!mode.contains("ONLY_FULL_GROUP_BY")) {
            jdbc.execute("SET SESSION sql_mode = CONCAT_WS(',', @@SESSION.sql_mode, 'ONLY_FULL_GROUP_BY')");
        }
        assertThat(jdbc.queryForObject("SELECT @@SESSION.sql_mode", String.class)).contains("ONLY_FULL_GROUP_BY");
        configuration = new Configuration();
        configuration.addMapper(SupplyDashboardQueryMapper.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    @Test
    void strictGroupingPreservesCombinedSpecificationAndFlagsConflictingSnapshots() {
        var rows = ranking(Map.of());
        assertThat(rows).hasSize(6);
        assertThat(row(rows, "39").get("dimensionName")).isEqualTo("Tip / 规格快照不一致（待核对）");
        assertThat(row(rows, "40").get("dimensionName")).isEqualTo("Tip / H/M");
        assertThat(row(rows, "41").get("dimensionName")).isEqualTo("商品名称快照不一致（待核对）");
        for (String code : List.of("39", "40", "41")) {
            var item = row(rows, code);
            assertThat(decimal(item, "salesAmount")).isEqualByComparingTo("20.50");
            assertThat(decimal(item, "salesNetAmount")).isEqualByComparingTo("20");
            assertThat(decimal(item, "estimatedGrossProfitRate")).isEqualByComparingTo("60");
            assertThat(decimal(item, "costCoverageRate")).isEqualByComparingTo("100");
            assertThat(((Number) item.get("orderCount")).intValue()).isEqualTo(2);
        }
    }

    @Test
    void strictGroupingKeepsUnknownAndFallbackKeysWithoutLosingSales() {
        var rows = ranking(Map.of());
        assertThat(row(rows, "UNKNOWN").get("dimensionName")).isEqualTo("商品/SKU关联待核对");
        assertThat(decimal(row(rows, "UNKNOWN"), "salesAmount")).isEqualByComparingTo("20.50");
        assertThat(row(rows, "FALLBACK-SKU").get("dimensionName")).isEqualTo("Fallback SKU / M");
        assertThat(row(rows, "FALLBACK-PRODUCT").get("dimensionName")).isEqualTo("Fallback product / H");
        assertThat(rows.stream().map(item -> decimal(item, "salesAmount")).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("102.50");
    }

    @Test
    void strictGroupingWorksWithEveryFilterAndCategoryDescendants() {
        Map<String, Object> filters = Map.of("regionCode", "BJ", "ownerStaffCode", "S1",
                "customerTypeCode", "T1", "productCategoryId", 1L, "sourceSystemCode", "FEISHU");
        assertThat(ranking(filters)).containsExactlyInAnyOrderElementsOf(ranking(Map.of()));
        assertThat(ranking(Map.of("productCategoryId", 10L))).hasSize(6);
        assertThat(ranking(Map.of("productCategoryId", 99L))).isEmpty();
        for (String filter : List.of("tenantId", "regionCode", "ownerStaffCode", "customerTypeCode", "sourceSystemCode")) {
            assertThat(ranking(Map.of(filter, "other"))).as(filter).isEmpty();
        }
        assertThat(ranking(Map.of("from", LocalDateTime.of(2026, 9, 13, 0, 0)))).isEmpty();
    }

    private List<Map<String, Object>> ranking(Map<String, Object> filters) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", TENANT);
        parameters.put("from", LocalDateTime.of(2026, 9, 1, 0, 0));
        parameters.put("to", LocalDateTime.of(2026, 9, 30, 23, 59));
        parameters.putAll(filters);
        var bound = configuration.getMappedStatement(SupplyDashboardQueryMapper.class.getName() + ".skuSalesRanking")
                .getBoundSql(parameters);
        Object[] args = bound.getParameterMappings().stream().map(item -> parameters.get(item.getProperty())).toArray();
        return jdbc.queryForList(FIXTURE + bound.getSql(), args);
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String code) {
        return rows.stream().filter(item -> code.equals(item.get("dimensionCode"))).findFirst().orElseThrow();
    }

    private static BigDecimal decimal(Map<String, Object> row, String field) {
        return new BigDecimal(row.get(field).toString());
    }
}

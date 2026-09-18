package com.rigour.analytics.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.analytics.application.model.ProductCategoryHierarchy;
import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 在隔离ERP/BI测试Schema执行真实刷新链，不写共享DEV业务数据。 */
class ProductCategoryProjectionTest {
    private SqlSession session;
    private JdbcTemplate jdbc;
    private SupplyDashboardQueryMapper mapper;
    private MybatisPlusSupplyDashboardRepository repository;
    private static final String TENANT = "tenant-category-test";
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");

    @BeforeEach
    void setUp() {
        var source =
                new UnpooledDataSource(
                        "org.h2.Driver",
                        "jdbc:h2:mem:categories_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "");
        var config =
                new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        config.addMapper(SupplyDashboardQueryMapper.class);
        session = new SqlSessionFactoryBuilder().build(config).openSession();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(session.getConnection(), true));
        mapper = session.getMapper(SupplyDashboardQueryMapper.class);
        repository = new MybatisPlusSupplyDashboardRepository(mapper);

        jdbc.execute(
                "CREATE TABLE bi_source_erp_erp_product_category (tenant_id VARCHAR(64), id BIGINT,"
                    + " category_code VARCHAR(64), category_name VARCHAR(160), parent_id BIGINT,"
                    + " category_level INT, ordinal INT, deleted INT, updated_time DATETIME(6))");
        jdbc.execute(
                "CREATE TABLE bi_source_erp_erp_product_brand (tenant_id VARCHAR(64), id BIGINT,"
                        + " brand_code VARCHAR(64), brand_name VARCHAR(160), updated_time"
                        + " DATETIME(6))");
        jdbc.execute(
                "CREATE TABLE bi_source_erp_erp_product (tenant_id VARCHAR(64), id BIGINT,"
                    + " product_code VARCHAR(64), product_name VARCHAR(160), category_id BIGINT,"
                    + " brand_id BIGINT, shelf_status_code VARCHAR(64), submit_status_code"
                    + " VARCHAR(64), deleted INT, updated_time DATETIME(6))");
        jdbc.execute(
                "CREATE TABLE bi_product_category_dim (tenant_id VARCHAR(64), category_id BIGINT,"
                    + " category_code VARCHAR(64), category_name VARCHAR(160), parent_id BIGINT,"
                    + " category_level INT, ordinal INT, deleted INT, source_updated_time"
                    + " DATETIME(6), synced_time DATETIME(6), PRIMARY KEY(tenant_id,"
                    + " category_id))");
        jdbc.execute(
                "CREATE TABLE bi_product_category_closure (tenant_id VARCHAR(64), ancestor_id"
                        + " BIGINT, descendant_id BIGINT, depth INT, PRIMARY KEY(tenant_id,"
                        + " ancestor_id, descendant_id))");
        jdbc.execute(
                "CREATE TABLE bi_product_dim (tenant_id VARCHAR(64), product_id BIGINT,"
                    + " product_code VARCHAR(64), product_name VARCHAR(160), product_category_id"
                    + " BIGINT, product_category_code VARCHAR(64), product_category_name"
                    + " VARCHAR(160), brand_id BIGINT, brand_code VARCHAR(64), brand_name"
                    + " VARCHAR(160), shelf_status_code VARCHAR(64), submit_status_code"
                    + " VARCHAR(64), source_updated_time DATETIME(6), synced_time DATETIME(6),"
                    + " deleted INT, created_time DATETIME(6), updated_time DATETIME(6), PRIMARY"
                    + " KEY(tenant_id, product_id))");
        jdbc.execute(
                """
CREATE TABLE bi_sales_order_line_fact (tenant_id VARCHAR(64), product_id BIGINT, product_category_id BIGINT,
product_category_code VARCHAR(64), product_category_name VARCHAR(160), brand_id BIGINT, brand_code VARCHAR(64), brand_name VARCHAR(160),
region_code VARCHAR(64), owner_staff_code VARCHAR(64), customer_type_code VARCHAR(64), source_system_code VARCHAR(64),
deleted INT DEFAULT 0, order_status_code VARCHAR(64) DEFAULT 'COMPLETED', order_date DATETIME(6),
quantity DECIMAL(24,6) DEFAULT 1, line_amount DECIMAL(24,6) DEFAULT 100,
discount_amount DECIMAL(24,6) DEFAULT 0, refund_amount DECIMAL(24,6) DEFAULT 0,
sales_net_amount DECIMAL(24,6) DEFAULT 100, estimated_cost_amount DECIMAL(24,6) DEFAULT 0,
estimated_gross_profit_amount DECIMAL(24,6) DEFAULT 0, cost_covered INT DEFAULT 0, order_id BIGINT, customer_id BIGINT)
""");
        jdbc.execute(
                "CREATE TABLE bi_inventory_balance_current (tenant_id VARCHAR(64), product_id"
                        + " BIGINT, product_category_id BIGINT, deleted INT DEFAULT 0)");
        jdbc.execute(
                "CREATE TABLE bi_inventory_operation_fact (tenant_id VARCHAR(64), product_id"
                        + " BIGINT, product_category_id BIGINT, product_category_code VARCHAR(64),"
                        + " product_category_name VARCHAR(160))");
        category(1, null, "Parent", 1);
        category(10, 1L, "Child", 2);
        category(20, 1L, "No sales", 2);
        category(30, 20L, "Grandchild", 3);
        jdbc.update(
                "INSERT INTO bi_source_erp_erp_product VALUES"
                        + " (?,100,'P100','Product',10,NULL,'ON_SHELF','SUBMITTED',0,?)",
                TENANT,
                local(NOW.minusSeconds(60)));
        jdbc.update(
                "INSERT INTO bi_sales_order_line_fact"
                    + " (tenant_id,product_id,product_category_id,order_date,order_id,customer_id)"
                    + " VALUES (?,100,999,?,1,1)",
                TENANT,
                local(NOW.minusSeconds(30)));
        jdbc.update("INSERT INTO bi_inventory_balance_current VALUES (?,100,999,0)", TENANT);
        jdbc.update(
                "INSERT INTO bi_inventory_operation_fact VALUES (?,100,999,'OLD','Old')", TENANT);
    }

    @AfterEach
    void tearDown() {
        session.close();
    }

    @Test
    void refreshProjectsFullErpTreeAndParentQueriesIncludeChildrenWithoutDoubleCounting() {
        repository.refreshProductDim(TENANT, NOW.minusSeconds(3600), NOW, NOW);
        assertThat(mapper.productCategoryOptions(TENANT)).hasSize(4);
        assertThat(mapper.productCategoryOptions(TENANT))
                .extracting(row -> value(row, "optionValue"))
                .containsExactlyInAnyOrder("1", "10", "20", "30");
        assertThat(
                        jdbc.queryForList(
                                "SELECT descendant_id FROM bi_product_category_closure WHERE"
                                        + " tenant_id=? AND ancestor_id=1",
                                Long.class,
                                TENANT))
                .containsExactlyInAnyOrder(1L, 10L, 20L, 30L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT product_category_id FROM bi_sales_order_line_fact",
                                Long.class))
                .isEqualTo(10L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT product_category_id FROM bi_inventory_balance_current",
                                Long.class))
                .isEqualTo(10L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT product_category_id FROM bi_inventory_operation_fact",
                                Long.class))
                .isEqualTo(10L);
        var chart =
                mapper.categorySalesRanking(
                        TENANT,
                        local(NOW.minusSeconds(3600)),
                        local(NOW),
                        null,
                        null,
                        null,
                        1L,
                        null);
        assertThat(chart).hasSize(2);
        assertThat(chart)
                .anySatisfy(
                        row -> {
                            assertThat(value(row, "dimensionName")).isEqualTo("Grandchild");
                            assertThat(value(row, "dimensionCode")).isEqualTo("30");
                            assertThat(value(row, "salesAmount").toString()).startsWith("0");
                        });
    }

    @Test
    void erpReclassificationAndParentMoveRepairOldSnapshotsOnNextRefresh() {
        repository.refreshProductDim(TENANT, NOW.minusSeconds(3600), NOW, NOW);
        jdbc.update(
                "UPDATE bi_source_erp_erp_product SET category_id=30,updated_time=? WHERE id=100",
                local(NOW.plusSeconds(1)));
        jdbc.update(
                "UPDATE bi_source_erp_erp_product_category SET"
                        + " category_name='Renamed',parent_id=NULL,updated_time=? WHERE id=20",
                local(NOW.plusSeconds(1)));
        repository.refreshProductDim(TENANT, NOW, NOW.plusSeconds(10), NOW.plusSeconds(10));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT product_category_id FROM bi_sales_order_line_fact",
                                Long.class))
                .isEqualTo(30L);
        assertThat(
                        jdbc.queryForList(
                                "SELECT descendant_id FROM bi_product_category_closure WHERE"
                                        + " tenant_id=? AND ancestor_id=1",
                                Long.class,
                                TENANT))
                .containsExactlyInAnyOrder(1L, 10L);
        assertThat(
                        jdbc.queryForList(
                                "SELECT descendant_id FROM bi_product_category_closure WHERE"
                                        + " tenant_id=? AND ancestor_id=20",
                                Long.class,
                                TENANT))
                .containsExactlyInAnyOrder(20L, 30L);
        assertThat(
                        mapper.categorySalesRanking(
                                TENANT,
                                local(NOW.minusSeconds(3600)),
                                local(NOW.plusSeconds(10)),
                                null,
                                null,
                                null,
                                1L,
                                null))
                .allSatisfy(
                        row -> assertThat(value(row, "salesAmount").toString()).startsWith("0"));
    }

    @Test
    void hierarchyRejectsCyclesAndMissingParents() {
        assertThatThrownBy(
                        () ->
                                ProductCategoryHierarchy.closure(
                                        List.of(new ProductCategoryHierarchy.Node(1L, 2L))))
                .hasMessageContaining("父级");
        assertThatThrownBy(
                        () ->
                                ProductCategoryHierarchy.closure(
                                        List.of(
                                                new ProductCategoryHierarchy.Node(1L, 2L),
                                                new ProductCategoryHierarchy.Node(2L, 1L))))
                .hasMessageContaining("环");
    }

    @Test
    void productRankingRetainsNullMissingAndRetiredProductKeysWithoutMatchingNames() {
        refresh();
        jdbc.update(
                "INSERT INTO bi_source_erp_erp_product VALUES"
                        + " (?,101,'P101','Product',10,NULL,'ON_SHELF','SUBMITTED',0,?)",
                TENANT,
                local(NOW.minusSeconds(60)));
        refresh();
        jdbc.update(
                "UPDATE bi_product_dim SET deleted=1 WHERE tenant_id=? AND product_id=101", TENANT);
        fact(2, null, null, "60");
        fact(2, null, null, "40");
        fact(3, 999L, null, "30");
        fact(4, 101L, 10L, "20");
        var rows = products(null, null, null, null, null);
        assertThat(rows).hasSize(2);
        assertThat(amount(rows)).isEqualByComparingTo("250");
        var unresolved = row(rows, "UNKNOWN");
        assertThat(value(unresolved, "dimensionName")).isEqualTo("商品关联待核对");
        assertThat(decimal(unresolved, "salesAmount")).isEqualByComparingTo("150");
        assertThat(((Number) value(unresolved, "orderCount")).longValue()).isEqualTo(3);
        assertThat(amount(categories(null))).isEqualByComparingTo("250");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bi_sales_order_line_fact WHERE product_id IS"
                                        + " NULL",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void unresolvedProductRowsRespectEveryFilterAndNeverCrossTenant() {
        refresh();
        fact(2, null, null, "7");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET"
                    + " region_code='BJ',owner_staff_code='S1',customer_type_code='T1',source_system_code='FEISHU'");
        for (int i = 3; i <= 10; i++) fact(i, null, null, "1000");
        jdbc.update("UPDATE bi_sales_order_line_fact SET region_code='SH' WHERE order_id=3");
        jdbc.update("UPDATE bi_sales_order_line_fact SET owner_staff_code='S2' WHERE order_id=4");
        jdbc.update("UPDATE bi_sales_order_line_fact SET customer_type_code='T2' WHERE order_id=5");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET source_system_code='MANUAL' WHERE order_id=6");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET order_status_code='CANCELLED' WHERE"
                        + " order_id=7");
        jdbc.update("UPDATE bi_sales_order_line_fact SET deleted=1 WHERE order_id=8");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET order_date=? WHERE order_id=9",
                local(NOW.plusSeconds(1)));
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET tenant_id='other-tenant' WHERE order_id=10");
        var rows = products(null, "BJ", "S1", "T1", "FEISHU");
        assertThat(amount(rows)).isEqualByComparingTo("107");
        assertThat(decimal(row(rows, "UNKNOWN"), "salesAmount")).isEqualByComparingTo("7");
        var categoryRows =
                mapper.categorySalesRanking(
                        TENANT,
                        local(NOW.minusSeconds(3600)),
                        local(NOW),
                        "BJ",
                        "S1",
                        "T1",
                        null,
                        "FEISHU");
        assertThat(amount(categoryRows)).isEqualByComparingTo("107");
        assertThat(products(null, "EMPTY", null, null, null))
                .noneSatisfy(item -> assertThat(value(item, "dimensionCode")).isEqualTo("UNKNOWN"));
    }

    @Test
    void categoryRankingUsesCurrentErpProjectionBeforeStaleLineSnapshots() {
        refresh();
        jdbc.update(
                "UPDATE bi_product_dim SET"
                    + " product_category_id=30,product_category_code='C30',product_category_name='Grandchild'"
                    + " WHERE tenant_id=? AND product_id=100",
                TENANT);
        var rows = categories(null);
        assertThat(decimal(row(rows, "30"), "salesAmount")).isEqualByComparingTo("100");
        assertThat(decimal(row(rows, "10"), "salesAmount")).isZero();
        assertThat(amount(categories(20L))).isEqualByComparingTo("100");
        assertThat(amount(products(20L, null, null, null, null))).isEqualByComparingTo("100");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT product_category_id FROM bi_sales_order_line_fact WHERE"
                                        + " order_id=1",
                                Long.class))
                .isEqualTo(10L);
    }

    @Test
    void removedErpClassificationStaysUnresolvedInsteadOfRevivingOldClassification() {
        refresh();
        jdbc.update(
                "UPDATE bi_product_dim SET"
                    + " product_category_id=NULL,product_category_code=NULL,product_category_name=NULL"
                    + " WHERE tenant_id=? AND product_id=100",
                TENANT);
        var rows = categories(null);
        assertThat(decimal(row(rows, "0"), "salesAmount")).isEqualByComparingTo("100");
        assertThat(decimal(row(rows, "10"), "salesAmount")).isZero();
        assertThat(amount(categories(1L))).isZero();
        assertThat(products(1L, null, null, null, null)).isEmpty();
    }

    @Test
    void directHistoricalParentSalesRemainEvenWithoutAnyActiveProductInThatParent() {
        refresh();
        fact(2, null, 1L, "50");
        var rows = categories(1L);
        assertThat(decimal(row(rows, "1"), "salesAmount")).isEqualByComparingTo("50");
        assertThat(decimal(row(rows, "10"), "salesAmount")).isEqualByComparingTo("100");
        assertThat(amount(rows)).isEqualByComparingTo("150");
        var products = products(1L, null, null, null, null);
        assertThat(amount(products)).isEqualByComparingTo("150");
        assertThat(decimal(row(products, "UNKNOWN"), "salesAmount")).isEqualByComparingTo("50");
    }

    @Test
    void deletedAndUnknownCategoriesKeepTheirAmountsWithoutInventingParentAssociation() {
        refresh();
        fact(2, null, 999L, "20");
        fact(3, null, 998L, "30");
        jdbc.update(
                "UPDATE bi_product_category_dim SET deleted=1 WHERE tenant_id=? AND category_id=10",
                TENANT);
        var rows = categories(null);
        assertThat(amount(rows)).isEqualByComparingTo("150");
        assertThat(value(row(rows, "10"), "dimensionName")).isEqualTo("分类关联待核对");
        assertThat(value(row(rows, "999"), "dimensionName")).isEqualTo("分类关联待核对");
        assertThat(value(row(rows, "998"), "dimensionName")).isEqualTo("分类关联待核对");
    }

    @Test
    void productRankingDoesNotDiscardMoneyBeyondFiveHundredProductGroups() {
        refresh();
        for (int i = 1000; i < 1501; i++) {
            jdbc.update(
                    "INSERT INTO bi_product_dim"
                        + " (tenant_id,product_id,product_code,product_name,product_category_id,deleted)"
                        + " VALUES (?,?,?, ?,10,0)",
                    TENANT,
                    i,
                    "P" + i,
                    "Product " + i);
            fact(i, (long) i, 10L, "1");
        }
        fact(2000, null, null, "0.01");
        var rows = products(null, null, null, null, null);
        assertThat(rows).hasSize(503);
        assertThat(amount(rows)).isEqualByComparingTo("601.01");
        assertThat(decimal(row(rows, "UNKNOWN"), "salesAmount")).isEqualByComparingTo("0.01");
    }

    @Test
    void skuRankingReturnsMoreThanEightyGroupsWithoutChangingAmountsOrScope() {
        refresh();
        prepareSkuFacts();
        for (int id = 1; id <= 92; id++) {
            fact(id, 100L, 10L, "1.25");
            jdbc.update(
                    "UPDATE bi_sales_order_line_fact SET product_variant_id=?,product_name=? WHERE"
                            + " order_id=?",
                    id,
                    "Product " + id,
                    id);
        }
        jdbc.update("UPDATE bi_sales_order_line_fact SET region_code='SH' WHERE order_id=83");
        jdbc.update("UPDATE bi_sales_order_line_fact SET owner_staff_code='S2' WHERE order_id=84");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET customer_type_code='T2' WHERE order_id=85");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET source_system_code='MANUAL' WHERE"
                        + " order_id=86");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET order_status_code='CANCELLED' WHERE"
                        + " order_id=87");
        jdbc.update("UPDATE bi_sales_order_line_fact SET deleted=1 WHERE order_id=88");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET order_date=? WHERE order_id=89",
                local(NOW.plusNanos(1000)));
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET order_date=? WHERE order_id=90",
                local(NOW.minusSeconds(3600).minusNanos(1000)));
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET tenant_id='other-tenant' WHERE order_id=91");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET product_category_id=999 WHERE order_id=92");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET order_date=? WHERE order_id=1",
                local(NOW.minusSeconds(3600)));
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET order_date=? WHERE order_id=82", local(NOW));

        var rows = skus(1L);

        assertThat(rows).hasSize(82);
        assertThat(rows)
                .extracting(row -> value(row, "dimensionCode").toString())
                .doesNotHaveDuplicates();
        for (int id = 1; id <= 82; id++) {
            assertThat(decimal(row(rows, String.valueOf(id)), "salesAmount"))
                    .isEqualByComparingTo("1.25");
        }
        assertThat(amount(rows)).isEqualByComparingTo("102.50");
    }

    @Test
    void unknownSkuGroupNeverCombinesNamesAndSpecificationsFromUnrelatedProducts() {
        prepareSkuFacts();
        fact(1, null, null, "10.123456");
        fact(2, null, null, "20");
        fact(3, null, null, "-0.123456");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET"
                        + " product_name='Noodles',specification_snapshot='Box' WHERE order_id=1");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET"
                        + " product_name='Cloth',specification_snapshot='Z-type' WHERE order_id=2");

        var rows = skus(null);

        assertThat(rows).hasSize(1);
        var unknown = row(rows, "UNKNOWN");
        assertThat(value(unknown, "dimensionName")).isEqualTo("商品/SKU关联待核对");
        assertThat(decimal(unknown, "salesAmount")).isEqualByComparingTo("30");
        assertThat(((Number) value(unknown, "orderCount")).longValue()).isEqualTo(3);
    }

    @Test
    void conflictingSkuSnapshotsAreExplicitAndConsistentHMIsNotSplitOrReplaced() {
        prepareSkuFacts();
        for (int id = 1; id <= 6; id++) fact(id, 100L, 10L, "10");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET"
                    + " product_variant_id=39,product_name='Tip',specification_snapshot='H/M' WHERE"
                    + " order_id IN (1,2)");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET specification_snapshot='Competitive' WHERE"
                        + " order_id=2");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET"
                    + " product_variant_id=40,product_name='Tip',specification_snapshot='H/M' WHERE"
                    + " order_id IN (3,4)");
        jdbc.update(
                "UPDATE bi_sales_order_line_fact SET"
                    + " product_variant_id=41,product_name='Tip',specification_snapshot='H' WHERE"
                    + " order_id IN (5,6)");
        jdbc.update("UPDATE bi_sales_order_line_fact SET product_name='Chalk' WHERE order_id=6");

        var rows = skus(null);

        assertThat(rows).hasSize(3);
        assertThat(value(row(rows, "39"), "dimensionName")).isEqualTo("Tip / 规格快照不一致（待核对）");
        assertThat(value(row(rows, "40"), "dimensionName")).isEqualTo("Tip / H/M");
        assertThat(value(row(rows, "41"), "dimensionName")).isEqualTo("商品名称快照不一致（待核对）");
        assertThat(rows)
                .allSatisfy(
                        row -> {
                            assertThat(decimal(row, "salesAmount")).isEqualByComparingTo("20");
                            assertThat(((Number) value(row, "orderCount")).longValue())
                                    .isEqualTo(2);
                        });
    }

    private void prepareSkuFacts() {
        jdbc.execute(
                "ALTER TABLE bi_sales_order_line_fact ADD (product_variant_id BIGINT, sku_code"
                        + " VARCHAR(64), product_code VARCHAR(64), product_name VARCHAR(160),"
                        + " specification_snapshot VARCHAR(160))");
        jdbc.update("DELETE FROM bi_sales_order_line_fact");
    }

    private List<Map<String, Object>> skus(Long category) {
        var parameters =
                new HashMap<String, Object>(
                        Map.of(
                                "tenantId",
                                TENANT,
                                "from",
                                local(NOW.minusSeconds(3600)),
                                "to",
                                local(NOW),
                                "regionCode",
                                "BJ",
                                "ownerStaffCode",
                                "S1",
                                "customerTypeCode",
                                "T1",
                                "sourceSystemCode",
                                "FEISHU"));
        parameters.put("productCategoryId", category);
        var bound =
                session.getConfiguration()
                        .getMappedStatement(
                                SupplyDashboardQueryMapper.class.getName() + ".skuSalesRanking")
                        .getBoundSql(parameters);
        // H2 treats an unsized CHAR cast as CHAR(1); MySQL preserves the complete identifier.
        String sql = bound.getSql().replace(" AS CHAR)", " AS VARCHAR)");
        Object[] args =
                bound.getParameterMappings().stream()
                        .map(mapping -> parameters.get(mapping.getProperty()))
                        .toArray();
        return jdbc.queryForList(sql, args);
    }

    private void refresh() {
        repository.refreshProductDim(TENANT, NOW.minusSeconds(3600), NOW, NOW);
    }

    private void fact(long order, Long product, Long category, String amount) {
        jdbc.update(
                """
INSERT INTO bi_sales_order_line_fact
    (tenant_id,product_id,product_category_id,order_date,order_id,customer_id,line_amount,sales_net_amount,
     region_code,owner_staff_code,customer_type_code,source_system_code)
VALUES (?,?,?,?,?,1,?,?,'BJ','S1','T1','FEISHU')
""",
                TENANT,
                product,
                category,
                local(NOW.minusSeconds(30)),
                order,
                new BigDecimal(amount),
                new BigDecimal(amount));
    }

    private List<Map<String, Object>> products(
            Long category, String city, String owner, String type, String source) {
        return mapper.productSalesRanking(
                TENANT,
                local(NOW.minusSeconds(3600)),
                local(NOW),
                city,
                owner,
                type,
                category,
                source);
    }

    private List<Map<String, Object>> categories(Long category) {
        return mapper.categorySalesRanking(
                TENANT,
                local(NOW.minusSeconds(3600)),
                local(NOW),
                null,
                null,
                null,
                category,
                null);
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String code) {
        return rows.stream()
                .filter(row -> code.equals(value(row, "dimensionCode").toString()))
                .findFirst()
                .orElseThrow();
    }

    private static BigDecimal amount(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> decimal(row, "salesAmount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        return new BigDecimal(value(row, key).toString());
    }

    private void category(long id, Long parent, String name, int level) {
        jdbc.update(
                "INSERT INTO bi_source_erp_erp_product_category VALUES (?,?,?,?,?,?,0,0,?)",
                TENANT,
                id,
                "C" + id,
                name,
                parent,
                level,
                local(NOW.minusSeconds(60)));
    }

    private static LocalDateTime local(Instant time) {
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    private static Object value(java.util.Map<String, Object> row, String key) {
        return row.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }
}

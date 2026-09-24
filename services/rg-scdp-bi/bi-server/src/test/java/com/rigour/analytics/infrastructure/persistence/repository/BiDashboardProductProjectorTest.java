package com.rigour.analytics.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.rigour.analytics.infrastructure.persistence.scope.BiDashboardProductProjector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

class BiDashboardProductProjectorTest {
    JdbcTemplate jdbc;
    BiDashboardProductProjector projector;
    TransactionTemplate transaction;
    static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        projector = new BiDashboardProductProjector(jdbc);
        jdbc.execute(
                "CREATE TABLE bi_sales_order_fact(tenant_id VARCHAR(64),order_id BIGINT,customer_id"
                    + " BIGINT,order_date DATETIME(6) DEFAULT '2026-08-01',deleted INT DEFAULT"
                    + " 0,order_status_code VARCHAR(64) DEFAULT 'COMPLETED',payable_amount"
                    + " DECIMAL(24,6),paid_amount DECIMAL(24,6))");
        jdbc.execute(
                "CREATE TABLE bi_sales_order_line_fact(tenant_id VARCHAR(64),order_id"
                    + " BIGINT,order_line_id BIGINT,deleted INT DEFAULT 0,product_id"
                    + " BIGINT,product_variant_id BIGINT,product_category_id BIGINT,product_name"
                    + " VARCHAR(200),product_category_name VARCHAR(120),sku_code"
                    + " VARCHAR(50),specification_snapshot VARCHAR(500),unit_price"
                    + " DECIMAL(24,6),quantity DECIMAL(24,6))");
        jdbc.execute(
                "CREATE TABLE bi_sales_payment_fact(tenant_id VARCHAR(64),payment_id"
                        + " BIGINT,order_id BIGINT,region_code VARCHAR(64),deleted INT DEFAULT"
                        + " 0,payment_time DATETIME(6),paid_amount DECIMAL(24,6))");
        jdbc.execute(
                "CREATE TABLE bi_dashboard_customer_history(tenant_id VARCHAR(64),customer_id"
                    + " BIGINT,first_order_date DATETIME(6),synced_time DATETIME(6),PRIMARY"
                    + " KEY(tenant_id,customer_id))");
        String ddl =
                Files.readString(
                        Path.of(
                                "src/main/resources/db/migration/V22__bi_dashboard_product_allocations.sql"));
        ddl = ddl.substring(ddl.indexOf("CREATE TABLE"));
        ddl = ddl.replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci", "");
        for (String sql : ddl.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        jdbc.update(
                "INSERT INTO bi_sales_order_fact(tenant_id,order_id,payable_amount,paid_amount)"
                        + " VALUES('T',1,10,5),('T',2,20,0),('OTHER',1,900,800)");
        for (int i = 1; i <= 3; i++)
            jdbc.update(
                    "INSERT INTO"
                        + " bi_sales_order_line_fact(tenant_id,order_id,order_line_id,product_id,unit_price,quantity)"
                        + " VALUES('T',1,?,?,1,1)",
                    i,
                    i);
        jdbc.update(
                "INSERT INTO bi_sales_payment_fact VALUES('T',1,1,'BJ',0,'2026-09-15"
                        + " 00:00:00',5),('T',2,99,'BJ',0,'2026-09-16 00:00:00',7)");
    }

    void refresh(long run) {
        transaction.executeWithoutResult(s -> projector.refresh("T", run, NOW));
    }

    java.math.BigDecimal amount(String sql) {
        return jdbc.queryForObject(sql, java.math.BigDecimal.class);
    }

    @Test
    void preservesDiscountedTotalsAndLateReceiptsWithExactRounding() {
        refresh(1);
        assertThat(
                        amount(
                                "SELECT SUM(sales_amount) FROM bi_dashboard_order_product WHERE"
                                        + " order_id=1"))
                .isEqualByComparingTo("10");
        assertThat(
                        amount(
                                "SELECT SUM(cohort_paid_amount) FROM bi_dashboard_order_product"
                                        + " WHERE order_id=1"))
                .isEqualByComparingTo("5");
        assertThat(
                        amount(
                                "SELECT SUM(allocated_amount) FROM bi_dashboard_payment_product"
                                        + " WHERE payment_time>='2026-09-01' AND order_id=1"))
                .isEqualByComparingTo("5");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bi_dashboard_order_product WHERE"
                                        + " tenant_id='OTHER'",
                                Integer.class))
                .isZero();
        assertThat(
                        amount(
                                "SELECT SUM(allocated_amount) FROM bi_dashboard_payment_product"
                                        + " WHERE allocation_status='UNALLOCATABLE'"))
                .isEqualByComparingTo("7");
    }

    @Test
    void replayIsIdempotentAndHistoricalCorrectionDeletionRebuildsAllocations() {
        refresh(1);
        refresh(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bi_dashboard_order_product", Integer.class))
                .isEqualTo(4);
        jdbc.update(
                "UPDATE bi_sales_order_fact SET payable_amount=12,paid_amount=6 WHERE tenant_id='T'"
                        + " AND order_id=1");
        jdbc.update(
                "UPDATE bi_sales_payment_fact SET paid_amount=6 WHERE tenant_id='T' AND"
                        + " payment_id=1");
        jdbc.update("UPDATE bi_sales_order_line_fact SET deleted=1 WHERE order_line_id=3");
        refresh(3);
        assertThat(
                        amount(
                                "SELECT SUM(sales_amount) FROM bi_dashboard_order_product WHERE"
                                        + " order_id=1"))
                .isEqualByComparingTo("12");
        assertThat(
                        amount(
                                "SELECT SUM(allocated_amount) FROM bi_dashboard_payment_product"
                                        + " WHERE order_id=1"))
                .isEqualByComparingTo("6");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bi_dashboard_order_product WHERE order_id=1",
                                Integer.class))
                .isEqualTo(2);
        jdbc.update("UPDATE bi_sales_payment_fact SET deleted=1 WHERE payment_id=1");
        refresh(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bi_dashboard_payment_product WHERE"
                                        + " payment_id=1",
                                Integer.class))
                .isZero();
    }

    @Test
    void zeroDenominatorAndCancelledOrderRemainUnallocatableWithoutInventingProducts() {
        jdbc.update("UPDATE bi_sales_order_line_fact SET unit_price=0");
        refresh(1);
        assertThat(
                        amount(
                                "SELECT SUM(sales_amount) FROM bi_dashboard_order_product WHERE"
                                        + " allocation_status='UNALLOCATABLE'"))
                .isEqualByComparingTo("30");
        jdbc.update(
                "UPDATE bi_sales_order_fact SET order_status_code='CANCELLED' WHERE order_id=1");
        refresh(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bi_dashboard_order_product WHERE order_id=1",
                                Integer.class))
                .isZero();
        assertThat(
                        amount(
                                "SELECT SUM(allocated_amount) FROM bi_dashboard_payment_product"
                                        + " WHERE allocation_status='UNALLOCATABLE'"))
                .isEqualByComparingTo("12");
    }

    @Test
    void retiresPhysicalDeletesOnlyAfterCompleteSourceMirrorAndOnlyInCurrentTenant() {
        jdbc.execute(
                "CREATE TABLE bi_customer_dim(tenant_id VARCHAR(64),customer_id BIGINT,deleted INT"
                        + " DEFAULT 0)");
        for (String name :
                java.util.List.of(
                        "bi_source_crm_crm_customer",
                        "bi_source_order_order_sales_order",
                        "bi_source_order_order_sales_order_line",
                        "bi_source_order_order_payment_record"))
            jdbc.execute("CREATE TABLE " + name + "(tenant_id VARCHAR(64),id BIGINT)");
        jdbc.update("INSERT INTO bi_source_order_order_sales_order VALUES('T',1)");
        projector.retireMissingFacts("T");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT deleted FROM bi_sales_order_fact WHERE tenant_id='T' AND"
                                        + " order_id=2",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT deleted FROM bi_sales_order_fact WHERE tenant_id='T' AND"
                                        + " order_id=1",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT deleted FROM bi_sales_order_fact WHERE tenant_id='OTHER'",
                                Integer.class))
                .isZero();
    }

    @Test
    void failedPublicationRollsBackAndKeepsPreviousSnapshot() {
        refresh(1);
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        s -> {
                                            projector.refresh("T", 2, NOW);
                                            throw new IllegalStateException("fail");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT run_id FROM bi_dashboard_product_snapshot", Long.class))
                .isEqualTo(1);
        assertThat(amount("SELECT SUM(allocated_amount) FROM bi_dashboard_payment_product"))
                .isEqualByComparingTo("12");
    }
}

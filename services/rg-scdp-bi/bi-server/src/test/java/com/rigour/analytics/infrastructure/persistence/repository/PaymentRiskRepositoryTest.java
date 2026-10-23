package com.rigour.analytics.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PaymentRiskRepositoryTest {
    JdbcTemplate jdbc;
    JdbcPaymentRiskStore store;
    final Instant from = Instant.parse("2026-09-01T00:00:00Z");
    final Instant to = Instant.parse("2026-10-20T00:00:00Z");

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        store = new JdbcPaymentRiskStore(ds);
        jdbc.execute("""
                CREATE TABLE bi_sales_order_fact(
                tenant_id VARCHAR,order_id BIGINT,order_no VARCHAR,customer_name VARCHAR,
                region_code VARCHAR,region_name VARCHAR,owner_staff_code VARCHAR,owner_staff_name VARCHAR,
                order_date TIMESTAMP,order_status_code VARCHAR,deleted INT,
                payable_amount DECIMAL(24,6),paid_amount DECIMAL(24,6),synced_time TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE bi_sales_order_line_fact(
                tenant_id VARCHAR,order_id BIGINT,order_line_id BIGINT,product_id BIGINT,
                product_name VARCHAR,product_category_id BIGINT,product_category_name VARCHAR,deleted INT,line_amount DECIMAL(24,6))
                """);
        jdbc.execute("""
                CREATE TABLE bi_product_dim(tenant_id VARCHAR,product_id BIGINT,
                product_category_id BIGINT,product_category_name VARCHAR)
                """);
        jdbc.execute("""
                CREATE TABLE bi_product_category_dim(tenant_id VARCHAR,category_id BIGINT,
                parent_id BIGINT,category_name VARCHAR,ordinal INT,deleted INT)
                """);
        jdbc.update("INSERT INTO bi_product_category_dim VALUES('T',1,NULL,'台呢',1,0),('OTHER',2,NULL,'私有分类',1,0)");
        for (int id = 1; id <= 6; id++) {
            jdbc.update("""
                    INSERT INTO bi_sales_order_fact VALUES(?,?,'ORDER','客户','CD','成都','A','业务员A',
                    '2026-09-25 00:00:00',?,?,?,?,'2026-10-20 00:00:00')
                    """, id == 6 ? "OTHER" : "T", id, id == 4 ? "CANCELLED" : "COMPLETED",
                    id == 5 ? 1 : 0, id == 3 ? 0 : 10000, id == 2 ? 10000 : 6000);
        }
        jdbc.update("""
                INSERT INTO bi_sales_order_line_fact VALUES
                ('T',1,1,11,'台呢A',1,'台呢',0,1000),('T',1,2,12,'球杆A',2,'球杆',0,9000),
                ('T',1,3,13,'删除行',3,'配件',1,2000),('OTHER',1,1,11,'其他租户',9,'私有分类',0,3000)
                """);
        jdbc.update("INSERT INTO bi_product_dim VALUES('T',12,2,'球杆'),('OTHER',11,9,'其他租户')");
        OperatingCityTestSchema.install(jdbc);
    }

    @Test void fullAmountForPartialPaymentAndNoSettledCancelledZeroDeletedOrOtherTenantOrders() {
        var rows = store.load("T", from, to, "CD", "A", 50001);
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.orderId()).isEqualTo("1");
            assertThat(row.orderAmount()).isEqualByComparingTo("10000");
            assertThat(row.paidAmount()).isEqualByComparingTo("6000");
        });
        assertThat(rows).extracting(row -> row.lineAmount().intValue()).containsExactly(1000, 9000);
        assertThat(rows).extracting(row -> row.productName()).containsExactly("台呢A", "球杆A");
        assertThat(store.categories("T")).hasSize(1).allMatch(c -> c.name().equals("台呢"));
    }

    @Test void missingProductStillRetainsWholeOrderForUnknownRisk() {
        jdbc.update("DELETE FROM bi_sales_order_line_fact WHERE tenant_id='T' AND order_id=1");
        var rows = store.load("T", from, to, null, null, 50001);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().categoryId()).isNull();
        assertThat(rows.getFirst().orderAmount()).isEqualByComparingTo("10000");
    }

    @Test void filtersAndLimitApplyWithoutReturningOtherCityOrSalesperson() {
        assertThat(store.load("T", from, to, "SH", null, 50001)).isEmpty();
        assertThat(store.load("T", from, to, null, "B", 50001)).isEmpty();
        assertThat(store.load("T", from, Instant.parse("2026-09-02T00:00:00Z"), null, null, 50001)).isEmpty();
        assertThat(store.load("T", from, to, null, null, 1)).hasSize(1);
    }
}

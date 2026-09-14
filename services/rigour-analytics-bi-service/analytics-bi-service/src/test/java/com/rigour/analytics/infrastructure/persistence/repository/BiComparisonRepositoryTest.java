package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.infrastructure.persistence.mapper.BiComparisonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
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
import static org.assertj.core.api.Assertions.assertThat;

/** 使用隔离 H2 执行实际聚合 SQL，不依赖共享 DEV 数据。 */
class BiComparisonRepositoryTest {
    private SqlSession session;
    private JdbcTemplate jdbc;
    private MybatisBiComparisonStore store;
    @BeforeEach void setup() {
        var ds = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:comparison_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        config.addMapper(BiComparisonMapper.class);
        session = new SqlSessionFactoryBuilder().build(config).openSession();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(session.getConnection(), true));
        jdbc.execute("""
                CREATE TABLE bi_sales_order_fact (
                  tenant_id VARCHAR(64), customer_id BIGINT, region_code VARCHAR(64), region_name VARCHAR(100),
                  owner_staff_code VARCHAR(50), customer_type_code VARCHAR(64), source_system_code VARCHAR(64),
                  order_date DATETIME(6), deleted INT DEFAULT 0, order_status_code VARCHAR(64) DEFAULT 'COMPLETED',
                  payable_amount DECIMAL(24,6), paid_amount DECIMAL(24,6), unpaid_amount DECIMAL(24,6))
                """);
        store = new MybatisBiComparisonStore(session.getMapper(BiComparisonMapper.class));
    }
    @AfterEach void close() { session.close(); }
    private SupplyDashboardFilter filter(String from, String to, String region) {
        return new SupplyDashboardFilter(Instant.parse(from), Instant.parse(to), region, "S1", "STORE", null, "FEISHU");
    }
    private void order(String tenant, String city, long customer, String date, String amount) {
        jdbc.update("""
                INSERT INTO bi_sales_order_fact (tenant_id,region_code,region_name,customer_id,owner_staff_code,
                  customer_type_code,source_system_code,order_date,payable_amount,paid_amount,unpaid_amount)
                VALUES (?,?,?,?,'S1','STORE','FEISHU',?,?,0,?)
                """, tenant, city, city, customer, date, new BigDecimal(amount), new BigDecimal(amount));
    }
    @Test void totalsAreNotSumOfTruncatedRankingsAndCustomersAreGloballyDeduplicated() {
        for (int i = 0; i < 30; i++) order("TENANT", "C" + i, 1, "2026-09-01 00:00:00", "10.125");
        order("TENANT", null, 2, "2026-09-01 23:59:59.999999", "5.25");
        order("TENANT", "OLD", 1, "2026-08-31 23:59:59.999999", "100.125");
        var result = store.compare("TENANT", filter("2026-09-01T00:00:00Z", "2026-09-01T23:59:59.999999Z", null),
                filter("2026-08-31T00:00:00Z", "2026-08-31T23:59:59.999999Z", null));
        assertThat(result.current().salesAmount()).isEqualByComparingTo("309");
        assertThat(result.current().orderCount()).isEqualTo(31);
        assertThat(result.current().customerCount()).isEqualTo(2);
        assertThat(result.previous().salesAmount()).isEqualByComparingTo("100.125");
        assertThat(result.cities()).hasSize(32);
        assertThat(result.cities()).anySatisfy(city -> {
            assertThat(city.regionCode()).isEqualTo("UNKNOWN");
            assertThat(city.current().salesAmount()).isEqualByComparingTo("5.25");
        });
    }
    @Test void filtersBoundaryTenantCancellationAndDeletionUseSameOrderFacts() {
        order("TENANT", "BJ", 1, "2026-09-01 00:00:00", "10");
        order("TENANT", "SH", 2, "2026-09-01 00:00:00", "500");
        order("OTHER", "BJ", 3, "2026-09-01 00:00:00", "500");
        order("TENANT", "BJ", 4, "2026-09-02 00:00:00", "500");
        order("TENANT", "BJ", 5, "2026-08-30 23:59:59.999999", "500");
        for (int i = 6; i <= 10; i++) order("TENANT", "BJ", i, "2026-09-01 00:00:00", "500");
        jdbc.update("UPDATE bi_sales_order_fact SET deleted=1 WHERE customer_id=6");
        jdbc.update("UPDATE bi_sales_order_fact SET order_status_code='CANCELLED' WHERE customer_id=7");
        jdbc.update("UPDATE bi_sales_order_fact SET owner_staff_code='S2' WHERE customer_id=8");
        jdbc.update("UPDATE bi_sales_order_fact SET customer_type_code='RETAIL' WHERE customer_id=9");
        jdbc.update("UPDATE bi_sales_order_fact SET source_system_code='DINGHUOBAO' WHERE customer_id=10");
        var result = store.compare("TENANT", filter("2026-09-01T00:00:00Z", "2026-09-01T23:59:59.999999Z", "BJ"),
                filter("2026-08-31T00:00:00Z", "2026-08-31T23:59:59.999999Z", "BJ"));
        assertThat(result.current().salesAmount()).isEqualByComparingTo("10");
        assertThat(result.previous().orderCount()).isZero();
        assertThat(result.cities()).hasSize(1);
    }
}

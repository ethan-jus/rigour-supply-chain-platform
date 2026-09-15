package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** 在隔离库执行投影和聚合 SQL，验证补录/清空、零订单客户和跨租户/城市/员工隔离。 */
class CustomerAttributeAnalyticsRepositoryTest {
    @Test void refreshesCurrentAttributesAndKeepsCustomerAndOrderCountsAtTheirOwnGrain() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:attributes_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA rigour_crm");
        jdbc.execute("CREATE TABLE rigour_crm.crm_customer (tenant_id VARCHAR, id BIGINT, customer_source_name VARCHAR, business_category_name VARCHAR, deleted INT)");
        jdbc.execute("CREATE TABLE bi_customer_attribute_current (tenant_id VARCHAR, customer_id BIGINT, customer_source_name VARCHAR, business_category_name VARCHAR, synced_time TIMESTAMP, PRIMARY KEY(tenant_id,customer_id))");
        jdbc.execute("CREATE TABLE bi_customer_attribute_snapshot (tenant_id VARCHAR PRIMARY KEY, synced_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE bi_customer_dim (tenant_id VARCHAR, customer_id BIGINT, region_code VARCHAR, owner_staff_code VARCHAR, deleted INT)");
        jdbc.execute("CREATE TABLE bi_sales_order_fact (tenant_id VARCHAR, customer_id BIGINT, region_code VARCHAR, owner_staff_code VARCHAR, order_date TIMESTAMP, order_status_code VARCHAR, payable_amount DECIMAL(18,2), paid_amount DECIMAL(18,2), deleted INT)");
        jdbc.execute("CREATE TABLE bi_etl_checkpoint (tenant_id VARCHAR, source_code VARCHAR, last_success_time TIMESTAMP)");
        jdbc.update("INSERT INTO rigour_crm.crm_customer VALUES ('T',1,' 转介绍 ','餐饮',0),('T',2,'转介绍',NULL,0),('T',3,NULL,'零售',0),('OTHER',1,'广告','零售',0),('T',4,'已删除','已删除',1)");
        jdbc.update("INSERT INTO bi_customer_dim VALUES ('T',1,'BJ','E1',0),('T',2,'BJ','E1',0),('T',3,'SH','E2',0),('OTHER',1,'BJ','E1',0),('T',4,'BJ','E1',1)");
        jdbc.update("INSERT INTO bi_sales_order_fact VALUES ('T',1,'BJ','E1','2026-09-02','COMPLETED',100,80,0),('T',1,'BJ','E1','2026-09-03','COMPLETED',200,100,0),('T',1,'SH','E2','2026-09-03','COMPLETED',500,100,0),('T',1,'BJ','E1','2026-09-03','CANCELLED',700,100,0),('T',1,'BJ','E1','2026-08-01','COMPLETED',800,100,0),('OTHER',1,'BJ','E1','2026-09-03','COMPLETED',900,100,0)");
        var store = new JdbcCustomerAttributeAnalyticsStore(ds);
        var from = Instant.parse("2026-09-01T00:00:00Z");
        var to = Instant.parse("2026-09-15T00:00:00Z");
        assertThat(store.read("T", from, to, "BJ", "E1").syncedAt()).isNull();
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        config.addMapper(SupplyDashboardQueryMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
            var mapper = session.getMapper(SupplyDashboardQueryMapper.class);
            var synced = LocalDateTime.parse("2026-09-15T00:00:00");
            mapper.clearCustomerAttributes("T");
            assertThat(mapper.refreshCustomerAttributes("T", synced)).isEqualTo(3);
            mapper.completeCustomerAttributes("T", synced);
            assertThat(store.read("T", from, to, "BJ", "E1").sources()).singleElement()
                    .satisfies(item -> { assertThat(item.customerCount()).isEqualTo(2); assertThat(item.salesAmount()).isNull(); });
            jdbc.update("INSERT INTO bi_etl_checkpoint VALUES ('T','ORDER_SALES_ORDER','2026-09-15')");
            var report = store.read("T", from, to, "BJ", "E1");
            assertThat(report.sources()).singleElement().satisfies(item -> {
                assertThat(item.name()).isEqualTo("转介绍");
                assertThat(item.customerCount()).isEqualTo(2);
                assertThat(item.orderingCustomerCount()).isEqualTo(1);
                assertThat(item.orderCount()).isEqualTo(2);
                assertThat(item.salesAmount()).isEqualByComparingTo("300");
                assertThat(item.paidAmount()).isEqualByComparingTo("180");
            });
            assertThat(report.businessCategories()).anySatisfy(item -> {
                assertThat(item.missing()).isTrue(); assertThat(item.customerCount()).isEqualTo(1);
            });
            jdbc.update("UPDATE rigour_crm.crm_customer SET customer_source_name=' ' WHERE tenant_id='T' AND id=1");
            jdbc.update("UPDATE rigour_crm.crm_customer SET deleted=1 WHERE tenant_id='T' AND id=2");
            mapper.clearCustomerAttributes("T");
            mapper.refreshCustomerAttributes("T", synced.plusMinutes(1));
            mapper.completeCustomerAttributes("T", synced.plusMinutes(1));
            assertThat(store.read("T", from, to, "BJ", "E1").sources()).singleElement().satisfies(item -> {
                assertThat(item.missing()).isTrue(); assertThat(item.customerCount()).isEqualTo(1);
            });
        }
    }
}

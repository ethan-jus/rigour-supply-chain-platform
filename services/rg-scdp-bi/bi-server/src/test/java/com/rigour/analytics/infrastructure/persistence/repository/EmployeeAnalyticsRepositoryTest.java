package com.rigour.analytics.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

/** 隔离库仅建 BI 表：验证无实时跨库读取、无订单员工保留、金额不因客户联接放大。 */
class EmployeeAnalyticsRepositoryTest {
    @Test
    void refreshKeepsZeroOrderStaffAndRollsBackIfHrBecomesUnavailable() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:employee_refresh_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE ALIAS UUID_TO_BIN FOR"
                    + " 'com.rigour.analytics.infrastructure.persistence.repository.CityContactRefreshTest.identity'");
        jdbc.execute(
                "CREATE TABLE bi_source_hr_hr_employee (tenant_id VARCHAR, employee_code VARCHAR,"
                    + " employee_name VARCHAR, employment_status VARCHAR, city_name VARCHAR,"
                    + " position_name VARCHAR, department_name VARCHAR, department_id BIGINT,"
                    + " department_path VARCHAR, entry_date TIMESTAMP, leave_date TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE bi_source_crm_crm_customer_area (tenant_id VARCHAR, area_name"
                    + " VARCHAR, area_code VARCHAR, status VARCHAR, deleted INT)");
        jdbc.execute(
                "CREATE TABLE bi_employee_dim (tenant_id VARCHAR, employee_code VARCHAR,"
                    + " employee_name VARCHAR, employment_status VARCHAR, city_name VARCHAR,"
                    + " region_code VARCHAR, position_name VARCHAR, department_name VARCHAR,"
                    + " department_id BIGINT, department_path VARCHAR, entry_date TIMESTAMP,"
                    + " leave_date TIMESTAMP, synced_time TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE bi_employee_snapshot (tenant_id VARCHAR, synced_time TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE bi_etl_checkpoint (tenant_id VARCHAR, source_code VARCHAR,"
                    + " last_success_time TIMESTAMP)");
        jdbc.update(
                "INSERT INTO bi_etl_checkpoint VALUES"
                    + " ('T','CRM_CUSTOMER','2026-09-15'),('T','ORDER_SALES_ORDER','2026-09-15'),('T','ORDER_PAYMENT_RECORD','2026-09-15')");
        jdbc.update(
                "INSERT INTO bi_source_hr_hr_employee VALUES"
                    + " ('T','E1','员工1','ACTIVE','北京','销售',NULL,NULL,'[]',NULL,NULL),('T','E2','员工2','LEFT','北京','销售',NULL,NULL,'[]','2026-08-01','2026-09-01'),('OTHER','E3','其他租户','ACTIVE','北京','销售',NULL,NULL,'[]',NULL,NULL)");
        jdbc.update(
                "INSERT INTO bi_source_crm_crm_customer_area VALUES ('T','北京','BJ','ACTIVE',0)");
        var target = new JdbcEmployeeAnalyticsStore(ds);
        var proxy = new org.springframework.aop.framework.ProxyFactory(target);
        proxy.addAdvice(
                new org.springframework.transaction.interceptor.TransactionInterceptor(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),
                        new org.springframework.transaction.annotation
                                .AnnotationTransactionAttributeSource()));
        var store =
                (com.rigour.analytics.application.port.out.EmployeeAnalyticsStore) proxy.getProxy();
        assertThat(store.refresh("T", Instant.now()).pulledCount()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT region_code FROM bi_employee_dim", String.class))
                .containsExactly("BJ", "BJ");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT position_name FROM bi_employee_dim WHERE"
                                    + " employee_code='E1'",
                                String.class))
                .isEqualTo("销售");
        jdbc.execute("DROP TABLE bi_source_hr_hr_employee");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.refresh("T", Instant.now()))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_employee_dim", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_employee_snapshot", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void readsOnlyBiAndAggregatesBeforeJoiningEmployees() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:employees_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE bi_employee_snapshot (tenant_id VARCHAR, synced_time TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE bi_etl_checkpoint (tenant_id VARCHAR, source_code VARCHAR,"
                    + " last_success_time TIMESTAMP)");
        jdbc.update(
                "INSERT INTO bi_etl_checkpoint VALUES"
                    + " ('T','CRM_CUSTOMER','2026-09-15'),('T','ORDER_SALES_ORDER','2026-09-15'),('T','ORDER_PAYMENT_RECORD','2026-09-15')");
        jdbc.execute(
                "CREATE TABLE bi_employee_dim (tenant_id VARCHAR, employee_code VARCHAR,"
                    + " employee_name VARCHAR, employment_status VARCHAR, city_name VARCHAR,"
                    + " region_code VARCHAR, position_name VARCHAR, department_name VARCHAR,"
                    + " department_id BIGINT, department_path VARCHAR, entry_date TIMESTAMP,"
                    + " leave_date TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE bi_customer_dim (tenant_id VARCHAR, owner_staff_code VARCHAR,"
                    + " region_code VARCHAR, deleted INT)");
        jdbc.execute(
                "CREATE TABLE bi_sales_order_fact (tenant_id VARCHAR, owner_staff_code VARCHAR,"
                    + " region_code VARCHAR, deleted INT, order_status_code VARCHAR, order_date"
                    + " TIMESTAMP, payable_amount DECIMAL(24,6), paid_amount DECIMAL(24,6))");
        jdbc.update("INSERT INTO bi_employee_snapshot VALUES ('T', '2026-09-15 00:00:00')");
        jdbc.update(
                "INSERT INTO bi_employee_dim"
                    + " (tenant_id,employee_code,employee_name,employment_status,city_name,region_code)"
                    + " VALUES"
                    + " ('T','E1','员工1','ACTIVE','北京','BJ'),('T','E2','员工2','ACTIVE','北京','BJ'),('OTHER','E1','其他租户','ACTIVE','北京','BJ')");
        jdbc.update(
                "INSERT INTO bi_customer_dim VALUES"
                    + " ('T','E1','BJ',0),('T','E1','BJ',0),('OTHER','E1','BJ',0)");
        jdbc.update(
                "INSERT INTO bi_sales_order_fact VALUES"
                    + " ('T','E1','BJ',0,'COMPLETED','2026-09-01',100,40),('T','E1','BJ',0,'COMPLETED','2026-09-02',200,60),('T','E1','BJ',0,'CANCELLED','2026-09-02',500,0),('OTHER','E1','BJ',0,'COMPLETED','2026-09-02',999,999),('T','E1','SH',0,'COMPLETED','2026-09-02',700,0)");
        jdbc.execute(
                "CREATE TABLE bi_sales_payment_fact(tenant_id VARCHAR,collector_staff_code"
                    + " VARCHAR,region_code VARCHAR,deleted INT,payment_time TIMESTAMP,paid_amount"
                    + " DECIMAL(24,6))");
        jdbc.update(
                "INSERT INTO bi_sales_payment_fact VALUES"
                    + " ('T','E2','BJ',0,'2026-09-02',100),('OTHER','E1','BJ',0,'2026-09-02',999),('T','E1','BJ',1,'2026-09-02',500),('T','E1','BJ',0,'2026-08-31',600)");
        var repository = new JdbcEmployeeAnalyticsStore(ds);
        var result =
                repository.read(
                        "T",
                        Instant.parse("2026-09-01T00:00:00Z"),
                        Instant.parse("2026-09-03T00:00:00Z"),
                        "BJ",
                        null);
        assertThat(result.rows()).hasSize(2);
        var first = result.rows().getFirst().employee();
        assertThat(first.customerCount()).isEqualTo(2);
        assertThat(first.orderCount()).isEqualTo(2);
        assertThat(first.salesAmount()).isEqualByComparingTo("300");
        assertThat(first.paidAmount()).isZero();
        assertThat(result.rows().get(1).employee().paidAmount()).isEqualByComparingTo("100");
        assertThat(result.rows().get(1).employee().orderCount()).isZero();
        assertThat(repository.read("T", Instant.EPOCH, Instant.now(), "BJ", "E2").rows())
                .hasSize(1);
        assertThat(repository.read("UNSYNCED", Instant.EPOCH, Instant.now(), null, null).syncedAt())
                .isNull();
        jdbc.update("DELETE FROM bi_etl_checkpoint WHERE source_code='ORDER_SALES_ORDER'");
        var pendingOrder =
                repository
                        .read("T", Instant.EPOCH, Instant.now(), "BJ", "E2")
                        .rows()
                        .getFirst()
                        .employee();
        assertThat(pendingOrder.orderCount()).isNull();
        assertThat(pendingOrder.salesAmount()).isNull();
    }
}

package com.rigour.analytics.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;

import com.rigour.analytics.application.model.SupplyDashboardFilter;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;

public class SalesDashboardRepositoryTest {
    JdbcTemplate jdbc;
    JdbcSalesDashboardStore store;

    public static String dateFormat(Timestamp date, String format) {
        return date.toLocalDateTime()
                .format(
                        java.time.format.DateTimeFormatter.ofPattern(
                                format.equals("%Y-%m") ? "yyyy-MM" : "yyyy-MM-dd"));
    }

    @BeforeEach
    void setup() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        var targets = org.mockito.Mockito.mock(HrDashboardTargets.class);
        org.mockito.Mockito.when(targets.values("T","2026-01","2026-12")).thenReturn(List.of(
            new com.rigour.hr.api.v1.model.TargetSettingsModels.Target("2026-08","SALES_OWNER","S1","Sales1","SALES_AMOUNT",new BigDecimal("123"),1),
            new com.rigour.hr.api.v1.model.TargetSettingsModels.Target("2026-08","SALES_OWNER","S1","Sales1","RECEIPT_AMOUNT",BigDecimal.ZERO,1),
            new com.rigour.hr.api.v1.model.TargetSettingsModels.Target("2026-08","SALES_OWNER","OUTSIDE","Private","SALES_AMOUNT",new BigDecimal("999"),1)));
        store = new JdbcSalesDashboardStore(ds, targets);
        jdbc.execute("CREATE TABLE bi_target_default(tenant_id VARCHAR(64),dimension_type VARCHAR(32),effective_month DATE,metric_code VARCHAR(64),target_value DECIMAL(24,6))");
        jdbc.execute(
                "CREATE ALIAS DATE_FORMAT FOR"
                    + " 'com.rigour.analytics.infrastructure.persistence.repository.SalesDashboardRepositoryTest.dateFormat'");
        jdbc.execute(
                "CREATE TABLE bi_sales_order_fact(tenant_id VARCHAR,order_id"
                        + " BIGINT,owner_staff_code VARCHAR,owner_staff_name VARCHAR,region_code"
                        + " VARCHAR,region_name VARCHAR,customer_id BIGINT,customer_name"
                        + " VARCHAR,customer_type_code VARCHAR,source_system_code VARCHAR,deleted"
                        + " INT,order_status_code VARCHAR,order_date TIMESTAMP,payable_amount"
                        + " DECIMAL(24,6),paid_amount DECIMAL(24,6))");
        jdbc.execute(
                "CREATE TABLE bi_sales_payment_fact(tenant_id VARCHAR,payment_id BIGINT,order_id"
                        + " BIGINT,owner_staff_code VARCHAR,owner_staff_name VARCHAR,region_code"
                        + " VARCHAR,region_name VARCHAR,collector_staff_code VARCHAR,deleted"
                        + " INT,payment_time TIMESTAMP,paid_amount DECIMAL(24,6),customer_type_code VARCHAR DEFAULT 'STORE',source_system_code VARCHAR DEFAULT 'DHB')");
        jdbc.execute(
                "CREATE TABLE bi_employee_dim(tenant_id VARCHAR,employee_code VARCHAR,employee_name"
                        + " VARCHAR,employment_status VARCHAR,city_name VARCHAR,department_id"
                        + " BIGINT,department_path VARCHAR)");
        jdbc.execute(
                "CREATE TABLE bi_sales_contact_city_dim(tenant_id VARCHAR,region_code"
                        + " VARCHAR,department_id BIGINT)");
        jdbc.execute(
                "CREATE TABLE bi_business_target(tenant_id VARCHAR,dimension_code"
                        + " VARCHAR,dimension_type VARCHAR,target_month DATE,metric_code"
                        + " VARCHAR,target_value DECIMAL(24,6),deleted INT)");
        jdbc.execute(
                "CREATE TABLE bi_dashboard_product_snapshot(tenant_id VARCHAR,synced_time"
                        + " TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE bi_dashboard_order_product(tenant_id VARCHAR,order_id"
                    + " BIGINT,order_line_id BIGINT,product_category_id"
                    + " BIGINT,product_category_name VARCHAR,product_id BIGINT,product_name"
                    + " VARCHAR,specification_snapshot VARCHAR,sku_code VARCHAR,sales_amount"
                    + " DECIMAL(24,6),cohort_paid_amount DECIMAL(24,6),allocation_status VARCHAR)");
        jdbc.execute(
                "CREATE TABLE bi_sales_order_line_fact(tenant_id VARCHAR,order_id"
                    + " BIGINT,order_line_id BIGINT,quantity DECIMAL(24,6),deleted INT)");
        jdbc.update(
                "INSERT INTO bi_sales_order_line_fact"
                    + " VALUES('T',1,1,2.5,0),('T',2,2,8,0),('OTHER',1,1,999,0)");
        jdbc.execute(
                "CREATE TABLE bi_dashboard_payment_product(tenant_id VARCHAR,payment_id"
                        + " BIGINT,order_id BIGINT,order_line_id BIGINT,payment_time"
                        + " TIMESTAMP,allocated_amount DECIMAL(24,6),allocation_status VARCHAR)");
        jdbc.update(
                "INSERT INTO bi_employee_dim"
                    + " VALUES('T','S1','Sales1','LEFT','杭州',1,'[1]'),('T','S2','Sales2','ACTIVE','杭州',1,'[1]'),('OTHER','S1','Private','ACTIVE','杭州',1,'[1]')");
        jdbc.update("INSERT INTO bi_sales_contact_city_dim VALUES('T','HZ',1),('OTHER','HZ',1)");
        jdbc.update(
                "INSERT INTO bi_sales_order_fact"
                    + " VALUES('T',1,'S1','Sales1','HZ','杭州',10,'CustomerA','STORE','DHB',0,'COMPLETED','2026-08-10"
                    + " 00:00:00',100,60),('T',2,'S1','Sales1','HZ','杭州',10,'CustomerA','STORE','DHB',0,'COMPLETED','2026-07-10"
                    + " 00:00:00',200,30),('OTHER',1,'S1','Private','HZ','杭州',20,'Private','STORE','DHB',0,'COMPLETED','2026-08-10"
                    + " 00:00:00',9000,9000)");
        jdbc.update(
                "INSERT INTO bi_sales_payment_fact(tenant_id,payment_id,order_id,owner_staff_code,owner_staff_name,region_code,region_name,collector_staff_code,deleted,payment_time,paid_amount)"
                    + " VALUES('T',1,1,'S1','Sales1','HZ','杭州','OTHER_HANDLER',0,'2026-08-11"
                    + " 00:00:00',20),('T',2,2,'S1','Sales1','HZ','杭州','OTHER_HANDLER',0,'2026-08-12"
                    + " 00:00:00',30),('T',3,1,'S1','Sales1','HZ','杭州','OTHER_HANDLER',0,'2026-09-02"
                    + " 00:00:00',40)");
        jdbc.update(
                "INSERT INTO bi_business_target"
                    + " VALUES('T','S1','SALES_OWNER','2026-08-01','NEW_CUSTOMER',20,0),('T','S1','SALES_OWNER','2026-08-01','REPEAT_CUSTOMER',10,0)");
        jdbc.update("INSERT INTO bi_dashboard_product_snapshot VALUES('T','2026-09-24 00:00:00')");
        jdbc.update(
                "INSERT INTO bi_dashboard_order_product"
                    + " VALUES('T',1,1,1,'品类',1,'产品','A','A',100,60,'ALLOCATED'),('T',2,2,1,'品类',1,'产品','A','A',200,30,'ALLOCATED')");
        jdbc.update(
                "INSERT INTO bi_dashboard_payment_product VALUES('T',1,1,1,'2026-08-11"
                        + " 00:00:00',20,'ALLOCATED'),('T',2,2,2,'2026-08-12"
                        + " 00:00:00',30,'ALLOCATED'),('T',3,1,1,'2026-09-02"
                        + " 00:00:00',40,'ALLOCATED')");
    }

    SupplyDashboardFilter filter(String owner) {
        return new SupplyDashboardFilter(
                Instant.parse("2026-07-31T16:00:00Z"),
                Instant.parse("2026-08-31T15:59:59.999999Z"),
                "HZ",
                owner,
                null,
                null,
                null);
    }

    @Test
    void distinguishesOrderCohortCashAndHistoryUsingOrderOwner() {
        var data = store.query("T", filter("S1"));
        assertThat(data.people()).hasSize(1);
        var person = data.people().get(0);
        assertThat(person.sales()).isEqualByComparingTo("100");
        assertThat(person.paid()).isEqualByComparingTo("60");
        assertThat(person.receipts()).isEqualByComparingTo("50");
        assertThat(person.employmentStatus()).isEqualTo("LEFT");
        assertThat(data.history().amount()).isEqualByComparingTo("300");
        assertThat(data.history().received()).isEqualByComparingTo("90");
        assertThat(data.receiptSplit().currentOrders()).isEqualByComparingTo("20");
        assertThat(data.receiptSplit().historicalOrders()).isEqualByComparingTo("30");
        assertThat(data.products()).hasSize(1);
        assertThat(data.products().get(0).sales()).isEqualByComparingTo("100");
        assertThat(data.products().get(0).receipts()).isEqualByComparingTo("50");
        assertThat(data.customers()).hasSize(1);
        assertThat(data.goals().stream().filter(g -> !g.code().equals("*"))).hasSize(2);
        assertThat(data.months()).hasSize(3);
        assertThat(data.dailyReceipts()).hasSize(2);
    }

    @Test
    void quantityCountsSelectedOrdersOnceDespiteMultipleAndHistoricalReceipts() {
        jdbc.update(
                "INSERT INTO bi_sales_payment_fact(tenant_id,payment_id,order_id,owner_staff_code,owner_staff_name,region_code,region_name,collector_staff_code,deleted,payment_time,paid_amount)"
                    + " VALUES('T',4,1,'S1','Sales1','HZ','杭州','OTHER_HANDLER',0,'2026-08-20"
                    + " 00:00:00',5)");
        jdbc.update(
                "INSERT INTO bi_dashboard_payment_product VALUES('T',4,1,1,'2026-08-20"
                    + " 00:00:00',5,'ALLOCATED')");
        var product = store.query("T", filter("S1")).products().get(0);
        assertThat(product.quantity()).isEqualByComparingTo("2.5");
        assertThat(product.receipts()).isEqualByComparingTo("55");
        jdbc.update("DELETE FROM bi_sales_order_line_fact WHERE tenant_id='T' AND order_id=1");
        assertThat(store.query("T", filter("S1")).products().get(0).quantity()).isNull();
    }

    @Test
    void reassignedCustomerReceiptsRemainVisibleWithoutTransferringOrderCohort() {
        jdbc.update("UPDATE bi_sales_payment_fact SET owner_staff_code='S2',owner_staff_name='Sales2' WHERE tenant_id='T'");
        var newOwner = store.query("T", filter("S2"));
        assertThat(newOwner.people().get(0).sales()).isZero();
        assertThat(newOwner.people().get(0).receipts()).isEqualByComparingTo("50");
        assertThat(newOwner.receiptSplit().currentOrders()).isEqualByComparingTo("20");
        assertThat(newOwner.products().get(0).receipts()).isEqualByComparingTo("50");
        assertThat(newOwner.products().get(0).sales()).isZero();
        assertThat(store.query("T", filter("S1")).people().get(0).receipts()).isZero();
    }

    @Test
    void keepsActiveZeroSalesPeopleWithoutReturningPrivateTenant() {
        var data = store.query("T", filter(null));
        assertThat(data.people()).hasSize(2);
        assertThat(data.people()).noneMatch(p -> p.name().equals("Private"));
        assertThat(
                        data.people().stream()
                                .filter(p -> p.code().equals("S2"))
                                .findFirst()
                                .orElseThrow()
                                .sales())
                .isZero();
        assertThat(data.history()).isNull();
        assertThat(data.products()).isEmpty();
    }

    @Test
    void dayBoundaryIsChinaTimeAndLatePaymentDoesNotBecomeThatDayCash() {
        var f =
                new SupplyDashboardFilter(
                        Instant.parse("2026-08-10T16:00:00Z"),
                        Instant.parse("2026-08-11T15:59:59.999999Z"),
                        "HZ",
                        "S1",
                        null,
                        null,
                        null);
        var data = store.query("T", f);
        assertThat(data.people().get(0).sales()).isZero();
        assertThat(data.people().get(0).receipts()).isEqualByComparingTo("20");
        assertThat(data.receiptSplit().historicalOrders()).isEqualByComparingTo("20");
        assertThat(data.customers()).isEmpty();
    }

    @Test
    void missingProductSnapshotIsNotAnEmptySuccessfulAllocation() {
        jdbc.update("DELETE FROM bi_dashboard_product_snapshot");
        var data = store.query("T", filter("S1"));
        assertThat(data.productSyncedAt()).isNull();
        assertThat(data.products()).isEmpty();
    }
}

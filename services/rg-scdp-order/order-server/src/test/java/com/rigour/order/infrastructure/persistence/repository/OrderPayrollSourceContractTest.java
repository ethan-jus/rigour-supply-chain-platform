package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.order.infrastructure.persistence.mapper.OrderCostSourceMapper;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.*;
import java.util.UUID;

/** Executes the source contract on isolated H2; MySQL migration validation remains separate. */
public class OrderPayrollSourceContractTest {
    JdbcTemplate jdbc;
    OrderCostSourceMapper mapper;

    public static LocalDateTime convertZone(LocalDateTime value, String from, String to) {
        return value == null
                ? null
                : value.atOffset(ZoneOffset.of(from))
                        .withOffsetSameInstant(ZoneOffset.of(to))
                        .toLocalDateTime();
    }

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:order_payroll_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE ALIAS CONVERT_TZ FOR"
                    + " 'com.rigour.order.infrastructure.persistence.repository.OrderPayrollSourceContractTest.convertZone'");
        jdbc.execute(
                "CREATE TABLE order_sales_order(id BIGINT,tenant_id VARCHAR(36),order_no"
                        + " VARCHAR(50),owner_employee_code VARCHAR(50),region_code"
                        + " VARCHAR(50),order_status_code VARCHAR(20),order_date"
                        + " TIMESTAMP,payable_amount DECIMAL(18,2),paid_amount"
                        + " DECIMAL(18,2),data_quality_status_code VARCHAR(30),deleted"
                        + " INT,source_system_code VARCHAR(30))");
        jdbc.execute(
                "CREATE TABLE order_sales_order_line(id BIGINT,tenant_id VARCHAR(36),order_id"
                    + " BIGINT,product_id BIGINT,product_variant_id BIGINT,product_name_snapshot"
                    + " VARCHAR(100),unit_code VARCHAR(20),quantity DECIMAL(18,4),line_amount"
                    + " DECIMAL(18,2),deleted INT)");
        jdbc.execute(
                "CREATE TABLE order_payment_record(id BIGINT,tenant_id VARCHAR(36),order_id"
                    + " BIGINT,paid_amount DECIMAL(18,2),payment_time TIMESTAMP,payment_status_code"
                    + " VARCHAR(20),deleted INT)");
        jdbc.execute(
                "CREATE TABLE order_refund_record(id BIGINT,tenant_id VARCHAR(36),order_id"
                    + " BIGINT,refund_amount DECIMAL(18,2),refund_time TIMESTAMP,refund_status_code"
                    + " VARCHAR(20),deleted INT)");
        jdbc.execute(
                "CREATE TABLE order_attribution_snapshot(tenant_id VARCHAR(36),order_id"
                        + " BIGINT,employee_code VARCHAR(50),region_code VARCHAR(50),department_id"
                        + " BIGINT,state VARCHAR(20))");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(OrderCostSourceMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(OrderCostSourceMapper.class);
        jdbc.update(
                "INSERT INTO order_sales_order"
                        + " VALUES(1,'t','ORDER-1','NEW','NEW-CITY','CONFIRMED','2026-09-01"
                        + " 02:00:00',6000,6000,'COMPLETE',0,NULL)");
        jdbc.update(
                "INSERT INTO order_sales_order_line"
                    + " VALUES(1,'t',1,1,1,'方便面','BOX',10,1000,0),(2,'t',1,2,2,'台呢','BOX',10,5000,0)");
        jdbc.update(
                "INSERT INTO order_attribution_snapshot"
                        + " VALUES('t',1,'ORIGINAL','ORIGINAL-CITY',7,'FROZEN')");
        jdbc.update(
                "INSERT INTO order_payment_record VALUES(1,'t',1,2000,'2026-09-10"
                    + " 02:00:00','RECEIVED',0),(2,'t',1,4000,'2026-10-01 17:00:00','CHECKED',0)");
    }

    @Test
    void wholeMixedOrderWaitsUntilFullPaymentAndUsesFrozenAttribution() {
        var september =
                mapper.payrollLines("t", "ORIGINAL", null, LocalDateTime.of(2026, 9, 30, 16, 0));
        assertThat(september).hasSize(2);
        assertThat(september)
                .allSatisfy(
                        line -> {
                            assertThat(line.fullyPaidDate()).isNull();
                            assertThat(line.paidAmount()).isEqualByComparingTo("2000");
                            assertThat(line.orderLineTotal()).isEqualByComparingTo("6000");
                        });
        var october =
                mapper.payrollLines(
                        "t", null, "ORIGINAL-CITY", LocalDateTime.of(2026, 10, 31, 16, 0));
        assertThat(october).hasSize(2);
        assertThat(october)
                .allSatisfy(
                        line -> {
                            assertThat(line.fullyPaidDate()).isEqualTo(LocalDate.of(2026, 10, 2));
                            assertThat(line.employeeCode()).isEqualTo("ORIGINAL");
                            assertThat(line.departmentId()).isEqualTo(7);
                        });
        assertThat(mapper.payrollLines("t", "NEW", null, LocalDateTime.of(2026, 10, 31, 16, 0)))
                .isEmpty();
        assertThat(
                        mapper.payrollLines(
                                "other", "ORIGINAL", null, LocalDateTime.of(2026, 10, 31, 16, 0)))
                .isEmpty();
    }

    @Test
    void cityReceiptsIncludeAllPartialPaymentsWithoutNettingRefundsOrDuplicatingOrderLines() {
        jdbc.update(
                "INSERT INTO order_refund_record VALUES(1,'t',1,200,'2026-09-20"
                        + " 02:00:00','CONFIRMED',0)");
        jdbc.update(
                "INSERT INTO order_payment_record VALUES(99,'other',1,9999,'2026-09-10"
                        + " 02:00:00','RECEIVED',0)");
        var from = LocalDateTime.of(2026, 8, 31, 16, 0);
        var until = LocalDateTime.of(2026, 9, 30, 16, 0);
        var rows = mapper.cityCollections("t", "ORIGINAL-CITY", from, until);
        assertThat(rows).hasSize(1);
        assertThat(rows)
                .extracting(r -> r.amount())
                .containsExactly(new java.math.BigDecimal("2000.00"));
        assertThat(rows)
                .allSatisfy(
                        r -> {
                            assertThat(r.qualityStatus()).isEqualTo("COMPLETE");
                            assertThat(r.cityCode()).isEqualTo("ORIGINAL-CITY");
                        });
        assertThat(mapper.cityCollections("t", "NEW-CITY", from, until)).isEmpty();
        assertThat(mapper.cityCollections("other", "ORIGINAL-CITY", from, until)).isEmpty();
    }

    @Test
    void importedOrderUsesItsRecordedOwnerButNeverBypassesExistingReviewOrMissingOwner() {
        jdbc.update("DELETE FROM order_attribution_snapshot");
        jdbc.update("UPDATE order_sales_order SET source_system_code='DHB'");
        var until = LocalDateTime.of(2026, 10, 31, 16, 0);
        var from = LocalDateTime.of(2026, 8, 31, 16, 0);
        assertThat(mapper.cityCollections("t", "NEW-CITY", from, until))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.qualityStatus()).isEqualTo("COMPLETE"));
        assertThat(mapper.payrollLines("t", "NEW", null, until))
                .allSatisfy(
                        line -> {
                            assertThat(line.employeeCode()).isEqualTo("NEW");
                            assertThat(line.cityCode()).isEqualTo("NEW-CITY");
                            assertThat(line.qualityStatus()).isEqualTo("COMPLETE");
                        });
        jdbc.update(
                "INSERT INTO order_attribution_snapshot VALUES('t',1,'NEW','NEW-CITY',7,'REVIEW')");
        assertThat(mapper.cityCollections("t", "NEW-CITY", from, until))
                .hasSize(2)
                .allSatisfy(
                        row -> assertThat(row.qualityStatus()).isEqualTo("ATTRIBUTION_PENDING"));
        assertThat(mapper.payrollLines("t", "NEW", null, until))
                .allSatisfy(
                        line -> assertThat(line.qualityStatus()).isEqualTo("ATTRIBUTION_PENDING"));
        jdbc.update("DELETE FROM order_attribution_snapshot");
        jdbc.update("UPDATE order_sales_order SET owner_employee_code=NULL");
        assertThat(mapper.cityCollections("t", "NEW-CITY", from, until))
                .hasSize(2)
                .allSatisfy(
                        row -> assertThat(row.qualityStatus()).isEqualTo("ATTRIBUTION_PENDING"));
        assertThat(mapper.payrollLines("t", null, "NEW-CITY", until))
                .allSatisfy(
                        line -> assertThat(line.qualityStatus()).isEqualTo("ATTRIBUTION_PENDING"));
        jdbc.update(
                "UPDATE order_sales_order SET owner_employee_code='NEW',source_system_code=NULL");
        assertThat(mapper.cityCollections("t", "NEW-CITY", from, until))
                .hasSize(2)
                .allSatisfy(
                        row -> assertThat(row.qualityStatus()).isEqualTo("ATTRIBUTION_PENDING"));
        assertThat(mapper.payrollLines("t", "NEW", null, until))
                .allSatisfy(
                        line -> assertThat(line.qualityStatus()).isEqualTo("ATTRIBUTION_PENDING"));
    }

    @Test
    void priorMonthOrderSettlesInSeptemberOnceAndLaterReceiptDoesNotMoveSettlementMonth() {
        jdbc.update("UPDATE order_sales_order SET order_date='2026-08-18 02:00:00'");
        jdbc.update(
                "UPDATE order_payment_record SET payment_time='2026-09-30 15:59:59' WHERE id=2");
        jdbc.update(
                "INSERT INTO order_payment_record VALUES(3,'t',1,100,'2026-10-05"
                    + " 02:00:00','RECEIVED',0),(4,'t',1,9000,'2026-08-20"
                    + " 02:00:00','PENDING',0),(5,'t',1,9000,'2026-08-20 02:00:00','RECEIVED',1)");
        var september =
                mapper.payrollLines("t", "ORIGINAL", null, LocalDateTime.of(2026, 9, 30, 16, 0));
        assertThat(september)
                .hasSize(2)
                .allSatisfy(
                        line -> {
                            assertThat(line.fullyPaidDate()).isEqualTo(LocalDate.of(2026, 9, 30));
                            assertThat(line.paidAmount()).isEqualByComparingTo("6000");
                        });
        assertThat(
                        mapper.payrollLines(
                                "t", "ORIGINAL", null, LocalDateTime.of(2026, 10, 31, 16, 0)))
                .allSatisfy(
                        line ->
                                assertThat(line.fullyPaidDate())
                                        .isEqualTo(LocalDate.of(2026, 9, 30)));
    }

    @Test
    void refundBeforeThresholdDoesNotCreateFalseFullyPaidEvent() {
        jdbc.update(
                "INSERT INTO order_refund_record VALUES(1,'t',1,1000,'2026-09-20"
                        + " 02:00:00','CONFIRMED',0)");
        assertThat(
                        mapper.payrollLines(
                                "t", "ORIGINAL", null, LocalDateTime.of(2026, 10, 31, 16, 0)))
                .allSatisfy(
                        line -> {
                            assertThat(line.fullyPaidDate()).isNull();
                            assertThat(line.refundedAmount()).isEqualByComparingTo("1000");
                        });
    }
}

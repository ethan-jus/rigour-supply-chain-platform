package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.order.api.OrderCostSourceController;
import com.rigour.order.api.v1.model.OrderPayrollSettlementView;
import com.rigour.order.application.port.out.OrderCostSourceStore;
import com.rigour.order.application.service.OrderCostSourceService;
import com.rigour.order.infrastructure.persistence.mapper.OrderCostSourceMapper;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Executes the source contract on isolated H2; MySQL migration validation remains separate. */
public class OrderPayrollSourceContractTest {
    JdbcTemplate jdbc;
    OrderCostSourceMapper mapper;
    MybatisPlusOrderCostSourceRepository repository;

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
                    + " VARCHAR(20),deleted INT,payment_no VARCHAR(50),payment_owner_code VARCHAR(50),"
                    + " payment_owner_name VARCHAR(128),payment_owner_evidence VARCHAR(1024),"
                    + " allocation_time TIMESTAMP,actual_cash_time TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE order_refund_record(id BIGINT,tenant_id VARCHAR(36),order_id"
                    + " BIGINT,refund_amount DECIMAL(18,2),refund_time TIMESTAMP,refund_status_code"
                    + " VARCHAR(20),deleted INT)");
        jdbc.execute(
                "CREATE TABLE order_attribution_snapshot(tenant_id VARCHAR(36),order_id"
                        + " BIGINT,employee_code VARCHAR(50),region_code VARCHAR(50),department_id"
                        + " BIGINT,state VARCHAR(20))");
        jdbc.execute("CREATE TABLE order_attribution_adjustment(tenant_id VARCHAR,order_id BIGINT,status VARCHAR)");
        jdbc.execute("CREATE TABLE order_financial_event(tenant_id VARCHAR,order_id BIGINT,deleted INT,event_type VARCHAR,effective_at TIMESTAMP)");
        jdbc.execute(
                """
                CREATE VIEW order_verified_payment_v AS
                SELECT p.*,COALESCE(allocation_time,payment_time) AS allocation_effective_at,
                       COALESCE(actual_cash_time,payment_time) AS actual_cash_at
                FROM order_payment_record p
                WHERE p.payment_status_code='CHECKED' AND p.deleted=0 AND p.paid_amount>0
                """);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(OrderCostSourceMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(OrderCostSourceMapper.class);
        repository = new MybatisPlusOrderCostSourceRepository(mapper, null);
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
                """
                INSERT INTO order_payment_record(id,tenant_id,order_id,paid_amount,payment_time,payment_status_code,deleted)
                VALUES (1,'t',1,2000,'2026-09-10 02:00:00','CHECKED',0),(2,'t',1,4000,'2026-10-01 17:00:00','CHECKED',0)
                """);
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
                """
                INSERT INTO order_payment_record(id,tenant_id,order_id,paid_amount,payment_time,payment_status_code,deleted)
                VALUES (99,'other',1,9999,'2026-09-10 02:00:00','RECEIVED',0)
                """);
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
                """
                INSERT INTO order_payment_record(id,tenant_id,order_id,paid_amount,payment_time,payment_status_code,deleted)
                VALUES (3,'t',1,100,'2026-10-05 02:00:00','RECEIVED',0),
                       (4,'t',1,9000,'2026-08-20 02:00:00','PENDING',0),
                       (5,'t',1,9000,'2026-08-20 02:00:00','RECEIVED',1)
                """);
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

    @Test
    void firstVerifiedClosingPaymentAssignsWholeOrderAndItsProductLinesToOneRecipient() {
        paymentOwner(1, "A", "MANUALLY_CONFIRMED");
        paymentOwner(2, "B", "MANUALLY_CONFIRMED");
        jdbc.update(
                """
                INSERT INTO order_payment_record(id,tenant_id,order_id,paid_amount,payment_time,payment_status_code,deleted,
                                                 payment_no,payment_owner_code,payment_owner_evidence)
                VALUES (3,'t',1,100,'2026-10-07 02:00:00','CHECKED',0,'OTHER-PAYMENT','C','CONFIRMED'),
                       (4,'t',1,9000,'2026-09-09 02:00:00','RECEIVED',0,'UNVERIFIED','A','CONFIRMED'),
                       (5,'other',1,9000,'2026-09-09 02:00:00','CHECKED',0,'FOREIGN','A','CONFIRMED')
                """);
        jdbc.update(
                "INSERT INTO order_sales_order_line VALUES"
                        + "(3,'other',1,1,1,'不属于当前租户','BOX',10,5000,0),"
                        + "(4,'t',1,1,1,'已删除','BOX',10,5000,1)");

        assertThat(settlements("t", "B", 9)).isEmpty();
        assertThat(settlements("t", "A", 10)).isEmpty();
        assertThat(settlements("t", "C", 10)).isEmpty();
        assertThat(settlements("t", "ORIGINAL", 10)).isEmpty();
        assertThat(settlements("other", "A", 9)).isEmpty();
        assertThat(settlements("t", "B", 10))
                .singleElement()
                .satisfies(
                        settlement -> {
                            assertThat(settlement.orderId()).isEqualTo("1");
                            assertThat(settlement.orderNo()).isEqualTo("ORDER-1");
                            assertThat(settlement.originalEmployeeCode()).isEqualTo("ORIGINAL");
                            assertThat(settlement.paymentId()).isEqualTo("2");
                            assertThat(settlement.paymentNo()).isEqualTo("PAY-2");
                            assertThat(settlement.paymentOwnerCode()).isEqualTo("B");
                            assertThat(settlement.paymentOwnerName()).isEqualTo("回款人B");
                            assertThat(settlement.paymentOwnerEvidence()).isEqualTo("MANUALLY_CONFIRMED");
                            assertThat(settlement.qualityStatus()).isEqualTo("COMPLETE");
                            assertThat(settlement.fullyPaidDate()).isEqualTo(LocalDate.of(2026, 10, 2));
                            assertThat(settlement.actualCashDate()).isEqualTo(LocalDate.of(2026, 10, 2));
                            assertThat(settlement.orderAmount()).isEqualByComparingTo("6000");
                            assertThat(settlement.lines())
                                    .extracting(OrderPayrollSettlementView.ProductLine::lineId)
                                    .containsExactly("1", "2");
                            assertThat(settlement.lines().getLast().productVariantId()).isEqualTo(2L);
                            assertThat(settlement.lines().getLast().unit()).isEqualTo("BOX");
                            assertThat(settlement.lines().getLast().quantity()).isEqualByComparingTo("10");
                            assertThat(settlement.lines().getLast().lineAmount()).isEqualByComparingTo("5000");
                        });
    }

    @Test
    void firstClosingPaymentWithoutOwnerEvidenceStaysPendingEvenWithSameTimeIdentifiedPayment() {
        paymentOwner(1, "A", "CONFIRMED");
        paymentOwner(2, "B", "   ");
        jdbc.update(
                """
                INSERT INTO order_payment_record(id,tenant_id,order_id,paid_amount,payment_time,payment_status_code,deleted,
                                                 payment_owner_code,payment_owner_evidence)
                VALUES (3,'t',1,500,'2026-10-01 17:00:00','CHECKED',0,'C','CONFIRMED')
                """);
        for (String employee : List.of("A", "B", "ORIGINAL")) {
            assertThat(settlements("t", employee, 10))
                    .singleElement()
                    .satisfies(
                            settlement -> {
                                assertThat(settlement.paymentId()).isEqualTo("2");
                                assertThat(settlement.paymentOwnerCode()).isNull();
                                assertThat(settlement.qualityStatus()).isEqualTo("PAYMENT_OWNER_PENDING");
                            });
        }
        assertThat(settlements("t", "C", 10)).isEmpty();
        assertThat(settlements("t", "OTHER", 10)).isEmpty();
    }

    @Test
    void refundBeforeThresholdWaitsForNextReceiptAndUsesAllocationDateForAdvance() {
        paymentOwner(1, "A", "CONFIRMED");
        paymentOwner(2, "B", "CONFIRMED");
        jdbc.update(
                "INSERT INTO order_refund_record VALUES(1,'t',1,1000,'2026-09-20"
                        + " 02:00:00','CONFIRMED',0)");
        assertThat(settlements("t", "B", 10)).isEmpty();

        jdbc.update(
                """
                INSERT INTO order_payment_record(id,tenant_id,order_id,paid_amount,payment_time,payment_status_code,deleted,
                                                 payment_owner_code,payment_owner_evidence,allocation_time,actual_cash_time)
                VALUES (3,'t',1,1000,'2026-10-03 02:00:00','CHECKED',0,'B','CONFIRMED',
                        '2026-11-03 02:00:00','2026-10-03 02:00:00')
                """);
        assertThat(settlements("t", "B", 10)).isEmpty();
        assertThat(settlements("t", "B", 11))
                .singleElement()
                .satisfies(
                        settlement -> {
                            assertThat(settlement.paymentId()).isEqualTo("3");
                            assertThat(settlement.fullyPaidDate()).isEqualTo(LocalDate.of(2026, 11, 3));
                            assertThat(settlement.actualCashDate()).isEqualTo(LocalDate.of(2026, 10, 3));
                            assertThat(settlement.refundedAmount()).isEqualByComparingTo("1000");
                        });
    }

    @Test
    void verifiedRecipientCanOwnSettlementWithNoOriginalSellerButQualityBlocksAreRetained() {
        jdbc.update("DELETE FROM order_attribution_snapshot");
        jdbc.update("UPDATE order_sales_order SET owner_employee_code=NULL,region_code=NULL");
        paymentOwner(1, "B", "CONFIRMED");
        paymentOwner(2, "B", "CONFIRMED");
        assertThat(settlements("t", "B", 10))
                .singleElement()
                .satisfies(
                        settlement -> {
                            assertThat(settlement.originalEmployeeCode()).isNull();
                            assertThat(settlement.orderAmount()).isEqualByComparingTo("6000");
                            assertThat(settlement.qualityStatus()).isEqualTo("COMPLETE");
                        });
        jdbc.update("INSERT INTO order_financial_event VALUES('t',1,0,'WRITE_OFF','2026-10-02 01:00:00')");
        assertThat(settlements("t", "B", 10))
                .extracting(OrderPayrollSettlementView::qualityStatus)
                .containsExactly("NON_CASH_SETTLEMENT");
        jdbc.update("INSERT INTO order_attribution_adjustment VALUES('t',1,'PENDING')");
        assertThat(settlements("t", "B", 10))
                .extracting(OrderPayrollSettlementView::qualityStatus)
                .containsExactly("ATTRIBUTION_PENDING");
    }

    @Test
    void exposesSettlementSourceOnlyUnderTheSignedTenantServicePermission() throws Exception {
        var tenant = UUID.randomUUID();
        var user = UUID.randomUUID();
        var scope = "order:payroll:source-read";
        var from = LocalDate.of(2026, 11, 1);
        var to = from.withDayOfMonth(30);
        var store = mock(OrderCostSourceStore.class);
        var service = new OrderCostSourceService(store);
        var endpoint = MockMvcBuilders.standaloneSetup(new OrderCostSourceController(service)).build();
        try {
            TestAuthorizationContext.set(new CallerIdentity(
                    "TENANT", user, tenant, user, null, UUID.randomUUID(), 0, 0, 0,
                    Set.of(), Set.of(scope)));
            assertThatThrownBy(() -> service.payrollSettlements("EMP-B", from, to))
                    .isInstanceOf(AuthorizationDeniedException.class);
            TestAuthorizationContext.set(new CallerIdentity(
                    "SERVICE", user, tenant, null, null, UUID.randomUUID(), 0, 0, 0,
                    Set.of(), Set.of()));
            assertThatThrownBy(() -> service.payrollSettlements("EMP-B", from, to))
                    .isInstanceOf(AuthorizationDeniedException.class);
            TestAuthorizationContext.set(new CallerIdentity(
                    "SERVICE", user, tenant, null, null, UUID.randomUUID(), 0, 0, 0,
                    Set.of(), Set.of(scope)));
            assertThatThrownBy(() -> service.payrollSettlements("EMP-B", to, from))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(store);

            endpoint.perform(get("/api/v1/orders/cost-sources/payroll-settlements")
                            .param("employeeCode", "EMP-B")
                            .param("from", from.toString())
                            .param("to", to.toString()))
                    .andExpect(status().isOk());
            verify(store).payrollSettlements(tenant.toString(), "EMP-B", from, to);
        } finally {
            TestAuthorizationContext.clear();
        }
    }

    private void paymentOwner(long paymentId, String employee, String evidence) {
        jdbc.update(
                """
                UPDATE order_payment_record SET payment_no=?,payment_owner_code=?,payment_owner_name=?,
                                                payment_owner_evidence=? WHERE tenant_id='t' AND id=?
                """,
                "PAY-" + paymentId, employee, "回款人" + employee, evidence, paymentId);
    }

    private List<OrderPayrollSettlementView> settlements(String tenant, String employee, int month) {
        LocalDate from = LocalDate.of(2026, month, 1);
        return repository.payrollSettlements(tenant, employee, from, from.plusMonths(1).minusDays(1));
    }
}

package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Verifies the refund facts consumed by payroll, with real SQL and Beijing cutoff boundaries. */
class PayrollRefundCutoffAcceptanceTest {
    final OrderPayrollSourceContractTest fixture = new OrderPayrollSourceContractTest();

    @BeforeEach
    void setup() throws Exception {
        fixture.setup();
    }

    @Test
    void laterFullRefundDoesNotRewriteEarlierPaidCutoffOrOriginalOwner() {
        fixture.jdbc.update("INSERT INTO order_refund_record VALUES"
                + "(1,'t',1,6000,'2026-10-02 16:00:00','CONFIRMED',0)");
        var beforeRefund = fixture.mapper.payrollLines("t", "ORIGINAL", null,
                LocalDateTime.of(2026, 10, 2, 16, 0));
        assertThat(beforeRefund).hasSize(2).allSatisfy(line -> {
            assertThat(line.fullyPaidDate()).isEqualTo(LocalDate.of(2026, 10, 2));
            assertThat(line.refundedAmount()).isZero();
            assertThat(line.lastRefundDate()).isNull();
            assertThat(line.employeeCode()).isEqualTo("ORIGINAL");
            assertThat(line.departmentId()).isEqualTo(7L);
        });
        var afterRefund = fixture.mapper.payrollLines("t", "ORIGINAL", null,
                LocalDateTime.of(2026, 10, 31, 16, 0));
        assertThat(afterRefund).hasSize(2).allSatisfy(line -> {
            assertThat(line.fullyPaidDate()).isEqualTo(LocalDate.of(2026, 10, 2));
            assertThat(line.refundedAmount()).isEqualByComparingTo("6000");
            assertThat(line.lastRefundDate()).isEqualTo(LocalDate.of(2026, 10, 3));
        });
        assertThat(fixture.mapper.payrollLines("t", "NEW", null,
                LocalDateTime.of(2026, 10, 31, 16, 0))).isEmpty();
    }

    @Test
    void pendingVoidedDeletedAndOtherTenantRefundsDoNotChangePayrollFacts() {
        fixture.jdbc.update("INSERT INTO order_refund_record VALUES"
                + "(1,'t',1,100,'2026-10-03 02:00:00','PENDING',0),"
                + "(2,'t',1,200,'2026-10-03 02:00:00','VOID',0),"
                + "(3,'t',1,300,'2026-10-03 02:00:00','CONFIRMED',1),"
                + "(4,'other',1,400,'2026-10-03 02:00:00','CONFIRMED',0)");
        assertThat(fixture.mapper.payrollLines("t", "ORIGINAL", null,
                LocalDateTime.of(2026, 10, 31, 16, 0))).hasSize(2).allSatisfy(line -> {
                    assertThat(line.fullyPaidDate()).isEqualTo(LocalDate.of(2026, 10, 2));
                    assertThat(line.refundedAmount()).isZero();
                });
    }
}

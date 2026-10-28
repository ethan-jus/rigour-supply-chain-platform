package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class PayrollAttendanceGateTest {
    final HrAttendanceStore attendance = mock(HrAttendanceStore.class);
    final HrPayrollComponentLifecycleStore reviews = mock(HrPayrollComponentLifecycleStore.class);
    final PayrollAttendanceGate gate = new PayrollAttendanceGate(attendance, reviews);
    final PayrollDetailView payroll =
            new PayrollDetailView(
                    "P",
                    "2026-09",
                    "E1",
                    "员工",
                    1L,
                    "销售部",
                    null,
                    "CONFIRMED",
                    1,
                    null,
                    Instant.EPOCH);

    void confirmed() {
        var snapshot =
                new AttendanceRow(
                        "E1",
                        "员工",
                        "销售部",
                        22,
                        "DEPARTMENT",
                        BigDecimal.TEN,
                        BigDecimal.ZERO,
                        10,
                        null,
                        null,
                        "NONE",
                        "CONFIRMED",
                        "AVAILABLE",
                        List.of(),
                        "",
                        1);
        when(attendance.employee("T", YearMonth.of(2026, 9), "E1", "hr:payroll:paid"))
                .thenReturn(
                        new HrAttendanceStore.Employee(
                                "E1",
                                "员工",
                                1L,
                                "销售部",
                                LocalDate.of(2026, 1, 1),
                                null,
                                null,
                                snapshot));
    }

    @Test
    void unconfirmedAttendanceBlocksPaymentRegardlessOfSalaryConfirmation() {
        assertThatThrownBy(() -> gate.require("T", payroll, "hr:payroll:paid"))
                .hasMessageContaining("考勤尚未确认");
        verifyNoInteractions(reviews);
    }

    @Test
    void confirmedAttendanceAllowsPaymentButPendingReviewStillBlocksIt() {
        confirmed();
        assertThat(gate.blockReason("T", payroll, "hr:payroll:paid")).isNull();
        var review = mock(PayrollRuleReviewView.class);
        when(review.employeeCode()).thenReturn("E1");
        when(reviews.reviews("T", "ATTENDANCE", "2026-09")).thenReturn(List.of(review));
        assertThatThrownBy(() -> gate.require("T", payroll, "hr:payroll:paid"))
                .hasMessageContaining("待复核");
    }

    @Test
    void alreadyPaidHistoricalRecordDoesNotRequireRecheckingAttendance() {
        var paid =
                new PayrollDetailView(
                        "P",
                        "2026-09",
                        "E1",
                        "员工",
                        1L,
                        "销售部",
                        null,
                        "PAID",
                        2,
                        null,
                        Instant.EPOCH);
        assertThat(gate.blockReason("T", paid, "hr:payroll:paid")).isNull();
        verifyNoInteractions(attendance, reviews);
    }

    @Test
    void reconfirmingAttendanceDoesNotAllowAnOlderSalaryToBePaidWithoutRecalculation() {
        confirmed();
        when(attendance.reopenedSince("T", YearMonth.of(2026, 9), "E1", Instant.EPOCH))
                .thenReturn(true);
        assertThat(gate.blockReason("T", payroll, "hr:payroll:paid")).contains("重新核算", "撤回确认");
        var fresh =
                new PayrollDetailView(
                        "P",
                        "2026-09",
                        "E1",
                        "员工",
                        1L,
                        "销售部",
                        null,
                        "CONFIRMED",
                        3,
                        null,
                        Instant.parse("2026-10-27T10:00:00Z"));
        assertThat(gate.blockReason("T", fresh, "hr:payroll:paid")).isNull();
        verify(attendance).reopenedSince("T", YearMonth.of(2026, 9), "E1", fresh.updatedTime());
    }
}

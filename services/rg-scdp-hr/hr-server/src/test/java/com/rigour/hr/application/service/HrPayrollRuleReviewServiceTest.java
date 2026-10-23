package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

class HrPayrollRuleReviewServiceTest {
    final HrPayrollComponentLifecycleStore lifecycle = mock(HrPayrollComponentLifecycleStore.class);
    final HrPayrollStore payroll = mock(HrPayrollStore.class);
    final HrAttendanceStore attendance = mock(HrAttendanceStore.class);
    final HrPayrollRuleReviewService service =
            new HrPayrollRuleReviewService(lifecycle, payroll, attendance);
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();

    void permissions(String... permissions) {
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of(permissions)));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    PayrollRuleReviewView review(String subject) {
        return new PayrollRuleReviewView(
                "review",
                subject,
                "subject",
                "E1",
                null,
                "2026-10",
                "测试规则",
                "DISABLE",
                "规则调整",
                Instant.EPOCH);
    }

    @Test
    void attendanceReadersUseTheirReadScopeWithoutRequiringWriteAccess() {
        permissions("hr:attendance:read");
        when(lifecycle.reviews(tenant.toString(), "ATTENDANCE", "2026-10"))
                .thenReturn(List.of(review("ATTENDANCE")));
        when(attendance.employee(
                        tenant.toString(), YearMonth.of(2026, 10), "E1", "hr:attendance:read"))
                .thenReturn(
                        new HrAttendanceStore.Employee(
                                "E1", "员工一", 1L, "销售部", null, null, null, null));
        assertThat(service.list("ATTENDANCE", "2026-10"))
                .extracting(PayrollRuleReviewView::employeeName)
                .containsExactly("员工一");
        verify(attendance, never()).employee(anyString(), any(), anyString());
        when(lifecycle.review(tenant.toString(), "review"))
                .thenReturn(Optional.of(review("ATTENDANCE")));
        assertThatThrownBy(() -> service.resolve("review", new PayrollRuleReviewCommand("核对完成")))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void aPayrollOutsideTheReadersScopeDoesNotBreakOrLeakTheReviewList() {
        permissions("hr:payroll:read");
        when(lifecycle.reviews(tenant.toString(), "PAYROLL", "2026-10"))
                .thenReturn(List.of(review("PAYROLL")));
        when(payroll.detail(tenant.toString(), "subject", "hr:payroll:read"))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "工资单不存在或无权限", List.of()));
        assertThat(service.list("PAYROLL", "2026-10")).isEmpty();
    }

    @Test
    void completingReviewRequiresTheSubjectWritePermissionAndAnExplanation() {
        permissions("hr:attendance:write");
        when(lifecycle.review(tenant.toString(), "review"))
                .thenReturn(Optional.of(review("ATTENDANCE")));
        when(attendance.employee(
                        tenant.toString(), YearMonth.of(2026, 10), "E1", "hr:attendance:write"))
                .thenReturn(
                        new HrAttendanceStore.Employee(
                                "E1", "员工一", 1L, "销售部", null, null, null, null));
        assertThatThrownBy(() -> service.resolve("review", new PayrollRuleReviewCommand(" ")))
                .hasMessageContaining("复核处理说明");
        service.resolve("review", new PayrollRuleReviewCommand("重算确认完成"));
        verify(lifecycle).resolve(tenant.toString(), "review", "重算确认完成", user.toString());
    }
}

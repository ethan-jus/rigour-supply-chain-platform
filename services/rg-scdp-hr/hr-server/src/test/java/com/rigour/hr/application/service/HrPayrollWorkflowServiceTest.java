package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class HrPayrollWorkflowServiceTest {
    final HrPayrollStore store = mock(HrPayrollStore.class);
    final HrPayrollService payroll = mock(HrPayrollService.class);
    final HrEmployeePayrollProfileStore profiles = mock(HrEmployeePayrollProfileStore.class);
    final HrPayrollWorkflowService service = new HrPayrollWorkflowService(store, payroll, profiles);

    @BeforeEach
    void setup() {
        UUID user = UUID.randomUUID(), tenant = UUID.randomUUID();
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
                        Set.of(
                                "hr:payroll:read",
                                "hr:payroll:calculate",
                                "hr:payroll:paid",
                                "hr:payroll:confirm",
                                "hr:employee:update")));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void preparationPreservesUnknownCategoryAndExcludesFutureEntries() {
        when(store.employees(anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new PayrollEmployeeView(
                                        "E1",
                                        "未知类别",
                                        10L,
                                        "销售部",
                                        "城市",
                                        LocalDate.of(2025, 1, 1),
                                        null,
                                        null,
                                        1,
                                        1L),
                                new PayrollEmployeeView(
                                        "E2",
                                        "未来入职",
                                        10L,
                                        "销售部",
                                        "城市",
                                        LocalDate.of(2026, 10, 1),
                                        null,
                                        "SALES",
                                        1,
                                        2L)));
        var rows = service.preparation("2026-09").employees();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().payrollCategory()).isNull();
        assertThat(rows.getFirst().profileIssues()).contains("未设置薪酬类别");
        verifyNoInteractions(payroll, profiles);
    }

    @Test
    void preparationKeepsFormerEmployeeWithPendingCorrection() {
        when(store.employees(anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new PayrollEmployeeView(
                                        "E1",
                                        "已离职",
                                        10L,
                                        "销售部",
                                        "城市",
                                        LocalDate.of(2025, 1, 1),
                                        LocalDate.of(2026, 8, 31),
                                        "SALES",
                                        1,
                                        1L)));
        when(store.corrections(anyString(), eq("E1"), eq("2026-09")))
                .thenReturn(
                        List.of(
                                new PayrollCorrectionView(
                                        "C1",
                                        "P0",
                                        "2026-08",
                                        "2026-09",
                                        BigDecimal.TEN,
                                        "补发",
                                        "ACTIVE",
                                        1,
                                        Instant.EPOCH)));
        var rows = service.preparation("2026-09").employees();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().profileIssues()).anyMatch(i -> i.contains("非在职月份有补差"));
    }

    @Test
    void invalidPaymentMetadataDoesNotReachPersistence() {
        String request = UUID.randomUUID().toString();
        assertThatThrownBy(
                        () ->
                                service.pay(
                                        "P1",
                                        new PayrollPaymentCommand(
                                                request,
                                                1,
                                                LocalDate.now(ZoneId.of("Asia/Shanghai"))
                                                        .plusDays(1),
                                                BigDecimal.TEN,
                                                "BANK",
                                                "说明")))
                .hasMessageContaining("实际发放日期");
        assertThatThrownBy(
                        () ->
                                service.pay(
                                        "P1",
                                        new PayrollPaymentCommand(
                                                request,
                                                1,
                                                LocalDate.of(2026, 9, 1),
                                                new BigDecimal("-1"),
                                                "BANK",
                                                "说明")))
                .hasMessageContaining("非负");
        assertThatThrownBy(
                        () ->
                                service.pay(
                                        "P1",
                                        new PayrollPaymentCommand(
                                                request,
                                                1,
                                                LocalDate.of(2026, 9, 1),
                                                BigDecimal.TEN,
                                                " ",
                                                "说明")))
                .hasMessageContaining("凭证号");
        verify(store, never()).pay(anyString(), anyString(), any(), anyString());
    }

    @Test
    void negativeCorrectionsAreAllowedButZeroIsRejected() {
        String request = UUID.randomUUID().toString();
        service.correct(
                "P1",
                new PayrollCorrectionCommand(request, 1, "2026-10", new BigDecimal("-20"), "冲回"));
        verify(store)
                .correct(anyString(), eq("P1"), argThat(c -> c.amount().signum() < 0), anyString());
        assertThatThrownBy(
                        () ->
                                service.correct(
                                        "P1",
                                        new PayrollCorrectionCommand(
                                                request, 1, "2026-10", BigDecimal.ZERO, "冲回")))
                .hasMessageContaining("不能为0");
    }

    @Test
    void profileBatchRejectsDuplicateEmployeesAndUnexplainedChanges() {
        var patch = new PayrollProfilePatchCommand("E1", 1, "SALES", null, null, null, null, null);
        assertThatThrownBy(
                        () ->
                                service.updateProfiles(
                                        new PayrollBatchProfileCommand(
                                                List.of(patch, patch), "补齐")))
                .hasMessageContaining("重复");
        assertThatThrownBy(
                        () ->
                                service.updateProfiles(
                                        new PayrollBatchProfileCommand(List.of(patch), " ")))
                .hasMessageContaining("维护原因");
        verifyNoInteractions(profiles);
        service.updateProfiles(new PayrollBatchProfileCommand(List.of(patch), "HR核实类别"));
        verify(profiles)
                .update(
                        anyString(),
                        argThat(c -> c.employees().getFirst().regularized() == null),
                        anyString());
    }

    @Test
    void profileSalaryRequiresNonNegativeDecimalWithAtMostTwoDecimals() {
        for (String value : List.of("-1", "4000+500", "100.001", " ", "1e4")) {
            var patch =
                    new PayrollProfilePatchCommand(
                            "E1", 1, null, null, null, LocalDate.of(2026, 9, 1), value, null);
            assertThatThrownBy(
                            () ->
                                    service.updateProfiles(
                                            new PayrollBatchProfileCommand(List.of(patch), "测试金额")))
                    .hasMessageContaining("非负金额");
        }
        verifyNoInteractions(profiles);
        var patch =
                new PayrollProfilePatchCommand(
                        "E1", 1, null, null, null, LocalDate.of(2026, 9, 1), "0", "5000.50");
        service.updateProfiles(new PayrollBatchProfileCommand(List.of(patch), "测试金额"));
        verify(profiles).update(anyString(), any(), anyString());
    }
}

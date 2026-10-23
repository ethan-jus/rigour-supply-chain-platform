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
        assertThat(rows.getFirst().profileIssues()).contains("薪资类别未维护，请在员工档案中设置");
        assertThat(rows.getFirst().canCalculate()).isFalse();
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
        assertThat(rows.getFirst().profileIssues()).isEmpty();
        assertThat(rows.getFirst().leaveDate()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    void previewComparesLockedSalaryWithoutAnyWritesAndDeduplicatesEmployees() {
        var e =
                new PayrollEmployeeView(
                        "E1",
                        "员工",
                        10L,
                        "销售",
                        "城市",
                        LocalDate.of(2025, 1, 1),
                        null,
                        "SALES",
                        1,
                        1L);
        when(store.employee(anyString(), eq("E1"), eq("hr:payroll:calculate"))).thenReturn(e);
        var before = calculation("old", "8000");
        var current = calculation("new", "8800");
        when(store.byMonth(anyString(), eq("E1"), eq("2026-09")))
                .thenReturn(
                        Optional.of(
                                new PayrollDetailView(
                                        "P1",
                                        "2026-09",
                                        "E1",
                                        "员工",
                                        10L,
                                        "销售",
                                        "城市",
                                        "PAID",
                                        3,
                                        before,
                                        Instant.EPOCH)));
        when(payroll.calculation(anyString(), eq(e), eq(YearMonth.of(2026, 9))))
                .thenReturn(current);
        var result = service.preview(new PayrollCalculateCommand("2026-09", List.of("E1", "E1")));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().savedStatus()).isEqualTo("PAID");
        assertThat(result.getFirst().calculation().netPay()).isEqualByComparingTo("8800");
        assertThat(result.getFirst().previousCalculation().netPay()).isEqualByComparingTo("8000");
        assertThat(result.getFirst().sourceChanged()).isTrue();
        verify(store, never())
                .saveCalculation(anyString(), anyString(), anyString(), any(), any(), anyString());
        verify(store, never()).lock(anyString());
        verifyNoInteractions(profiles);
    }

    @Test
    void invalidPreviewBatchNeverReadsEmployees() {
        assertThatThrownBy(() -> service.preview(new PayrollCalculateCommand("2026-09", List.of())))
                .hasMessageContaining("1至100");
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        new PayrollCalculateCommand("2026-13", List.of("E1"))))
                .hasMessageContaining("月份格式");
        verifyNoInteractions(store, payroll);
    }

    private PayrollCalculationView calculation(String fingerprint, String amount) {
        var value = new BigDecimal(amount);
        return new PayrollCalculationView(
                value,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                value,
                value,
                List.of(),
                List.of(),
                List.of(),
                null,
                fingerprint,
                null,
                null);
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
    void paidSalaryRetriesRemainIdempotentWhenLaterRuleChangesRequireReview() {
        var reviews = mock(HrPayrollComponentLifecycleStore.class);
        service.setRuleReviews(reviews);
        var paid =
                new PayrollDetailView(
                        "P1",
                        "2026-09",
                        "E1",
                        "员工",
                        1L,
                        "销售",
                        "北京",
                        "PAID",
                        2,
                        null,
                        Instant.EPOCH);
        when(store.detail(anyString(), eq("P1"), eq("hr:payroll:paid"))).thenReturn(paid);
        when(store.pay(anyString(), eq("P1"), any(), anyString())).thenReturn(paid);
        var command =
                new PayrollPaymentCommand(
                        UUID.randomUUID().toString(),
                        1,
                        LocalDate.of(2026, 9, 1),
                        BigDecimal.TEN,
                        "BANK",
                        "重试同一发放请求");
        assertThat(service.pay("P1", command)).isSameAs(paid);
        verifyNoInteractions(reviews);
    }

    @Test
    void unresolvedRuleReviewPreventsPaymentOfAConfirmedSalary() {
        var reviews = mock(HrPayrollComponentLifecycleStore.class);
        service.setRuleReviews(reviews);
        when(store.detail(anyString(), eq("P1"), eq("hr:payroll:paid")))
                .thenReturn(
                        new PayrollDetailView(
                                "P1",
                                "2026-09",
                                "E1",
                                "员工",
                                1L,
                                "销售",
                                "北京",
                                "CONFIRMED",
                                1,
                                null,
                                Instant.EPOCH));
        when(reviews.reviews(anyString(), eq("PAYROLL"), eq("2026-09")))
                .thenReturn(
                        List.of(
                                new PayrollRuleReviewView(
                                        "review",
                                        "PAYROLL",
                                        "P1",
                                        "E1",
                                        "员工",
                                        "2026-09",
                                        "提成规则",
                                        "DISABLE",
                                        "调整",
                                        Instant.EPOCH)));
        assertThatThrownBy(
                        () ->
                                service.pay(
                                        "P1",
                                        new PayrollPaymentCommand(
                                                UUID.randomUUID().toString(),
                                                1,
                                                LocalDate.of(2026, 9, 30),
                                                BigDecimal.TEN,
                                                "银行凭证",
                                                "工资发放")))
                .hasMessageContaining("规则变更待复核");
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
    void correctionRequiresPayrollConfirmationPermission() {
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
                        Set.of("hr:payroll:read")));
        assertThatThrownBy(
                        () ->
                                service.correct(
                                        "P1",
                                        new PayrollCorrectionCommand(
                                                UUID.randomUUID().toString(),
                                                1,
                                                "2026-10",
                                                BigDecimal.TEN,
                                                "补发")))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
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

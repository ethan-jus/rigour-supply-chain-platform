package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class ConfiguredPayrollServiceTest {
    HrPayrollStore payroll = mock(HrPayrollStore.class);
    HrPayrollSchemeStore schemes = mock(HrPayrollSchemeStore.class);
    HrPayrollSource source = mock(HrPayrollSource.class);
    ConfiguredCommissionService commissions = mock(ConfiguredCommissionService.class);
    PayrollEmployeeView employee =
            new PayrollEmployeeView(
                    "A", "员工", 1L, "销售部", null, LocalDate.of(2026, 9, 15), null, "SALES");
    ConfiguredPayrollService service;
    HrAttendanceStore attendance = mock(HrAttendanceStore.class);
    PayrollRuleResolver rules = mock(PayrollRuleResolver.class);

    @BeforeEach
    void setup() {
        when(schemes.versions("t"))
                .thenReturn(
                        List.of(
                                PayrollSchemeTestFixture.version(
                                        PayrollSchemeTestFixture.definition())));
        when(schemes.facts(anyString(), anyString(), anyString()))
                .thenReturn(new PayrollSchemeFactsView(0, null, List.of(), ""));
        when(payroll.input(anyString(), anyString(), anyString()))
                .thenReturn(new PayrollInputCommand(0, null, null, BigDecimal.ZERO, ""));
        when(payroll.departmentAt(anyString(), anyString(), any())).thenReturn(1L);
        when(commissions.calculate(
                        anyString(), any(), any(), any(), anyList(), anyList(), anyList()))
                .thenReturn(
                        new ConfiguredCommissionResult(
                                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of()));
        when(attendance.employee(anyString(), any(), anyString()))
                .thenAnswer(
                        i -> {
                            var confirmation =
                                    new AttendanceRow(
                                            "A",
                                            "员工",
                                            "销售部",
                                            22,
                                            "DEPARTMENT",
                                            new BigDecimal("22"),
                                            BigDecimal.ZERO,
                                            22,
                                            null,
                                            null,
                                            "NONE",
                                            "CONFIRMED",
                                            "AVAILABLE",
                                            List.of(),
                                            "",
                                            1);
                            return new HrAttendanceStore.Employee(
                                    "A",
                                    "员工",
                                    1L,
                                    "销售部",
                                    employee.entryDate(),
                                    null,
                                    new AttendanceRecordCommand(null, null, "NONE", "", 1),
                                    confirmation);
                        });
        service =
                new ConfiguredPayrollService(
                        payroll,
                        schemes,
                        source,
                        attendance,
                        commissions,
                        JsonMapper.builder().build());
        service.setRules(rules);
        when(rules.resolve(anyString(), any(), any(), any()))
                .thenReturn(
                        new PayrollRuleMatch(
                                PayrollSchemeTestFixture.version(
                                        PayrollSchemeTestFixture.definition()),
                                List.of()));
    }

    @ParameterizedTest
    @CsvSource({"2026-10-01,false", "2026-05-01,true"})
    void normalPayrollCalculationIncludesHistoricalCommissionInGrossAndNet(String ruleFrom, boolean missingHistory) {
        var fixture = new ConfiguredCommissionServiceTest();
        fixture.setup();
        employee = fixture.employee;
        var original = PayrollSchemeTestFixture.version(PayrollSchemeTestFixture.definition());
        var selected = new PayrollSchemeVersionView(original.id(), original.schemeId(), 1, 1,
                "RESOLVED", original.definition(), original.reason(), original.createdBy(), null, original.updatedTime(), null);
        when(rules.resolve(anyString(), any(), any(), any())).thenReturn(new PayrollRuleMatch(selected, List.of()));
        var catalog = mock(HrPayrollComponentStore.class);
        var standard = new PayrollComponentVersionView("october-commission", "commission", 1,
                new PayrollComponentDefinition("10月提成", "COMMISSION", "ALL", List.of(1L),
                        LocalDate.parse(ruleFrom), LocalDate.MAX, null, null,
                        new PayrollSchemeCommissionPolicy("FULLY_PAID_DATE", "FULLY_PAID_DATE", original.definition().commission().rules()),
                        null, null, List.of()), "规则配置", "hr", Instant.EPOCH);
        when(catalog.versions("t")).thenReturn(List.of(standard));
        var engine = new ConfiguredCommissionService(payroll, source);
        engine.setRules(new PayrollRuleResolver(catalog));
        service = new ConfiguredPayrollService(payroll, schemes, source, attendance, engine, JsonMapper.builder().build());
        service.setRules(rules);
        var fact = fixture.line("history", 1, "C", true);
        if (missingHistory) {
            var mapper = JsonMapper.builder().build();
            tools.jackson.databind.node.ObjectNode node = mapper.valueToTree(fact);
            node.putNull("departmentId");
            fact = mapper.treeToValue(node, com.rigour.order.api.v1.model.OrderPayrollLineView.class);
            when(payroll.departmentAt(eq("t"), eq("A"), any())).thenAnswer(i ->
                    ((LocalDate) i.getArgument(2)).isBefore(LocalDate.of(2026, 9, 16)) ? null : 1L);
        }
        when(source.payrollLines(eq("t"), eq("A"), isNull(), any())).thenReturn(List.of(fact));
        when(source.productsByVariants(eq("t"), anyList())).thenReturn(List.of(fixture.product(1, 10)));
        when(source.productCategories("t")).thenReturn(List.of(new com.rigour.erp.api.v1.model.ErpPayrollCategoryView(10L, null, "方便面", 0)));
        when(source.performanceEvidence(eq("t"), eq("A"), any(), any())).thenReturn(new com.rigour.sales.api.v1.model.SalesPayrollEvidenceView(true, List.of()));
        var result = service.calculateIfConfigured("t", employee, YearMonth.of(2026, 10)).orElseThrow();
        assertThat(result.issues()).isEmpty();
        assertThat(result.commission()).isEqualByComparingTo("40");
        assertThat(result.grossPay()).isEqualByComparingTo(result.baseSalary().add(result.performance()).add(result.allowance()).add(new BigDecimal("40")));
        assertThat(result.netPay()).isEqualByComparingTo(result.grossPay().subtract(result.deduction()));
        assertThat(result.details()).singleElement().satisfies(d -> {
            assertThat(d.message()).contains("历史订单补算");
            assertThat(d.trace().schemeVersionId()).isEqualTo("october-commission");
        });
        var repeated = service.calculateIfConfigured("t", employee, YearMonth.of(2026, 10)).orElseThrow();
        assertThat(repeated.commission()).isEqualByComparingTo(result.commission());
        assertThat(repeated.sourceFingerprint()).isEqualTo(result.sourceFingerprint());
    }

    @Test
    void salesLeaveProratesBaseAndAllowanceButNotEarnedPerformance() {
        employee =
                new PayrollEmployeeView(
                        "A", "员工", 1L, "销售部", null, LocalDate.of(2026, 9, 1), null, "SALES");
        var confirmed =
                new AttendanceRow(
                        "A",
                        "员工",
                        "销售部",
                        22,
                        "DEPARTMENT",
                        new BigDecimal("11"),
                        new BigDecimal("11"),
                        11,
                        new BigDecimal("11"),
                        new BigDecimal("11"),
                        "PERSONAL",
                        "CONFIRMED",
                        "AVAILABLE",
                        List.of(),
                        "请假",
                        2,
                        List.of(
                                new AttendanceExceptionPeriod(
                                        LocalDateTime.of(2026, 9, 15, 0, 0),
                                        LocalDateTime.of(2026, 9, 30, 0, 0),
                                        "UNPAID_LEAVE",
                                        "请假")));
        when(attendance.employee(anyString(), any(), anyString()))
                .thenReturn(
                        new HrAttendanceStore.Employee(
                                "A",
                                "员工",
                                1L,
                                "销售部",
                                employee.entryDate(),
                                null,
                                new AttendanceRecordCommand(
                                        new BigDecimal("11"),
                                        new BigDecimal("11"),
                                        "PERSONAL",
                                        "请假",
                                        2),
                                confirmed));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.issues()).isEmpty();
        assertThat(result.baseSalary()).isEqualByComparingTo("1500");
        assertThat(result.allowance()).isEqualByComparingTo("375");
        assertThat(result.performance()).isEqualByComparingTo("1000");
        assertThat(result.grossPay()).isEqualByComparingTo("2875");
        assertThat(result.scheme().scheme().definition().proration().base())
                .isEqualTo("CONFIRMED_ATTENDANCE");
    }

    @Test
    void salesMissingAttendanceNeverSilentlyPaysFullBaseOrAllowance() {
        when(attendance.employee(anyString(), any(), anyString())).thenReturn(null);
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.grossPay()).isNull();
        assertThat(result.issues()).anyMatch(issue -> issue.contains("考勤尚未确认"));
        assertThat(result.performance()).isEqualByComparingTo("1000");
    }

    @Test
    void annualReferenceVersionIsFrozenAndChangingItInvalidatesTheConfirmationFingerprint() {
        var definition =
                PayrollSchemeTestFixture.definition()
                        .withCalendar(HrStatutoryCalendarResolverTest.automatic(), null);
        when(rules.resolve(anyString(), any(), any(), any()))
                .thenReturn(
                        new PayrollRuleMatch(
                                PayrollSchemeTestFixture.version(definition), List.of()));
        when(source.statutoryCalendar("t", 2026))
                .thenReturn(HrStatutoryCalendarResolverTest.reference(2026, "v1"));
        var before =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(before.scheme().scheme().definition().calendar().referenceVersion())
                .isEqualTo("v1");
        when(source.statutoryCalendar("t", 2026))
                .thenReturn(HrStatutoryCalendarResolverTest.reference(2026, "v2"));
        var after =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(after.sourceFingerprint()).isNotEqualTo(before.sourceFingerprint());
        assertThat(before.scheme().scheme().definition().calendar().referenceVersion())
                .isEqualTo("v1");
    }

    @Test
    void newEmploymentInTheSameDepartmentIsNotAMidMonthTransfer() {
        var month = YearMonth.of(2026, 10);
        employee =
                new PayrollEmployeeView(
                        "A", "员工", 1L, "销售部", null, month.atDay(29), null, "SALES");
        when(payroll.departmentAt("t", "A", month.atDay(1))).thenReturn(null);
        var result = service.calculateIfConfigured("t", employee, month).orElseThrow();
        assertThat(result.issues()).noneMatch(issue -> issue.contains("月内部门发生变化"));
        verify(payroll).departmentAt("t", "A", month.atDay(29));
    }

    private YearMonth lateEntryAttendance(String actual) {
        return lateEntryAttendance(actual, 22);
    }

    private YearMonth lateEntryAttendance(String actual, Integer departmentExpectedDays) {
        var month = YearMonth.of(2026, 10);
        employee =
                new PayrollEmployeeView(
                        "A", "员工", 1L, "销售部", null, month.atDay(29), null, "SALES");
        var original = PayrollSchemeTestFixture.definition();
        var definition =
                original.withCalendar(
                        new PayrollWorkCalendar(
                                List.of(1, 2, 3, 4, 5), List.of(), List.of(), List.of(),
                                original.calendar().window(), List.of(), null),
                        null);
        when(rules.resolve(anyString(), any(), any(), any()))
                .thenReturn(new PayrollRuleMatch(PayrollSchemeTestFixture.version(definition), List.of()));
        var actualDays = new BigDecimal(actual);
        var confirmed =
                new AttendanceRow(
                        "A", "员工", "销售部", departmentExpectedDays, "DEPARTMENT", actualDays,
                        BigDecimal.valueOf(departmentExpectedDays == null ? 0 : departmentExpectedDays)
                                .subtract(actualDays), actualDays.intValue(),
                        actualDays, null, "NONE", "CONFIRMED", "AVAILABLE", List.of(), "核定出勤", 1);
        when(attendance.employee(anyString(), any(), anyString()))
                .thenReturn(new HrAttendanceStore.Employee(
                        "A", "员工", 1L, "销售部", employee.entryDate(), null,
                        new AttendanceRecordCommand(actualDays, null, "NONE", "核定出勤", 1), confirmed));
        when(payroll.departmentAt("t", "A", month.atDay(1))).thenReturn(null);
        return month;
    }

    @ParameterizedTest
    @CsvSource({
        "22,2,68.18,272.73", "22,1,34.09,136.36", "22,0.5,17.05,68.18", "22,0,0,0",
        "18,2,83.33,333.33", "18,1,41.67,166.67", "18,0.5,20.83,83.33", "18,0,0,0"
    })
    void lateEntryAllowanceAndBaseUseConfirmedDepartmentWholeMonthDenominator(
            Integer departmentExpectedDays, String actual, String allowance, String base) {
        var month = lateEntryAttendance(actual, departmentExpectedDays);
        var result = service.calculateIfConfigured("t", employee, month).orElseThrow();
        assertThat(result.issues()).isEmpty();
        assertThat(result.allowance()).isEqualByComparingTo(allowance);
        assertThat(result.baseSalary()).isEqualByComparingTo(base);
        assertThat(result.scheme().explanations())
                .anyMatch(line -> line.contains("部门整月应出勤：" + departmentExpectedDays + "天")
                        && line.contains("实际出勤：" + actual + "天"));
    }

    @Test
    void lateEntryAllowanceRetainsManualOverride() {
        var month = lateEntryAttendance("1");
        when(payroll.input(anyString(), anyString(), anyString()))
                .thenReturn(new PayrollInputCommand(
                        1, null, null, BigDecimal.ZERO, "人工核定补贴", new BigDecimal("123")));
        var result = service.calculateIfConfigured("t", employee, month).orElseThrow();
        assertThat(result.allowance()).isEqualByComparingTo("123");
        assertThat(result.baseSalary()).isEqualByComparingTo("136.36");
    }

    @Test
    void lateEntryDoesNotReplaceWholeMonthDenominatorWithRemainingCalendarDays() {
        var month = lateEntryAttendance("1");
        var original = PayrollSchemeTestFixture.definition();
        var definition = original.withCalendar(
                new PayrollWorkCalendar(
                        List.of(1, 2, 3, 4, 5), List.of(month.atDay(31)), List.of(month.atDay(29), month.atDay(30)),
                        List.of(), original.calendar().window(), List.of(), null), null);
        when(rules.resolve(anyString(), any(), any(), any()))
                .thenReturn(new PayrollRuleMatch(PayrollSchemeTestFixture.version(definition), List.of()));
        var result = service.calculateIfConfigured("t", employee, month).orElseThrow();
        assertThat(result.issues()).isEmpty();
        assertThat(result.allowance()).isEqualByComparingTo("34.09");
    }

    @Test
    void lateEntryWithoutConfirmedAttendanceRemainsPending() {
        var month = lateEntryAttendance("0");
        when(attendance.employee(anyString(), any(), anyString())).thenReturn(null);
        var missing = service.calculateIfConfigured("t", employee, month).orElseThrow();
        assertThat(missing.allowance()).isNull();
        assertThat(missing.issues()).anyMatch(issue -> issue.contains("考勤尚未确认"));
    }

    @ParameterizedTest
    @CsvSource(value = {"0", "null"}, nullValues = "null")
    void lateEntryWithoutDepartmentWholeMonthExpectedDaysRemainsPending(Integer expected) {
        var month = lateEntryAttendance("0", expected);
        var result = service.calculateIfConfigured("t", employee, month).orElseThrow();
        assertThat(result.allowance()).isNull();
        assertThat(result.issues()).anyMatch(issue -> issue.contains("补贴金额或折算依据待核对"));
    }

    @Test
    void entryOnRestDayWithZeroAttendanceHasZeroAllowance() {
        var month = lateEntryAttendance("0");
        employee = new PayrollEmployeeView(
                "A", "员工", 1L, "销售部", null, month.atDay(31), null, "SALES");
        var result = service.calculateIfConfigured("t", employee, month).orElseThrow();
        assertThat(result.allowance()).isZero();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void lateEntryStillRequiresConfirmationForAnActualDepartmentTransfer() {
        var month = lateEntryAttendance("2");
        when(payroll.departmentAt("t", "A", month.atEndOfMonth())).thenReturn(2L);
        var result = service.calculateIfConfigured("t", employee, month).orElseThrow();
        assertThat(result.issues()).anyMatch(issue -> issue.contains("月内部门发生变化"));
    }

    @Test
    void firstCalendarMonthUsesBronzeBaseAndBothExemptions() {
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.issues()).isEmpty();
        assertThat(result.baseSalary()).isEqualByComparingTo("3000");
        assertThat(result.performance()).isEqualByComparingTo("1000");
        assertThat(result.allowance()).isEqualByComparingTo("750");
        assertThat(result.grossPay()).isEqualByComparingTo("4750");
        assertThat(result.scheme().salaryTier()).isEqualTo("BRONZE");
        verify(source, never()).performanceEvidence(anyString(), anyString(), any(), any());
    }

    @Test
    void unavailableProductSourceDoesNotBecomeZeroCommissionOrConfirmableSalary() {
        when(commissions.calculate(
                        anyString(), any(), any(), any(), anyList(), anyList(), anyList()))
                .thenThrow(new IllegalStateException("product source offline"));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.commission()).isNull();
        assertThat(result.grossPay()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("商品来源暂不可用"));
    }

    @Test
    void confirmationFingerprintChangesWhenMatchedCommissionChanges() {
        var before =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        when(commissions.calculate(
                        anyString(), any(), any(), any(), anyList(), anyList(), anyList()))
                .thenReturn(
                        new ConfiguredCommissionResult(
                                BigDecimal.TEN, BigDecimal.ZERO, List.of(), List.of()));
        var after =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(after.sourceFingerprint()).isNotEqualTo(before.sourceFingerprint());
    }

    @Test
    void missingIndependentRulesNeverFallBackToOldSchemeOrOldMonth() {
        when(rules.resolve(anyString(), any(), any(), any()))
                .thenReturn(new PayrollRuleMatch(null, List.of("员工薪资标准未配置")));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.grossPay()).isNull();
        assertThat(result.issues()).containsExactly("员工薪资标准未配置");
        verify(source, never()).payrollLines(anyString(), any(), any(), any());
    }

    @Test
    void unavailableOrdersDoNotBecomeZeroAchievement() {
        when(source.payrollLines(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("offline"));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.scheme().achievement()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("来源暂不可用"));
    }

    @Test
    void fixedHeadquartersSalaryWithoutPerformanceOrCommissionNeedsNoOrderSource() {
        useIndependentHeadquartersPolicy("NONE", List.of());
        when(source.payrollLines(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("order source offline"));
        when(commissions.calculate(
                        anyString(), any(), any(), any(), anyList(), anyList(), anyList()))
                .thenThrow(new IllegalStateException("commission product source offline"));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.issues()).isEmpty();
        assertThat(result.baseSalary()).isEqualByComparingTo("6000");
        assertThat(result.performance()).isZero();
        assertThat(result.commission()).isZero();
        assertThat(result.grossPay()).isEqualByComparingTo("6000");
        assertThat(result.netPay()).isEqualByComparingTo("6000");
        assertThat(result.scheme().achievement()).isNull();
        verify(source, never()).payrollLines(anyString(), any(), any(), any());
        verifyNoInteractions(commissions);
    }

    @Test
    void fixedHeadquartersPerformanceUsesPersonalCompletionWithoutOrderSource() {
        useIndependentHeadquartersPolicy("FIXED", List.of());
        when(source.payrollLines(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("order source offline"));
        when(schemes.facts(anyString(), anyString(), anyString()))
                .thenReturn(
                        new PayrollSchemeFactsView(
                                1, null, List.of(), "月度核定", new BigDecimal("80")));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.issues()).isEmpty();
        assertThat(result.performance()).isEqualByComparingTo("800");
        assertThat(result.grossPay()).isEqualByComparingTo("6800");
        verify(source, never()).payrollLines(anyString(), any(), any(), any());
    }

    @Test
    void headquartersWithCommissionRulesStillRequiresAvailableOrders() {
        useIndependentHeadquartersPolicy(
                "NONE", PayrollSchemeTestFixture.definition().commission().rules());
        when(source.payrollLines(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("order source offline"));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.grossPay()).isNull();
        assertThat(result.commission()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("订单付清核算来源暂不可用"));
        verify(source).payrollLines(eq("t"), eq(employee.employeeCode()), isNull(), any());
    }

    @Test
    void headquartersWithHistoricalSalesOrCommissionStillRequiresOrders() {
        useIndependentHeadquartersPolicy("NONE", List.of());
        when(rules.hasHistoricalOrderDependency(eq("t"), eq(employee), eq(Set.of(1L)), any()))
                .thenReturn(true);
        when(source.payrollLines(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("order source offline"));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.grossPay()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("订单付清核算来源暂不可用"));
    }

    @Test
    void headquartersWithLegacySalesSchemeStillRequiresOrders() {
        useIndependentHeadquartersPolicy("NONE", List.of());
        when(schemes.versions("t"))
                .thenReturn(
                        List.of(
                                PayrollSchemeTestFixture.version(
                                        PayrollSchemeTestFixture.definition())));
        when(source.payrollLines(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("order source offline"));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.grossPay()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("订单付清核算来源暂不可用"));
    }

    @Test
    void headquartersWithMissingAssignmentHistoryDoesNotAssumeNoHistoricalCommission() {
        useIndependentHeadquartersPolicy("NONE", List.of());
        when(payroll.departmentsDuring(anyString(), anyString(), any(), any()))
                .thenReturn(Set.of());
        when(source.payrollLines(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("order source offline"));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.grossPay()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("订单付清核算来源暂不可用"));
    }

    private void useIndependentHeadquartersPolicy(
            String mode, List<PayrollSchemeCommissionRule> commissionRules) {
        employee =
                new PayrollEmployeeView(
                        "A",
                        "总部员工",
                        1L,
                        "总部",
                        null,
                        LocalDate.of(2026, 9, 15),
                        null,
                        "HEADQUARTERS");
        var d = PayrollSchemeTestFixture.definition();
        var definition =
                new PayrollSchemeDefinition(
                        d.name(),
                        "HEADQUARTERS",
                        d.departmentIds(),
                        d.employeeCodes(),
                        d.effectiveFrom(),
                        d.effectiveTo(),
                        new PayrollSchemeBaseSalary(
                                "FIXED", new BigDecimal("6000"), List.of(), null),
                        new BigDecimal("1000"),
                        mode,
                        null,
                        null,
                        d.calendar(),
                        d.proration(),
                        List.of(),
                        new PayrollSchemeCommissionPolicy(
                                "ORDER_DATE", "ORDER_DATE", commissionRules),
                        d.remark());
        when(rules.resolve(anyString(), any(), any(), any()))
                .thenReturn(
                        new PayrollRuleMatch(
                                PayrollSchemeTestFixture.version(definition), List.of()));
        when(schemes.versions("t"))
                .thenReturn(List.of(PayrollSchemeTestFixture.version(definition)));
        when(payroll.departmentsDuring(anyString(), anyString(), any(), any()))
                .thenReturn(Set.of(1L));
    }

    @Test
    void silverGradeSelectsBothSalaryAndPerformanceAndCapsCollectionAtOne() {
        employee =
                new PayrollEmployeeView(
                        "A", "员工", 1L, "销售部", null, LocalDate.of(2026, 8, 1), null, "SALES");
        var line =
                new com.rigour.order.api.v1.model.OrderPayrollLineView(
                        "L",
                        "O",
                        "SO",
                        "A",
                        "C",
                        1L,
                        1L,
                        1L,
                        "方便面",
                        "BOX",
                        LocalDate.of(2026, 9, 1),
                        BigDecimal.ONE,
                        new BigDecimal("11000"),
                        new BigDecimal("11000"),
                        new BigDecimal("11000"),
                        new BigDecimal("11000"),
                        LocalDate.of(2026, 9, 2),
                        BigDecimal.ZERO,
                        null,
                        "COMPLETE");
        when(source.payrollLines(eq("t"), eq("A"), isNull(), any())).thenReturn(List.of(line));
        when(source.performanceEvidence(anyString(), anyString(), any(), any()))
                .thenReturn(
                        new com.rigour.sales.api.v1.model.SalesPayrollEvidenceView(
                                true, List.of()));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.issues()).isEmpty();
        assertThat(result.baseSalary()).isEqualByComparingTo("4000");
        assertThat(result.scheme().performanceBase()).isEqualByComparingTo("1500");
        assertThat(result.performanceDetail().processPay()).isZero();
        assertThat(result.performanceDetail().collectionPay()).isEqualByComparingTo("900");
        assertThat(result.grossPay()).isEqualByComparingTo("5650");
    }

    @Test
    void incompleteOrderAttributionMustNotSelectBronzeAsZeroAchievement() {
        var line =
                new com.rigour.order.api.v1.model.OrderPayrollLineView(
                        "L",
                        "O",
                        "SO",
                        "A",
                        "C",
                        null,
                        1L,
                        1L,
                        "商品",
                        "BOX",
                        LocalDate.of(2026, 9, 1),
                        BigDecimal.ONE,
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        LocalDate.of(2026, 9, 2),
                        BigDecimal.ZERO,
                        null,
                        "ATTRIBUTION_PENDING");
        var issues = new ArrayList<String>();
        assertThat(
                        ConfiguredPayrollService.achievement(
                                List.of(line),
                                LocalDate.of(2026, 9, 1),
                                LocalDate.of(2026, 9, 30),
                                issues))
                .isNull();
        assertThat(issues).isNotEmpty();
    }

    @Test
    void manualAdjustmentAndHistoricalCorrectionRemainDistinctAndChangeFingerprint() {
        when(payroll.input(anyString(), anyString(), anyString()))
                .thenReturn(
                        new PayrollInputCommand(
                                1,
                                null,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "测试核定",
                                new BigDecimal("375")));
        when(payroll.corrections(anyString(), anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new PayrollCorrectionView(
                                        "C",
                                        "P0",
                                        "2026-08",
                                        "2026-09",
                                        new BigDecimal("-200"),
                                        "原期退款冲回",
                                        "ACTIVE",
                                        1,
                                        Instant.EPOCH)));
        var before =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(before.performance()).isZero();
        assertThat(before.allowance()).isEqualByComparingTo("375");
        assertThat(before.correctionAmount()).isEqualByComparingTo("-200");
        assertThat(before.netPay()).isEqualByComparingTo("3175");
        when(payroll.corrections(anyString(), anyString(), anyString())).thenReturn(List.of());
        var after =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(after.sourceFingerprint()).isNotEqualTo(before.sourceFingerprint());
    }

    private void usePolicy(
            String role, String mode, PayrollSalesPolicy sales, PayrollPersonalPolicy personal) {
        var d = PayrollSchemeTestFixture.definition();
        var next =
                new PayrollSchemeDefinition(
                        d.name(),
                        role,
                        d.departmentIds(),
                        d.employeeCodes(),
                        d.effectiveFrom(),
                        d.effectiveTo(),
                        "HEADQUARTERS".equals(role)
                                ? new PayrollSchemeBaseSalary(
                                        "FIXED", new BigDecimal("8000"), List.of(), null)
                                : d.baseSalary(),
                        d.performanceBase(),
                        mode,
                        sales,
                        d.manager(),
                        d.calendar(),
                        d.proration(),
                        "HEADQUARTERS".equals(role) ? List.of() : d.allowances(),
                        d.commission(),
                        d.remark(),
                        d.ruleReferences(),
                        personal);
        when(rules.resolve(anyString(), any(), any(), any()))
                .thenReturn(
                        new PayrollRuleMatch(PayrollSchemeTestFixture.version(next), List.of()));
    }

    @Test
    void headquartersRequiresMonthlyCompletionAndCapturesActualPercentage() {
        usePolicy("HEADQUARTERS", "FIXED", PayrollSchemeTestFixture.definition().sales(), null);
        var pending =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(pending.issues()).anyMatch(v -> v.contains("绩效完成率"));
        when(schemes.facts(anyString(), anyString(), anyString()))
                .thenReturn(
                        new PayrollSchemeFactsView(
                                1,
                                new BigDecimal("4000"),
                                List.of(),
                                "月度核定",
                                new BigDecimal("80"),
                                new BigDecimal("100")));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.performance()).isEqualByComparingTo("3200");
        assertThat(result.commission()).isEqualByComparingTo("100");
        assertThat(result.scheme().performanceCompletionPercent()).isEqualByComparingTo("80");
        assertThat(result.sourceFingerprint()).isNotEqualTo(pending.sourceFingerprint());
    }

    @Test
    void headquartersEmployeesInTheSameDepartmentUseTheirOwnMonthlyCompletion() {
        usePolicy("HEADQUARTERS", "FIXED", PayrollSchemeTestFixture.definition().sales(), null);
        when(schemes.facts("t", "A", "2026-09"))
                .thenReturn(
                        new PayrollSchemeFactsView(
                                1,
                                new BigDecimal("4000"),
                                List.of(),
                                "A个人月度核定",
                                new BigDecimal("80")));
        when(schemes.facts("t", "B", "2026-09"))
                .thenReturn(
                        new PayrollSchemeFactsView(
                                1,
                                new BigDecimal("4000"),
                                List.of(),
                                "B个人月度核定",
                                new BigDecimal("110")));
        var colleague =
                new PayrollEmployeeView(
                        "B",
                        "同部门员工B",
                        employee.departmentId(),
                        employee.departmentName(),
                        null,
                        employee.entryDate(),
                        null,
                        "HEADQUARTERS");
        var a = service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        var b = service.calculateIfConfigured("t", colleague, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(a.performance()).isEqualByComparingTo("3200");
        assertThat(b.performance()).isEqualByComparingTo("4400");
        assertThat(b.scheme().performanceCompletionPercent()).isEqualByComparingTo("110");
        var nextMonth =
                service.calculateIfConfigured("t", colleague, YearMonth.of(2026, 10)).orElseThrow();
        assertThat(nextMonth.performance()).isNull();
        assertThat(nextMonth.issues()).anyMatch(v -> v.contains("绩效完成率"));
    }

    @Test
    void independentPostProbationBaseRatioReachesPayrollWithoutReducingPerformance() {
        employee =
                new PayrollEmployeeView(
                        "A", "员工", 1L, "销售部", null, LocalDate.of(2026, 9, 1), null, "SALES");
        when(source.performanceEvidence(anyString(), anyString(), any(), any()))
                .thenReturn(
                        new com.rigour.sales.api.v1.model.SalesPayrollEvidenceView(
                                true, List.of()));
        var month = YearMonth.of(2026, 9);
        var sales = PayrollSchemeTestFixture.definition().sales();
        usePolicy("SALES", "SALES", sales, null);
        var baseline = service.calculateIfConfigured("t", employee, month).orElseThrow();
        var personal =
                new PayrollPersonalPolicy(
                        List.of(),
                        null,
                        month.atDay(16),
                        new BigDecimal("80"),
                        null,
                        null,
                        null,
                        null,
                        new BigDecimal("90"));
        usePolicy("SALES", "SALES", sales, personal);
        var result = service.calculateIfConfigured("t", employee, month).orElseThrow();
        assertThat(result.baseSalary()).isEqualByComparingTo("2550");
        assertThat(result.performance()).isNotNull().isEqualByComparingTo(baseline.performance());
        assertThat(result.allowance()).isEqualByComparingTo(baseline.allowance());
        var definition = result.scheme().scheme().definition();
        var mapper = JsonMapper.builder().build();
        var reloaded =
                mapper.readValue(
                        mapper.writeValueAsString(definition), PayrollSchemeDefinition.class);
        assertThat(reloaded.personalPolicy().postProbationBasePercent()).isEqualByComparingTo("90");
        assertThat(PayrollSchemeValidator.check(reloaded).errors())
                .noneMatch(v -> v.contains("结束后底薪比例"));
        var tree = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(reloaded);
        ((tools.jackson.databind.node.ObjectNode) tree.get("personalPolicy"))
                .put("postProbationBasePercent", new BigDecimal("100.01"));
        assertThat(
                        PayrollSchemeValidator.check(
                                        mapper.treeToValue(tree, PayrollSchemeDefinition.class))
                                .errors())
                .anyMatch(v -> v.contains("结束后底薪比例"));
    }

    @Test
    void firstMonthOnlyExemptsCollectionAndFirstTenDaysOfProcessPlusPersonalExceptions() {
        var old = PayrollSchemeTestFixture.definition().sales();
        var sales =
                new PayrollSalesPolicy(
                        old.processPercent(),
                        7,
                        old.checkinPenalty(),
                        3,
                        old.minimumSeconds(),
                        old.visitPenalty(),
                        true,
                        old.targets(),
                        old.resultCap(),
                        old.checkinCountMode(),
                        10);
        usePolicy(
                "SALES",
                "SALES",
                sales,
                new PayrollPersonalPolicy(
                        List.of(LocalDate.of(2026, 9, 25)),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
        when(source.performanceEvidence(anyString(), anyString(), any(), any()))
                .thenReturn(
                        new com.rigour.sales.api.v1.model.SalesPayrollEvidenceView(
                                true, List.of()));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.performanceDetail().collectionPay()).isEqualByComparingTo("600");
        assertThat(result.performanceDetail().days())
                .allMatch(d -> d.date().isAfter(LocalDate.of(2026, 9, 25)));
        assertThat(result.performanceDetail().days()).isNotEmpty();
        assertThat(result.performanceDetail().processDeduction()).isPositive();
        assertThat(result.scheme().scheme().definition().sales().dailyCheckins()).isEqualTo(7);
        assertThat(result.scheme().scheme().definition().sales().dailyVisits()).isEqualTo(3);
    }

    @Test
    void payrollUsesConfirmedManualCountsAndExplainsTheirBasisEvenIfSalesEvidenceFails() {
        employee =
                new PayrollEmployeeView(
                        "A", "员工", 1L, "销售部", null, LocalDate.of(2026, 1, 1), null, "SALES");
        var adjustment = new AttendanceAssessmentOverride(0, 1, "业务已核对补卡和拜访");
        var confirmation =
                new AttendanceRow(
                        "A",
                        "员工",
                        "销售部",
                        22,
                        "DEPARTMENT",
                        new BigDecimal("22"),
                        BigDecimal.ZERO,
                        22,
                        null,
                        null,
                        "NONE",
                        "CONFIRMED",
                        "AVAILABLE",
                        List.of(),
                        "",
                        2,
                        List.of(),
                        null,
                        adjustment);
        when(attendance.employee(anyString(), any(), anyString()))
                .thenReturn(
                        new HrAttendanceStore.Employee(
                                "A",
                                "员工",
                                1L,
                                "销售部",
                                employee.entryDate(),
                                null,
                                new AttendanceRecordCommand(null, null, "NONE", "", 2),
                                confirmation));
        when(source.performanceEvidence(anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("销售证据暂不可用"));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.performanceDetail().processDeduction()).isEqualByComparingTo("30");
        assertThat(result.performanceDetail().processPay()).isEqualByComparingTo("370");
        assertThat(result.performanceDetail().assessmentOverride()).isEqualTo(adjustment);
        assertThat(result.issues()).doesNotContain("打卡拜访依据暂不可用", "销售打卡账号未关联员工，过程考核待核对");
    }
}

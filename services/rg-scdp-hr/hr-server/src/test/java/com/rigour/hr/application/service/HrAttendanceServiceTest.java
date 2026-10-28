package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.AttendanceDepartmentOption;
import com.rigour.hr.api.v1.model.AttendanceExceptionPeriod;
import com.rigour.hr.api.v1.model.AttendanceRecordCommand;
import com.rigour.hr.api.v1.model.AttendanceRow;
import com.rigour.hr.api.v1.model.AttendanceRuleCommand;
import com.rigour.hr.api.v1.model.AttendanceRuleListRow;
import com.rigour.hr.api.v1.model.AttendanceRuleView;
import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.application.port.out.*;
import com.rigour.sales.api.v1.model.SalesAttendanceModels.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class HrAttendanceServiceTest {
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    HrAttendanceStore store;
    HrAttendanceSource source;
    HrAttendanceService service;

    @BeforeEach
    void setup() {
        store = mock(HrAttendanceStore.class);
        source = mock(HrAttendanceSource.class);
        when(source.leaveTypes(tenant.toString()))
                .thenReturn(Set.of("NONE", "COMP_TIME", "PERSONAL", "SICK", "ANNUAL"));
        service =
                new HrAttendanceService(
                        store,
                        source,
                        Clock.fixed(Instant.parse("2026-10-13T02:00:00Z"), ZoneOffset.UTC));
        identity(
                Set.of(
                        HrAttendanceService.READ,
                        HrAttendanceService.WRITE,
                        HrAttendanceService.RULE_READ,
                        HrAttendanceService.RULE_WRITE));
        when(store.rule(anyString(), anyString(), isNull()))
                .thenAnswer(i -> new AttendanceRuleView(i.getArgument(1), null, 26, 1, 26, false));
        when(store.employees(
                        anyString(),
                        any(),
                        any(HrAttendanceStore.EmployeeFilter.class),
                        anyInt(),
                        anyInt()))
                .thenReturn(
                        new HrPageView<>(
                                1,
                                0,
                                20,
                                List.of(
                                        new HrAttendanceStore.Employee(
                                                "E1",
                                                "员工",
                                                2L,
                                                "销售部",
                                                null,
                                                null,
                                                new AttendanceRecordCommand(
                                                        null, null, "NONE", "已说明", 2),
                                                null))));
    }

    void identity(Set<String> actions) {
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        1,
                        1,
                        1,
                        Set.of(),
                        actions));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void ruleListSortsByOrganizationThenFiltersBeforePagingAndKeepsFullMonthOptions() {
        var a = mock(AttendanceDepartmentOption.class);
        var b = mock(AttendanceDepartmentOption.class);
        var c = mock(AttendanceDepartmentOption.class);
        when(a.id()).thenReturn(1L);
        when(a.departmentName()).thenReturn("销售部");
        when(a.sortOrder()).thenReturn(0);
        when(b.id()).thenReturn(2L);
        when(b.parentId()).thenReturn(1L);
        when(b.departmentName()).thenReturn("销售一部");
        when(c.id()).thenReturn(3L);
        when(c.departmentName()).thenReturn("人事部");
        when(c.sortOrder()).thenReturn(1);
        when(store.departments(tenant.toString())).thenReturn(List.of(c, b, a));
        when(store.ruleOverview(tenant.toString(), "2026-10"))
                .thenReturn(
                        new HrAttendanceStore.RuleOverview(
                                List.of(
                                        new HrAttendanceStore.RuleRecord(3L, 22, 1, Instant.EPOCH),
                                        new HrAttendanceStore.RuleRecord(0L, 26, 1, Instant.EPOCH),
                                        new HrAttendanceStore.RuleRecord(2L, 24, 1, Instant.EPOCH)),
                                Set.of(2L)));
        var all = service.rules("2026-10", null, "", 0, 20);
        assertThat(all.page().items())
                .extracting(AttendanceRuleListRow::departmentName)
                .containsExactly("公司默认", "销售一部", "人事部");
        var unlocked = service.rules("2026-10", null, "UNLOCKED", 1, 1);
        assertThat(unlocked.page().total()).isEqualTo(2);
        assertThat(unlocked.page().items())
                .extracting(AttendanceRuleListRow::departmentId)
                .containsExactly(3L);
        assertThat(unlocked.configuredDepartmentIds()).containsExactly(0L, 2L, 3L);
        assertThat(unlocked.companyExpectedDays()).isEqualTo(26);
        assertThat(service.rules("2026-10", 0L, "LOCKED", 0, 20).page().items()).isEmpty();
        assertThat(service.rules("2026-10", 1L, "", 0, 20).page().items()).isEmpty();
        assertThat(service.rules("2026-10", 2L, "LOCKED", 0, 20).page().total()).isEqualTo(1);
    }

    @Test
    void ruleListValidatesFiltersAndRequiresRuleReadPermission() {
        assertThatThrownBy(() -> service.rules("bad", null, "", 0, 20))
                .hasMessageContaining("月份格式");
        assertThatThrownBy(() -> service.rules("2026-10", -1L, "", 0, 20))
                .hasMessageContaining("部门无效");
        assertThatThrownBy(() -> service.rules("2026-10", null, "PENDING", 0, 20))
                .hasMessageContaining("规则状态无效");
        assertThatThrownBy(() -> service.rules("2026-10", null, "", -1, 20))
                .hasMessageContaining("分页范围");
        identity(Set.of(HrAttendanceService.READ));
        assertThatThrownBy(() -> service.rules("2026-10", null, "", 0, 20))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(store, never()).ruleOverview(anyString(), anyString());
    }

    @Test
    void deduplicatesDatesAndUsesMonthBoundaryWithoutChangingNotes() {
        when(source.evidence(anyString(), any()))
                .thenReturn(
                        List.of(
                                new Evidence(
                                        "E1",
                                        true,
                                        List.of(
                                                LocalDate.parse("2026-09-01"),
                                                LocalDate.parse("2026-09-01"),
                                                LocalDate.parse("2026-09-02"),
                                                LocalDate.parse("2026-10-01")))));
        var result = service.list("2026-09", "", "", null, "", 0, 20);
        assertThat(result.provisional()).isFalse();
        assertThat(result.page().items().getFirst())
                .satisfies(
                        row -> {
                            assertThat(row.actualDays()).isEqualByComparingTo("2");
                            assertThat(row.absentDays()).isEqualByComparingTo("24");
                            assertThat(row.reason()).isEqualTo("已说明");
                        });
        verify(source)
                .evidence(
                        tenant.toString(),
                        new Query(
                                List.of("E1"),
                                LocalDate.parse("2026-09-01"),
                                LocalDate.parse("2026-09-30")));
    }

    @Test
    void zeroPunchesDifferFromMissingLinkAndUnavailableSource() {
        when(source.evidence(anyString(), any()))
                .thenReturn(List.of(new Evidence("E1", true, List.of())));
        assertThat(
                        service.list("2026-09", "", "", null, "", 0, 20)
                                .page()
                                .items()
                                .getFirst()
                                .absentDays())
                .isEqualByComparingTo("26");
        when(source.evidence(anyString(), any()))
                .thenReturn(List.of(new Evidence("E1", false, List.of())));
        var unlinked = service.list("2026-09", "", "", null, "", 0, 20).page().items().getFirst();
        assertThat(unlinked.actualDays()).isNull();
        assertThat(unlinked.absentDays()).isNull();
        assertThat(unlinked.sourceStatus()).isEqualTo("UNLINKED");
        when(source.evidence(anyString(), any())).thenThrow(new IllegalStateException("down"));
        var unavailable = service.list("2026-09", "", "", null, "", 0, 20);
        assertThat(unavailable.sourceError()).isNotBlank();
        assertThat(unavailable.page().items().getFirst().actualDays()).isNull();
    }

    @Test
    void currentMonthDoesNotCountFutureDaysAsAbsence() {
        when(source.evidence(anyString(), any()))
                .thenReturn(
                        List.of(new Evidence("E1", true, List.of(LocalDate.parse("2026-10-01")))));
        var result = service.list("2026-10", "", "", null, "", 0, 20);
        assertThat(result.through()).isEqualTo(LocalDate.parse("2026-10-13"));
        assertThat(result.provisional()).isTrue();
        assertThat(result.page().items().getFirst().absentDays()).isNull();
        clearInvocations(source);
        assertThat(
                        service.list("2026-11", "", "", null, "", 0, 20)
                                .page()
                                .items()
                                .getFirst()
                                .sourceStatus())
                .isEqualTo("FUTURE");
        verifyNoInteractions(source);
    }

    @Test
    void preservesExtraDaysAndMissingRulesWithoutNegativeAbsence() {
        when(store.rule(anyString(), anyString(), isNull()))
                .thenReturn(new AttendanceRuleView("2026-09", null, 1, 1, 1, false));
        when(source.evidence(anyString(), any()))
                .thenReturn(
                        List.of(
                                new Evidence(
                                        "E1",
                                        true,
                                        List.of(
                                                LocalDate.parse("2026-09-01"),
                                                LocalDate.parse("2026-09-02")))));
        var row = service.list("2026-09", "", "", null, "", 0, 20).page().items().getFirst();
        assertThat(row.actualDays()).isEqualByComparingTo("2");
        assertThat(row.absentDays()).isZero();
        when(store.rule(anyString(), anyString(), isNull()))
                .thenReturn(new AttendanceRuleView("2026-09", null, null, 0, null, false));
        assertThat(
                        service.list("2026-09", "", "", null, "", 0, 20)
                                .page()
                                .items()
                                .getFirst()
                                .absentDays())
                .isNull();
    }

    @Test
    void validatesCalendarDaysAndRequiresPermissionsBeforeReadingSources() {
        assertThatThrownBy(
                        () -> service.saveRule(new AttendanceRuleCommand("2026-02", null, 29, 0)))
                .hasMessageContaining("当月天数");
        service.saveRule(new AttendanceRuleCommand("2028-02", null, 29, 0));
        verify(store)
                .saveRule(
                        tenant.toString(),
                        new AttendanceRuleCommand("2028-02", null, 29, 0),
                        user.toString());
        identity(Set.of());
        assertThatThrownBy(() -> service.list("2026-09", "", "", null, "", 0, 20))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(source);
    }

    @Test
    void filtersAreIndependentAndValidatedBeforeReadingEmployees() {
        service.list("2026-09", " 员工 ", " E1 ", 7L, "", 0, 20);
        verify(store)
                .employees(
                        tenant.toString(),
                        YearMonth.of(2026, 9),
                        new HrAttendanceStore.EmployeeFilter("员工", "E1", 7L, ""),
                        0,
                        20);
        clearInvocations(store);
        assertThatThrownBy(() -> service.list("2026-09", "x".repeat(129), "", null, "", 0, 20))
                .hasMessageContaining("员工姓名");
        assertThatThrownBy(() -> service.list("2026-09", "", "x".repeat(51), null, "", 0, 20))
                .hasMessageContaining("员工编号");
        assertThatThrownBy(() -> service.list("2026-09", "", "", 0L, "", 0, 20))
                .hasMessageContaining("部门无效");
        verifyNoInteractions(store);
    }

    @Test
    void departmentOptionsUseAttendancePermissionAndCurrentTenant() {
        identity(Set.of(HrAttendanceService.READ));
        when(store.departments(eq(tenant.toString()), anyString())).thenReturn(List.of());
        assertThat(service.departments()).isEmpty();
        verify(store).departments(eq(tenant.toString()), anyString());
        clearInvocations(store);
        identity(Set.of(HrAttendanceService.RULE_READ));
        assertThat(service.departments()).isEmpty();
        verify(store).departments(eq(tenant.toString()), anyString());
        clearInvocations(store);
        identity(Set.of());
        assertThatThrownBy(() -> service.departments())
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }

    @Test
    void annualCalendarExpectedDaysOverrideLegacyCountsAndUnavailableReferenceDoesNotFallBack() {
        var attendance = mock(PayrollAttendanceEvidenceService.class);
        when(attendance.expectedDays(tenant.toString(), 2L, YearMonth.of(2026, 10)))
                .thenReturn(Optional.of(18))
                .thenThrow(
                        new com.rigour.shared.core.exception.BusinessException(
                                com.rigour.shared.core.api.ErrorCode.SERVICE_UNAVAILABLE,
                                "年度日历未就绪",
                                List.of()));
        when(attendance.applyDetailed(anyString(), any(), any(), any(), anyList(), anyMap()))
                .thenAnswer(
                        i ->
                                new PayrollAttendanceEvidenceService.Batch(
                                        i.getArgument(5), Map.of()));
        service =
                new HrAttendanceService(
                        store,
                        source,
                        Clock.fixed(Instant.parse("2026-11-01T02:00:00Z"), ZoneOffset.UTC),
                        attendance);
        var available = service.list("2026-10", "", "", null, "", 0, 20);
        assertThat(available.page().items().getFirst().expectedDays()).isEqualTo(18);
        var unavailable = service.list("2026-10", "", "", null, "", 0, 20);
        assertThat(unavailable.page().items().getFirst().expectedDays()).isNull();
        assertThat(unavailable.sourceError()).contains("法定日历尚不可用");
    }

    @Test
    void departmentRulesOverrideCompanyWithoutInheritingParentOrSiblingRules() {
        when(source.evidence(anyString(), any()))
                .thenReturn(List.of(new Evidence("E1", true, List.of())));
        when(store.departmentRules(tenant.toString(), "2026-09"))
                .thenReturn(Map.of(1L, 20, 2L, 22, 3L, 24));
        var own = service.list("2026-09", "", "", null, "", 0, 20).page().items().getFirst();
        assertThat(own.expectedDays()).isEqualTo(22);
        assertThat(own.absentDays()).isEqualByComparingTo("22");
        assertThat(own.ruleSource()).isEqualTo("DEPARTMENT");
        when(store.departmentRules(tenant.toString(), "2026-09"))
                .thenReturn(Map.of(1L, 20, 3L, 24));
        var fallback = service.list("2026-09", "", "", null, "", 0, 20).page().items().getFirst();
        assertThat(fallback.expectedDays()).isEqualTo(26);
        assertThat(fallback.ruleSource()).isEqualTo("COMPANY");
        when(store.rule(tenant.toString(), "2026-09", null))
                .thenReturn(new AttendanceRuleView("2026-09", null, null, 0, null, false));
        var unset = service.list("2026-09", "", "", null, "", 0, 20).page().items().getFirst();
        assertThat(unset.expectedDays()).isNull();
        assertThat(unset.absentDays()).isNull();
        assertThat(unset.ruleSource()).isEqualTo("UNSET");
        when(store.departmentRules(tenant.toString(), "2026-09")).thenReturn(Map.of(2L, 0));
        var zero = service.list("2026-09", "", "", null, "", 0, 20).page().items().getFirst();
        assertThat(zero.expectedDays()).isZero();
        assertThat(zero.absentDays()).isZero();
        assertThat(zero.ruleSource()).isEqualTo("DEPARTMENT");
    }

    @Test
    void validatesDepartmentInCurrentTenantAndAllowsRestoringOnlyDepartmentDefaults() {
        var department = mock(AttendanceDepartmentOption.class);
        when(department.id()).thenReturn(2L);
        when(store.departments(tenant.toString())).thenReturn(List.of(department));
        when(store.rule(tenant.toString(), "2026-09", 2L))
                .thenReturn(new AttendanceRuleView("2026-09", 2L, null, 0, 26, false));
        service.rule("2026-09", 2L);
        verify(store).rule(tenant.toString(), "2026-09", 2L);
        service.saveRule(new AttendanceRuleCommand("2026-09", 2L, 0, 0));
        service.saveRule(new AttendanceRuleCommand("2026-09", 2L, null, 1));
        verify(store)
                .saveRule(
                        tenant.toString(),
                        new AttendanceRuleCommand("2026-09", 2L, null, 1),
                        user.toString());
        clearInvocations(store);
        assertThatThrownBy(() -> service.rule("2026-09", 9L)).hasMessageContaining("部门不存在");
        assertThatThrownBy(() -> service.saveRule(new AttendanceRuleCommand("2026-09", 9L, 22, 0)))
                .hasMessageContaining("部门不存在");
        assertThatThrownBy(
                        () -> service.saveRule(new AttendanceRuleCommand("2026-09", null, null, 1)))
                .hasMessageContaining("当月天数");
        identity(Set.of(HrAttendanceService.RULE_READ));
        assertThatThrownBy(() -> service.saveRule(new AttendanceRuleCommand("2026-09", 2L, 22, 0)))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(store, never()).rule(anyString(), anyString(), any());
        verify(store, never()).saveRule(anyString(), any(), anyString());
    }

    @Test
    void ruleEditorUsesTheSameCalendarCalculationWithoutReplacingSavedMonthlyValues() {
        var attendance = mock(PayrollAttendanceEvidenceService.class);
        service = new HrAttendanceService(store, source, Clock.systemUTC(), attendance);
        when(store.departments(tenant.toString()))
                .thenReturn(List.of(new AttendanceDepartmentOption(2L, 1L, "销售一部", 0)));
        when(store.rule(tenant.toString(), "2026-10", 2L))
                .thenReturn(new AttendanceRuleView("2026-10", 2L, 24, 3, 26, false));
        when(attendance.expectedDays(tenant.toString(), 2L, YearMonth.of(2026, 10)))
                .thenReturn(Optional.of(18), Optional.of(0), Optional.empty());
        var computed = service.rule("2026-10", 2L);
        assertThat(computed.expectedDays()).isEqualTo(24);
        assertThat(computed.calendarExpectedDays()).isEqualTo(18);
        assertThat(computed.calendarMessage()).contains("生效的薪资考勤规则");
        assertThat(service.rule("2026-10", 2L).calendarExpectedDays()).isZero();
        var missing = service.rule("2026-10", 2L);
        assertThat(missing.calendarExpectedDays()).isNull();
        assertThat(missing.calendarMessage()).contains("未匹配");
        assertThat(missing.companyExpectedDays()).isEqualTo(26);
        verify(store, never()).saveRule(anyString(), any(), anyString());
    }

    @Test
    void stoppedOrUnavailableCalendarsNeverInventSuggestedDays() {
        var attendance = mock(PayrollAttendanceEvidenceService.class);
        service = new HrAttendanceService(store, source, Clock.systemUTC(), attendance);
        when(store.departments(tenant.toString()))
                .thenReturn(List.of(new AttendanceDepartmentOption(2L, 1L, "销售一部", 0)));
        when(store.rule(tenant.toString(), "2026-10", 2L))
                .thenReturn(new AttendanceRuleView("2026-10", 2L, null, 0, 26, false));
        when(attendance.stopped(tenant.toString(), 2L, YearMonth.of(2026, 10))).thenReturn(true);
        var stopped = service.rule("2026-10", 2L);
        assertThat(stopped.calendarExpectedDays()).isNull();
        assertThat(stopped.calendarMessage()).contains("已停用");
        verify(attendance, never()).expectedDays(anyString(), any(), any());
        when(attendance.stopped(tenant.toString(), 2L, YearMonth.of(2026, 10))).thenReturn(false);
        when(attendance.expectedDays(tenant.toString(), 2L, YearMonth.of(2026, 10)))
                .thenThrow(
                        new com.rigour.shared.core.exception.BusinessException(
                                com.rigour.shared.core.api.ErrorCode.SERVICE_UNAVAILABLE,
                                "年度日历未就绪",
                                List.of()));
        var unavailable = service.rule("2026-10", 2L);
        assertThat(unavailable.calendarExpectedDays()).isNull();
        assertThat(unavailable.calendarMessage()).isEqualTo("年度日历未就绪");
    }

    @Test
    void batchRulesValidateEveryDepartmentPermissionAndValueBeforeAnyWrite() {
        when(store.departments(tenant.toString()))
                .thenReturn(
                        List.of(
                                new AttendanceDepartmentOption(2L, 1L, "北京", 0),
                                new AttendanceDepartmentOption(3L, 1L, "杭州", 1)));
        var first = new AttendanceRuleCommand("2026-10", 2L, 22, 0);
        var second = new AttendanceRuleCommand("2026-10", 3L, 21, 0);
        var commands = List.of(first, second);
        service.saveRules(commands);
        verify(store).saveRules(tenant.toString(), commands, user.toString());
        clearInvocations(store);
        assertThatThrownBy(() -> service.saveRules(List.of(first, first)))
                .hasMessageContaining("重复");
        assertThatThrownBy(
                        () ->
                                service.saveRules(
                                        List.of(
                                                first,
                                                new AttendanceRuleCommand("2026-10", 3L, 32, 0))))
                .hasMessageContaining("当月天数");
        assertThatThrownBy(
                        () ->
                                service.saveRules(
                                        List.of(
                                                first,
                                                new AttendanceRuleCommand("2026-11", 3L, 22, 0))))
                .hasMessageContaining("同一考勤月份");
        assertThatThrownBy(
                        () ->
                                service.saveRules(
                                        List.of(
                                                first,
                                                new AttendanceRuleCommand("2026-10", null, 22, 0))))
                .hasMessageContaining("单独设置");
        assertThatThrownBy(() -> service.saveRules(List.of())).hasMessageContaining("选择");
        doThrow(new AuthorizationDeniedException("无部门权限"))
                .when(store)
                .requireRuleScope(tenant.toString(), 3L, HrAttendanceService.RULE_WRITE);
        assertThatThrownBy(() -> service.saveRules(commands))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(store, never()).saveRules(anyString(), anyList(), anyString());
        identity(Set.of(HrAttendanceService.RULE_READ));
        assertThatThrownBy(() -> service.saveRules(commands))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    void employeeWith(AttendanceRecordCommand record) {
        when(store.employees(anyString(), any(), any(), anyInt(), anyInt()))
                .thenReturn(
                        new HrPageView<>(
                                1,
                                0,
                                20,
                                List.of(
                                        new HrAttendanceStore.Employee(
                                                "E1", "员工", 2L, "销售部", null, null, record, null))));
    }

    @Test
    void manualDaysOverrideIndependentlyAndKeepPunchEvidence() {
        when(source.evidence(anyString(), any()))
                .thenReturn(
                        List.of(new Evidence("E1", true, List.of(LocalDate.parse("2026-09-01")))));
        employeeWith(
                new AttendanceRecordCommand(
                        new BigDecimal("20.5"), new BigDecimal("2.5"), "SICK", "核对后调整", 3));
        var row = service.list("2026-09", "", "", null, "", 0, 20).page().items().getFirst();
        assertThat(row.actualDays()).isEqualByComparingTo("20.5");
        assertThat(row.absentDays()).isEqualByComparingTo("2.5");
        assertThat(row.punchDays()).isEqualTo(1);
        assertThat(row.dates()).containsExactly(LocalDate.parse("2026-09-01"));
        assertThat(row.leaveTypeCode()).isEqualTo("SICK");
        employeeWith(new AttendanceRecordCommand(new BigDecimal("20.5"), null, "PERSONAL", "", 4));
        assertThat(
                        service.list("2026-09", "", "", null, "", 0, 20)
                                .page()
                                .items()
                                .getFirst()
                                .absentDays())
                .isEqualByComparingTo("5.5");
        employeeWith(new AttendanceRecordCommand(null, null, "NONE", "", 5));
        assertThat(
                        service.list("2026-09", "", "", null, "", 0, 20)
                                .page()
                                .items()
                                .getFirst()
                                .actualDays())
                .isEqualByComparingTo("1");
    }

    @Test
    void manualZeroAndHalfDaysWorkForCurrentMonthAndUnavailableSources() {
        employeeWith(
                new AttendanceRecordCommand(
                        BigDecimal.ZERO, new BigDecimal("0.5"), "COMP_TIME", "", 1));
        when(source.evidence(anyString(), any())).thenThrow(new IllegalStateException("down"));
        var row = service.list("2026-10", "", "", null, "", 0, 20).page().items().getFirst();
        assertThat(row.actualDays()).isZero();
        assertThat(row.absentDays()).isEqualByComparingTo("0.5");
        assertThat(row.punchDays()).isNull();
        assertThat(row.sourceStatus()).isEqualTo("UNAVAILABLE");
        doReturn(List.of(new Evidence("E1", false, List.of())))
                .when(source)
                .evidence(anyString(), any());
        assertThat(
                        service.list("2026-10", "", "", null, "", 0, 20)
                                .page()
                                .items()
                                .getFirst()
                                .actualDays())
                .isZero();
    }

    @Test
    void confirmsPendingRecordWithHalfDaysAndPreservesImmutableSnapshot() {
        var command =
                new AttendanceRecordCommand(
                        new BigDecimal("20.5"), new BigDecimal("0.5"), "ANNUAL", "  核对  ", 2);
        var employee =
                new HrAttendanceStore.Employee(
                        "E1",
                        "员工",
                        2L,
                        "销售部",
                        LocalDate.of(2026, 9, 2),
                        LocalDate.of(2026, 9, 29),
                        new AttendanceRecordCommand(null, null, "NONE", "", 2),
                        null);
        when(store.employee(tenant.toString(), YearMonth.of(2026, 9), "E1")).thenReturn(employee);
        when(source.evidence(anyString(), any()))
                .thenReturn(
                        List.of(
                                new Evidence(
                                        "E1",
                                        true,
                                        List.of(
                                                LocalDate.of(2026, 9, 1),
                                                LocalDate.of(2026, 9, 2),
                                                LocalDate.of(2026, 9, 30)))));
        when(store.employees(anyString(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new HrPageView<>(1, 0, 20, List.of(employee)));
        var pending = service.list("2026-09", "", "", null, "", 0, 20).page().items().getFirst();
        assertThat(pending.entryDate()).isEqualTo(employee.entry());
        assertThat(pending.leaveDate()).isEqualTo(employee.leave());
        assertThat(pending.dates()).containsExactly(LocalDate.of(2026, 9, 2));
        service.confirmRecord("2026-09", "E1", command);
        var saved = org.mockito.ArgumentCaptor.forClass(AttendanceRow.class);
        verify(store)
                .confirmRecord(
                        eq(tenant.toString()),
                        eq("2026-09"),
                        eq("E1"),
                        eq(
                                new AttendanceRecordCommand(
                                        new BigDecimal("20.5"),
                                        new BigDecimal("0.5"),
                                        "ANNUAL",
                                        "核对",
                                        2)),
                        saved.capture(),
                        eq(2L),
                        eq(user.toString()));
        var snapshot = saved.getValue();
        assertThat(snapshot.attendanceStatus()).isEqualTo("CONFIRMED");
        assertThat(snapshot.revision()).isEqualTo(3);
        assertThat(snapshot.entryDate()).isEqualTo(employee.entry());
        assertThat(snapshot.leaveDate()).isEqualTo(employee.leave());
        assertThat(snapshot.dates()).containsExactly(LocalDate.of(2026, 9, 2));
        assertThat(snapshot.expectedDays()).isEqualTo(26);
        assertThat(snapshot.actualDays()).isEqualByComparingTo("20.5");
        assertThat(snapshot.absentDays()).isEqualByComparingTo("0.5");
        var confirmed =
                new HrAttendanceStore.Employee(
                        "E1", "员工新名字", 3L, "其他部", null, null, command, snapshot);
        when(store.employees(anyString(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new HrPageView<>(1, 0, 20, List.of(confirmed)));
        when(store.rule(anyString(), anyString(), isNull()))
                .thenReturn(new AttendanceRuleView("2026-09", null, 10, 2, 10, false));
        clearInvocations(source);
        var result = service.list("2026-09", "", "", null, "CONFIRMED", 0, 20);
        assertThat(result.page().items()).containsExactly(snapshot);
        verifyNoInteractions(source);
        when(store.employee(tenant.toString(), YearMonth.of(2026, 9), "E1")).thenReturn(confirmed);
        clearInvocations(store);
        assertThatThrownBy(() -> service.confirmRecord("2026-09", "E1", command))
                .hasMessageContaining("已确认");
        verify(store, never())
                .confirmRecord(
                        anyString(), anyString(), anyString(), any(), any(), any(), anyString());
        verifyNoInteractions(source);
    }

    @Test
    void confirmationRequiresARuleAndCompleteFinalDays() {
        when(store.employee(anyString(), any(), anyString()))
                .thenReturn(
                        new HrAttendanceStore.Employee(
                                "E1",
                                "员工",
                                2L,
                                "销售部",
                                null,
                                null,
                                new AttendanceRecordCommand(null, null, "NONE", "", 0),
                                null));
        when(source.evidence(anyString(), any()))
                .thenReturn(List.of(new Evidence("E1", true, List.of())));
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-10",
                                        "E1",
                                        new AttendanceRecordCommand(null, null, "NONE", "", 0)))
                .hasMessageContaining("实际出勤和缺勤天数");
        when(source.evidence(anyString(), any()))
                .thenReturn(List.of(new Evidence("E1", false, List.of())));
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-09",
                                        "E1",
                                        new AttendanceRecordCommand(
                                                null, BigDecimal.ZERO, "NONE", "", 0)))
                .hasMessageContaining("实际出勤和缺勤天数");
        when(store.rule(anyString(), anyString(), isNull()))
                .thenReturn(new AttendanceRuleView("2026-09", null, null, 0, null, false));
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-09",
                                        "E1",
                                        new AttendanceRecordCommand(
                                                BigDecimal.ZERO, BigDecimal.ZERO, "NONE", "", 0)))
                .hasMessageContaining("考勤规则");
        verify(store, never())
                .confirmRecord(
                        anyString(), anyString(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void validatesHalfDaysDictionaryCalendarRevisionAndWritePermission() {
        when(store.employee(anyString(), any(), anyString()))
                .thenReturn(
                        new HrAttendanceStore.Employee(
                                "E1",
                                "员工",
                                2L,
                                "销售部",
                                null,
                                null,
                                new AttendanceRecordCommand(null, null, "NONE", "", 0),
                                null));
        service.confirmRecord(
                "2026-02",
                "E1",
                new AttendanceRecordCommand(
                        new BigDecimal("27.5"), BigDecimal.ZERO, "ANNUAL", "年假已核对", 0));
        clearInvocations(store);
        for (String days : List.of("-0.5", "0.1", "28.5")) {
            assertThatThrownBy(
                            () ->
                                    service.confirmRecord(
                                            "2026-02",
                                            "E1",
                                            new AttendanceRecordCommand(
                                                    new BigDecimal(days), null, "NONE", "", 0)))
                    .hasMessageContaining("0.5天");
            assertThatThrownBy(
                            () ->
                                    service.confirmRecord(
                                            "2026-02",
                                            "E1",
                                            new AttendanceRecordCommand(
                                                    null, new BigDecimal(days), "NONE", "", 0)))
                    .hasMessageContaining("0.5天");
        }
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-02",
                                        "E1",
                                        new AttendanceRecordCommand(
                                                null, null, "NONE", "x".repeat(501), 0)))
                .hasMessageContaining("500");
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-02",
                                        "E1",
                                        new AttendanceRecordCommand(null, null, "NONE", "", 2)))
                .hasMessageContaining("已变更");
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-02",
                                        "E1",
                                        new AttendanceRecordCommand(null, null, "UNKNOWN", "", 0)))
                .hasMessageContaining("请假类型");
        when(source.leaveTypes(anyString())).thenThrow(new IllegalStateException("字典暂不可用"));
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-02",
                                        "E1",
                                        new AttendanceRecordCommand(null, null, "NONE", "", 0)))
                .hasMessageContaining("字典暂不可用");
        identity(Set.of());
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-02",
                                        "E1",
                                        new AttendanceRecordCommand(null, null, "NONE", "", 0)))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(store, never())
                .confirmRecord(
                        anyString(), anyString(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void pendingIsDefaultAndStatusFilterIsIndependentAndValidated() {
        assertThat(
                        service.list("2026-09", "", "", null, "", 0, 20)
                                .page()
                                .items()
                                .getFirst()
                                .attendanceStatus())
                .isEqualTo("PENDING");
        service.list("2026-09", "员工", "E1", 2L, " PENDING ", 20, 20);
        verify(store)
                .employees(
                        tenant.toString(),
                        YearMonth.of(2026, 9),
                        new HrAttendanceStore.EmployeeFilter("员工", "E1", 2L, "PENDING"),
                        20,
                        20);
        clearInvocations(store);
        assertThatThrownBy(() -> service.list("2026-09", "", "", null, "INVALID", 0, 20))
                .hasMessageContaining("考勤状态");
        verifyNoInteractions(store);
    }

    @Test
    void crossDayExceptionsPreserveSourceDatesAndRequireExplicitDayConfirmation() {
        var periods =
                List.of(
                        new AttendanceExceptionPeriod(
                                LocalDateTime.of(2026, 9, 3, 12, 0),
                                LocalDateTime.of(2026, 9, 5, 12, 0),
                                "COMPENSATORY_LEAVE",
                                "活动调休"));
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-09",
                                        "E1",
                                        new AttendanceRecordCommand(
                                                null, null, "COMP_TIME", "调休核定", 2, periods)))
                .hasMessageContaining("同时核定");
        when(store.employee(tenant.toString(), YearMonth.of(2026, 9), "E1"))
                .thenReturn(
                        new HrAttendanceStore.Employee(
                                "E1",
                                "员工",
                                2L,
                                "销售部",
                                null,
                                null,
                                new AttendanceRecordCommand(null, null, "NONE", "", 2),
                                null));
        when(source.evidence(anyString(), any()))
                .thenReturn(List.of(new Evidence("E1", true, List.of(LocalDate.of(2026, 9, 1)))));
        service.confirmRecord(
                "2026-09",
                "E1",
                new AttendanceRecordCommand(
                        new BigDecimal("26"), BigDecimal.ZERO, "COMP_TIME", "调休核定", 2, periods));
        var saved = org.mockito.ArgumentCaptor.forClass(AttendanceRow.class);
        verify(store)
                .confirmRecord(
                        eq(tenant.toString()),
                        eq("2026-09"),
                        eq("E1"),
                        any(),
                        saved.capture(),
                        eq(2L),
                        anyString());
        assertThat(saved.getValue().exceptions()).isEqualTo(periods);
        assertThat(saved.getValue().dates()).containsExactly(LocalDate.of(2026, 9, 1));
        assertThat(saved.getValue().actualDays()).isEqualByComparingTo("26");
    }

    @Test
    void overlappingExceptionPeriodsCannotBeConfirmed() {
        var periods =
                List.of(
                        new AttendanceExceptionPeriod(
                                LocalDateTime.of(2026, 9, 3, 0, 0),
                                LocalDateTime.of(2026, 9, 5, 0, 0),
                                "ACTIVITY",
                                "活动"),
                        new AttendanceExceptionPeriod(
                                LocalDateTime.of(2026, 9, 4, 0, 0),
                                LocalDateTime.of(2026, 9, 6, 0, 0),
                                "COMPENSATORY_LEAVE",
                                "调休"));
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-09",
                                        "E1",
                                        new AttendanceRecordCommand(
                                                new BigDecimal("26"),
                                                BigDecimal.ZERO,
                                                "COMP_TIME",
                                                "调整",
                                                2,
                                                periods)))
                .hasMessageContaining("不能重叠");
        verify(store, never())
                .confirmRecord(
                        anyString(), anyString(), anyString(), any(), any(), any(), anyString());
    }

    private void stubPendingEmployee() {
        when(store.employee(tenant.toString(), YearMonth.of(2026, 9), "E1"))
                .thenReturn(
                        new HrAttendanceStore.Employee(
                                "E1",
                                "员工",
                                2L,
                                "销售部",
                                null,
                                null,
                                new AttendanceRecordCommand(null, null, "NONE", "", 0),
                                null));
    }

    @Test
    void everyNonNoneLeaveTypeRequiresANonblankReason() {
        stubPendingEmployee();
        for (String type : List.of("COMP_TIME", "PERSONAL", "SICK", "ANNUAL")) {
            for (String reason : new String[] {null, "", "  \t  "}) {
                assertThatThrownBy(
                                () ->
                                        service.confirmRecord(
                                                "2026-09",
                                                "E1",
                                                new AttendanceRecordCommand(
                                                        new BigDecimal("26"),
                                                        BigDecimal.ZERO,
                                                        type,
                                                        reason,
                                                        0)))
                        .hasMessageContaining("考勤核定说明");
            }
        }
        verify(store, never())
                .confirmRecord(
                        anyString(), anyString(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void noneAllowsBlankReasonWithOrWithoutExceptionDates() {
        stubPendingEmployee();
        var exception =
                new AttendanceExceptionPeriod(
                        LocalDateTime.of(2026, 9, 3, 0, 0),
                        LocalDateTime.of(2026, 9, 4, 0, 0),
                        "MISSING_PUNCH",
                        "当天忘记打卡，已核对");
        for (String reason : new String[] {null, "", "  \t  "}) {
            for (var periods : List.of(List.<AttendanceExceptionPeriod>of(), List.of(exception))) {
                service.confirmRecord(
                        "2026-09",
                        "E1",
                        new AttendanceRecordCommand(
                                new BigDecimal("26"), BigDecimal.ZERO, "NONE", reason, 0, periods));
            }
        }
        var saved = org.mockito.ArgumentCaptor.forClass(AttendanceRecordCommand.class);
        var snapshots = org.mockito.ArgumentCaptor.forClass(AttendanceRow.class);
        verify(store, times(6))
                .confirmRecord(
                        eq(tenant.toString()),
                        eq("2026-09"),
                        eq("E1"),
                        saved.capture(),
                        snapshots.capture(),
                        eq(2L),
                        eq(user.toString()));
        assertThat(saved.getAllValues())
                .allSatisfy(command -> assertThat(command.reason()).isEmpty());
        assertThat(snapshots.getAllValues()).allSatisfy(row -> assertThat(row.reason()).isEmpty());
    }

    @Test
    void separateTimesOnTheSameDateCannotBeRegisteredTwice() {
        var periods =
                List.of(
                        new AttendanceExceptionPeriod(
                                LocalDateTime.of(2026, 9, 3, 9, 0),
                                LocalDateTime.of(2026, 9, 3, 12, 0),
                                "ACTIVITY",
                                "上午活动"),
                        new AttendanceExceptionPeriod(
                                LocalDateTime.of(2026, 9, 3, 14, 0),
                                LocalDateTime.of(2026, 9, 3, 18, 0),
                                "MISSING_PUNCH",
                                "下午缺卡"));
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-09",
                                        "E1",
                                        new AttendanceRecordCommand(
                                                new BigDecimal("26"),
                                                BigDecimal.ZERO,
                                                "NONE",
                                                "",
                                                0,
                                                periods)))
                .hasMessageContaining("日期不能重复");
        verify(store, never())
                .confirmRecord(
                        anyString(), anyString(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void adjacentDatesDoNotRepeatWhenPreviousPeriodEndsAtMidnight() {
        stubPendingEmployee();
        var periods =
                List.of(
                        new AttendanceExceptionPeriod(
                                LocalDateTime.of(2026, 9, 3, 0, 0),
                                LocalDateTime.of(2026, 9, 4, 0, 0),
                                "ACTIVITY",
                                "活动"),
                        new AttendanceExceptionPeriod(
                                LocalDateTime.of(2026, 9, 4, 0, 0),
                                LocalDateTime.of(2026, 9, 5, 0, 0),
                                "MISSING_PUNCH",
                                "缺卡"));
        service.confirmRecord(
                "2026-09",
                "E1",
                new AttendanceRecordCommand(
                        new BigDecimal("26"), BigDecimal.ZERO, "NONE", "", 0, periods));
        verify(store)
                .confirmRecord(
                        eq(tenant.toString()),
                        eq("2026-09"),
                        eq("E1"),
                        argThat(command -> command.exceptions().equals(periods)),
                        any(),
                        eq(2L),
                        anyString());
    }

    @Test
    void optionalGeneralReasonDoesNotRemoveIndividualExceptionExplanationRequirement() {
        var periods =
                List.of(
                        new AttendanceExceptionPeriod(
                                LocalDateTime.of(2026, 9, 3, 0, 0),
                                LocalDateTime.of(2026, 9, 4, 0, 0),
                                "MISSING_PUNCH",
                                " "));
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-09",
                                        "E1",
                                        new AttendanceRecordCommand(
                                                new BigDecimal("26"),
                                                BigDecimal.ZERO,
                                                "NONE",
                                                "",
                                                0,
                                                periods)))
                .hasMessageContaining("每条异常须填写");
        verify(store, never())
                .confirmRecord(
                        anyString(), anyString(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void attendanceMenuPermissionCanDetermineCountsAndPreservesZeroAndEvidence() {
        identity(Set.of(HrAttendanceService.READ));
        stubPendingEmployee();
        var adjustment =
                new com.rigour.hr.api.v1.model.AttendanceAssessmentOverride(0, 2, "已核对补卡与拜访");
        service.confirmRecord(
                "2026-09",
                "E1",
                new AttendanceRecordCommand(
                        new BigDecimal("26"),
                        BigDecimal.ZERO,
                        "NONE",
                        "",
                        0,
                        List.of(),
                        adjustment));
        var saved = org.mockito.ArgumentCaptor.forClass(AttendanceRecordCommand.class);
        var snapshot = org.mockito.ArgumentCaptor.forClass(AttendanceRow.class);
        verify(store)
                .confirmRecord(
                        eq(tenant.toString()),
                        eq("2026-09"),
                        eq("E1"),
                        saved.capture(),
                        snapshot.capture(),
                        eq(2L),
                        eq(user.toString()));
        assertThat(saved.getValue().assessmentOverride()).isEqualTo(adjustment);
        assertThat(snapshot.getValue().assessmentOverride()).isEqualTo(adjustment);
    }

    @Test
    void manualCountsRequireAnExplanationAndValidCountsButLeaveExplanationIsNotDuplicated() {
        stubPendingEmployee();
        for (var adjustment :
                List.of(
                        new com.rigour.hr.api.v1.model.AttendanceAssessmentOverride(-1, 0, "核对"),
                        new com.rigour.hr.api.v1.model.AttendanceAssessmentOverride(
                                null, null, "核对"),
                        new com.rigour.hr.api.v1.model.AttendanceAssessmentOverride(0, 0, " "))) {
            assertThatThrownBy(
                            () ->
                                    service.confirmRecord(
                                            "2026-09",
                                            "E1",
                                            new AttendanceRecordCommand(
                                                    new BigDecimal("26"),
                                                    BigDecimal.ZERO,
                                                    "NONE",
                                                    "",
                                                    0,
                                                    List.of(),
                                                    adjustment)))
                    .isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
        }
        service.confirmRecord(
                "2026-09",
                "E1",
                new AttendanceRecordCommand(
                        new BigDecimal("26"),
                        BigDecimal.ZERO,
                        "PERSONAL",
                        "",
                        0,
                        List.of(
                                new AttendanceExceptionPeriod(
                                        LocalDateTime.of(2026, 9, 3, 0, 0),
                                        LocalDateTime.of(2026, 9, 4, 0, 0),
                                        "UNPAID_LEAVE",
                                        "已填写请假依据"))));
        verify(store)
                .confirmRecord(
                        anyString(), anyString(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void attendanceMenuCanReopenAnyConfirmedRecordWithoutARuleReviewTask() {
        identity(Set.of(HrAttendanceService.READ));
        var snapshot =
                new AttendanceRow(
                        "E1",
                        "员工",
                        "销售部",
                        26,
                        "COMPANY",
                        new BigDecimal("24"),
                        new BigDecimal("2"),
                        24,
                        new BigDecimal("24"),
                        new BigDecimal("2"),
                        "PERSONAL",
                        "CONFIRMED",
                        "AVAILABLE",
                        List.of(),
                        "已核定",
                        3);
        when(store.employee(tenant.toString(), YearMonth.of(2026, 9), "E1"))
                .thenReturn(
                        new HrAttendanceStore.Employee(
                                "E1",
                                "员工",
                                2L,
                                "销售部",
                                null,
                                null,
                                new AttendanceRecordCommand(null, null, "PERSONAL", "已核定", 3),
                                snapshot));
        var command = new com.rigour.hr.api.v1.model.AttendanceReopenCommand(3, "出勤资料需要修正");
        service.reopenRecord("2026-09", "E1", command);
        verify(store).reopenRecord(tenant.toString(), "2026-09", "E1", command, user.toString());
        verifyNoInteractions(source);
        identity(Set.of());
        assertThatThrownBy(() -> service.reopenRecord("2026-09", "E1", command))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void reopeningRequiresAConfirmedRecordAndANonblankReason() {
        stubPendingEmployee();
        assertThatThrownBy(
                        () ->
                                service.reopenRecord(
                                        "2026-09",
                                        "E1",
                                        new com.rigour.hr.api.v1.model.AttendanceReopenCommand(
                                                0, " ")))
                .hasMessageContaining("撤销确认原因");
        assertThatThrownBy(
                        () ->
                                service.reopenRecord(
                                        "2026-09",
                                        "E1",
                                        new com.rigour.hr.api.v1.model.AttendanceReopenCommand(
                                                0, "核对资料")))
                .hasMessageContaining("尚未确认");
        verify(store, never())
                .reopenRecord(anyString(), anyString(), anyString(), any(), anyString());
    }
}

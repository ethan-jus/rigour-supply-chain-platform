package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.HrAttendanceModels.*;
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
                .thenAnswer(i -> new Rule(i.getArgument(1), null, 26, 1, 26, false));
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
                                                new RecordCommand(null, null, "NONE", "已说明", 2),
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
        var a = mock(DepartmentOption.class);
        var b = mock(DepartmentOption.class);
        var c = mock(DepartmentOption.class);
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
                .extracting(RuleListRow::departmentName)
                .containsExactly("公司默认", "销售一部", "人事部");
        var unlocked = service.rules("2026-10", null, "UNLOCKED", 1, 1);
        assertThat(unlocked.page().total()).isEqualTo(2);
        assertThat(unlocked.page().items())
                .extracting(RuleListRow::departmentId)
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
                .thenReturn(new Rule("2026-09", null, 1, 1, 1, false));
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
                .thenReturn(new Rule("2026-09", null, null, 0, null, false));
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
        assertThatThrownBy(() -> service.saveRule(new RuleCommand("2026-02", null, 29, 0)))
                .hasMessageContaining("当月天数");
        service.saveRule(new RuleCommand("2028-02", null, 29, 0));
        verify(store)
                .saveRule(
                        tenant.toString(),
                        new RuleCommand("2028-02", null, 29, 0),
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
        when(store.departments(tenant.toString())).thenReturn(List.of());
        assertThat(service.departments()).isEmpty();
        verify(store).departments(tenant.toString());
        clearInvocations(store);
        identity(Set.of(HrAttendanceService.RULE_READ));
        assertThat(service.departments()).isEmpty();
        verify(store).departments(tenant.toString());
        clearInvocations(store);
        identity(Set.of());
        assertThatThrownBy(() -> service.departments())
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
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
                .thenReturn(new Rule("2026-09", null, null, 0, null, false));
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
        var department = mock(DepartmentOption.class);
        when(department.id()).thenReturn(2L);
        when(store.departments(tenant.toString())).thenReturn(List.of(department));
        service.rule("2026-09", 2L);
        verify(store).rule(tenant.toString(), "2026-09", 2L);
        service.saveRule(new RuleCommand("2026-09", 2L, 0, 0));
        service.saveRule(new RuleCommand("2026-09", 2L, null, 1));
        verify(store)
                .saveRule(
                        tenant.toString(),
                        new RuleCommand("2026-09", 2L, null, 1),
                        user.toString());
        clearInvocations(store);
        assertThatThrownBy(() -> service.rule("2026-09", 9L)).hasMessageContaining("部门不存在");
        assertThatThrownBy(() -> service.saveRule(new RuleCommand("2026-09", 9L, 22, 0)))
                .hasMessageContaining("部门不存在");
        assertThatThrownBy(() -> service.saveRule(new RuleCommand("2026-09", null, null, 1)))
                .hasMessageContaining("当月天数");
        identity(Set.of(HrAttendanceService.RULE_READ));
        assertThatThrownBy(() -> service.saveRule(new RuleCommand("2026-09", 2L, 22, 0)))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(store, never()).rule(anyString(), anyString(), any());
        verify(store, never()).saveRule(anyString(), any(), anyString());
    }

    void employeeWith(RecordCommand record) {
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
                new RecordCommand(
                        new BigDecimal("20.5"), new BigDecimal("2.5"), "SICK", "核对后调整", 3));
        var row = service.list("2026-09", "", "", null, "", 0, 20).page().items().getFirst();
        assertThat(row.actualDays()).isEqualByComparingTo("20.5");
        assertThat(row.absentDays()).isEqualByComparingTo("2.5");
        assertThat(row.punchDays()).isEqualTo(1);
        assertThat(row.dates()).containsExactly(LocalDate.parse("2026-09-01"));
        assertThat(row.leaveTypeCode()).isEqualTo("SICK");
        employeeWith(new RecordCommand(new BigDecimal("20.5"), null, "PERSONAL", "", 4));
        assertThat(
                        service.list("2026-09", "", "", null, "", 0, 20)
                                .page()
                                .items()
                                .getFirst()
                                .absentDays())
                .isEqualByComparingTo("5.5");
        employeeWith(new RecordCommand(null, null, "NONE", "", 5));
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
        employeeWith(new RecordCommand(BigDecimal.ZERO, new BigDecimal("0.5"), "COMP_TIME", "", 1));
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
                new RecordCommand(
                        new BigDecimal("20.5"), new BigDecimal("0.5"), "ANNUAL", "  核对  ", 2);
        var employee =
                new HrAttendanceStore.Employee(
                        "E1",
                        "员工",
                        2L,
                        "销售部",
                        null,
                        null,
                        new RecordCommand(null, null, "NONE", "", 2),
                        null);
        when(store.employee(tenant.toString(), YearMonth.of(2026, 9), "E1")).thenReturn(employee);
        when(source.evidence(anyString(), any()))
                .thenReturn(
                        List.of(new Evidence("E1", true, List.of(LocalDate.parse("2026-09-01")))));
        service.confirmRecord("2026-09", "E1", command);
        var saved = org.mockito.ArgumentCaptor.forClass(Row.class);
        verify(store)
                .confirmRecord(
                        eq(tenant.toString()),
                        eq("2026-09"),
                        eq("E1"),
                        eq(
                                new RecordCommand(
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
        assertThat(snapshot.expectedDays()).isEqualTo(26);
        assertThat(snapshot.actualDays()).isEqualByComparingTo("20.5");
        assertThat(snapshot.absentDays()).isEqualByComparingTo("0.5");
        var confirmed =
                new HrAttendanceStore.Employee(
                        "E1", "员工新名字", 3L, "其他部", null, null, command, snapshot);
        when(store.employees(anyString(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new HrPageView<>(1, 0, 20, List.of(confirmed)));
        when(store.rule(anyString(), anyString(), isNull()))
                .thenReturn(new Rule("2026-09", null, 10, 2, 10, false));
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
                                new RecordCommand(null, null, "NONE", "", 0),
                                null));
        when(source.evidence(anyString(), any()))
                .thenReturn(List.of(new Evidence("E1", true, List.of())));
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-10",
                                        "E1",
                                        new RecordCommand(null, null, "NONE", "", 0)))
                .hasMessageContaining("实际出勤和缺勤天数");
        when(source.evidence(anyString(), any()))
                .thenReturn(List.of(new Evidence("E1", false, List.of())));
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-09",
                                        "E1",
                                        new RecordCommand(null, BigDecimal.ZERO, "NONE", "", 0)))
                .hasMessageContaining("实际出勤和缺勤天数");
        when(store.rule(anyString(), anyString(), isNull()))
                .thenReturn(new Rule("2026-09", null, null, 0, null, false));
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-09",
                                        "E1",
                                        new RecordCommand(
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
                                new RecordCommand(null, null, "NONE", "", 0),
                                null));
        service.confirmRecord(
                "2026-02",
                "E1",
                new RecordCommand(new BigDecimal("27.5"), BigDecimal.ZERO, "ANNUAL", "", 0));
        clearInvocations(store);
        for (String days : List.of("-0.5", "0.1", "28.5")) {
            assertThatThrownBy(
                            () ->
                                    service.confirmRecord(
                                            "2026-02",
                                            "E1",
                                            new RecordCommand(
                                                    new BigDecimal(days), null, "NONE", "", 0)))
                    .hasMessageContaining("0.5天");
            assertThatThrownBy(
                            () ->
                                    service.confirmRecord(
                                            "2026-02",
                                            "E1",
                                            new RecordCommand(
                                                    null, new BigDecimal(days), "NONE", "", 0)))
                    .hasMessageContaining("0.5天");
        }
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-02",
                                        "E1",
                                        new RecordCommand(null, null, "NONE", "x".repeat(501), 0)))
                .hasMessageContaining("500");
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-02",
                                        "E1",
                                        new RecordCommand(null, null, "NONE", "", 2)))
                .hasMessageContaining("已变更");
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-02",
                                        "E1",
                                        new RecordCommand(null, null, "UNKNOWN", "", 0)))
                .hasMessageContaining("请假类型");
        when(source.leaveTypes(anyString())).thenThrow(new IllegalStateException("字典暂不可用"));
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-02",
                                        "E1",
                                        new RecordCommand(null, null, "NONE", "", 0)))
                .hasMessageContaining("字典暂不可用");
        identity(Set.of(HrAttendanceService.READ));
        assertThatThrownBy(
                        () ->
                                service.confirmRecord(
                                        "2026-02",
                                        "E1",
                                        new RecordCommand(null, null, "NONE", "", 0)))
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
}

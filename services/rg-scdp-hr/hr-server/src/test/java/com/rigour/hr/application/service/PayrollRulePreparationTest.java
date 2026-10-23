package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrPayrollComponentStore;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class PayrollRulePreparationTest {
    @Test
    void historicalSalesIdentityKeepsOrdersRequiredOnlyForTheSameEmployeeAndEmployment() {
        var store = mock(HrPayrollComponentStore.class);
        var resolver = new PayrollRuleResolver(store);
        var employee = new PayrollEmployeeView("E1", "总部员工", 1L, "总部", null,
                LocalDate.of(2026, 9, 1), null, "HEADQUARTERS");
        var definition = new PayrollComponentDefinition(
                "历史销售薪资", "SALARY", "SALES", List.of(), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), null, null, null, null, null, List.of("E1"));
        when(store.versions("t")).thenReturn(List.of(
                new PayrollComponentVersionView("salary", "salary", 1, definition, "", "actor", Instant.EPOCH)));
        assertThat(resolver.hasHistoricalOrderDependency("t", employee, Set.of(1L), YearMonth.of(2026, 10))).isTrue();
        var other = new PayrollEmployeeView("E2", "其他员工", 1L, "总部", null,
                LocalDate.of(2026, 9, 1), null, "HEADQUARTERS");
        assertThat(resolver.hasHistoricalOrderDependency("t", other, Set.of(1L), YearMonth.of(2026, 10))).isFalse();
        assertThat(resolver.hasHistoricalOrderDependency("other-tenant", employee, Set.of(1L), YearMonth.of(2026, 10))).isFalse();
        var joinedLater = new PayrollEmployeeView("E1", "总部员工", 1L, "总部", null,
                LocalDate.of(2026, 10, 1), null, "HEADQUARTERS");
        assertThat(resolver.hasHistoricalOrderDependency("t", joinedLater, Set.of(1L), YearMonth.of(2026, 10))).isFalse();
    }

    @Test
    void historicalCommissionInAFormerDepartmentRemainsAnOrderDependency() {
        var store = mock(HrPayrollComponentStore.class);
        var resolver = new PayrollRuleResolver(store);
        var employee = new PayrollEmployeeView("E1", "总部员工", 1L, "总部", null,
                LocalDate.of(2026, 9, 1), null, "HEADQUARTERS");
        var definition = new PayrollComponentDefinition(
                "原部门提成", "COMMISSION", "ALL", List.of(9L), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), null, null,
                PayrollSchemeTestFixture.definition().commission(), null);
        when(store.versions("t")).thenReturn(List.of(
                new PayrollComponentVersionView("commission", "commission", 1, definition, "", "actor", Instant.EPOCH)));
        assertThat(resolver.hasHistoricalOrderDependency("t", employee, Set.of(1L, 9L), YearMonth.of(2026, 10))).isTrue();
        assertThat(resolver.hasHistoricalOrderDependency("t", employee, Set.of(1L), YearMonth.of(2026, 10))).isFalse();
        assertThat(resolver.hasHistoricalOrderDependency("t", employee, Set.of(1L, 9L), YearMonth.of(2026, 8))).isFalse();
    }

    @Test
    void missingSalaryNeverGuessesAStandardOrQueriesEmployeeDepartments() {
        var store = mock(HrPayrollComponentStore.class);
        when(store.versions("tenant-a")).thenReturn(List.of());
        var resolver = new PayrollRuleResolver(store);
        var people =
                java.util.stream.IntStream.range(0, 216)
                        .mapToObj(
                                i ->
                                        new PayrollEmployeeView(
                                                "E" + i,
                                                "员工" + i,
                                                1L,
                                                "销售",
                                                "城市",
                                                LocalDate.of(2026, 1, 1),
                                                null,
                                                "SALES",
                                                1,
                                                (long) i))
                        .toList();
        var matches =
                resolver.prepare(
                        "tenant-a",
                        people,
                        YearMonth.of(2026, 9),
                        employee -> {
                            throw new AssertionError(
                                    "Missing salary must not trigger a per-employee database"
                                        + " query");
                        });
        assertThat(matches).hasSize(216);
        assertThat(matches.values())
                .allSatisfy(
                        match -> {
                            assertThat(match.snapshot()).isNull();
                            assertThat(match.issues()).containsExactly("员工薪资标准未配置");
                        });
        verify(store, times(1)).versions("tenant-a");
        verifyNoMoreInteractions(store);
    }
}

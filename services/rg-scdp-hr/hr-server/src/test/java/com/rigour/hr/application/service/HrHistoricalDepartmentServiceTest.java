package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.HrHistoricalDepartmentApi.*;
import com.rigour.hr.application.port.out.HrHistoricalDepartmentStore;
import com.rigour.hr.application.port.out.HrHistoricalDepartmentStore.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

class HrHistoricalDepartmentServiceTest {
    final Employee employee = new Employee("E1", "历史员工", 99L, "调岗后部门", false);

    Query at(String date) {
        return new Query("E1", LocalDate.parse(date));
    }

    Assignment assignment(long department, String from, String to) {
        return new Assignment(
                "E1",
                department,
                "历史部门" + department,
                Instant.parse(from),
                to == null ? null : Instant.parse(to));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void readsBusinessDateAssignmentInsteadOfCurrentDepartmentAfterTransferOrDeparture() {
        var history =
                List.of(
                        assignment(6, "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z"),
                        assignment(99, "2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z"));
        var old = HrHistoricalDepartmentService.select(at("2026-09-10"), employee, history);
        assertThat(old.departmentId()).isEqualTo(6L);
        assertThat(old.basis()).isEqualTo("ASSIGNMENT");
        assertThat(old.issue()).isNull();
        assertThat(
                        HrHistoricalDepartmentService.select(at("2026-10-10"), employee, history)
                                .departmentId())
                .isEqualTo(99L);
    }

    @Test
    void importedEmployeeWithoutAssignmentUsesExplicitDepartmentSnapshotNotEmploymentEligibility() {
        var result = HrHistoricalDepartmentService.select(at("2026-09-10"), employee, List.of());
        assertThat(result.departmentId()).isEqualTo(99L);
        assertThat(result.basis()).isEqualTo("EMPLOYEE_SNAPSHOT");
        assertThat(result.issue()).isNull();
    }

    @Test
    void deletedEmployeeCanRetainProvenPastAssignmentButCannotUseCurrentFallback() {
        var deleted = new Employee("E1", "旧员工", 99L, "现部门", true);
        assertThat(
                        HrHistoricalDepartmentService.select(
                                        at("2026-09-10"),
                                        deleted,
                                        List.of(
                                                assignment(
                                                        6,
                                                        "2026-09-01T00:00:00Z",
                                                        "2026-10-01T00:00:00Z")))
                                .departmentId())
                .isEqualTo(6L);
        assertThat(
                        HrHistoricalDepartmentService.select(at("2026-09-10"), deleted, List.of())
                                .issue())
                .isNotNull();
    }

    @Test
    void overlappingDepartmentsOrSameDayTransferMustNotChooseNewest() {
        for (String from : List.of("2026-09-01T00:00:00Z", "2026-09-10T04:00:00Z")) {
            var result =
                    HrHistoricalDepartmentService.select(
                            at("2026-09-10"),
                            employee,
                            List.of(
                                    assignment(6, "2026-09-01T00:00:00Z", "2026-09-10T04:00:00Z"),
                                    assignment(99, from, null)));
            assertThat(result.departmentId()).isNull();
            assertThat(result.issue()).contains("不同部门");
        }
    }

    @Test
    void historicalGapDoesNotFallBackToTransferredCurrentDepartment() {
        var result =
                HrHistoricalDepartmentService.select(
                        at("2026-09-10"),
                        employee,
                        List.of(assignment(6, "2026-10-01T00:00:00Z", null)));
        assertThat(result.issue()).contains("业务日期");
        assertThat(result.departmentId()).isNull();
    }

    @Test
    void usesBeijingBusinessDayAndExclusiveEndBoundary() {
        var history = List.of(assignment(6, "2026-09-09T16:00:00Z", "2026-09-10T16:00:00Z"));
        assertThat(
                        HrHistoricalDepartmentService.select(at("2026-09-10"), employee, history)
                                .departmentId())
                .isEqualTo(6L);
        assertThat(
                        HrHistoricalDepartmentService.select(at("2026-09-11"), employee, history)
                                .issue())
                .isNotNull();
    }

    @Test
    void unresolvedEmployeeOrMissingDepartmentNeverCreatesAttribution() {
        assertThat(
                        HrHistoricalDepartmentService.select(at("2026-09-10"), null, List.of())
                                .departmentId())
                .isNull();
        assertThat(
                        HrHistoricalDepartmentService.select(
                                        at("2026-09-10"),
                                        new Employee("E1", "未知", null, null, false),
                                        List.of())
                                .issue())
                .isNotNull();
    }

    @Test
    void batchesOnlyCallerTenantAndSeparatesSameEmployeeDifferentDates() {
        var tenant = UUID.randomUUID();
        caller("SERVICE", tenant, Set.of("hr:employee:identity-read"));
        var store = mock(HrHistoricalDepartmentStore.class);
        when(store.employees(tenant.toString(), Set.of("E1"))).thenReturn(List.of(employee));
        when(store.assignments(tenant.toString(), Set.of("E1")))
                .thenReturn(
                        List.of(
                                assignment(6, "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z"),
                                assignment(99, "2026-10-01T00:00:00Z", null)));
        var rows =
                new HrHistoricalDepartmentService(store)
                        .resolve(List.of(at("2026-09-10"), at("2026-10-10"), at("2026-09-10")));
        assertThat(rows).extracting(Attribution::departmentId).containsExactly(6L, 99L);
        verify(store).employees(tenant.toString(), Set.of("E1"));
        verify(store).assignments(tenant.toString(), Set.of("E1"));
        verifyNoMoreInteractions(store);
    }

    @Test
    void rejectsUserOrMissingPermissionOrInvalidBatchBeforeReading() {
        var store = mock(HrHistoricalDepartmentStore.class);
        var service = new HrHistoricalDepartmentService(store);
        caller("TENANT", UUID.randomUUID(), Set.of("hr:employee:identity-read"));
        assertThatThrownBy(() -> service.resolve(List.of(at("2026-09-10"))))
                .isInstanceOf(AuthorizationDeniedException.class);
        caller("SERVICE", UUID.randomUUID(), Set.of());
        assertThatThrownBy(() -> service.resolve(List.of(at("2026-09-10"))))
                .isInstanceOf(AuthorizationDeniedException.class);
        caller("SERVICE", UUID.randomUUID(), Set.of("hr:employee:identity-read"));
        assertThatThrownBy(() -> service.resolve(Collections.nCopies(101, at("2026-09-10"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolve(List.of(new Query("E1", null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.resolve(List.of())).isEmpty();
        verifyNoInteractions(store);
    }

    private static void caller(String scope, UUID tenant, Set<String> permissions) {
        var principal = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        scope,
                        principal,
                        tenant,
                        "TENANT".equals(scope) ? principal : null,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        permissions));
    }
}

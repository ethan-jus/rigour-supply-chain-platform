package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.hr.application.port.out.HrAuditActorNameResolver;
import com.rigour.hr.infrastructure.persistence.mapper.HrEmployeeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.LocalDateTime;

class HrInitialAssignmentDateTest {
    final LocalDateTime now = LocalDateTime.parse("2026-11-03T05:52:47");
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final JdbcHrOrganizationStore store = new JdbcHrOrganizationStore(jdbc,
            mock(PlatformTransactionManager.class), mock(HrDataScope.class),
            mock(HrAuditActorNameResolver.class), mock(HrEmployeeMapper.class));

    @BeforeEach
    void useDatabaseClock() {
        when(jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class)).thenReturn(now);
    }

    @Test
    void firstSourceImportUsesExistingEntryDateInsteadOfTheImportTimestamp() {
        store.sourceEmployeeChanged("tenant", "employee", null, "ACTIVE", null, false, "actor");
        var insert = mockingDetails(jdbc).getInvocations().stream()
                .filter(i -> i.getArgument(0) instanceof String sql && sql.contains("INSERT INTO hr_employee_assignment"))
                .findFirst().orElseThrow();
        assertThat((String)insert.getArgument(0))
                .contains("DATE(CONVERT_TZ(e.entry_date,'+00:00','+08:00'))");
        assertThat((Boolean)insert.getArgument(1)).isTrue();
        assertThat((LocalDateTime)insert.getArgument(2)).isEqualTo(now);
        assertThat((String)insert.getArgument(5)).isEqualTo("tenant");
        assertThat((String)insert.getArgument(6)).isEqualTo("employee");
    }

    @Test
    void reactivationDoesNotBackdateTheNewTermToTheOriginalEntryDate() {
        store.sourceEmployeeChanged("tenant", "employee", previous("LEFT"), "ACTIVE", null, false, "actor");
        var insert = mockingDetails(jdbc).getInvocations().stream()
                .filter(i -> i.getArgument(0) instanceof String sql && sql.contains("INSERT INTO hr_employee_assignment"))
                .findFirst().orElseThrow();
        assertThat((Boolean)insert.getArgument(1)).isFalse();
        assertThat((LocalDateTime)insert.getArgument(3)).isEqualTo(now);
    }

    @Test
    void firstPendingEmployeeAssignmentUsesEntryDate() {
        store.sourceEmployeeChanged("tenant", "employee", previous("PENDING"), "ACTIVE", null, false, "actor");
        var insert = mockingDetails(jdbc).getInvocations().stream()
                .filter(i -> i.getArgument(0) instanceof String sql && sql.contains("INSERT INTO hr_employee_assignment"))
                .findFirst().orElseThrow();
        assertThat((Boolean)insert.getArgument(1)).isTrue();
    }

    @Test
    void knownLeaveDateClosesAssignmentAtNextShanghaiMidnight() {
        store.sourceEmployeeChanged(
                "tenant", "employee", previous("ACTIVE"), "LEFT",
                Instant.parse("2026-10-30T16:00:00Z"), false, "actor");

        var end = LocalDateTime.parse("2026-10-31T16:00:00");
        verify(jdbc).update(contains("UPDATE hr_employee_assignment"), eq(end), eq("tenant"), eq("employee"), eq(end));
    }

    @Test
    void closingBeforeCurrentAssignmentStartIsRejected() {
        when(jdbc.queryForObject(
                contains("AND employee_code=? AND effective_to IS NULL"),
                eq(Integer.class), eq("tenant"), eq("employee"))).thenReturn(1);

        assertThatThrownBy(() -> store.sourceEmployeeChanged(
                "tenant", "employee", previous("ACTIVE"), "LEFT",
                Instant.parse("2026-10-30T16:00:00Z"), false, "actor"))
                .hasMessageContaining("任职结束时间必须晚于当前任职开始时间");
    }

    @Test
    void sameStatusDoesNotRecreateOrCloseAnAssignment() {
        store.sourceEmployeeChanged("tenant", "employee", previous("ACTIVE"), "ACTIVE", null, false, "actor");
        assertThat(mockingDetails(jdbc).getInvocations()).noneMatch(i ->
                i.getArgument(0) instanceof String sql && sql.contains("hr_employee_assignment"));
    }

    private static JdbcHrOrganizationStore.PreviousAssignment previous(String status) {
        return new JdbcHrOrganizationStore.PreviousAssignment(status, null, 1L, "SALES");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void sourceCancelsScheduledDepartureBeforeCreatingAnyActualTransfer(boolean transfer) {
        var previous = new JdbcHrOrganizationStore.PreviousAssignment(
                "LEFT", LocalDateTime.parse("2026-11-07T16:00:00"), 1L, "SALES");
        var scheduledEnd = LocalDateTime.parse("2026-11-08T16:00:00");
        when(jdbc.queryForList(
                contains("ORDER BY effective_from DESC"), eq(Long.class), eq("tenant"), eq("employee")))
                .thenReturn(java.util.List.of(7L));
        when(jdbc.update(
                contains("SET effective_to=NULL"), eq("tenant"), eq("employee"), eq(7L), eq(1L),
                eq("SALES"), eq(scheduledEnd))).thenReturn(1);

        store.sourceEmployeeChanged("tenant", "employee", previous, "ACTIVE", null, transfer, "actor");

        var hasNewAssignment = mockingDetails(jdbc).getInvocations().stream()
                .anyMatch(call -> call.getArgument(0) instanceof String sql
                        && sql.contains("INSERT INTO hr_employee_assignment"));
        assertThat(hasNewAssignment).isEqualTo(transfer);
    }
}

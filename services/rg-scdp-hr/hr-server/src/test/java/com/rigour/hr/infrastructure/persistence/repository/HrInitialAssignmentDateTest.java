package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.hr.application.port.out.HrAuditActorNameResolver;
import com.rigour.hr.infrastructure.persistence.mapper.HrEmployeeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class HrInitialAssignmentDateTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final JdbcHrOrganizationStore store = new JdbcHrOrganizationStore(jdbc,
            mock(PlatformTransactionManager.class), mock(HrDataScope.class),
            mock(HrAuditActorNameResolver.class), mock(HrEmployeeMapper.class));

    @Test
    void firstSourceImportUsesExistingEntryDateInsteadOfTheImportTimestamp() {
        store.sourceEmployeeChanged("tenant", "employee", null, "ACTIVE", "actor");
        var insert = mockingDetails(jdbc).getInvocations().stream()
                .filter(i -> i.getArgument(0) instanceof String sql && sql.contains("INSERT INTO hr_employee_assignment"))
                .findFirst().orElseThrow();
        assertThat((String)insert.getArgument(0)).contains("COALESCE(e.entry_date,UTC_TIMESTAMP(6))");
        assertThat((Boolean)insert.getArgument(1)).isTrue();
        assertThat((String)insert.getArgument(3)).isEqualTo("tenant");
        assertThat((String)insert.getArgument(4)).isEqualTo("employee");
    }

    @Test
    void reactivationDoesNotBackdateTheNewTermToTheOriginalEntryDate() {
        store.sourceEmployeeChanged("tenant", "employee", "LEFT", "ACTIVE", "actor");
        var insert = mockingDetails(jdbc).getInvocations().stream()
                .filter(i -> i.getArgument(0) instanceof String sql && sql.contains("INSERT INTO hr_employee_assignment"))
                .findFirst().orElseThrow();
        assertThat((Boolean)insert.getArgument(1)).isFalse();
    }

    @Test
    void sameStatusDoesNotRecreateOrCloseAnAssignment() {
        store.sourceEmployeeChanged("tenant", "employee", "ACTIVE", "ACTIVE", "actor");
        assertThat(mockingDetails(jdbc).getInvocations()).noneMatch(i ->
                i.getArgument(0) instanceof String sql && sql.contains("hr_employee_assignment"));
    }
}

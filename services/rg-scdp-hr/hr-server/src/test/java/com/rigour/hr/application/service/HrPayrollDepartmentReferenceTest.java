package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.PayrollDepartmentView;
import com.rigour.hr.application.port.out.HrPayrollSource;
import com.rigour.hr.application.port.out.HrPayrollStore;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

class HrPayrollDepartmentReferenceTest {
    private final UUID tenant = UUID.randomUUID();
    private final HrPayrollStore store = mock(HrPayrollStore.class);
    private final HrPayrollService service =
            new HrPayrollService(store, mock(HrPayrollSource.class), JsonMapper.builder().build());

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void payrollReaderCanLoadDepartmentHierarchyWithoutRulePermission() {
        authorize("hr:payroll:read");
        var departments = List.of(new PayrollDepartmentView(2L, 1L, "销售部", 10));
        when(store.departments(tenant.toString(), "hr:payroll:read")).thenReturn(departments);

        assertThat(service.payrollDepartments()).isEqualTo(departments);
        assertThatThrownBy(service::departments).isInstanceOf(AuthorizationDeniedException.class);
        verify(store).departments(tenant.toString(), "hr:payroll:read");
        verifyNoMoreInteractions(store);
    }

    @Test
    void ruleReaderUsesRuleDataScopeAndCannotReadPayrollReferences() {
        authorize("hr:payroll-rule:read");
        var departments = List.of(new PayrollDepartmentView(3L, 1L, "运营部", 20));
        when(store.departments(tenant.toString(), "hr:payroll-rule:read")).thenReturn(departments);

        assertThat(service.departments()).isEqualTo(departments);
        assertThatThrownBy(service::payrollDepartments)
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(store).departments(tenant.toString(), "hr:payroll-rule:read");
        verifyNoMoreInteractions(store);
    }

    private void authorize(String permission) {
        var user = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT", user, tenant, user, null, UUID.randomUUID(),
                        0, 0, 0, Set.of(), Set.of(permission)));
    }
}

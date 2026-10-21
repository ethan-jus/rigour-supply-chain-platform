package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.PayrollQuery;
import com.rigour.hr.application.port.out.HrPayrollSource;
import com.rigour.hr.application.port.out.HrPayrollStore;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

class HrPayrollSummaryAccessTest {
    final UUID tenant = UUID.randomUUID();
    final HrPayrollStore store = mock(HrPayrollStore.class);
    final HrPayrollService service =
            new HrPayrollService(
                    store, mock(HrPayrollSource.class), mock(ConfiguredPayrollService.class));

    void actor(Set<String> permissions) {
        UUID user = UUID.randomUUID();
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
                        permissions));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void aggregateUsesCurrentTenantAndSameReadScopeAsList() {
        actor(Set.of("hr:payroll:read"));
        var query = new PayrollQuery(0, 20, "2026-09", "许", 6L, "READY");
        service.summary(query);
        verify(store).summary(tenant.toString(), query, "hr:payroll:read");
    }

    @Test
    void aggregateRejectsMissingPayrollReadPermissionBeforeQuery() {
        actor(Set.of("hr:employee:read"));
        assertThatThrownBy(
                        () -> service.summary(new PayrollQuery(0, 20, "2026-09", null, null, null)))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(store);
    }

    @Test
    void monthRequiredForSummaryAndInvalidMonthDoesNotQueryOtherPeriods() {
        actor(Set.of("hr:payroll:read"));
        for (String month : new String[] {null, "", "2026-13", "2026-9"}) {
            assertThatThrownBy(
                            () -> service.summary(new PayrollQuery(0, 20, month, null, null, null)))
                    .hasMessageContaining("月份格式");
        }
        verifyNoInteractions(store);
    }
}

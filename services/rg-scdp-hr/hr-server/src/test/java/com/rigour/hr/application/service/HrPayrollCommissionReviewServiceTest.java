package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HrPayrollCommissionReviewServiceTest {
    @AfterEach void clear() { TestAuthorizationContext.clear(); }
    @Test void readOnlyUsersAndOutOfScopeSelectionsCannotReachCalculationSources() {
        var workflow = mock(HrPayrollWorkflowService.class); var store = mock(HrPayrollStore.class);
        var schemes = mock(HrPayrollSchemeStore.class); var source = mock(HrPayrollSource.class);
        var engine = mock(ConfiguredCommissionService.class);
        var service = new HrPayrollCommissionReviewService(workflow, store, schemes, source, engine);
        var query = new PayrollQuery(0, 200, "2026-10", null, null, null, null, null, null, List.of("P1"));
        UUID user = UUID.randomUUID(), tenant = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT", user, tenant, user, null, UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("hr:payroll:read")));
        assertThatThrownBy(() -> service.preview(query)).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(workflow, store, schemes, source, engine);
        TestAuthorizationContext.set(new CallerIdentity("TENANT", user, tenant, user, null, UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("hr:payroll:read", "hr:payroll:calculate")));
        when(workflow.export(query)).thenThrow(new IllegalArgumentException("无权访问"));
        assertThatThrownBy(() -> service.preview(query)).hasMessageContaining("无权访问");
        verifyNoInteractions(store, schemes, source, engine);
    }
    @Test void pendingAmountsDoNotBecomeZeroAndUnresolvedRowsKeepTheResultIncomplete() {
        var row = new PayrollPerformanceReportFactsTest().row();
        var partial = new ConfiguredCommissionResult(new BigDecimal("40"), BigDecimal.ZERO, List.of(), List.of("鹰眼缺少提成标准"));
        var pending = HrPayrollCommissionReviewService.compare(row, partial);
        assertThat(pending.savedCommission()).isNull(); assertThat(pending.previewCommission()).isNull(); assertThat(pending.difference()).isNull();
        assertThat(pending.resolvedSubtotal()).isEqualByComparingTo("40");
        var completed = HrPayrollCommissionReviewService.compare(row, new ConfiguredCommissionResult(new BigDecimal("40"), BigDecimal.ZERO, List.of(), List.of()));
        assertThat(completed.previewCommission()).isEqualByComparingTo("40");
        assertThat(completed.difference()).isNull();
    }
}

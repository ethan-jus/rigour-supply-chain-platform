package com.rigour.analytics.api.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.analytics.application.port.out.BiDataScopeStore;
import com.rigour.analytics.application.port.out.BiDataScopeStore.*;
import com.rigour.analytics.application.service.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

/** 直接访问报表、库存和治理入口不能绕过统一数据范围。 */
class BiScopedEndpointsTest {
    private static final UUID TENANT = UUID.randomUUID(), USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private final BiDataScopeStore store = mock(BiDataScopeStore.class);
    private final BiDataScopeService scopes = new BiDataScopeService(store);
    private final CityProductReportService reports = mock(CityProductReportService.class);
    private final CityProductSupplyService supply = mock(CityProductSupplyService.class);
    private final SupplyDashboardGovernanceService governance =
            mock(SupplyDashboardGovernanceService.class);
    private final SupplyDashboardRefreshService refresh = mock(SupplyDashboardRefreshService.class);
    private final SupplyDashboardCityCostImportService costs =
            mock(SupplyDashboardCityCostImportService.class);
    private final AnalyticsSupplyDashboardController dashboard =
            new AnalyticsSupplyDashboardController(
                    mock(SupplyDashboardQueryService.class), refresh, costs, governance, scopes);

    @BeforeEach
    void setup() {
        authorize(Set.of("assigned-role"));
    }

    @AfterEach
    void clear() {
        com.rigour.analytics.TestRoleScope.clear();
    }

    @Test
    void exportUsesQueryFiltersAndCurrentRoleScope() {
        new AnalyticsCityProductReportController(reports, scopes)
                .cityProductReport(NOW, NOW, "SH", "E2", null, null, null, "NONE", 1L, 2L, 3L);
        verify(reports).report(NOW, NOW, "SH", "E2", null, null, null, "NONE", 1L, 2L, 3L);
        assertThat(scopes.resolve("SH", "E2").fullTenant()).isFalse();
    }

    @Test
    void restrictedFilterOptionsNeverQueryGlobalGovernance() {
        dashboard.filterOptions();
        verify(store).filterOptions(TENANT.toString(), null, null);
        verifyNoInteractions(governance);
    }

    @Test
    void unresolvedIdentityCannotObtainFiltersOrExports() {
        com.rigour.analytics.TestRoleScope.clear();
        assertThatThrownBy(dashboard::filterOptions)
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(
                        () ->
                                new AnalyticsCityProductReportController(reports, scopes)
                                        .cityProductReport(
                                                NOW, NOW, null, null, null, null, null, "NONE",
                                                null, null, null))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(governance, reports);
    }

    @Test
    void restrictedUserCannotAccessUnpartitionedInventoryEvenWithKnownWarehouseId() {
        var controller = new AnalyticsCityProductSupplyController(supply, scopes);
        assertThatThrownBy(() -> controller.supply(NOW, NOW, 1L, 1L, 1L))
                .isInstanceOf(AuthorizationDeniedException.class);

        assertThatThrownBy(() -> controller.supply(NOW, NOW, 1L, 1L, 1L))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(supply);
    }

    @Test
    void restrictedUserCannotReadReconciliationSourcesOrTriggerTenantJobs() {
        assertThatThrownBy(dashboard::trust).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(dashboard::feishuArchives)
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> dashboard.reconciliation(NOW, NOW, "BJ", "E1", null, null, null))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> dashboard.triggerRefreshRun(null))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> dashboard.importCityCosts(null))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> dashboard.registerFeishuArchive(null))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(governance, refresh, costs);
    }

    @Test
    void establishedAdminCanReadTenantInventoryAndFilters() {
        authorize(Set.of("TENANT_SUPER_ADMIN"));
        new AnalyticsCityProductSupplyController(supply, scopes).supply(NOW, NOW, 1L, 1L, 1L);
        dashboard.filterOptions();
        verify(supply).supply(NOW, NOW, 1L, 1L, 1L);
        verify(governance).filterOptions();
    }

    private static void authorize(Set<String> roles) {
        com.rigour.analytics.TestRoleScope.set(
                new CallerIdentity(
                        "TENANT",
                        USER,
                        TENANT,
                        USER,
                        null,
                        UUID.randomUUID(),
                        1,
                        1,
                        1,
                        roles,
                        Set.of("analytics:dashboard:read")),
                roles.contains("TENANT_SUPER_ADMIN") ? "ALL" : "SELF");
    }
}

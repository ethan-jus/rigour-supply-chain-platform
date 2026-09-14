package com.rigour.analytics.api.controller;

import com.rigour.analytics.application.port.out.BiDataScopeStore;
import com.rigour.analytics.application.port.out.BiDataScopeStore.*;
import com.rigour.analytics.application.service.*;
import com.rigour.shared.context.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 直接访问报表、库存和治理入口不能绕过统一数据范围。 */
class BiScopedEndpointsTest {
    private static final UUID TENANT = UUID.randomUUID(), USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private final BiDataScopeStore store = mock(BiDataScopeStore.class);
    private final BiDataScopeService scopes = new BiDataScopeService(store, Clock.fixed(NOW, ZoneOffset.UTC), mock(BiDataScopeRenewer.class));
    private final CityProductReportService reports = mock(CityProductReportService.class);
    private final CityProductSupplyService supply = mock(CityProductSupplyService.class);
    private final SupplyDashboardGovernanceService governance = mock(SupplyDashboardGovernanceService.class);
    private final SupplyDashboardRefreshService refresh = mock(SupplyDashboardRefreshService.class);
    private final SupplyDashboardCityCostImportService costs = mock(SupplyDashboardCityCostImportService.class);
    private final AnalyticsSupplyDashboardController dashboard = new AnalyticsSupplyDashboardController(
            mock(SupplyDashboardQueryService.class), refresh, costs, governance, scopes);

    @BeforeEach void setup() {
        authorize(Set.of("assigned-role"));
        when(store.identity(any(), any())).thenReturn(Optional.of(new Identity("E1", "E1", "iam:b", "hr:1", "crm:contract", 1, 1, NOW, NOW.plusSeconds(28800))));
        when(store.grants(any(), any())).thenReturn(List.of(new Grant("assigned-role", "SELF", "BJ", "policy")));
    }
    @AfterEach void clear() { TestAuthorizationContext.clear(); }
    @Test void directExportReportCannotOverrideSelfOwnerOrCity() {
        var controller = new AnalyticsCityProductReportController(reports, scopes);
        assertThatThrownBy(() -> controller.cityProductReport(NOW, NOW, "BJ", "E2", null, null, null, "NONE", null, null, null))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> controller.cityProductReport(NOW, NOW, "SH", "E1", null, null, null, "PROPORTIONAL", null, null, null))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(reports);
    }
    @Test void omittedExportFiltersAreReplacedWithAuthorizedDefaults() {
        new AnalyticsCityProductReportController(reports, scopes).cityProductReport(NOW, NOW, null, null, null, null, null, "NONE", 1L, 2L, 3L);
        verify(reports).report(NOW, NOW, "BJ", "E1", null, null, null, "NONE", 1L, 2L, 3L);
    }
    @Test void restrictedFilterOptionsNeverQueryGlobalGovernance() {
        dashboard.filterOptions();
        verify(store).filterOptions(TENANT.toString(), List.of("BJ"), "E1");
        verifyNoInteractions(governance);
    }
    @Test void unresolvedIdentityCannotObtainFiltersOrExports() {
        when(store.identity(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(dashboard::filterOptions).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> new AnalyticsCityProductReportController(reports, scopes)
                .cityProductReport(NOW, NOW, null, null, null, null, null, "NONE", null, null, null))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(governance, reports);
    }
    @Test void restrictedUserCannotAccessUnpartitionedInventoryEvenWithKnownWarehouseId() {
        var controller = new AnalyticsCityProductSupplyController(supply, scopes);
        assertThatThrownBy(() -> controller.supply(NOW, NOW, 1L, 1L, 1L)).isInstanceOf(AuthorizationDeniedException.class);
        when(store.grants(any(), any())).thenReturn(List.of(new Grant("assigned-role", "MY_CITY", "BJ", "policy")));
        assertThatThrownBy(() -> controller.supply(NOW, NOW, 1L, 1L, 1L)).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(supply);
    }
    @Test void restrictedUserCannotReadReconciliationSourcesOrTriggerTenantJobs() {
        assertThatThrownBy(dashboard::trust).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(dashboard::feishuArchives).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> dashboard.reconciliation(NOW, NOW, "BJ", "E1", null, null, null)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> dashboard.triggerRefreshRun(null)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> dashboard.importCityCosts(null)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> dashboard.registerFeishuArchive(null)).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(governance, refresh, costs);
    }
    @Test void establishedAdminCanReadTenantInventoryAndFilters() {
        authorize(Set.of("TENANT_SUPER_ADMIN"));
        new AnalyticsCityProductSupplyController(supply, scopes).supply(NOW, NOW, 1L, 1L, 1L);
        dashboard.filterOptions();
        verify(supply).supply(NOW, NOW, 1L, 1L, 1L);
        verify(governance).filterOptions();
    }
    private static void authorize(Set<String> roles) {
        TestAuthorizationContext.set(new CallerIdentity("TENANT", USER, TENANT, USER, null, UUID.randomUUID(), 1, 1, 1, roles, Set.of("analytics:dashboard:read")));
    }
}

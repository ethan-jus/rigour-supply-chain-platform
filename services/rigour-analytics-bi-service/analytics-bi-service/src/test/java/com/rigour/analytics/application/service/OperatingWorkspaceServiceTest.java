package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.OperatingWorkspaceModels.*;
import com.rigour.analytics.api.v1.model.BiEffectiveScopeView;
import com.rigour.analytics.application.port.out.OperatingWorkspaceStore;
import com.rigour.analytics.application.port.out.OperatingWorkspaceStore.BusinessSubject;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 在应用边界验证权限、真实业务对象、状态流转及目标金额限制。 */
class OperatingWorkspaceServiceTest {
    private static final UUID TENANT = UUID.randomUUID(), USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private final OperatingWorkspaceStore store = mock(OperatingWorkspaceStore.class);
    private final BiDataScopeService scope = mock(BiDataScopeService.class);
    private final OperatingWorkspaceService service = new OperatingWorkspaceService(store, Clock.fixed(NOW, ZoneOffset.UTC), scope);

    @BeforeEach void setup() {
        authorize(Set.of(OperatingWorkspaceService.READ, OperatingWorkspaceService.TARGET_WRITE, OperatingWorkspaceService.ACTION_WRITE));
        when(scope.effective()).thenReturn(new BiEffectiveScopeView("TENANT", "AUTHORIZED", null, List.of(), null, null, null, null, true, List.of()));
        when(scope.resolve(any(), any())).thenReturn(new BiDataScopeService.ScopedSelection(TENANT.toString(), "BJ", "E1", false));
        when(store.targetRegions(any(), any(), any())).thenReturn(List.of("BJ"));
        when(store.subjects(any(), any(), any())).thenReturn(List.of(new BusinessSubject("customer-id:17", "真实客户", "BJ", "E1")));
    }
    @AfterEach void clear() { TestAuthorizationContext.clear(); }
    @Test void readonlyPermissionCannotWriteEitherSurface() {
        authorize(Set.of(OperatingWorkspaceService.READ));
        assertThatThrownBy(() -> service.saveTarget(target("1", 0))).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.createAction(create())).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }
    @Test void dashboardPermissionAloneNeverCreatesGlobalScope() {
        service.actions(null, null, "SH", "OTHER", null, null, 1, 20);
        verify(scope).resolve("SH", "OTHER");
        verify(store).actions(eq(TENANT.toString()), argThat(f -> f.cityCode().equals("BJ") && f.employeeCode().equals("E1")));
    }
    @ParameterizedTest @ValueSource(strings={"-1", "1000000000000", "1.001"})
    void rejectsInvalidBoundedDecimals(String value) {
        assertThatThrownBy(() -> service.saveTarget(target(value, 0))).isInstanceOf(BusinessException.class);
        verify(store, never()).saveTarget(any(), any(), any(), any());
    }
    @Test void acceptsZeroAndPassesRevisionWithoutRounding() {
        service.saveTarget(target("0.00", 7));
        verify(store).saveTarget(eq(TENANT.toString()), eq(USER.toString()),
                argThat(c -> c.targetValue().scale() == 2 && c.expectedRevision() == 7), eq(NOW));
        verify(scope).requireObjectScope("BJ", null);
    }
    @Test void rejectsFractionalCustomerCountsAndInvalidMonth() {
        var command = new TargetCommand("2026-09", "CITY", "BJ", "北京", "CONTACTED_CUSTOMER", new BigDecimal("1.2"), null, 0);
        assertThatThrownBy(() -> service.saveTarget(command)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.targets("2026-13", null, null)).isInstanceOf(BusinessException.class);
    }
    @Test void selfCannotChangeCityTargetOrDeleteOthersTarget() {
        when(scope.effective()).thenReturn(new BiEffectiveScopeView("SELF", "AUTHORIZED", null, List.of("BJ"), "E1", "E1", "BJ", "E1", false, List.of()));
        assertThatThrownBy(() -> service.saveTarget(target("1", 0))).isInstanceOf(AuthorizationDeniedException.class);
        when(store.target(TENANT.toString(), "x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteTarget("x", 1)).isInstanceOf(BusinessException.class);
        verify(store, never()).deleteTarget(any(), any(), any(), anyInt(), any());
    }
    @Test void createsFromActualSubjectNotClientSpoofedCityOwnerOrLabel() {
        service.createAction(create());
        verify(store).createAction(eq(TENANT.toString()), eq(USER.toString()),
                argThat(c -> c.businessRef().equals("customer-id:17") && c.cityCode().equals("BJ")
                        && c.employeeCode().equals("E1") && c.businessLabel().equals("真实客户")), eq(NOW));
    }
    @Test void unknownOrAmbiguousBusinessSubjectCannotBecomeTask() {
        when(store.subjects(any(), any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.createAction(create())).isInstanceOf(BusinessException.class);
        verify(store, never()).createAction(any(), any(), any(), any());
    }
    @Test void directCompletionAndStaleWritesRejected() {
        when(store.action(TENANT.toString(), "id")).thenReturn(Optional.of(action("OPEN", 1)));
        assertThatThrownBy(() -> service.updateAction("id", update("RESOLVED", 1))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.updateAction("id", update("IN_PROGRESS", 2))).isInstanceOf(BusinessException.class);
        verify(store, never()).updateAction(any(), any(), any(), any(), any());
    }
    @Test void progressResolutionAndReopenPersistExplicitNotes() {
        for (var state : List.of(new String[]{"OPEN", "IN_PROGRESS"}, new String[]{"IN_PROGRESS", "RESOLVED"}, new String[]{"RESOLVED", "OPEN"}, new String[]{"DISMISSED", "OPEN"})) {
            var previous = action(state[0], 3);
            when(store.action(TENANT.toString(), "id")).thenReturn(Optional.of(previous));
            when(store.updateAction(any(), any(), any(), any(), any())).thenReturn(Optional.of(action(state[1], 4)));
            assertThat(service.updateAction("id", update(state[1], 3)).status()).isEqualTo(state[1]);
        }
    }
    @Test void currentBusinessScopeCheckedForHistoryAndMutation() {
        when(store.action(TENANT.toString(), "id")).thenReturn(Optional.of(action("OPEN", 1)));
        doThrow(new AuthorizationDeniedException("denied")).when(scope).requireObjectScope("BJ", "E1");
        assertThatThrownBy(() -> service.events("id")).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.updateAction("id", update("IN_PROGRESS", 1))).isInstanceOf(AuthorizationDeniedException.class);
        verify(store, never()).events(any(), any());
    }
    @Test void selectedCityIsResolvedRatherThanSilentlyUsingDefaultCity() {
        service.targets("2026-09", "CITY", "SH");
        verify(scope).resolve("SH", null);
    }
    @Test void explicitlyAssignedSelfCanFollowUpWithoutGainingBusinessReadAccess() {
        when(scope.effective()).thenReturn(new BiEffectiveScopeView("SELF", "AUTHORIZED", null, List.of("BJ"), "E2", "E2", "BJ", "E2", false, List.of()));
        var assigned = new ActionView("id", "COLLECTION", "customer-id:17", "客户", "BJ", "E1", "E2", NOW,
                "OPEN", "已指派", 1, USER.toString(), NOW, NOW);
        when(store.action(TENANT.toString(), "id")).thenReturn(Optional.of(assigned));
        service.events("id");
        verify(scope, times(2)).requireObjectScope("BJ", "E2");
        verify(store).events(TENANT.toString(), "id");
        verify(scope, never()).requireGlobalGovernance();
    }
    private TargetCommand target(String value, int revision) {
        return new TargetCommand("2026-09", "CITY", "BJ", "北京", "SALES_AMOUNT", new BigDecimal(value), null, revision);
    }
    private ActionCommand create() { return new ActionCommand("COLLECTION", "customer-code:C17", "伪造名称", "SH", "OTHER", "E1", NOW, "联系客户"); }
    private ActionUpdateCommand update(String status, int revision) { return new ActionUpdateCommand("E1", NOW, status, "已与客户确认下一步", revision); }
    private ActionView action(String status, int revision) {
        return new ActionView("id", "COLLECTION", "customer-id:17", "客户", "BJ", "E1", "E1", NOW, status, "原记录", revision, USER.toString(), NOW, NOW);
    }
    private void authorize(Set<String> permissions) {
        TestAuthorizationContext.set(new CallerIdentity("TENANT", USER, TENANT, USER, null, UUID.randomUUID(), 1, 1, 1, Set.of(), permissions));
    }
}

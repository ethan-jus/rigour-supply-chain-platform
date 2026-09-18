package com.rigour.analytics.application.service;

import com.rigour.analytics.application.port.out.BiDataScopeStore;
import com.rigour.analytics.application.port.out.BiDataScopeStore.Grant;
import com.rigour.analytics.application.port.out.BiDataScopeStore.Identity;
import com.rigour.shared.context.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证默认范围与直接篡改参数均由服务端收紧。 */
class BiDataScopeServiceTest {
    private static final UUID TENANT = UUID.randomUUID(), USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private final BiDataScopeStore store = mock(BiDataScopeStore.class);
    private final BiDataScopeRenewer renewer = mock(BiDataScopeRenewer.class);
    private final BiDataScopeService service = new BiDataScopeService(store, Clock.fixed(NOW, ZoneOffset.UTC), renewer);

    @BeforeEach void setup() {
        authorize(Set.of("assigned-role"), Set.of("analytics:dashboard:read"));
        when(store.identity(TENANT.toString(), USER.toString())).thenReturn(Optional.of(identity(NOW, 1)));
        when(store.grants(TENANT.toString(), USER.toString())).thenReturn(List.of(grant("SELF", "BJ")));
    }
    @AfterEach void clear() { TestAuthorizationContext.clear(); }
    @Test void ordinaryDashboardPermissionDoesNotImplyTenantAccess() {
        when(store.identity(any(), any())).thenReturn(Optional.empty());
        assertThat(service.effective().accessLevel()).isEqualTo("DENIED");
        assertThatThrownBy(() -> service.resolve(null, null)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(service::restrictedFilterOptions).isInstanceOf(AuthorizationDeniedException.class);
    }
    @Test void establishedTenantAdminKeepsGlobalAccessWithoutEmployeeProjection() {
        authorize(Set.of("TENANT_SUPER_ADMIN"), Set.of("analytics:dashboard:read"));
        assertThat(service.resolve(null, null).fullTenant()).isTrue();
        assertThat(service.effective().defaultRegionCode()).isNull();
        service.requireGlobalGovernance();
        verifyNoInteractions(store, renewer);
    }
    @Test void adminRoleWithoutBiPermissionIsNotEnough() {
        authorize(Set.of("TENANT_SUPER_ADMIN"), Set.of());
        assertThatThrownBy(service::effective).isInstanceOf(AuthorizationDeniedException.class);
    }
    @Test void selfHasExactDefaultsEvenWithNoBusinessRecords() {
        var selection = service.resolve(null, null);
        assertThat(selection.regionCode()).isEqualTo("BJ");
        assertThat(selection.ownerStaffCode()).isEqualTo("E1");
        assertThat(selection.tenantId()).isEqualTo(TENANT.toString());
        assertThat(selection.fullTenant()).isFalse();
        service.restrictedFilterOptions();
        verify(store).filterOptions(TENANT.toString(), List.of("BJ"), "E1");
    }
    @Test void selfCannotChangeCityOrEmployeeViaQueryOrObjectId() {
        assertThatThrownBy(() -> service.resolve("SH", "E1")).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.resolve("BJ", "E2")).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.requireObjectScope("BJ", "E2")).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.requireObjectScope(null, "E1")).isInstanceOf(AuthorizationDeniedException.class);
        service.requireObjectScope("BJ", "E1");
    }
    @Test void multipleCityScopeDefaultsToOneAuthorizedCityNeverAll() {
        when(store.grants(any(), any())).thenReturn(List.of(grant("MY_REGION", "SH"), grant("MY_REGION", "BJ")));
        assertThat(service.effective().regionCodes()).containsExactly("BJ", "SH");
        assertThat(service.resolve(null, null).regionCode()).isEqualTo("BJ");
        assertThat(service.resolve("SH", "E2").ownerStaffCode()).isEqualTo("E2");
        assertThatThrownBy(() -> service.resolve("WH", null)).isInstanceOf(AuthorizationDeniedException.class);
        service.restrictedFilterOptions();
        verify(store).filterOptions(TENANT.toString(), List.of("BJ", "SH"), null);
    }
    @Test void mixedSelfAndCityGrantsDoNotExpandSelfCityToAllEmployees() {
        when(store.grants(any(), any())).thenReturn(List.of(grant("SELF", "SH"), grant("MY_CITY", "BJ")));
        assertThat(service.effective().accessLevel()).isEqualTo("CITY");
        assertThat(service.effective().regionCodes()).containsExactly("BJ");
        assertThatThrownBy(() -> service.resolve("SH", "E2")).isInstanceOf(AuthorizationDeniedException.class);
    }
    @Test void revokedRoleOrUnsupportedPolicyNeverFallsBackToAll() {
        when(store.grants(any(), any())).thenReturn(List.of(new Grant("not-assigned", "MY_CITY", "BJ", "p")));
        assertThat(service.effective().reasonCode()).isEqualTo("SCOPE_UNAVAILABLE");
        when(store.grants(any(), any())).thenReturn(List.of(grant("ALL", "BJ")));
        assertThat(service.effective().reasonCode()).isEqualTo("SCOPE_UNSUPPORTED");
    }
    @Test void changedSecurityVersionRequiresNewConfiguration() {
        when(store.identity(any(), any())).thenReturn(Optional.of(identity(NOW, 2)));
        assertThat(service.effective().reasonCode()).isEqualTo("SCOPE_STALE");
        verifyNoInteractions(renewer);
    }
    @Test void staleSourceIsReverifiedAndFailureDeniesInsteadOfUsingOldCache() {
        var previous = identity(NOW.minusSeconds(901), 1);
        when(store.identity(any(), any())).thenReturn(Optional.of(previous));
        when(renewer.renew(any(), eq(previous), any())).thenReturn(Optional.empty());
        assertThat(service.effective().reasonCode()).isEqualTo("SOURCE_REVALIDATION_FAILED");
        when(renewer.renew(any(), eq(previous), any())).thenReturn(Optional.of(identity(NOW, 1)));
        assertThat(service.resolve(null, null).ownerStaffCode()).isEqualTo("E1");
    }
    @Test void restrictedUserCannotReadTenantReconciliationOrUnpartitionedSubjects() {
        assertThatThrownBy(service::requireGlobalGovernance).isInstanceOf(AuthorizationDeniedException.class);
        assertThat(service.effective().unavailableSubjects()).contains("INVENTORY", "CITY_COST", "RECONCILIATION");
    }
    @Test void sourceScopedCustomerHistoryAndTargetsAreNotPermanentlyDisabledForSelfOrCity() {
        assertThat(service.effective().unavailableSubjects()).doesNotContain("LEGACY_CUSTOMER_ACTIVITY", "LEGACY_TARGETS", "LEGACY_SALES_TARGETS");
        when(store.grants(any(), any())).thenReturn(List.of(grant("MY_CITY", "BJ")));
        assertThat(service.effective().unavailableSubjects()).doesNotContain("LEGACY_CUSTOMER_ACTIVITY", "LEGACY_TARGETS", "LEGACY_SALES_TARGETS", "CITY_COST");
    }
    private static Grant grant(String type, String city) { return new Grant("assigned-role", type, city, "verified-policy"); }
    private static Identity identity(Instant verifiedAt, long version) {
        return new Identity("E1", "E1", "iam:binding", "hr:employee", "crm:v1:ownerEmployeeCode-contract", version, 1,
                verifiedAt, verifiedAt.plusSeconds(28800));
    }
    private static void authorize(Set<String> roles, Set<String> permissions) {
        TestAuthorizationContext.set(new CallerIdentity("TENANT", USER, TENANT, USER, null, UUID.randomUUID(), 1, 1, 1, roles, permissions));
    }
}

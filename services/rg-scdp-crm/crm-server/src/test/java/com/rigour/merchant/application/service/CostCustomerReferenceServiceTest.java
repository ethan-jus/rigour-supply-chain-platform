package com.rigour.merchant.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.merchant.api.v1.model.CostCustomerView;
import com.rigour.merchant.application.port.out.CostCustomerReferenceStore;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.SupplyAuthorizationClient;

import org.junit.jupiter.api.*;

import java.util.*;

class CostCustomerReferenceServiceTest {
    private static final String EXPORT = "order:logistics-cost:export";
    private final CostCustomerReferenceStore store = mock(CostCustomerReferenceStore.class);
    private final SupplyAuthorizationClient auth = mock(SupplyAuthorizationClient.class);
    private final CostCustomerReferenceService service =
            new CostCustomerReferenceService(store, auth);
    private final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void setup() {
        caller(Set.of(EXPORT));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    private void caller(Set<String> permissions) {
        var user = UUID.randomUUID();
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

    private SupplyAuthorizationView policy(boolean enabled) {
        var all = new Limit("ALL", List.of());
        return new SupplyAuthorizationView(
                "ACTIVE",
                tenant,
                UUID.randomUUID(),
                "E",
                1,
                1,
                1,
                1,
                Set.of(EXPORT),
                EXPORT,
                enabled,
                List.of(new Clause(UUID.randomUUID(), "INVENTORY", "ALL", all, all, all, false)),
                new Limit("SPECIFIED", List.of("WH")),
                all);
    }

    @Test
    void exportResolvesCustomerNamesUsingExportPermissionAndCityScope() {
        when(auth.authorization(any(), eq(EXPORT))).thenReturn(policy(true));
        var rows = List.of(new CostCustomerView("1", "CUS1", "星河门店"));
        when(store.customers(tenant.toString(), "WH", "CUS1", null, 0)).thenReturn(rows);
        assertThat(service.customers("WH", "CUS1", null, 0, EXPORT)).isEqualTo(rows);
        verify(auth).authorization(any(), eq(EXPORT));
        verify(store).customers(tenant.toString(), "WH", "CUS1", null, 0);
    }

    @Test
    void batchContactsUseExactCodesWithinTheSameAuthorizedCityAndTenant() {
        when(auth.authorization(any(), eq(EXPORT))).thenReturn(policy(true));
        var rows = List.of(new CostCustomerView("1", "CUS1", "星河门店", "王先生", "13800000000", "门店地址"));
        when(store.customersByCodes(tenant.toString(), "WH", List.of("CUS1"))).thenReturn(rows);
        assertThat(service.customersByCodes("WH", EXPORT, List.of("CUS1", "CUS1"))).isEqualTo(rows);
        verify(store).customersByCodes(tenant.toString(), "WH", List.of("CUS1"));
        assertThatThrownBy(() -> service.customersByCodes("CQ", EXPORT, List.of("CUS1")))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(store, never()).customersByCodes(any(), eq("CQ"), any());
    }

    @Test
    void readPermissionCannotSubstituteForExportPermission() {
        caller(Set.of("order:logistics-cost:read"));
        assertThatThrownBy(() -> service.customers("WH", "CUS1", null, 0, EXPORT))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store, auth);
    }

    @Test
    void exportCannotReadCustomersOutsideItsAuthorizedCities() {
        when(auth.authorization(any(), eq(EXPORT))).thenReturn(policy(true));
        assertThatThrownBy(() -> service.customers("CQ", "CUS2", null, 0, EXPORT))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }

    @Test
    void disabledExportPolicyCannotExposeCustomerDirectory() {
        when(auth.authorization(any(), eq(EXPORT))).thenReturn(policy(false));
        assertThatThrownBy(() -> service.customers("WH", "CUS1", null, 0, EXPORT))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }
}

package com.rigour.merchant.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.merchant.api.v1.model.*;
import com.rigour.merchant.application.port.out.CrmCustomerQueryStore;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.SupplyAuthorizationClient;

import org.junit.jupiter.api.*;

import java.util.*;

class CostCityReferenceServiceTest {
    final CrmCustomerQueryStore store = mock(CrmCustomerQueryStore.class);
    final SupplyAuthorizationClient authorization = mock(SupplyAuthorizationClient.class);
    final CostCityReferenceService service = new CostCityReferenceService(store, authorization);
    final String action = "order:logistics-cost:write";
    final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void setup() {
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
                        Set.of(action)));
        when(store.customerAreas(eq(tenant), anyInt(), anyInt(), isNull()))
                .thenReturn(
                        new PageView<>(
                                3,
                                0,
                                200,
                                List.of(
                                        city("C1", "ACTIVE"),
                                        city("C2", "ACTIVE"),
                                        city("C3", "DISABLED"))));
    }

    DictionaryView city(String code, String status) {
        return new DictionaryView(UUID.randomUUID(), code, code, status, null, null, null);
    }

    SupplyAuthorizationView policy(boolean enabled, List<String> cities) {
        Limit all = new Limit("ALL", List.of());
        return new SupplyAuthorizationView(
                "ACTIVE",
                tenant,
                UUID.randomUUID(),
                "E",
                1,
                1,
                1,
                1,
                Set.of(action),
                action,
                enabled,
                List.of(new Clause(UUID.randomUUID(), "INVENTORY", "ALL", all, all, all, false)),
                new Limit("SPECIFIED", cities),
                all);
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void filtersByRequestedActionScopeAndActiveCity() {
        when(authorization.authorization(any(), eq(action)))
                .thenReturn(policy(true, List.of("C1", "C3")));
        assertThat(service.cities(action)).extracting(CostCityView::code).containsExactly("C1");
        verify(authorization).authorization(any(), eq(action));
    }

    @Test
    void deniedPolicyDoesNotExposeDirectory() {
        when(authorization.authorization(any(), eq(action)))
                .thenReturn(policy(false, List.of("C1")));
        assertThat(service.cities(action)).isEmpty();
    }

    @Test
    void unrelatedOrUnauthorizedActionIsRejected() {
        assertThatThrownBy(() -> service.cities("crm:customer:write"))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.cities("erp:cost:rental:write"))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store, authorization);
    }
}

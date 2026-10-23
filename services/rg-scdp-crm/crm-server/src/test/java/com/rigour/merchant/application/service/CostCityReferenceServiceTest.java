package com.rigour.merchant.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.HrCostCityReference;
import com.rigour.merchant.api.v1.model.*;
import com.rigour.merchant.application.port.out.CostOperatingCitySource;
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
    final CostOperatingCitySource operatingCities = mock(CostOperatingCitySource.class);
    final CostCityReferenceService service =
            new CostCityReferenceService(store, authorization, operatingCities);
    final String action = "order:logistics-cost:write";
    final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void setup() {
        when(operatingCities.cities(anyString()))
                .thenReturn(
                        List.of(
                                new HrCostCityReference("D1", "C1", "1", List.of("0", "1")),
                                new HrCostCityReference("D2", "C2", "2", List.of("0", "2"))));
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

    @Test
    void nonOperatingCustomerRegionsNeverBecomeCostCities() {
        when(authorization.authorization(any(), eq(action)))
                .thenReturn(policy(true, List.of("C1", "C2", "P1")));
        when(store.customerAreas(eq(tenant), anyInt(), anyInt(), isNull()))
                .thenReturn(
                        new PageView<>(
                                3,
                                0,
                                200,
                                List.of(
                                        city("C1", "ACTIVE"),
                                        city("P1", "ACTIVE"),
                                        city("GROUP", "ACTIVE"))));
        assertThat(service.cities(action)).extracting(CostCityView::code).containsExactly("C1");
    }

    @Test
    void ambiguousAreaOrDepartmentNameIsNotSelectable() {
        when(authorization.authorization(any(), eq(action)))
                .thenReturn(policy(true, List.of("C1", "DUP", "C2")));
        when(store.customerAreas(eq(tenant), anyInt(), anyInt(), isNull()))
                .thenReturn(
                        new PageView<>(
                                3,
                                0,
                                200,
                                List.of(
                                        city("C1", "ACTIVE"),
                                        new DictionaryView(
                                                UUID.randomUUID(),
                                                "DUP",
                                                "C1",
                                                "ACTIVE",
                                                null,
                                                null,
                                                null),
                                        city("C2", "ACTIVE"))));
        assertThat(service.cities(action)).extracting(CostCityView::code).containsExactly("C2");
        when(operatingCities.cities(action))
                .thenReturn(
                        List.of(
                                new HrCostCityReference("D1", "C2", "1", List.of("1")),
                                new HrCostCityReference("D2", "C2", "2", List.of("2"))));
        assertThat(service.cities(action)).isEmpty();
    }

    @Test
    void headquartersRequiresAllCitiesOrItsExplicitScope() {
        when(authorization.authorization(any(), eq(action)))
                .thenReturn(policy(true, List.of("C1")));
        assertThat(service.cities(action)).extracting(CostCityView::code).containsExactly("C1");
        when(authorization.authorization(any(), eq(action)))
                .thenReturn(policy(true, List.of(HrCostCityReference.HEADQUARTERS_CODE)));
        assertThat(service.cities(action))
                .containsExactly(new CostCityView(HrCostCityReference.HEADQUARTERS_CODE, "总部"));
        var limited = policy(true, List.of());
        var all = new Limit("ALL", List.of());
        when(authorization.authorization(any(), eq(action)))
                .thenReturn(
                        new SupplyAuthorizationView(
                                limited.mode(),
                                limited.tenantId(),
                                limited.userId(),
                                limited.employeeCode(),
                                limited.applicationVersion(),
                                limited.memberVersion(),
                                limited.employeeRevision(),
                                limited.organizationVersion(),
                                limited.permissions(),
                                limited.action(),
                                true,
                                limited.clauses(),
                                all,
                                all));
        assertThat(service.cities(action))
                .extracting(CostCityView::code)
                .containsExactly("C1", "C2", HrCostCityReference.HEADQUARTERS_CODE);
    }

    @Test
    void missingDirectoryNeverFallsBackToAllCustomerAreas() {
        when(authorization.authorization(any(), eq(action)))
                .thenReturn(policy(true, List.of("C1")));
        when(operatingCities.cities(action)).thenReturn(List.of());
        assertThatThrownBy(() -> service.cities(action)).hasMessageContaining("经营城市目录未完整配置");
        verifyNoInteractions(store);
    }

    @Test
    void headquartersReservedCodeCollisionStopsDirectory() {
        when(authorization.authorization(any(), eq(action)))
                .thenReturn(policy(true, List.of("C1")));
        when(store.customerAreas(eq(tenant), anyInt(), anyInt(), isNull()))
                .thenReturn(
                        new PageView<>(
                                1,
                                0,
                                200,
                                List.of(city(HrCostCityReference.HEADQUARTERS_CODE, "ACTIVE"))));
        assertThatThrownBy(() -> service.cities(action)).hasMessageContaining("经营城市目录未完整配置");
    }

    @Test
    void headquartersIsNeverAnAssignableCityManagerCity() {
        String rule = "hr:payroll-rule:write";
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
                        Set.of(rule)));
        var all = new Limit("ALL", List.of());
        when(authorization.authorization(any(), eq(rule)))
                .thenReturn(
                        new SupplyAuthorizationView(
                                "ACTIVE",
                                tenant,
                                user,
                                "E",
                                1,
                                1,
                                1,
                                1,
                                Set.of(rule),
                                rule,
                                true,
                                List.of(
                                        new Clause(
                                                UUID.randomUUID(),
                                                "EMPLOYEE",
                                                "ALL",
                                                all,
                                                all,
                                                all,
                                                false)),
                                all,
                                all));
        assertThat(service.cities(rule)).extracting(CostCityView::code).containsExactly("C1", "C2");
    }
}

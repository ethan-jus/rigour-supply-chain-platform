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
    void warehouseEditorUsesTheSameScopedCityAndHeadquartersDirectory() {
        String warehouseAction = "erp:warehouse:update";
        var user = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity(
                "TENANT", user, tenant, user, null, UUID.randomUUID(),
                0, 0, 0, Set.of(), Set.of(warehouseAction)));
        when(authorization.authorization(any(), eq(warehouseAction)))
                .thenReturn(policy(true, List.of("C1", HrCostCityReference.HEADQUARTERS_CODE)));

        assertThat(service.cities(warehouseAction)).extracting(CostCityView::code)
                .containsExactly("C1", HrCostCityReference.HEADQUARTERS_CODE);
        assertThatThrownBy(() -> service.cities(null))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(operatingCities).cities(warehouseAction);
    }

    @Test
    void checksDistinguishDirtyConfigurationFromValidButUnauthorizedCity() {
        when(authorization.authorization(any(), eq(action)))
                .thenReturn(policy(true, List.of("C1")));
        var rows =
                service.checks(
                        action,
                        List.of(
                                "C1",
                                "C2",
                                "C3",
                                "DHB_AREA_1027",
                                HrCostCityReference.HEADQUARTERS_CODE));
        assertThat(rows)
                .containsExactly(
                        new CostCityCheckView("C1", true, true),
                        new CostCityCheckView("C2", true, false),
                        new CostCityCheckView(
                                "C3", false, false, "业务归属地区“C3”（C3）已停用，请在仓库信息中重新选择启用的经营城市或总部"),
                        new CostCityCheckView(
                                "DHB_AREA_1027",
                                false,
                                false,
                                "业务归属地区编码（DHB_AREA_1027）不存在，请在仓库信息中重新选择实际经营城市或总部"),
                        new CostCityCheckView(HrCostCityReference.HEADQUARTERS_CODE, true, false));
    }

    @Test
    void dirtyCustomerGroupNamesTheExactRegionAndReason() {
        when(authorization.authorization(any(), eq(action)))
                .thenReturn(policy(true, List.of("C1")));
        when(store.customerAreas(eq(tenant), anyInt(), anyInt(), isNull()))
                .thenReturn(
                        new PageView<>(
                                1,
                                0,
                                200,
                                List.of(
                                        new DictionaryView(
                                                UUID.randomUUID(),
                                                "DHB_AREA_1027",
                                                "散客",
                                                "ACTIVE",
                                                null,
                                                null,
                                                null))));
        var check = service.checks(action, List.of("DHB_AREA_1027")).getFirst();
        assertThat(check.configured()).isFalse();
        assertThat(check.allowed()).isFalse();
        assertThat(check.issue()).contains("散客", "DHB_AREA_1027", "不是经营城市", "仓库信息");
    }

    @Test
    void checksDoNotExposeConfigurationWithoutFunctionPermission() {
        when(authorization.authorization(any(), eq(action)))
                .thenReturn(policy(false, List.of("C1")));
        assertThatThrownBy(() -> service.checks(action, List.of("C1")))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store, operatingCities);
        assertThatThrownBy(() -> service.checks("crm:customer:write", List.of("C1")))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void invalidOrAmbiguousCityConfigurationNeverBecomesAllowed() {
        when(authorization.authorization(any(), eq(action)))
                .thenReturn(policy(true, List.of("C1", "C2")));
        when(operatingCities.cities(action))
                .thenReturn(
                        List.of(
                                new HrCostCityReference("D1", "C1", "1", List.of("1")),
                                new HrCostCityReference("D2", "C1", "2", List.of("2"))));
        assertThat(service.checks(action, List.of("C1")))
                .containsExactly(
                        new CostCityCheckView(
                                "C1",
                                false,
                                false,
                                "业务归属地区“C1”（C1）对应多个同名经营城市部门，无法唯一匹配，请先在部门管理中核对同名经营城市"));
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

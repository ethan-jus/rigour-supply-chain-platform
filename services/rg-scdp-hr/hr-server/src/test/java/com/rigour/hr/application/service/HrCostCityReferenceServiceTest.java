package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrOrganizationStore;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.SupplyAuthorizationClient;

import org.junit.jupiter.api.*;

import java.util.*;

class HrCostCityReferenceServiceTest {
    final HrOrganizationStore store = mock(HrOrganizationStore.class);
    final SupplyAuthorizationClient authorization = mock(SupplyAuthorizationClient.class);
    final HrCostCityReferenceService service = new HrCostCityReferenceService(store, authorization);
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    final String action = "hr:expense:write";

    @BeforeEach
    void setup() {
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        1,
                        1,
                        1,
                        Set.of(),
                        Set.of(action)));
        var all = new Limit("ALL", List.of());
        when(authorization.authorization(any(), eq(action)))
                .thenReturn(
                        new SupplyAuthorizationView(
                                "ACTIVE",
                                tenant,
                                user,
                                "EMP1",
                                1,
                                1,
                                1,
                                1,
                                Set.of(action),
                                action,
                                true,
                                List.of(
                                        new Clause(
                                                UUID.randomUUID(),
                                                "EMPLOYEE",
                                                "ALL",
                                                all,
                                                all,
                                                all,
                                                true)),
                                all,
                                all));
        when(store.departments(tenant.toString()))
                .thenReturn(
                        List.of(
                                dept(1, null, "ROOT", "公司", "ACTIVE"),
                                        dept(2, 1L, "SALES", "销售部", "ACTIVE"),
                                dept(3, 2L, "WH", "武汉市", "ACTIVE"),
                                        dept(4, 3L, "WHT", "武汉业务组", "ACTIVE"),
                                dept(5, 2L, "CD", "成都市", "INACTIVE"),
                                        dept(6, 1L, "HR", "人事部", "ACTIVE")));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void returnsOnlyActiveOperatingDepartmentReferencesWithoutDepartmentPermission() {
        assertThat(AuthorizationContext.hasPermission("hr:department:read")).isFalse();
        var result = service.cities(action);
        assertThat(result).extracting(HrCostCityReference::code).containsExactly("WH", "WHT");
        assertThat(result.getFirst().departmentPath()).containsExactly("1", "2", "3");
        verify(store).departments(tenant.toString());
    }

    @Test
    void unrelatedOrMissingActionCannotReadOrganization() {
        assertThatThrownBy(() -> service.cities("hr:department:read"))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.cities(null))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store, authorization);
    }

    @Test
    void revokedPolicyDoesNotReadDirectory() {
        var policy = authorization.authorization(AuthorizationContext.requireCurrent(), action);
        when(authorization.authorization(any(), eq(action)))
                .thenReturn(
                        new SupplyAuthorizationView(
                                "ACTIVE",
                                tenant,
                                user,
                                "EMP1",
                                1,
                                1,
                                1,
                                1,
                                Set.of(action),
                                action,
                                false,
                                policy.clauses(),
                                policy.regionLimit(),
                                policy.warehouseLimit()));
        assertThatThrownBy(() -> service.cities(action))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }

    @Test
    void corruptOrganizationNeverLoopsOrBroadensResult() {
        when(store.departments(tenant.toString()))
                .thenReturn(
                        List.of(
                                dept(1, 2L, "A", "城市A", "ACTIVE"),
                                dept(2, 1L, "B", "销售部", "ACTIVE")));
        assertThatThrownBy(() -> service.cities(action)).hasMessageContaining("循环");
    }

    private HrDepartmentView dept(long id, Long parent, String code, String name, String status) {
        return new HrDepartmentView(
                id, parent, code, name, 0, status, 1, null, null, null, null, null, null, null,
                null, null, null);
    }
}

package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.SupplyAuthorizationClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

class HrCostEmployeeScopeTest {
    final SupplyAuthorizationClient client = mock(SupplyAuthorizationClient.class);
    final HrDataScope scope = new HrDataScope(client, mock(JdbcTemplate.class));
    final Limit all = new Limit("ALL", List.of()), none = new Limit("NONE", List.of());
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();

    void policy(String action, String object, String mode, Limit warehouses, String employee) {
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
        when(client.authorization(any(), eq(action)))
                .thenReturn(
                        new SupplyAuthorizationView(
                                "ACTIVE",
                                tenant,
                                user,
                                employee,
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
                                                object,
                                                mode,
                                                none,
                                                none,
                                                warehouses,
                                                false)),
                                all,
                                warehouses));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "erp:cost:purchase:read",
                "erp:cost:loss:write",
                "erp:cost:rental:confirm",
                "order:logistics-cost:read"
            })
    void unrestrictedInventoryCostCanReferenceEmployeesWithoutEmployeeObjectClause(String action) {
        policy(action, "INVENTORY", "ALL", all, "E1");
        assertThat(scope.costEmployeePredicate(action, "e.").sql()).isEqualTo("1=1");
    }

    @Test
    void limitedWarehouseCostCannotReadOtherEmployeeNames() {
        String action = "order:logistics-cost:write";
        policy(action, "INVENTORY", "WAREHOUSE", new Limit("SPECIFIED", List.of("7")), "E1");
        var predicate = scope.costEmployeePredicate(action, "e.");
        assertThat(predicate.sql()).isEqualTo("e.employee_code=?");
        assertThat(predicate.args()).containsExactly("E1");
    }

    @Test
    void noEmployeeBindingOrWrongObjectCannotOpenDirectory() {
        String action = "erp:cost:purchase:read";
        policy(action, "INVENTORY", "WAREHOUSE", new Limit("SPECIFIED", List.of("7")), null);
        assertThat(scope.costEmployeePredicate(action, "e.").sql()).isEqualTo("1=0");
        policy(action, "EMPLOYEE", "ALL", all, "E1");
        assertThat(scope.costEmployeePredicate(action, "e.").sql()).isEqualTo("1=0");
    }

    @Test
    void memberCityLimitDoesNotExpandEmployeeDirectory() {
        String action = "erp:cost:purchase:read";
        policy(action, "INVENTORY", "ALL", all, "E1");
        var original = client.authorization(AuthorizationContext.requireCurrent(), action);
        when(client.authorization(any(), eq(action)))
                .thenReturn(
                        new SupplyAuthorizationView(
                                original.mode(),
                                tenant,
                                user,
                                "E1",
                                1,
                                1,
                                1,
                                1,
                                Set.of(action),
                                action,
                                true,
                                original.clauses(),
                                new Limit("SPECIFIED", List.of("C1")),
                                all));
        assertThat(scope.costEmployeePredicate(action, "e.").sql()).isEqualTo("e.employee_code=?");
    }

    @Test
    void marketingKeepsEmployeeScopeInsteadOfUsingInventoryAccess() {
        String action = "erp:cost:marketing:read";
        policy(action, "EMPLOYEE", "SELF", all, "E1");
        var predicate = scope.costEmployeePredicate(action, "e.");
        assertThat(predicate.sql()).contains("e.employee_code=?");
        assertThat(predicate.args()).containsExactly("E1");
    }
}

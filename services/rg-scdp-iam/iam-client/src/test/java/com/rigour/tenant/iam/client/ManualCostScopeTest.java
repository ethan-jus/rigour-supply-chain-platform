package com.rigour.tenant.iam.client;

import static org.assertj.core.api.Assertions.*;

import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class ManualCostScopeTest {
    final Limit all = new Limit("ALL", List.of()), none = new Limit("NONE", List.of());

    SupplyAuthorizationView policy(String scope, Limit warehouses, Limit regions) {
        return new SupplyAuthorizationView(
                "ACTIVE",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E",
                1,
                1,
                1,
                1,
                Set.of("cost:read"),
                "cost:read",
                true,
                List.of(
                        new Clause(
                                UUID.randomUUID(),
                                "INVENTORY",
                                scope,
                                none,
                                none,
                                warehouses,
                                false)),
                regions,
                warehouses);
    }

    @Test
    void warehouseLimitedUserSeesOnlyOwnManualEntriesAndAuthorizedCity() {
        var p =
                policy(
                        "WAREHOUSE",
                        new Limit("SPECIFIED", List.of("7")),
                        new Limit("SPECIFIED", List.of("C1")));
        var access = ManualCostScope.access(p, "INVENTORY");
        assertThat(access.allCreators()).isFalse();
        assertThat(access.cities()).containsExactly("C1");
        assertThatThrownBy(() -> ManualCostScope.require(p, "INVENTORY", "C2", "cost:write"))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void memberWarehouseLimitStillPreventsAllManualVisibility() {
        assertThat(
                        ManualCostScope.access(
                                        policy("ALL", new Limit("SPECIFIED", List.of("7")), all),
                                        "INVENTORY")
                                .allCreators())
                .isFalse();
    }

    @Test
    void noneRoleCannotCreateManualRecords() {
        assertThatThrownBy(
                        () ->
                                ManualCostScope.require(
                                        policy("NONE", all, all), "INVENTORY", "C1", "cost:write"))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void fullyUnrestrictedRoleCanSeeAllCreators() {
        assertThat(ManualCostScope.access(policy("ALL", all, all), "INVENTORY").allCreators())
                .isTrue();
    }
}

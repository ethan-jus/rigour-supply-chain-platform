package com.rigour.tenant.iam.client;

import static org.assertj.core.api.Assertions.*;

import com.rigour.shared.context.CallerIdentity;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;

import org.junit.jupiter.api.Test;

import java.util.*;

class SupplyAuthorizationContextTest {
    @Test
    void staleApplicationVersionIsRejectedAndRequestContextIsCleared() {
        UUID user = UUID.randomUUID();
        var caller =
                new CallerIdentity(
                        "TENANT",
                        user,
                        UUID.randomUUID(),
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of());
        var initial = view(caller, "supply:application:access", 10);
        SupplyAuthorizationClient client = (identity, action) -> view(identity, action, 11);
        try (var context = SupplyAuthorizationContext.open(client, caller, initial)) {
            assertThat(context.active()).isTrue();
            assertThatThrownBy(() -> SupplyAuthorizationContext.requireAction("order:read"))
                    .hasMessageContaining("授权配置已变化");
        }
        assertThat(SupplyAuthorizationContext.current()).isEmpty();
        assertThatThrownBy(() -> SupplyAuthorizationContext.requireAction("order:read"))
                .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
    }

    @Test
    void preparingModeCannotRestoreLegacyAuthorization() {
        UUID user = UUID.randomUUID();
        var caller =
                new CallerIdentity(
                        "TENANT",
                        user,
                        UUID.randomUUID(),
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of("TENANT_SUPER_ADMIN"),
                        Set.of("*:*:*"));
        var active = view(caller, "order:read", 1);
        var preparing =
                new SupplyAuthorizationView(
                        "PREPARING",
                        active.tenantId(),
                        active.userId(),
                        active.employeeCode(),
                        1,
                        1,
                        1,
                        1,
                        active.permissions(),
                        active.action(),
                        true,
                        active.clauses(),
                        active.regionLimit(),
                        active.warehouseLimit());
        assertThatThrownBy(
                        () ->
                                SupplyAuthorizationContext.open(
                                        (c, action) -> preparing, caller, preparing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(SupplyAuthorizationContext.current()).isEmpty();
    }

    @Test
    void anAllowedReadCannotGrantAnUnconfiguredButton() {
        UUID user = UUID.randomUUID();
        var caller =
                new CallerIdentity(
                        "TENANT",
                        user,
                        UUID.randomUUID(),
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("order:read"));
        var initial = view(caller, "order:read", 1);
        try (var context =
                SupplyAuthorizationContext.open(
                        (c, action) ->
                                new SupplyAuthorizationView(
                                        "ACTIVE",
                                        c.tenantId(),
                                        c.userId(),
                                        "E1",
                                        1,
                                        1,
                                        1,
                                        1,
                                        Set.of("order:read"),
                                        action,
                                        false,
                                        List.of(),
                                        initial.regionLimit(),
                                        initial.warehouseLimit()),
                        caller,
                        initial)) {
            assertThatThrownBy(() -> SupplyAuthorizationContext.requireAction("order:delete"))
                    .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
        }
    }

    private static SupplyAuthorizationView view(CallerIdentity c, String action, long version) {
        return new SupplyAuthorizationView(
                "ACTIVE",
                c.tenantId(),
                c.userId(),
                "EMP-1",
                version,
                1,
                1,
                1,
                Set.of("order:read"),
                action,
                true,
                List.of(),
                new SupplyAuthorizationView.Limit("ALL", List.of()),
                new SupplyAuthorizationView.Limit("ALL", List.of()));
    }
}

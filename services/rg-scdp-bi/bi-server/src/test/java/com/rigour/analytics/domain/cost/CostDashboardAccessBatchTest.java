package com.rigour.analytics.domain.cost;

import static org.assertj.core.api.Assertions.*;

import com.rigour.analytics.application.service.CostDashboardAccess;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.client.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.atomic.*;

class CostDashboardAccessBatchTest {
    @Test
    void dashboardAndEachSourceUseOneOnlineBatchAndNextRequestSeesRevokedPayroll() {
        UUID user = UUID.randomUUID();
        var caller =
                new CallerIdentity(
                        "TENANT",
                        user,
                        UUID.randomUUID(),
                        user,
                        null,
                        UUID.randomUUID(),
                        1,
                        2,
                        3,
                        Set.of(),
                        Set.of("*:*:*"));
        Set<String> initialPermissions = new HashSet<>(CostDashboardAccess.ACTIONS.values());
        initialPermissions.add(CostDashboardAccess.READ);
        initialPermissions.add(CostDashboardAccess.EXPORT);
        var permissions = new AtomicReference<Set<String>>(Set.copyOf(initialPermissions));
        var batches = new AtomicInteger();
        var requested = new AtomicReference<List<String>>();
        SupplyAuthorizationClient client =
                new SupplyAuthorizationClient() {
                    public boolean supportsBatchAuthorization() {
                        return true;
                    }

                    public SupplyAuthorizationView authorization(
                            CallerIdentity actor, String action) {
                        throw new AssertionError("成本批读不得逐动作再请求IAM");
                    }

                    public List<SupplyAuthorizationView> authorizations(
                            CallerIdentity actor, List<String> actions) {
                        assertThat(actor).isEqualTo(caller);
                        batches.incrementAndGet();
                        requested.set(actions);
                        return actions.stream()
                                .map(action -> view(caller, action, permissions.get()))
                                .toList();
                    }
                };
        TestAuthorizationContext.set(caller);
        var access = new CostDashboardAccess(JsonMapper.builder().build());
        try {
            try (var ignored =
                    SupplyAuthorizationContext.open(
                            client,
                            caller,
                            view(caller, "supply:application:access", permissions.get()))) {
                assertThat(access.current().categories()).contains("labor", "expense", "freight");
                assertThat(requested.get())
                        .contains(
                                CostDashboardAccess.READ,
                                CostDashboardAccess.EXPORT,
                                "hr:payroll:read",
                                "erp:cost:purchase:read");
                assertThat(batches).hasValue(1);
            }
            var revoked = new HashSet<>(permissions.get());
            revoked.remove("hr:payroll:read");
            permissions.set(Set.copyOf(revoked));
            try (var ignored =
                    SupplyAuthorizationContext.open(
                            client,
                            caller,
                            view(caller, "supply:application:access", permissions.get()))) {
                var result = access.current();
                assertThat(result.categories())
                        .doesNotContain("labor")
                        .contains("expense", "freight");
                assertThat(result.exportAllowed()).isTrue();
                assertThat(batches).hasValue(2);
            }
        } finally {
            access.close();
            TestAuthorizationContext.clear();
        }
    }

    private SupplyAuthorizationView view(
            CallerIdentity actor, String action, Set<String> permissions) {
        var all = new SupplyAuthorizationView.Limit("ALL", List.of());
        String object =
                action.startsWith("analytics:")
                        ? "ANALYTICS"
                        : action.startsWith("hr:") || action.contains("marketing")
                                ? "EMPLOYEE"
                                : "INVENTORY";
        return new SupplyAuthorizationView(
                "ACTIVE",
                actor.tenantId(),
                actor.userId(),
                "E1",
                1,
                2,
                3,
                4,
                permissions,
                action,
                permissions.contains(action),
                List.of(
                        new SupplyAuthorizationView.Clause(
                                UUID.nameUUIDFromBytes(
                                        object.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                object,
                                "ALL",
                                all,
                                all,
                                all,
                                true)),
                all,
                all);
    }
}

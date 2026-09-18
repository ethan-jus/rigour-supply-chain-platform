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
    void preparingObservationCannotChangeEnforcementAndFailuresAreVisibleButDoNotGrant() {
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
                        Set.of("order:write"));
        var p =
                new SupplyAuthorizationView(
                        "PREPARING",
                        caller.tenantId(),
                        caller.userId(),
                        null,
                        1,
                        0,
                        0,
                        0,
                        Set.of(),
                        "supply:application:access",
                        false,
                        List.of(),
                        new SupplyAuthorizationView.Limit("NONE", List.of()),
                        new SupplyAuthorizationView.Limit("NONE", List.of()));
        var count = new java.util.concurrent.atomic.AtomicInteger();
        SupplyAuthorizationClient client =
                new SupplyAuthorizationClient() {
                    public SupplyAuthorizationView authorization(CallerIdentity c, String a) {
                        throw new AssertionError("不应切换到新业务决定");
                    }

                    public void observe(CallerIdentity c, String a, String old) {
                        count.incrementAndGet();
                        throw new IllegalStateException("offline");
                    }
                };
        try (var ctx = SupplyAuthorizationContext.open(client, caller, p)) {
            SupplyAuthorizationContext.observe("order:create", "order:write");
            SupplyAuthorizationContext.observe("order:create", "order:write");
            assertThat(ctx.active()).isFalse();
            assertThat(SupplyAuthorizationContext.requireAction("order:create")).isSameAs(p);
            assertThat(count.get()).isEqualTo(1);
        }
        assertThat(SupplyAuthorizationContext.current()).isEmpty();
    }

    @Test
    void dataSamplingIsBoundedAndDoesNotChangeThePreparingPolicy() {
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
        var p =
                new SupplyAuthorizationView(
                        "PREPARING",
                        caller.tenantId(),
                        user,
                        "EMP-1",
                        5,
                        2,
                        3,
                        4,
                        Set.of("order:read"),
                        "order:read",
                        true,
                        List.of(),
                        new SupplyAuthorizationView.Limit("ALL", List.of()),
                        new SupplyAuthorizationView.Limit("ALL", List.of()));
        var observations =
                new ArrayList<com.rigour.tenant.iam.api.v1.model.SupplyDataObservation>();
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        SupplyAuthorizationClient client =
                new SupplyAuthorizationClient() {
                    public SupplyAuthorizationView authorization(CallerIdentity a, String action) {
                        throw new AssertionError("准备期不可换业务决定");
                    }

                    public SupplyAuthorizationView candidate(CallerIdentity a, String action) {
                        requests.incrementAndGet();
                        return p;
                    }

                    public void observeData(
                            CallerIdentity a,
                            com.rigour.tenant.iam.api.v1.model.SupplyDataObservation r) {
                        observations.add(r);
                    }
                };
        try (var ctx = SupplyAuthorizationContext.open(client, caller, p)) {
            for (int i = 0; i < 50; i++)
                SupplyAuthorizationContext.compare(
                        "order:read", "ORDER", "record-" + i, true, ignored -> false);
            assertThat(observations).hasSize(20);
            assertThat(requests.get()).isEqualTo(1);
            assertThat(observations).allMatch(x -> x.legacyAllowed() && !x.proposedAllowed());
            assertThat(ctx.active()).isFalse();
            assertThat(SupplyAuthorizationContext.requireAction("order:read")).isSameAs(p);
        }
    }

    @Test
    void absenceOfPreparingContextDoesNotExecuteComparisonQueries() {
        assertThat(SupplyAuthorizationContext.current()).isEmpty();
        SupplyAuthorizationContext.compare(
                "order:read",
                "ORDER",
                "1",
                () -> {
                    throw new AssertionError("不应额外查询旧数据");
                },
                ignored -> {
                    throw new AssertionError("不应查询候选数据");
                });
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

package com.rigour.tenant.iam.client;

import static org.assertj.core.api.Assertions.*;

import com.rigour.shared.context.CallerIdentity;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class SupplyAuthorizationContextTest {
    private CallerIdentity caller() {
        UUID user = UUID.randomUUID();
        return new CallerIdentity(
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
    }

    @Test
    void prefetchOverlapsReadsDeduplicatesActionsAndDoesNotShareThreadContext() {
        var caller = caller();
        var initial = view(caller, "application:read", 1);
        var calls = new ConcurrentHashMap<String, AtomicInteger>();
        var together = new CountDownLatch(2);
        SupplyAuthorizationClient client =
                (identity, action) -> {
                    assertThat(SupplyAuthorizationContext.current()).isEmpty();
                    assertThat(identity).isEqualTo(caller);
                    calls.computeIfAbsent(action, key -> new AtomicInteger()).incrementAndGet();
                    together.countDown();
                    try {
                        assertThat(together.await(3, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException ex) {
                        throw new IllegalStateException(ex);
                    }
                    return view(identity, action, 1);
                };
        try (var executor = Executors.newFixedThreadPool(2);
                var context = SupplyAuthorizationContext.open(client, caller, initial)) {
            context.prefetchActions(
                    List.of("a:read", "b:read", "a:read"), executor, Duration.ofSeconds(5));
            assertThat(SupplyAuthorizationContext.requireAction("a:read").action())
                    .isEqualTo("a:read");
            assertThat(SupplyAuthorizationContext.requireAction("b:read").action())
                    .isEqualTo("b:read");
            context.prefetchActions(List.of("a:read"), executor, Duration.ofSeconds(1));
            assertThat(calls).hasSize(2);
            assertThat(calls.values()).allSatisfy(count -> assertThat(count.get()).isOne());
        }
        assertThat(SupplyAuthorizationContext.current()).isEmpty();
    }

    @Test
    void deniedActionStaysDeniedAndNoPoliciesAreReusedByAnotherRequest() {
        var caller = caller();
        var initial = view(caller, "application:read", 1);
        var calls = new AtomicInteger();
        SupplyAuthorizationClient client =
                (identity, action) -> {
                    calls.incrementAndGet();
                    var v = view(identity, action, 1);
                    return new SupplyAuthorizationView(
                            v.mode(),
                            v.tenantId(),
                            v.userId(),
                            v.employeeCode(),
                            1,
                            1,
                            1,
                            1,
                            v.permissions(),
                            action,
                            false,
                            v.clauses(),
                            v.regionLimit(),
                            v.warehouseLimit());
                };
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int request = 0; request < 2; request++) {
                try (var context = SupplyAuthorizationContext.open(client, caller, initial)) {
                    context.prefetchActions(
                            List.of("hr:payroll:read"), executor, Duration.ofSeconds(2));
                    assertThatThrownBy(
                                    () ->
                                            SupplyAuthorizationContext.requireAction(
                                                    "hr:payroll:read"))
                            .isInstanceOf(
                                    com.rigour.shared.context.AuthorizationDeniedException.class);
                }
            }
            assertThat(calls.get()).isEqualTo(2);
        }
    }

    @Test
    void changedVersionOrTimeoutDoesNotPublishPartialPolicies() {
        var caller = caller();
        var initial = view(caller, "application:read", 1);
        var calls = new AtomicInteger();
        SupplyAuthorizationClient client =
                (identity, action) -> {
                    calls.incrementAndGet();
                    return view(identity, action, action.equals("b:read") ? 2 : 1);
                };
        try (var executor = Executors.newFixedThreadPool(2);
                var context = SupplyAuthorizationContext.open(client, caller, initial)) {
            assertThatThrownBy(
                            () ->
                                    context.prefetchActions(
                                            List.of("a:read", "b:read"),
                                            executor,
                                            Duration.ofSeconds(2)))
                    .hasMessageContaining("权限配置已更新");
            int before = calls.get();
            SupplyAuthorizationContext.requireAction("a:read");
            assertThat(calls.get()).isEqualTo(before + 1);
        }
        var pending = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(1);
                var context =
                        SupplyAuthorizationContext.open(
                                (identity, action) -> {
                                    try {
                                        pending.await();
                                    } catch (InterruptedException ex) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(ex);
                                    }
                                    return view(identity, action, 1);
                                },
                                caller,
                                initial)) {
            assertThatThrownBy(
                            () ->
                                    context.prefetchActions(
                                            List.of("a:read"), executor, Duration.ofMillis(20)))
                    .hasMessageContaining("授权查询繁忙");
        } finally {
            pending.countDown();
        }
    }

    @Test
    void mismatchedUserCannotSupplyAnotherUsersPolicy() {
        var caller = caller();
        try (var executor = Executors.newFixedThreadPool(1);
                var context =
                        SupplyAuthorizationContext.open(
                                (identity, action) -> view(caller(), action, 1),
                                caller,
                                view(caller, "application:read", 1))) {
            assertThatThrownBy(
                            () ->
                                    context.prefetchActions(
                                            List.of("a:read"), executor, Duration.ofSeconds(2)))
                    .hasMessageContaining("权限配置已更新");
        }
    }

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
                    .hasMessageContaining("权限配置已更新");
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
                                        initial.employeeCode(),
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

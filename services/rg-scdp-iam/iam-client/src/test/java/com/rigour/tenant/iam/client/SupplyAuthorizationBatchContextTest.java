package com.rigour.tenant.iam.client;

import static org.assertj.core.api.Assertions.*;

import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class SupplyAuthorizationBatchContextTest {
    static final String A = "order:read", B = "hr:expense:read";
    final UUID user = UUID.randomUUID();
    final CallerIdentity caller =
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
                    Set.of());

    SupplyAuthorizationView view(String action, Set<String> permissions) {
        return new SupplyAuthorizationView(
                "ACTIVE",
                caller.tenantId(),
                user,
                "E1",
                5,
                6,
                7,
                8,
                permissions,
                action,
                permissions.contains(action),
                List.of(),
                new SupplyAuthorizationView.Limit("ALL", List.of()),
                new SupplyAuthorizationView.Limit("ALL", List.of()));
    }

    class Client implements SupplyAuthorizationClient {
        int batches, singles;
        Set<String> permissions = Set.of(A);
        java.util.function.UnaryOperator<List<SupplyAuthorizationView>> corrupt = v -> v;

        public boolean supportsBatchAuthorization() {
            return true;
        }

        public SupplyAuthorizationView authorization(CallerIdentity c, String action) {
            singles++;
            return view(action, permissions);
        }

        public List<SupplyAuthorizationView> authorizations(
                CallerIdentity c, List<String> actions) {
            assertThat(c).isEqualTo(caller);
            batches++;
            return corrupt.apply(
                    actions.stream().map(action -> view(action, permissions)).toList());
        }
    }

    @Test
    void oneBatchKeepsDeniedActionsDeniedAndNeverReusesAnotherRequest() {
        var client = new Client();
        try (var executor = Executors.newFixedThreadPool(8)) {
            try (var context =
                    SupplyAuthorizationContext.open(
                            client,
                            caller,
                            view("supply:application:access", client.permissions))) {
                context.prefetchActions(List.of(A, B, A), executor, Duration.ofSeconds(2));
                context.prefetchActions(List.of(A), executor, Duration.ofSeconds(2));
                assertThat(SupplyAuthorizationContext.requireAction(A).functionAllowed()).isTrue();
                assertThatThrownBy(() -> SupplyAuthorizationContext.requireAction(B))
                        .isInstanceOf(AuthorizationDeniedException.class);
            }
            client.permissions = Set.of();
            try (var context =
                    SupplyAuthorizationContext.open(
                            client,
                            caller,
                            view("supply:application:access", client.permissions))) {
                context.prefetchActions(List.of(A), executor, Duration.ofSeconds(2));
                assertThatThrownBy(() -> SupplyAuthorizationContext.requireAction(A))
                        .isInstanceOf(AuthorizationDeniedException.class);
            }
        }
        assertThat(client.batches).isEqualTo(2);
        assertThat(client.singles).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "missing",
                "duplicate",
                "tenant",
                "actor",
                "action",
                "mode",
                "application",
                "member",
                "employee",
                "organization",
                "flag"
            })
    void malformedBatchFailsClosedWithoutPublishingEvenOnePolicy(String defect) {
        var client = new Client();
        client.permissions = Set.of(A, B);
        client.corrupt =
                views ->
                        switch (defect) {
                            case "null" -> null;
                            case "missing" -> views.subList(0, 1);
                            case "duplicate" -> List.of(views.getFirst(), views.getFirst());
                            default -> List.of(views.getFirst(), corrupt(views.get(1), defect));
                        };
        try (var executor = Executors.newFixedThreadPool(8);
                var context =
                        SupplyAuthorizationContext.open(
                                client,
                                caller,
                                view("supply:application:access", client.permissions))) {
            assertThatThrownBy(
                            () ->
                                    context.prefetchActions(
                                            List.of(A, B), executor, Duration.ofSeconds(2)))
                    .isInstanceOf(IllegalStateException.class);
            SupplyAuthorizationContext.requireAction(A);
            assertThat(client.singles).isOne();
        }
    }

    SupplyAuthorizationView corrupt(SupplyAuthorizationView v, String field) {
        return new SupplyAuthorizationView(
                field.equals("mode") ? "INACTIVE" : v.mode(),
                field.equals("tenant") ? UUID.randomUUID() : v.tenantId(),
                field.equals("actor") ? UUID.randomUUID() : v.userId(),
                v.employeeCode(),
                v.applicationVersion() + (field.equals("application") ? 1 : 0),
                v.memberVersion() + (field.equals("member") ? 1 : 0),
                v.employeeRevision() + (field.equals("employee") ? 1 : 0),
                v.organizationVersion() + (field.equals("organization") ? 1 : 0),
                v.permissions(),
                field.equals("action") ? "unknown:read" : v.action(),
                field.equals("flag") ? !v.functionAllowed() : v.functionAllowed(),
                v.clauses(),
                v.regionLimit(),
                v.warehouseLimit());
    }

    @Test
    void uniformlyChangedBatchVersionStillFailsAgainstInitialOnlineSnapshot() {
        var client = new Client();
        client.permissions = Set.of(A, B);
        client.corrupt = views -> views.stream().map(v -> corrupt(v, "application")).toList();
        try (var executor = Executors.newSingleThreadExecutor();
                var context =
                        SupplyAuthorizationContext.open(
                                client,
                                caller,
                                view("supply:application:access", client.permissions))) {
            assertThatThrownBy(
                            () ->
                                    context.prefetchActions(
                                            List.of(A, B), executor, Duration.ofSeconds(2)))
                    .hasMessageContaining("权限配置已更新");
            SupplyAuthorizationContext.requireAction(A);
            assertThat(client.singles).isOne();
        }
    }
}

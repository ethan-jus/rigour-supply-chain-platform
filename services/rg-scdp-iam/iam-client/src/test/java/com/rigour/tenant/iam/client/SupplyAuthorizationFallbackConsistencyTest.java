package com.rigour.tenant.iam.client;

import static org.assertj.core.api.Assertions.*;

import com.rigour.shared.context.CallerIdentity;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 旧单动作客户端也必须以本请求初始在线身份为版本边界，失败不得缓存部分结果。 */
class SupplyAuthorizationFallbackConsistencyTest {
    private static final String A = "order:read", B = "hr:expense:read";
    private final UUID user = UUID.randomUUID();
    private final CallerIdentity caller =
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

    private SupplyAuthorizationView view(String action, String changed) {
        return new SupplyAuthorizationView(
                "ACTIVE",
                caller.tenantId(),
                caller.userId(),
                changed.equals("employeeCode") ? "E2" : "E1",
                11 + (changed.equals("application") ? 1 : 0),
                12 + (changed.equals("member") ? 1 : 0),
                13 + (changed.equals("employeeRevision") ? 1 : 0),
                14 + (changed.equals("organizationVersion") ? 1 : 0),
                changed.equals("permissions") ? Set.of(A) : Set.of(A, B),
                action,
                true,
                List.of(),
                new SupplyAuthorizationView.Limit("ALL", List.of()),
                new SupplyAuthorizationView.Limit("ALL", List.of()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "application",
                "member",
                "employeeRevision",
                "organizationVersion",
                "employeeCode",
                "permissions"
            })
    void lazySingleActionRejectsChangedFactsAndDoesNotCacheFailure(String changed) {
        var calls = new AtomicInteger();
        var defect = new AtomicReference<>(changed);
        SupplyAuthorizationClient legacy =
                (identity, action) -> {
                    calls.incrementAndGet();
                    return view(action, defect.get());
                };
        assertThat(legacy.supportsBatchAuthorization()).isFalse();
        try (var ignored =
                SupplyAuthorizationContext.open(
                        legacy, caller, view("supply:application:access", ""))) {
            assertThatThrownBy(() -> SupplyAuthorizationContext.requireAction(A))
                    .hasMessageContaining("权限配置已更新");
            defect.set("");
            assertThat(SupplyAuthorizationContext.requireAction(A).employeeCode()).isEqualTo("E1");
            assertThat(calls).hasValue(2);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "application",
                "member",
                "employeeRevision",
                "organizationVersion",
                "employeeCode",
                "permissions"
            })
    void legacyConcurrentPrefetchRejectsTheEntireGroupWhenAnyActionChanged(String changed) {
        var calls = new ConcurrentHashMap<String, AtomicInteger>();
        var defect = new AtomicReference<>(changed);
        SupplyAuthorizationClient legacy =
                (identity, action) -> {
                    calls.computeIfAbsent(action, key -> new AtomicInteger()).incrementAndGet();
                    return view(action, action.equals(B) ? defect.get() : "");
                };
        try (var executor = Executors.newFixedThreadPool(2);
                var context =
                        SupplyAuthorizationContext.open(
                                legacy, caller, view("supply:application:access", ""))) {
            assertThatThrownBy(
                            () ->
                                    context.prefetchActions(
                                            List.of(A, B), executor, Duration.ofSeconds(2)))
                    .hasMessageContaining("权限配置已更新");
            int originalReads = calls.get(A).get();
            defect.set("");
            SupplyAuthorizationContext.requireAction(A);
            assertThat(calls.get(A)).hasValue(originalReads + 1);
        }
    }
}

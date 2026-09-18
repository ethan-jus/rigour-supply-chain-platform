package com.rigour.tenant.iam.client;

import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;

import java.util.*;

/** 单请求授权状态；每个动作保留完整角色条件，应用版本变化时要求重试。 */
public final class SupplyAuthorizationContext implements AutoCloseable {
    private static final ThreadLocal<SupplyAuthorizationContext> CURRENT = new ThreadLocal<>();
    private final SupplyAuthorizationContext previous;
    private final SupplyAuthorizationClient client;
    private final CallerIdentity caller;
    private final SupplyAuthorizationView initial;
    private final Map<String, SupplyAuthorizationView> actions = new HashMap<>();

    private SupplyAuthorizationContext(
            SupplyAuthorizationClient client,
            CallerIdentity caller,
            SupplyAuthorizationView initial) {
        this.client = client;
        this.caller = caller;
        this.initial = initial;
        this.previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static SupplyAuthorizationContext open(
            SupplyAuthorizationClient client,
            CallerIdentity caller,
            SupplyAuthorizationView initial) {
        return new SupplyAuthorizationContext(client, caller, initial);
    }

    public static Optional<SupplyAuthorizationContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    private final java.util.Set<String> observed = new java.util.HashSet<>();

    /** 仅旁路记录；观察失败只记日志，绝不把新授权用于准备阶段的业务放行。 */
    public static void observe(String action, String legacyAction) {
        var state = CURRENT.get();
        if (state == null || state.active() || state.initial.applicationVersion() == 0) return;
        if (!state.observed.add(action + "|" + legacyAction)) return;
        try {
            state.client.observe(state.caller, action, legacyAction);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(SupplyAuthorizationContext.class)
                    .warn(
                            "供应链授权对比记录失败 action={} legacy={} reason={}",
                            action,
                            legacyAction,
                            e.getClass().getSimpleName());
        }
    }

    private final Map<String, SupplyAuthorizationView> candidates = new HashMap<>();
    private final Set<String> dataSamples = new HashSet<>();

    /** 当前实际记录的旧决定与候选 SQL 对比。最多20个记录，错误仅可观测，不改变业务返回。 */
    public static void compare(
            String action,
            String domain,
            String recordKey,
            boolean legacyAllowed,
            java.util.function.Predicate<SupplyAuthorizationView> evaluate) {
        compare(action, domain, recordKey, () -> legacyAllowed, evaluate);
    }

    public static void compare(
            String action,
            String domain,
            String recordKey,
            java.util.function.BooleanSupplier legacyAllowed,
            java.util.function.Predicate<SupplyAuthorizationView> evaluate) {
        var state = CURRENT.get();
        if (state == null
                || state.active()
                || state.initial.applicationVersion() == 0
                || state.dataSamples.size() >= 20
                || !state.dataSamples.add(action + "|" + domain + "|" + recordKey)) return;
        try {
            var next =
                    state.candidates.computeIfAbsent(
                            action, a -> state.client.candidate(state.caller, a));
            if (!"PREPARING".equals(next.mode())
                    || next.applicationVersion() != state.initial.applicationVersion())
                throw new IllegalStateException("候选授权版本变化");
            boolean allowed = next.functionAllowed() && evaluate.test(next);
            state.client.observeData(
                    state.caller,
                    new com.rigour.tenant.iam.api.v1.model.SupplyDataObservation(
                            action,
                            domain,
                            recordKey,
                            next.applicationVersion(),
                            next.memberVersion(),
                            next.employeeRevision(),
                            next.organizationVersion(),
                            legacyAllowed.getAsBoolean(),
                            allowed));
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(SupplyAuthorizationContext.class)
                    .warn(
                            "供应链数据对比失败 domain={} action={} reason={}",
                            domain,
                            action,
                            e.getClass().getSimpleName());
        }
    }

    public static SupplyAuthorizationView requireAction(String action) {
        SupplyAuthorizationContext state =
                current()
                        .orElseThrow(
                                () ->
                                        new AuthorizationDeniedException(
                                                "supply-authorization-context"));
        if (!"ACTIVE".equals(state.initial.mode())) return state.initial;
        return state.actions.computeIfAbsent(
                action,
                key -> {
                    SupplyAuthorizationView result = state.client.authorization(state.caller, key);
                    if (!"ACTIVE".equals(result.mode())
                            || result.applicationVersion() != state.initial.applicationVersion()
                            || result.memberVersion() != state.initial.memberVersion())
                        throw new IllegalStateException("授权配置已变化，请重试");
                    if (!result.functionAllowed()) throw new AuthorizationDeniedException(action);
                    return result;
                });
    }

    public boolean active() {
        return "ACTIVE".equals(initial.mode());
    }

    public SupplyAuthorizationView initial() {
        return initial;
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}

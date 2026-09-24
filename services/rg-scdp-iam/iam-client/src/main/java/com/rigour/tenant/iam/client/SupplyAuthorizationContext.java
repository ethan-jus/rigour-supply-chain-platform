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
        if (!"ACTIVE".equals(initial.mode())) throw new IllegalStateException("供应链授权协议已更新，请重启授权服务");
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

    public static SupplyAuthorizationView requireAction(String action) {
        SupplyAuthorizationContext state =
                current()
                        .orElseThrow(
                                () ->
                                        new AuthorizationDeniedException(
                                                "supply-authorization-context"));
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

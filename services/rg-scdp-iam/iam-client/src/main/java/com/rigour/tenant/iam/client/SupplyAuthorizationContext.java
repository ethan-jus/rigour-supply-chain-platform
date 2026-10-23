package com.rigour.tenant.iam.client;

import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

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
        var result = state.actions.computeIfAbsent(action, state::readAction);
        if (!result.functionAllowed()) throw new AuthorizationDeniedException(action);
        return result;
    }

    private SupplyAuthorizationView readAction(String action) {
        var result = client.authorization(caller, action);
        if (result == null
                || !Objects.equals(caller.tenantId(), result.tenantId())
                || !Objects.equals(caller.userId(), result.userId())
                || !Objects.equals(action, result.action())
                || !"ACTIVE".equals(result.mode())
                || !SupplyAuthorizationBatches.sameVersions(initial, result)
                || !Objects.equals(initial.employeeCode(), result.employeeCode())
                || !initial.permissions().equals(result.permissions()))
            throw new AuthorizationStateChangedException();
        return result;
    }

    /** 并发获取本请求所需的独立策略；全部成功校验后才写入请求缓存，不跨请求复用。 */
    public void prefetchActions(
            Collection<String> requested, ExecutorService executor, Duration timeout) {
        if (CURRENT.get() != this) throw new IllegalStateException("授权上下文不属于当前请求");
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("授权超时必须大于零");
        if (client.supportsBatchAuthorization()) {
            prefetchBatch(requested, executor, timeout);
            return;
        }
        Map<String, Future<SupplyAuthorizationView>> pending = new LinkedHashMap<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            for (String action : new LinkedHashSet<>(requested)) {
                if (action == null || action.isBlank())
                    throw new IllegalArgumentException("授权动作不能为空");
                if (!actions.containsKey(action))
                    pending.put(action, executor.submit(() -> readAction(action)));
            }
            Map<String, SupplyAuthorizationView> verified = new HashMap<>();
            for (var entry : pending.entrySet()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException();
                verified.put(entry.getKey(), entry.getValue().get(remaining, TimeUnit.NANOSECONDS));
            }
            actions.putAll(verified);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("授权查询已中断，请重试", ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("授权查询失败，请重试", ex.getCause());
        } catch (TimeoutException | RejectedExecutionException ex) {
            throw new IllegalStateException("授权查询繁忙，请稍后重试", ex);
        } finally {
            pending.values().forEach(future -> future.cancel(true));
        }
    }

    private void prefetchBatch(
            Collection<String> requested, ExecutorService executor, Duration timeout) {
        var missing = new LinkedHashSet<>(SupplyAuthorizationBatches.actions(requested));
        missing.removeAll(actions.keySet());
        if (missing.isEmpty()) return;
        Future<List<SupplyAuthorizationView>> pending = null;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            var batch = List.copyOf(missing);
            pending = executor.submit(() -> client.authorizations(caller, batch));
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            var verified =
                    SupplyAuthorizationBatches.verify(
                            caller, missing, pending.get(remaining, TimeUnit.NANOSECONDS));
            for (var result : verified.values())
                if (!SupplyAuthorizationBatches.sameVersions(initial, result)
                        || !Objects.equals(initial.employeeCode(), result.employeeCode())
                        || !initial.permissions().equals(result.permissions()))
                    throw new AuthorizationStateChangedException();
            actions.putAll(verified);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("授权查询已中断，请重试", ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("授权查询失败，请重试", ex.getCause());
        } catch (TimeoutException | RejectedExecutionException ex) {
            throw new IllegalStateException("授权查询繁忙，请稍后重试", ex);
        } finally {
            if (pending != null) pending.cancel(true);
            if (executor instanceof ThreadPoolExecutor pool) pool.purge();
        }
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

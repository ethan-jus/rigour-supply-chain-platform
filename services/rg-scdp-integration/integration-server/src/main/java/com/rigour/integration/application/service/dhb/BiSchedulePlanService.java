package com.rigour.integration.application.service.dhb;

import com.rigour.integration.application.port.out.SyncScheduleStore;
import com.rigour.shared.context.*;
import com.rigour.shared.core.scheduling.*;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/** 所有计划配置归 Integration；这里只协调 BI，绝不读取或写入 BI 业务库。 */
@Service
public class BiSchedulePlanService {
    public static final String KEY = "BI_REFRESH";
    private final SyncScheduleStore store;
    private final Clock clock;

    public BiSchedulePlanService(SyncScheduleStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public ScheduleView get() {
        var actor = user(false);
        return store.find(actor.tenantId().toString(), KEY)
                .orElseGet(
                        () ->
                                new ScheduleView(
                                        KEY,
                                        new ScheduleSettings(false, "FIXED_DELAY", 60, null),
                                        0,
                                        false,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "尚未由同步中心接管；原BI刷新配置继续生效。保存后使用集中计划。",
                                        null));
    }

    public ScheduleView save(SaveScheduleCommand command) {
        var actor = user(true);
        return store.save(
                actor.tenantId().toString(),
                KEY,
                command,
                actor.principalId().toString(),
                clock.instant());
    }

    private static CallerIdentity user(boolean write) {
        var a = AuthorizationContext.requireCurrent();
        if (a.tenantId() == null || a.userId() == null)
            throw new AuthorizationDeniedException("tenant-user-caller");
        AuthorizationContext.requirePermission("integration:dhb:read");
        AuthorizationContext.requirePermission("analytics:dashboard:read");
        if (write) {
            AuthorizationContext.requirePermission("integration:dhb:write");
            AuthorizationContext.requirePermission("analytics:refresh:write");
            if (com.rigour.tenant.iam.client.SupplyAuthorizationContext.current()
                    .map(com.rigour.tenant.iam.client.SupplyAuthorizationContext::active)
                    .orElse(false)) {
                var p =
                        com.rigour.tenant.iam.client.SupplyAuthorizationContext.requireAction(
                                "analytics:dashboard:read");
                boolean full =
                        "ALL".equals(p.regionLimit().mode())
                                && "ALL".equals(p.warehouseLimit().mode())
                                && p.clauses().stream()
                                        .anyMatch(
                                                c ->
                                                        "ANALYTICS".equals(c.objectType())
                                                                && "ALL".equals(c.scopeMode())
                                                                && List.of("ALL", "NONE")
                                                                        .contains(
                                                                                c.departments()
                                                                                        .mode())
                                                                && List.of("ALL", "NONE")
                                                                        .contains(
                                                                                c.regions().mode())
                                                                && List.of("ALL", "NONE")
                                                                        .contains(
                                                                                c.warehouses()
                                                                                        .mode()));
                if (!full) throw new AuthorizationDeniedException("bi-refresh-global-scope");
            }
        }
        return a;
    }

    public List<SyncScheduleStore.Entry> due() {
        for (var e : store.running())
            if (KEY.equals(e.plan().key())
                    && e.heartbeatAt() != null
                    && e.heartbeatAt().isBefore(clock.instant().minusSeconds(90)))
                store.finish(
                        e.tenant(),
                        KEY,
                        e.plan().runningJobId(),
                        "UNKNOWN",
                        "BI执行心跳中断，请核实原任务；不会重复派发",
                        clock.instant());
        return store.due(clock.instant()).stream().filter(e -> KEY.equals(e.plan().key())).toList();
    }

    public boolean managed(UUID tenant) {
        return store.find(tenant.toString(), KEY).isPresent();
    }

    public boolean claim(UUID tenant, long version, UUID token) {
        return store.claim(tenant.toString(), KEY, version, token.toString(), clock.instant());
    }

    public void heartbeat(UUID tenant, UUID token) {
        store.heartbeat(tenant.toString(), KEY, token.toString(), clock.instant());
    }

    public void finish(UUID tenant, UUID token, String status, String message) {
        if (status == null
                || !Set.of("SUCCESS", "SUCCEEDED", "PARTIAL", "FAILED", "SKIPPED").contains(status))
            throw new IllegalArgumentException("无效执行结果");
        store.finish(
                tenant.toString(),
                KEY,
                token.toString(),
                status,
                message == null ? null : message.substring(0, Math.min(500, message.length())),
                clock.instant());
    }
}
